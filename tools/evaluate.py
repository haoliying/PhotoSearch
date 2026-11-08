# -*- coding: utf-8 -*-
"""
PhotoSearch 搜索准确度离线仿真评测

按仓库源码 1:1 复刻当前检索链路（IntentParser -> SearchEngine -> toSearchableTags），
在带人工标注的模拟照片集上对比三套策略：

  A  现行方案          : 自由文本子串 + 双向 contains + 字符集合 fuzzy + 宽松打分
  B  仅改检索          : 规范化概念 + 词边界/负向词 + 否定句抑制 + 合理阈值
  C  检索 + 分析结构化 : B + VLM 输出受控结构化对象（objects / scene / attributes）

运行: python3 evaluate.py
仅用于验证诊断结论与改进方向，指标为仿真值，非真机实测。
"""

import re
from dataclasses import dataclass, field

# ----------------------------------------------------------------------------
# 1. 模拟照片集（贴近 VLM + ML Kit 真实输出形态）
#    gt = 该照片真实包含的规范概念
# ----------------------------------------------------------------------------

@dataclass
class Photo:
    pid: int
    desc: str
    kw: list
    labels: list                      # (英文标签, 置信度)
    scene: str = "UNKNOWN"
    faces: list = field(default_factory=list)
    is_outdoor: object = None
    objects: list = field(default_factory=list)   # ML Kit 粗分类对象
    gt: set = field(default_factory=set)
    structured: dict = field(default_factory=dict)  # 新方案: VLM 结构化输出

P = [
    # ---- 真·汽车 ----
    Photo(1, "一辆白色轿车停在路边，背景是高楼", ["轿车", "汽车", "道路", "建筑"],
          [("Car", .93), ("Vehicle", .88), ("Road", .71)], "VEHICLE_SCENE", None, True,
          gt={"CAR", "BUILDING"},
          structured={"objects": [("CAR", .96)], "scene": "STREET", "attributes": []}),
    Photo(2, "地下停车场里停着几辆车，光线较暗", ["停车场", "汽车", "室内"],
          [("Car", .81), ("Vehicle", .74)], "VEHICLE_SCENE", None, False,
          gt={"CAR", "INDOOR"},
          structured={"objects": [("CAR", .90)], "scene": "PARKING", "attributes": []}),
    Photo(3, "高速公路上一辆红色 SUV 正在行驶", ["SUV", "汽车", "公路"],
          [("Sports utility vehicle", .78), ("Car", .62)], "VEHICLE_SCENE", None, True,
          gt={"CAR"},
          structured={"objects": [("CAR", .94)], "scene": "HIGHWAY", "attributes": []}),
    Photo(4, "路边停着一辆黑色大巴车和几辆小汽车", ["大巴", "公交车", "汽车"],
          [("Bus", .86), ("Vehicle", .80)], "VEHICLE_SCENE", None, True,
          gt={"CAR"},
          structured={"objects": [("CAR", .88), ("BUS", .91)], "scene": "STREET", "attributes": []}),
    # ---- 含「车」字但不是汽车（最典型的误召回） ----
    Photo(5, "一辆自行车靠在墙边，旁边有绿植", ["自行车", "墙", "植物"],
          [("Bicycle", .91), ("Plant", .55)], "OUTDOOR_URBAN", None, True,
          gt={"BICYCLE"},
          structured={"objects": [("BICYCLE", .93)], "scene": "STREET", "attributes": []}),
    Photo(6, "空荡荡的车库，卷帘门半开着", ["车库", "室内", "门"],
          [("Garage", .72), ("Building", .51)], "INDOOR_HOME", None, False,
          gt={"INDOOR"},
          structured={"objects": [], "scene": "GARAGE", "attributes": []}),
    Photo(7, "火车站台上人来人往，没有汽车", ["火车站", "站台", "人"],
          [("Train station", .83), ("Person", .70)], "OUTDOOR_URBAN", None, True,
          gt={"PERSON"},
          structured={"objects": [("PERSON", .82)], "scene": "STATION", "attributes": []}),
    Photo(8, "书桌上摆着一个汽车模型玩具", ["汽车模型", "玩具", "桌面"],
          [("Toy", .79), ("Model", .48)], "INDOOR_HOME", None, False,
          gt={"INDOOR"},
          structured={"objects": [("TOY_CAR", .85)], "scene": "INDOOR", "attributes": []}),
    Photo(9, "街景，画面中没有汽车，只有行人和自行车", ["街景", "行人", "自行车"],
          [("Person", .77), ("Bicycle", .66)], "OUTDOOR_URBAN", None, True,
          gt={"PERSON", "BICYCLE"},
          structured={"objects": [("PERSON", .80), ("BICYCLE", .70)], "scene": "STREET", "attributes": []}),
    Photo(10, "轮胎和轮毂的特写，摆在修车店门口", ["轮胎", "轮子", "修车"],
          [("Tire", .88), ("Wheel", .81)], "UNKNOWN", None, True,
          gt=set(),
          structured={"objects": [("TIRE", .90)], "scene": "UNKNOWN", "attributes": []}),
    # ---- 海滩 / 山（含提示词诱导的地名幻觉） ----
    Photo(11, "海边沙滩，几个人在散步，海浪轻拍岸边", ["海滩", "大海", "沙滩", "人"],
          [("Beach", .92), ("Sea", .87), ("Person", .60)], "BEACH_SEA", None, True,
          gt={"BEACH", "PERSON"},
          structured={"objects": [("PERSON", .70)], "scene": "BEACH", "attributes": []}),
    Photo(12, "海边日落，天空是橙红色，有椰子树", ["日落", "海边", "夕阳", "椰子树"],
          [("Sunset", .90), ("Sky", .82)], "BEACH_SEA", None, True,
          gt={"BEACH", "SUNSET"},
          structured={"objects": [], "scene": "BEACH", "attributes": []}),
    Photo(13, "山顶云海，看起来像泰山的日出", ["山", "云海", "泰山", "日出"],
          [("Mountain", .89), ("Cloud", .74)], "MOUNTAIN_HILL", None, True,
          gt={"MOUNTAIN", "SUNSET"},
          structured={"objects": [], "scene": "MOUNTAIN", "attributes": []}),
    Photo(14, "在泰山玉皇顶的合影，三个人笑着看镜头", ["泰山", "合影", "山", "微笑"],
          [("Mountain", .77), ("Person", .84)], "MOUNTAIN_HILL",
          [{"smile": .91}, {"smile": .82}, {"smile": .60}], True,
          gt={"MOUNT_TAI", "MOUNTAIN", "PERSON", "SMILE"},
          structured={"objects": [("PERSON", .90)], "scene": "MOUNTAIN_TAI", "attributes": ["SMILE"]}),
    Photo(15, "郊外小山坡上的野花，远处有树林", ["山丘", "野花", "树林"],
          [("Hill", .68), ("Flower", .79)], "OUTDOOR_NATURE", None, True,
          gt={"MOUNTAIN", "FLOWER"},
          structured={"objects": [("FLOWER", .85), ("MOUNTAIN", .70)], "scene": "NATURE", "attributes": []}),
    # ---- 人物 / 光膀子 ----
    Photo(16, "一个男人光着上身在海边跑步，皮肤晒得黝黑", ["光膀子", "男人", "海边", "跑步"],
          [("Person", .82), ("Skin", .55)], "BEACH_SEA", [{"smile": .20}], True,
          gt={"PERSON", "SHIRTLESS", "BEACH"},
          structured={"objects": [("PERSON", .90)], "scene": "BEACH", "attributes": ["SHIRTLESS"]}),
    Photo(17, "穿白衬衫的男人坐在办公室里看电脑", ["衬衫", "男人", "办公室", "电脑"],
          [("Person", .80), ("Shirt", .61)], "INDOOR_HOME", [{"smile": .12}], False,
          gt={"PERSON", "INDOOR"},
          structured={"objects": [("PERSON", .88), ("LAPTOP", .80)], "scene": "OFFICE", "attributes": []}),
    Photo(18, "一个女生对着镜头自拍，笑容很灿烂", ["自拍", "微笑", "人像"],
          [("Person", .86), ("Face", .79)], "PEOPLE_PORTRAIT", [{"smile": .95}], None,
          gt={"PERSON", "SMILE", "SELFIE"},
          structured={"objects": [("PERSON", .92)], "scene": "PORTRAIT", "attributes": ["SMILE", "SELFIE"]}),
    Photo(19, "婚礼现场，新娘和新郎在交换戒指", ["婚礼", "新娘", "新郎", "合影"],
          [("Person", .78), ("Wedding", .71)], "INDOOR_EVENT", [{"smile": .88}, {"smile": .74}], False,
          gt={"PERSON", "WEDDING"},
          structured={"objects": [("PERSON", .85)], "scene": "WEDDING", "attributes": []}),
    Photo(20, "桌上一盘红烧肉和一碗米饭", ["美食", "红烧肉", "米饭"],
          [("Food", .93), ("Dish", .80)], "FOOD_DRINK", None, False,
          gt={"FOOD"},
          structured={"objects": [("FOOD", .95)], "scene": "DINING", "attributes": []}),
    Photo(21, "一只橘猫趴在沙发上睡觉", ["猫", "宠物", "沙发"],
          [("Cat", .94), ("Animal", .83)], "INDOOR_HOME", None, False,
          gt={"CAT", "INDOOR"},
          structured={"objects": [("CAT", .96)], "scene": "INDOOR", "attributes": []}),
    Photo(22, "夜晚的城市街道，霓虹灯和车流", ["夜景", "霓虹灯", "街道", "车流"],
          [("Night", .72), ("City", .69)], "NIGHT_SCENE", None, True,
          gt={"NIGHT"},
          structured={"objects": [], "scene": "NIGHT_STREET", "attributes": []}),
    Photo(23, "公园里的樱花树，地上落满花瓣", ["樱花", "花", "公园", "树"],
          [("Flower", .90), ("Tree", .76)], "OUTDOOR_NATURE", None, True,
          gt={"FLOWER"},
          structured={"objects": [("FLOWER", .92)], "scene": "PARK", "attributes": []}),
    Photo(24, "纯蓝天和白云，没有别的物体", ["天空", "云", "蓝天"],
          [("Sky", .95), ("Cloud", .88)], "LANDSCAPE", None, True,
          gt=set(),
          structured={"objects": [], "scene": "SKY", "attributes": []}),
]

# ----------------------------------------------------------------------------
# 2. 现行方案复刻（源码对应位置见报告）
# ----------------------------------------------------------------------------

# LabelTranslator: 仅当英文标签「完全等于」key 时才翻译
TRANSLATIONS = {
    "car": ["车", "汽车", "轿车"], "vehicle": ["车辆", "交通工具"],
    "bus": ["公交车", "大巴"], "bicycle": ["自行车"], "wheel": ["轮子", "轮胎"],
    "tire": ["轮胎"], "person": ["人", "人物"], "people": ["人们", "人物"],
    "face": ["脸", "面部"], "mountain": ["山", "山峰"], "hill": ["山丘", "小山"],
    "beach": ["海滩", "沙滩"], "sea": ["海", "大海"], "sky": ["天空"],
    "food": ["食物", "美食"], "cat": ["猫"], "dog": ["狗"], "flower": ["花", "花朵"],
    "tree": ["树", "树木"], "night": ["夜晚", "夜景"], "shirt": ["衬衫", "上衣"],
    "smile": ["微笑", "笑"],
}

SCENE_TAGS = {
    "OUTDOOR_NATURE": ["outdoor", "nature", "户外", "自然"],
    "OUTDOOR_URBAN": ["urban", "city", "城市", "街道"],
    "INDOOR_HOME": ["indoor", "home", "室内", "家"],
    "INDOOR_PUBLIC": ["indoor", "public", "公共场所"],
    "INDOOR_EVENT": ["indoor", "event"],
    "BEACH_SEA": ["beach", "sea", "ocean", "海滩", "海边", "沙滩", "大海"],
    "MOUNTAIN_HILL": ["mountain", "hill", "hiking", "山", "泰山", "登山", "爬山"],
    "NIGHT_SCENE": ["night", "dark", "夜景", "夜晚", "晚上"],
    "FOOD_DRINK": ["food", "meal", "美食", "食物", "吃的"],
    "VEHICLE_SCENE": ["vehicle", "car", "车", "汽车", "车辆"],
    "ANIMAL_PET": ["animal", "pet", "动物", "宠物", "猫", "狗"],
    "PEOPLE_PORTRAIT": ["person", "people", "人", "人物", "人像"],
    "LANDSCAPE": ["landscape", "scenery", "风景", "景色"],
    "SPORT_EXERCISE": ["sport", "exercise", "运动", "健身"],
}


def to_searchable_tags(p):
    """复刻 PhotoMetadata.toSearchableTags()"""
    tags = set()
    if p.desc.strip():
        tags.add(p.desc.lower())
        for seg in re.split(r"[，。、；！？,\.\s]+", p.desc):
            if len(seg) >= 2:
                tags.add(seg.lower())
    for k in p.kw:
        tags.add(k.lower())
    for text, _conf in p.labels:
        tags.add(text.lower())
        for t in TRANSLATIONS.get(text.lower(), []):
            tags.add(t)
    for o in p.objects:
        tags.add(o.lower())
        for t in TRANSLATIONS.get(o.lower(), []):
            tags.add(t)
    tags.update(SCENE_TAGS.get(p.scene, []))
    if p.faces:
        tags.update(["person", "people", "face", "人", "人物", "人脸"])
        if any(f["smile"] > .5 for f in p.faces):
            tags.update(["smile", "happy", "笑", "开心"])
    if p.is_outdoor is True:
        tags.update(["outdoor", "户外", "室外"])
    elif p.is_outdoor is False:
        tags.update(["indoor", "室内"])
    label_texts = {t.lower() for t, _ in p.labels}
    has_shirt = bool(label_texts & {"shirt", "t-shirt", "tshirt", "clothing", "garment", "top", "jacket", "coat", "sweater"})
    has_person = bool(label_texts & {"person", "people", "man", "woman", "human"})
    has_skin = bool(label_texts & {"body", "skin", "chest", "torso", "muscle"})
    if has_person and not has_shirt and (has_skin or p.scene in ("BEACH_SEA", "SPORT_EXERCISE")):
        tags.update(["shirtless", "bare chest", "光膀子", "赤裸上身", "没穿上衣"])
    if has_person and has_shirt:
        tags.update(["wearing shirt", "穿着衣服", "有上衣"])
    return tags


def fuzzy_match(a, b):
    """复刻 SearchEngine.fuzzyMatch() —— 英文字符集合重叠 > 0.6 即判定匹配"""
    if not a or not b:
        return False
    if a == b:
        return True
    if len(a) >= 2 and len(b) >= 2:
        for i in range(len(a) - 1):
            if a[i:i + 2] in b:
                return True
    sa, sb = set(a), set(b)
    m = max(len(sa), len(sb))
    return m > 0 and len(sa & sb) / m > .6


def match_against_text(kw, text):
    """复刻 SearchEngine.matchAgainstText()"""
    t = text.lower()
    if kw in t:
        return True
    if len(kw) >= 2:
        for i in range(len(kw) - 1):
            if kw[i:i + 2] in t:
                return True
    for w in re.split(r"[\s，。、；！？,.\s]+", t):
        if len(w) >= 2 and fuzzy_match(kw, w):
            return True
    return False


def old_match_condition(p, keywords, min_conf=.3):
    """复刻 SearchEngine.matchCondition() 四级匹配"""
    tags = to_searchable_tags(p)
    for raw in keywords:
        kw = raw.lower().strip()
        if not kw:
            continue
        if p.desc.strip() and match_against_text(kw, p.desc):
            return True
        for v in p.kw:
            v = v.lower()
            if kw in v or v in kw or fuzzy_match(kw, v):
                return True
        for tag in tags:
            if kw in tag or tag in kw or fuzzy_match(kw, tag):
                return True
        for text, conf in p.labels:
            lt = text.lower()
            if (kw in lt or lt in kw) and conf >= min_conf:
                return True
    return False


# IntentParser 中「车辆」模式的 conditions.keywords（原样复制）
COND_CAR = ["car", "vehicle", "truck", "automobile", "van", "bus", "wheel", "tire",
            "motorcycle", "bicycle", "车", "汽车", "车辆", "卡车", "轮胎"]
COND_BEACH = ["beach", "sand", "sea", "ocean", "water", "coast", "shore", "wave",
              "palm", "swimming", "海滩", "沙滩", "大海", "海边", "海浪", "游泳", "棕榈"]
COND_MOUNTAIN = ["mountain", "hill", "peak", "ridge", "cliff", "rock", "valley",
                 "landscape", "hiking", "山", "泰山", "山峰", "登山", "爬山", "山谷", "岩石", "风景"]
COND_SHIRTLESS = [
    (["person", "people", "man", "human", "body", "人", "人物", "男人"], 1.0, False),
    (["光膀子", "赤裸上身", "shirtless", "bare chest", "body", "skin", "chest", "torso",
      "muscle", "身体", "皮肤", "胸", "肌肉"], .8, False),
    (["beach", "sea", "swimming", "sport", "exercise", "海滩", "游泳", "运动"], .5, False),
    (["shirt", "jacket", "coat", "sweater", "formal", "suit", "long sleeve",
      "衬衫", "夹克", "外套", "毛衣", "正装", "西装"], 1.5, True),
]

QUERIES = [
    ("有汽车的照片", {"CAR"}, [("AND", [COND_CAR])]),
    ("海滩", {"BEACH"}, [("AND", [COND_BEACH])]),
    ("山", {"MOUNTAIN"}, [("AND", [COND_MOUNTAIN])]),
    ("泰山", {"MOUNT_TAI"}, [("AND", [COND_MOUNTAIN])]),
    ("光膀子", {"SHIRTLESS"}, [("RANK", COND_SHIRTLESS)]),
]


def old_search(query_item):
    """复刻 AND / RANK 两种逻辑 + 0.05 的 RANK 阈值"""
    _q, _need, plan = query_item
    out = []
    for p in P:
        if plan[0][0] == "AND":
            if old_match_condition(p, plan[0][1][0]):
                out.append(p.pid)
        else:  # RANK：复刻 calculateScore 中 negated 条件 (1 - score) 的贡献
            total, weight = 0., 0.
            for kws, w, neg in plan[0][1]:
                weight += w
                s = 1.0 if old_match_condition(p, kws) else 0.0
                total += (1 - s if neg else s) * w
            score = total / weight if weight else 0
            if score > .05:
                out.append(p.pid)
    return set(out)


# ----------------------------------------------------------------------------
# 3. 改进方案 B / C：规范化概念 + 严格匹配
# ----------------------------------------------------------------------------

NEGATIVE_SURFACES = ["自行车", "摩托车", "火车", "车站", "车库", "停车场", "车票",
                     "车轮", "轮胎", "车模", "汽车模型", "玩具车", "堵车", "车间",
                     "修车", "车流", "车站台", "火车站", "大巴车"]
NEGATION_PATTERNS = ["没有", "无", "不含", "看不到", "并非", "不是"]
UNCERTAIN_PREFIXES = ["看起来像", "疑似", "可能是", "似乎是", "仿佛", "有点像", "像是", "像"]

CONCEPT_SURFACES = {
    "CAR": ["汽车", "轿车", "小汽车", "私家车", "SUV", "suv", "跑车", "卡车", "货车",
            "公交车", "大巴", "机动车", "车辆", "车", "car", "sedan", "automobile",
            "vehicle", "truck", "bus", "van"],
    "BICYCLE": ["自行车", "单车", "脚踏车", "bicycle", "bike"],
    "BEACH": ["海滩", "沙滩", "海边", "海岸", "海滨", "beach", "seaside", "shore", "sand"],
    "MOUNTAIN": ["山", "山峰", "山顶", "山丘", "山坡", "登山", "爬山", "mountain", "hill"],
    "MOUNT_TAI": ["泰山", "玉皇顶", "mount tai"],
    "PERSON": ["人", "人物", "男人", "女人", "行人", "合影", "人像", "person", "people", "man", "woman"],
    "SHIRTLESS": ["光膀子", "赤裸上身", "没穿上衣", "光着上身", "赤膊", "shirtless", "bare chest"],
    "SMILE": ["微笑", "笑容", "笑", "开心", "smile", "smiling"],
    "FLOWER": ["花", "花朵", "樱花", "野花", "flower", "blossom"],
    "FOOD": ["美食", "食物", "红烧肉", "米饭", "food", "dish", "meal"],
    "CAT": ["猫", "猫咪", "cat", "kitten"],
    "NIGHT": ["夜景", "夜晚", "霓虹灯", "night", "neon"],
    "SUNSET": ["日落", "夕阳", "日出", "sunset", "sunrise"],
}


def normalize_text(text):
    """把自由文本映射为规范概念集合；先剔除易混淆组合词，再做最长匹配"""
    t = text.lower()
    for w in sorted(NEGATIVE_SURFACES, key=len, reverse=True):
        t = t.replace(w.lower(), " ")
    found = set()
    for concept, surfaces in CONCEPT_SURFACES.items():
        for s in sorted(surfaces, key=len, reverse=True):
            sl = s.lower()
            if re.fullmatch(r"[a-z0-9 ]+", sl):
                if re.search(r"(?<![a-z])" + re.escape(sl) + r"(?![a-z])", t):
                    found.add(concept)
                    break
            elif sl in t:
                found.add(concept)
                break
    # 否定句 / 不确定表述抑制（修正自由文本中的幻觉与否定）
    for concept, surfaces in CONCEPT_SURFACES.items():
        for s in surfaces:
            for neg in NEGATION_PATTERNS:
                if (neg + s) in text or (neg + " " + s) in text:
                    found.discard(concept)
            for pre in UNCERTAIN_PREFIXES:
                if (pre + s) in text:
                    found.discard(concept)
    return found


def canonicalize_photo(p, use_structured):
    """B: 从旧元数据规范化；C: 使用 VLM 结构化输出"""
    if use_structured and p.structured:
        c = set()
        for name, conf in p.structured["objects"]:
            if name == "CAR" and conf >= .55:
                c.add("CAR")
            elif name not in ("TOY_CAR", "TIRE") and conf >= .55:
                c.add(name)
        scene = p.structured["scene"]
        if scene in ("BEACH", "BEACH_SEA"):
            c.add("BEACH")
        if scene == "MOUNTAIN":
            c.add("MOUNTAIN")
        if scene == "MOUNTAIN_TAI":
            c.update(["MOUNTAIN", "MOUNT_TAI"])
        c.update(p.structured["attributes"])
        return c
    c = set()
    for surface_text in [p.desc] + p.kw:
        c |= normalize_text(surface_text)
    for text, conf in p.labels:
        if conf >= .45:
            c |= normalize_text(text)
    # 关键：不采信 toSearchableTags() 里的启发式推断标签
    # （源码第 151 行会把「海滩 + 人物」直接推断为「光膀子」）
    if p.scene == "VEHICLE_SCENE":
        c.add("CAR")
    if p.scene == "BEACH_SEA":
        c.add("BEACH")
    if p.scene == "MOUNTAIN_HILL":
        c.add("MOUNTAIN")
    if p.faces and any(f["smile"] > .7 for f in p.faces):
        c.add("SMILE")
    return c


def new_search(need, use_structured):
    out = set()
    for p in P:
        concepts = canonicalize_photo(p, use_structured)
        if need & concepts:
            out.add(p.pid)
    return out


# ----------------------------------------------------------------------------
# 4. 评测
# ----------------------------------------------------------------------------

def metrics(retrieved, relevant):
    tp = len(retrieved & relevant)
    prec = tp / len(retrieved) if retrieved else 0.0
    rec = tp / len(relevant) if relevant else 0.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
    return prec, rec, f1, tp


def main():
    print("=" * 88)
    print("PhotoSearch 搜索准确度仿真评测  (24 张带标注照片 / 5 个查询)")
    print("=" * 88)
    totals = {"A": [0., 0., 0, 0], "B": [0., 0., 0, 0], "C": [0., 0., 0, 0]}
    for q, need, plan in QUERIES:
        relevant = {p.pid for p in P if need <= p.gt}
        a = old_search((q, need, plan))
        b = new_search(need, use_structured=False)
        c = new_search(need, use_structured=True)
        rows = []
        for tag, res in (("A", a), ("B", b), ("C", c)):
            prec, rec, f1, tp = metrics(res, relevant)
            rows.append(f"{tag}: P={prec:.2f} R={rec:.2f} F1={f1:.2f} (命中{len(res)}/应为{len(relevant)})")
            totals[tag][0] += prec
            totals[tag][1] += rec
            totals[tag][2] += tp
            totals[tag][3] += len(relevant)
        print(f"\n查询「{q}」  相关照片 {len(relevant)} 张")
        for r in rows:
            print("   ", r)
        print("    A 的误召回:", sorted(a - relevant), " 漏召回:", sorted(relevant - a))

    n = len(QUERIES)
    print("\n" + "-" * 88)
    print("宏平均 / 微平均")
    for tag, name in (("A", "现行方案"), ("B", "仅改检索层"), ("C", "检索层+结构化分析")):
        p, r, tp, rel = totals[tag][0] / n, totals[tag][1] / n, totals[tag][2], totals[tag][3]
        print(f"  {name:<20} 宏平均P={p:.2f}  宏平均R={r:.2f}  累计TP={tp}/{rel}")

    print("\n" + "-" * 88)
    print("独立复现的解析层 Bug")
    # splitByOperators 用裸 "or" / "and" 做分隔符
    for word in ["portrait", "forest", "color", "sand", "mountain"]:
        parts = [x for x in re.split(r"\s*or\s*", word) if x.strip()]
        parts2 = [x for x in re.split(r"\s*and\s*", word) if x.strip()]
        if len(parts) > 1 or len(parts2) > 1:
            print(f"  输入「{word}」被 OR/AND 拆分 -> {parts if len(parts)>1 else parts2}  (查询意图被破坏)")
    print("  RANK 模式：negated 条件贡献 (1-0)*1.5 分，阈值 0.05 => 几乎全库通过")
    print("-" * 88)


if __name__ == "__main__":
    main()
