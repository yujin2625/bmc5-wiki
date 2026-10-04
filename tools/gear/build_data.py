"""Build the gear & enchantment dataset (tools/gear/wiki.json) from the mod jars + global datapacks.

    python tools/gear/stats.py        # weapon/armor numbers from item registration code -> stats.json
    python tools/gear/build_data.py   # everything else (names, tags, recipes, loot, enchantments) -> wiki.json

What comes from where:
  * which items are weapons/armor/accessories: item tags (minecraft:swords, head_armor, curios:ring, ...)
  * names, tooltips: each mod's ko_kr/en_us lang + tools/gear/ko.json (our translations where the mod has none)
  * how to get: recipe JSONs (crafting, smithing, mod machines) and loot tables (mob drops, structure chests)
  * enchantments: data/<ns>/enchantment/*.json (1.21 data-driven) + enchantment tags (treasure, curse, ...)
"""
import base64, collections, glob, json, os, re, struct, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
INST = r"C:\Users\Yujin Park\curseforge\minecraft\Instances\Better MC [NEOFORGE] BMC5"
INSTALL = os.environ.get("MC_INSTALL", r"C:\Users\Yujin Park\curseforge\minecraft\Install")
NEO = os.path.join(INSTALL, r"libraries\net\neoforged\neoforge\21.1.250\neoforge-21.1.250-universal.jar")

jars = [os.path.join(INSTALL, "versions", "1.21.1", "1.21.1.jar"), NEO]
jars += sorted(glob.glob(os.path.join(glob.escape(INST), "mods", "*.jar")))
jars += sorted(glob.glob(os.path.join(glob.escape(INST), "config", "paxi", "datapacks", "*.zip")))   # global datapacks load last
zips = []
for j in jars:
    try: zips.append((os.path.basename(j), zipfile.ZipFile(j)))
    except Exception: pass

def jload(z, n):
    try: return json.loads(z.read(n).decode("utf-8-sig"))
    except Exception: return None

# ---------------------------------------------------------------- read everything once
tags = collections.defaultdict(list); etags = collections.defaultdict(list)
ko, en = {}, {}
recipes = {}; loot = {}; enchants = {}; models = set(); modids = {"minecraft", "c"}; modnames = {}
asset_index = {}
TAG = re.compile(r"^data/([^/]+)/tags/(items?|enchantments?)/(.+)\.json$")
for jn, z in zips:
    for n in z.namelist():
        m = TAG.match(n)
        if m:
            d = jload(z, n)
            if not d: continue
            store = tags if m.group(2).startswith("item") else etags
            k = f"{m.group(1)}:{m.group(3)}"
            if d.get("replace"): store[k] = []
            store[k] += d.get("values", [])
            continue
        if n.startswith("assets/"):
            if n.endswith("/lang/ko_kr.json") or n.endswith("/lang/en_us.json"):
                d = jload(z, n)
                if isinstance(d, dict): (ko if n.endswith("ko_kr.json") else en).update(d)
            elif ("/textures/" in n and n.endswith(".png")) or ("/models/" in n and n.endswith(".json")):
                asset_index.setdefault(n, z)
                mm = re.match(r"assets/([^/]+)/models/item/([^/]+)\.json$", n)
                if mm: models.add(f"{mm.group(1)}:{mm.group(2)}")
            continue
        m = re.match(r"^data/([^/]+)/recipes?/(.+)\.json$", n)
        if m: recipes[f"{m.group(1)}:{m.group(2)}"] = (jn, n, z); continue
        m = re.match(r"^data/([^/]+)/loot_tables?/(.+)\.json$", n)
        if m: loot[f"{m.group(1)}:{m.group(2)}"] = (n, z); continue
        m = re.match(r"^data/([^/]+)/enchantments?/(.+)\.json$", n)
        if m: enchants[f"{m.group(1)}:{m.group(2)}"] = jload(z, n); continue
        if n == "META-INF/neoforge.mods.toml":
            t = z.read(n).decode("utf-8", "ignore").split("[[dependencies")[0]
            for mm in re.finditer(r'modId\s*=\s*"([^"]+)"(?:[^\[]*?displayName\s*=\s*"([^"]+)")?', t, re.S):
                modids.add(mm.group(1)); modnames.setdefault(mm.group(1), mm.group(2) or mm.group(1))

idx = json.load(open(os.path.join(INSTALL, "assets", "indexes", "17.json")))["objects"]
h = idx["minecraft/lang/ko_kr.json"]["hash"]
vko = json.load(open(os.path.join(INSTALL, "assets", "objects", h[:2], h), encoding="utf-8"))
ko.update(vko)
KO = json.load(open(os.path.join(HERE, "ko.json"), encoding="utf-8")) if os.path.exists(os.path.join(HERE, "ko.json")) else {}
STATS = json.load(open(os.path.join(HERE, "stats.json"), encoding="utf-8"))

def resolve(tag, store=tags, seen=None):
    seen = seen or set()
    if tag in seen: return []
    seen.add(tag); out = []
    for v in store.get(tag, []):
        if isinstance(v, dict): v = v.get("id")
        if not v: continue
        out += resolve(v[1:], store, seen) if v.startswith("#") else [v]
    return list(dict.fromkeys(out))

def exists(rid): return rid.split(":")[0] in modids and rid in models

def lang(key):
    if key in KO: return KO[key]
    return ko.get(key)

def item_name(rid):
    ns, p = rid.split(":", 1)
    for k in (f"item.{ns}.{p}", f"block.{ns}.{p}"):
        v = lang(k)
        if v: return v
    for k in (f"item.{ns}.{p}", f"block.{ns}.{p}"):
        if k in en: return en[k]
    return p.replace("_", " ")

def item_en(rid):
    ns, p = rid.split(":", 1)
    for k in (f"item.{ns}.{p}", f"block.{ns}.{p}"):
        if k in en: return en[k]
    return p.replace("_", " ").title()

# ---------------------------------------------------------------- icons (same approach as the mob guide)
def read_asset(n):
    z = asset_index.get(n)
    return z.read(n) if z else None

def model_tex(model, depth=0):
    if depth > 6: return None
    ns, p = model.split(":", 1) if ":" in model else ("minecraft", model)
    d = read_asset(f"assets/{ns}/models/{p}.json")
    if not d: return None
    try: m = json.loads(d)
    except Exception: return None
    t = m.get("textures", {})
    for k in ("layer0", "cross", "all", "front", "side", "top", "particle", "texture", "plant"):
        v = t.get(k)
        if v and not v.startswith("#"): return v
    # item model overrides (bows, crossbows, shields with variants) -> first override's model
    if "parent" in m: return model_tex(m["parent"], depth + 1)
    return None

ICON_FALLBACK = {"minecraft:shield": "assets/minecraft/textures/entity/shield_base_nopattern.png"}
icons = {}
def icon(rid):
    if rid in icons: return rid if icons[rid] else ""
    ns, p = rid.split(":", 1)
    cand = [f"assets/{ns}/textures/item/{p}.png"]
    t = model_tex(f"{ns}:item/{p}")
    if t:
        tns, tp = t.split(":", 1) if ":" in t else ("minecraft", t)
        cand.append(f"assets/{tns}/textures/{tp}.png")
    for alt in (f"{ns}:item/{p}_gui", f"{ns}:item/{p}_inventory"):
        t2 = model_tex(alt)
        if t2:
            tns, tp = t2.split(":", 1) if ":" in t2 else ("minecraft", t2)
            cand.append(f"assets/{tns}/textures/{tp}.png")
    cand += [f"assets/{ns}/textures/item/{p}_icon.png", f"assets/{ns}/textures/item/{p}_gui.png", f"assets/{ns}/textures/item/{p}_inventory.png",
             f"assets/{ns}/textures/block/{p}.png"]
    if rid in ICON_FALLBACK: cand.append(ICON_FALLBACK[rid])
    out = None
    for c in cand:
        b = read_asset(c)
        if b and b[:8] == b"\x89PNG\r\n\x1a\n":
            w, hh = struct.unpack(">II", b[16:24])
            if w > 64 and c != ICON_FALLBACK.get(rid): continue
            out = "data:image/png;base64," + base64.b64encode(b).decode(); break
    icons[rid] = out
    return rid if out else ""

def chip(rid, count=1):
    """[name, icon key, count] -- the shape the page renders as an inventory slot"""
    return [item_name(rid), icon(rid), count] if count != 1 else [item_name(rid), icon(rid)]

# ---------------------------------------------------------------- what is gear
def tagset(*names):
    s = []
    for t in names: s += resolve(t)
    return [x for x in dict.fromkeys(s) if exists(x)]

ARMOR_SLOTS = [("head", "투구", ["minecraft:head_armor", "minecraft:enchantable/head_armor", "c:armors/helmets", "c:armor/helmets"]),
               ("chest", "흉갑", ["minecraft:chest_armor", "minecraft:enchantable/chest_armor", "c:armors/chestplates", "c:armor/chestplates"]),
               ("legs", "레깅스", ["minecraft:leg_armor", "minecraft:enchantable/leg_armor", "c:armors/leggings", "c:armor/leggings"]),
               ("feet", "부츠", ["minecraft:foot_armor", "minecraft:enchantable/foot_armor", "c:armors/boots", "c:armor/boots"])]
ACC_SLOTS = [  # (label, tag substrings)
    ("반지", ["curios:ring", "aether:accessories_rings", "accessories:ring", "curios:aether_ring"]),
    ("목걸이", ["curios:necklace", "aether:accessories_pendants", "accessories:necklace"]),
    ("장갑", ["curios:hands", "aether:accessories_gloves", "accessories:hand", "curios:aether_gloves"]),
    ("망토", ["aether:accessories_capes", "trinkets:chest/cape", "accessories:cape"]),
    ("벨트", ["curios:belt", "curios:waist", "accessories:belt"]),
    ("등", ["curios:back", "accessories:back"]),
    ("부적", ["curios:charm", "curios:talisman", "accessories:charm"]),
    ("화살통", ["trinkets:legs/quiver"]),
    ("머리", ["curios:head", "trinkets:head/hat", "accessories:hat", "accessories:face"]),
    ("발", ["curios:feet", "accessories:shoes"]),
    ("방어막", ["aether:accessories_shields"]),
    ("기타", ["aether:accessories_miscellaneous", "trinkets:legs/key"]),
]
NOT_GEAR = {"minecraft:lantern", "minecraft:soul_lantern", "twilightforest:cicada", "twilightforest:firefly", "twilightforest:moonworm",
            "toms_storage:adv_wireless_terminal", "supplementaries:key", "supplementaries:wrench", "starcatcher:fish_radar",
            "minecraft:carved_pumpkin", "minecraft:skeleton_skull", "minecraft:wither_skeleton_skull", "minecraft:zombie_head",
            "minecraft:creeper_head", "minecraft:dragon_head", "minecraft:player_head", "minecraft:piglin_head"}

COSMETIC = re.compile(r"_trophy$|_skull_candle$|_plushie$|hatxolotl$|fisherman_hat_|moon_jellyfish_hat$|^cataclysm:(draugr_head|aptrgangr_head|kobolediator_skull)$")
gear = {}   # rid -> dict(cat, sub)
def put(rid, cat, sub):
    if rid in NOT_GEAR or COSMETIC.search(rid) or rid in gear or not exists(rid): return
    gear[rid] = {"cat": cat, "sub": sub}

for key, label, ts in ARMOR_SLOTS:
    for rid in tagset(*ts):
        if STATS.get(rid, {}).get("cls") in ("ElytraItem",) or "elytra" in rid: put(rid, "armor", "겉날개")
        else: put(rid, "armor", label)
SLOT_SUB = {"HELMET": "투구", "CHESTPLATE": "흉갑", "LEGGINGS": "레깅스", "BOOTS": "부츠"}
for rid, st in STATS.items():          # armor the code builds as ArmorItem but no tag lists (e.g. Hybrid Aquatic diving suits)
    if st.get("cls", "").endswith("ElytraItem"): put(rid, "armor", "겉날개")
    elif st.get("slot") in SLOT_SUB: put(rid, "armor", SLOT_SUB[st["slot"]])
for rid in tagset("c:tools/shield", "c:tools/shields", "minecraft:enchantable/shield"): put(rid, "shield", "방패")
for rid, st in STATS.items():
    if st.get("cls", "").endswith(("ShieldItem", "BucklerItem")): put(rid, "shield", "방패")
for rid in tagset("minecraft:enchantable/crossbow", "c:tools/crossbow", "c:tools/crossbows"): put(rid, "ranged", "석궁")
for rid in tagset("minecraft:enchantable/bow", "c:tools/bow", "c:tools/bows"): put(rid, "ranged", "활")
for rid in tagset("minecraft:enchantable/mace"): put(rid, "melee", "철퇴")
for rid in tagset("minecraft:enchantable/trident", "c:tools/tridents", "c:tools/spear", "c:tools/spears", "aether:tools/lances"): put(rid, "melee", "창")
for rid in tagset("c:tools/ranged_weapon"): put(rid, "ranged", "기타 원거리")
for rid in tagset("minecraft:swords", "c:tools/swords"): put(rid, "melee", "검")
for rid in tagset("minecraft:axes", "c:tools/axes"): put(rid, "melee", "도끼")
for rid in tagset("farmersdelight:tools/knives", "c:tools/knife", "c:tools/knives"): put(rid, "melee", "단검")
for rid in tagset("aether:tools/hammers"): put(rid, "melee", "망치")
for rid in tagset("minecraft:pickaxes", "c:tools/pickaxes"): put(rid, "tool", "곡괭이")
for rid in tagset("minecraft:shovels", "c:tools/shovels"): put(rid, "tool", "삽")
for rid in tagset("minecraft:hoes", "c:tools/hoes"): put(rid, "tool", "괭이")
for rid in tagset("c:tools/melee_weapon", "minecraft:enchantable/sword", "minecraft:enchantable/weapon", "minecraft:enchantable/sharp_weapon", "mowziesmobs:hand_weapons"):
    put(rid, "melee", "기타 근접")
for rid, st in STATS.items():          # anything else that hits harder than a fist in the main hand
    if rid in gear: continue
    if any(a[0] == "ATTACK_DAMAGE" and a[3] == "MAINHAND" and isinstance(a[1], (int, float)) and a[1] > 0 for a in st.get("attrs", [])):
        put(rid, "melee", "기타 근접")
for label, subs in ACC_SLOTS:
    for t in list(tags):
        if any(t == s for s in subs):
            for rid in tagset(t): put(rid, "acc", label)
for t in list(tags):                   # any other accessory slot tag
    if t.startswith(("curios:", "accessories:", "trinkets:")) and t not in ("curios:curio", "accessories:any", "accessories:all"):
        for rid in tagset(t): put(rid, "acc", "기타")

# ---------------------------------------------------------------- numbers
ATTR_KO = {"ATTACK_DAMAGE": "공격력", "ATTACK_SPEED": "공격 속도", "ARMOR": "방어력", "ARMOR_TOUGHNESS": "방어 강도",
           "KNOCKBACK_RESISTANCE": "밀치기 저항", "MOVEMENT_SPEED": "이동 속도", "MAX_HEALTH": "최대 체력", "LUCK": "행운",
           "ATTACK_KNOCKBACK": "밀치기", "ENTITY_INTERACTION_RANGE": "공격 거리", "BLOCK_INTERACTION_RANGE": "블록 상호작용 거리",
           "MINING_EFFICIENCY": "채굴 효율", "SAFE_FALL_DISTANCE": "안전 낙하 거리", "GRAVITY": "중력", "JUMP_STRENGTH": "점프력",
           "OXYGEN_BONUS": "산소 보너스", "WATER_MOVEMENT_EFFICIENCY": "수중 이동 효율", "SWEEPING_DAMAGE_RATIO": "휩쓸기 피해 비율",
           "SCALE": "크기", "STEP_HEIGHT": "오르기 높이", "FALL_DAMAGE_MULTIPLIER": "낙하 피해 배율", "BURNING_TIME": "불타는 시간",
           "SNEAKING_SPEED": "웅크리기 속도", "SUBMERGED_MINING_SPEED": "물속 채굴 속도", "BLOCK_BREAK_SPEED": "블록 파괴 속도",
           "SWIM_SPEED": "수영 속도", "NAMETAG_DISTANCE": "이름표 거리", "EXPLOSION_KNOCKBACK_RESISTANCE": "폭발 밀치기 저항"}
SLOT_KO = {"MAINHAND": "주 손", "OFFHAND": "보조 손", "HAND": "손", "HEAD": "머리", "CHEST": "몸", "LEGS": "다리", "FEET": "발",
           "ARMOR": "갑옷", "ANY": "어디든", "HELMET": "머리", "CHESTPLATE": "몸", "LEGGINGS": "다리", "BOOTS": "발", "BODY": "몸"}

def numbers(rid):
    st = STATS.get(rid)
    if not st: return None
    out = {}
    if st.get("dur"): out["dur"] = st["dur"]
    if st.get("ench") is not None: out["ench"] = st["ench"]
    if st.get("fire"): out["fire"] = 1
    if st.get("unbreakable"): out["unb"] = 1
    extra = []
    for a, amt, op, slot in st.get("attrs", []):
        if not isinstance(amt, (int, float)): continue
        amt = round(amt, 3)
        if a == "ATTACK_DAMAGE" and slot in ("MAINHAND", "HAND") and op in ("ADD_VALUE", None): out["dmg"] = round(1 + amt, 2)
        elif a == "ATTACK_SPEED" and slot in ("MAINHAND", "HAND") and op in ("ADD_VALUE", None): out["spd"] = round(4 + amt, 2)
        elif a == "ARMOR" and op in ("ADD_VALUE", None): out["armor"] = out.get("armor", 0) + amt
        elif a == "ARMOR_TOUGHNESS" and op in ("ADD_VALUE", None): out["tough"] = out.get("tough", 0) + amt
        elif a == "KNOCKBACK_RESISTANCE" and op in ("ADD_VALUE", None): out["kb"] = round(out.get("kb", 0) + amt * 10, 2)
        else:
            nm = ATTR_KO.get(a, a.replace("_", " ").lower())
            pct = op in ("ADD_MULTIPLIED_BASE", "ADD_MULTIPLIED_TOTAL")
            extra.append([nm, round(amt * 100, 1) if pct else amt, 1 if pct else 0, SLOT_KO.get(slot, slot)])
    if extra: out["extra"] = extra
    return out or None

# ---------------------------------------------------------------- tooltips / lore
def tooltip(rid):
    ns, p = rid.split(":", 1)
    if f"tip.{rid}" in KO:      # our own wording when the mod's lines only make sense in-game (key prompts, cooldowns)
        return [t for t in KO[f"tip.{rid}"].split("\n") if t], []
    keys = [k for k in en if k.startswith((f"item.{ns}.{p}.", f"tooltip.{ns}.{p}.", f"tooltip.{ns}.{p}", f"{ns}.{p}.tooltip"))
            and not k.endswith((".title",))]
    keys += [k for k in (f"lore.item.{ns}.{p}", f"item.{ns}.{p}_desc", f"item.{ns}.{p}.desc") if k in en and k not in keys]
    keys.sort(key=lambda k: [int(x) if x.isdigit() else x for x in re.split(r"(\d+)", k)])
    lines, untranslated = [], []
    for k in keys:
        v = lang(k) or en.get(k)
        if not v: continue
        if not lang(k): untranslated.append(k)
        v = re.sub(r"§.", "", v).replace("%s", "").strip()
        if v and v not in lines: lines.append(v)
    return lines, untranslated

# ---------------------------------------------------------------- recipes
def ingredient(x):
    """recipe ingredient -> chip (first matching item for the icon, '...등' name for tags)"""
    if isinstance(x, list):
        alts = [ingredient(i) for i in x]; alts = [a for a in alts if a]
        if not alts: return None
        if len(alts) == 1: return alts[0]
        a = list(alts[0]); a[0] = a[0] + " 등"; return a
    if isinstance(x, str):
        if x.startswith("#"): x = {"tag": x[1:]}
        else: x = {"item": x}
    if not isinstance(x, dict): return None
    if "item" in x or "id" in x:
        rid = x.get("item") or x.get("id")
        if not isinstance(rid, str): return None
        return chip(rid, x.get("count", 1))
    if "tag" in x:
        items = [i for i in resolve(x["tag"]) if exists(i)]
        tk = "tag.item." + x["tag"].replace(":", ".").replace("/", ".")
        nm = lang(tk)          # Korean tag name only; otherwise "first item 등" reads better than an English tag name
        if not items: return [nm or "#" + x["tag"], ""]
        c = chip(items[0])
        c[0] = nm or (c[0] + (" 등" if len(items) > 1 else ""))
        return c
    for k in ("ingredient", "base", "value"):
        if k in x: return ingredient(x[k])
    return None

def result_of(d):
    r = d.get("result") or d.get("output") or d.get("results")
    if isinstance(r, list) and r: r = r[0]
    if isinstance(r, str): return r, 1
    if isinstance(r, dict):
        rid = r.get("id") or r.get("item")
        if isinstance(rid, dict): rid = rid.get("id") or rid.get("item")
        return rid, r.get("count", 1)
    return None, 1

RTYPE = {"minecraft:crafting_shaped": "제작대", "minecraft:crafting_shapeless": "제작대", "minecraft:smithing_transform": "대장장이 작업대",
         "minecraft:smelting": "화로", "minecraft:blasting": "용광로", "minecraft:stonecutting": "석재 절단기",
         "aether:enchanting": "에테르 알타 (마법 부여)", "aether:repairing": "에테르 알타 (수리)", "aether:freezing": "에테르 냉동기",
         "cataclysm:weapon_fusion": "기계 융합 모루", "cataclysm:amethyst_bless": "자수정 축복",
         "deep_aether:combining": "결합기", "farmersdelight:cutting": "도마", "twilightforest:uncrafting": "분해 작업대",
         "neoforge:conditional": "제작대"}

by_result = collections.defaultdict(list)
for rkey, (jn, n, z) in recipes.items():
    d = jload(z, n)
    if not isinstance(d, dict): continue
    conds = d.get("neoforge:conditions") or []
    if any(c.get("type") == "neoforge:mod_loaded" and c.get("modid") not in modids for c in conds if isinstance(c, dict)): continue
    if any(c.get("type") == "neoforge:false" for c in conds if isinstance(c, dict)): continue
    rid, cnt = result_of(d)
    if isinstance(rid, str): by_result[rid].append((rkey, d))

def recipes_for(rid):
    out = []
    for rkey, d in by_result.get(rid, []):
        t = d.get("type", "")
        r = {"t": RTYPE.get(t) or t.split(":")[-1].replace("_", " ")}
        if t == "minecraft:crafting_shaped":
            pat = d.get("pattern", []); key = d.get("key", {})
            w = max((len(row) for row in pat), default=0)
            r["grid"] = [[(ingredient(key[c]) if c in key else None) for c in row.ljust(w)] for row in pat]
        elif t == "minecraft:crafting_shapeless":
            r["grid"] = [[ingredient(i) for i in d.get("ingredients", [])[k:k + 3]] for k in range(0, len(d.get("ingredients", [])), 3)]
        elif t == "minecraft:smithing_transform":
            r["in"] = [x for x in (ingredient(d.get("template")), ingredient(d.get("base")), ingredient(d.get("addition"))) if x]
        else:
            ins = []
            def walk(v, depth=0):
                if depth > 4: return
                if isinstance(v, dict):
                    if "item" in v or "tag" in v:
                        c = ingredient(v)
                        if c: ins.append(c)
                        return
                    for k2, v2 in v.items():
                        if k2 in ("result", "output", "results", "type", "neoforge:conditions"): continue
                        walk(v2, depth + 1)
                elif isinstance(v, list):
                    for v2 in v: walk(v2, depth + 1)
            walk(d)
            if not ins: continue
            r["in"] = ins
        cnt = result_of(d)[1]
        if cnt and cnt > 1: r["n"] = cnt
        if r not in out: out.append(r)
    return out[:4]

# ---------------------------------------------------------------- loot (mob drops, chests)
def loot_items(node, out):
    if isinstance(node, dict):
        if node.get("type") in ("minecraft:item", "item") and isinstance(node.get("name"), str): out.add(node["name"])
        for v in node.values():
            if isinstance(v, (dict, list)): loot_items(v, out)
    elif isinstance(node, list):
        for v in node: loot_items(v, out)
    return out

CHEST_KO = {"end_city_treasure": "엔드 시티", "bastion_treasure": "보루 잔해 (보물실)", "bastion_other": "보루 잔해", "bastion_bridge": "보루 잔해 (다리)",
            "bastion_hoglin_stable": "보루 잔해 (호글린 우리)", "nether_bridge": "네더 요새", "ancient_city": "고대 도시", "ancient_city_ice_box": "고대 도시 (아이스박스)",
            "stronghold_corridor": "요새 복도", "stronghold_crossing": "요새 교차로", "stronghold_library": "요새 도서관",
            "woodland_mansion": "삼림 대저택", "desert_pyramid": "사막 피라미드", "jungle_temple": "정글 사원", "simple_dungeon": "던전",
            "abandoned_mineshaft": "폐광", "buried_treasure": "묻힌 보물", "shipwreck_treasure": "난파선 (보물)", "shipwreck_supply": "난파선 (보급품)",
            "ruined_portal": "폐허가 된 차원문", "pillager_outpost": "약탈자 전초기지", "igloo_chest": "이글루", "underwater_ruin_big": "바다 폐허 (큰)",
            "underwater_ruin_small": "바다 폐허 (작은)", "trial_chambers/reward": "시련의 회당 (보상)", "trial_chambers/reward_ominous": "시련의 회당 (불길한 보상)",
            "trial_chambers/reward_rare": "시련의 회당 (희귀 보상)", "trial_chambers/reward_ominous_rare": "시련의 회당 (불길한 희귀 보상)",
            "trial_chambers/reward_unique": "시련의 회당 (고유 보상)", "trial_chambers/reward_ominous_unique": "시련의 회당 (불길한 고유 보상)",
            "spawn_bonus_chest": "보너스 상자", "ancient_city_center": "고대 도시 (중앙)", "ancient_city": "고대 도시", "trial_chambers": "시련의 회당", "village": "마을", "village/village_weaponsmith": "마을 무기 대장장이", "village/village_armorer": "마을 갑옷 제조인",
            "village/village_toolsmith": "마을 도구 대장장이"}
MOD_KO = {"minecraft": "바닐라", "aether": "에테르", "deep_aether": "Deep Aether", "twilightforest": "황혼의 숲", "cataclysm": "L_Ender's Cataclysm",
          "alexsmobs": "Alex's Mobs", "mowziesmobs": "Mowzie's Mobs", "deeperdarker": "Deeper and Darker", "advancednetherite": "Advanced Netherite",
          "dragonloot": "DragonLoot", "hybrid_aquatic": "Hybrid Aquatic", "farmersdelight": "Farmer's Delight", "twilightdelight": "Twilight Delight",
          "eternalnether": "Eternal Nether", "friendsandfoes": "Friends & Foes", "piglinproliferation": "Piglin Proliferation",
          "supplementaries": "Supplementaries", "starcatcher": "Starcatcher", "inmis": "Inmis (배낭)", "dungeons_arise": "When Dungeons Arise",
          "nova_structures": "Dungeons & Taverns", "yigd": "You're in Grave Danger", "veinmining": "Vein Mining", "mynethersdelight": "My Nether's Delight",
          "endersdelight": "Ender's Delight", "oceansdelight": "Ocean's Delight", "revampedwolf": "Revamped Wolf", "backpacked": "Backpacked",
          "vanillabackport": "Vanilla Backport", "variantsandventures": "Variants & Ventures", "toms_storage": "Tom's Storage"}

DUNGEON_KO = {"bronze": "청동 던전", "silver": "은 던전", "gold": "금 던전", "brass": "황동 던전"}
MOD_KO.update({"mns": "Moog's Nether Structures", "mes": "Moog's End Structures", "mvs": "Moog's Voyager Structures",
               "repurposed_structures": "Repurposed Structures", "betterdungeons": "YUNG's 던전", "betterstrongholds": "YUNG's 요새",
               "betterfortresses": "YUNG's 네더 요새", "betterjungletemples": "YUNG's 정글 사원", "betterwitchhuts": "YUNG's 마녀 오두막",
               "bettermineshafts": "YUNG's 폐광", "betteroceanmonuments": "YUNG's 바다 신전", "hellish_trials": "Hellish Trials",
               "adventuredungeons": "Adventure Dungeons", "explorify": "Explorify", "formations": "Formations", "structory": "Structory",
               "aether_villages": "Aether Villages", "eternalnether": "Eternal Nether"})

def humanize(path):
    p = path.split("/")
    return " ".join(x.replace("_", " ") for x in p[1:] if x not in ("chests",)) or path

drops_of = collections.defaultdict(list)
for lkey, (n, z) in loot.items():
    ns, p = lkey.split(":", 1)
    if not (p.startswith("entities/") or p.startswith("chests/") or "/chests/" in p or p.startswith(("gameplay/", "archaeology/", "rewards/", "trial_spawner/", "equipment/"))):
        continue
    d = jload(z, n)
    if not d: continue
    for it in loot_items(d, set()):
        if p.startswith("entities/"):
            ent = p.split("/", 1)[1].split("/")[0]
            nm = lang(f"entity.{ns}.{ent}") or en.get(f"entity.{ns}.{ent}")
            if not nm: continue
            drops_of[it].append(["몹", nm])
        elif "chests/" in p:
            rest = p.split("chests/", 1)[1]
            segs = rest.split("/"); first = segs[0]
            if first in ("dungeon", "dungeons") and len(segs) > 2: first = DUNGEON_KO.get(segs[1], segs[1] + " dungeon")
            nm = CHEST_KO.get(rest) or CHEST_KO.get(first) or (MOD_KO.get(ns, ns) + ": " + first.replace("_", " "))
            drops_of[it].append(["상자", nm])
        elif p.startswith("archaeology/"):
            drops_of[it].append(["고고학", p.split("/", 1)[1].replace("_", " ")])
        elif p.startswith("trial_spawner/") or p.startswith("rewards/"):
            drops_of[it].append(["시련", p.split("/", 1)[1].replace("_", " ")])
        elif p.startswith("gameplay/"):
            drops_of[it].append(["기타", p.split("/", 1)[1].replace("_", " ")])

# ---------------------------------------------------------------- enchantments
SKIP_ENCH = {"nova_structures:boss_behaviour", "nova_structures:shulker_boss", "nova_structures:shulker_miniboss"}
def etag(t): return set(resolve(t, etags))
TREASURE, TABLE, TRADE, RANDOM, CURSE = etag("minecraft:treasure"), etag("minecraft:in_enchanting_table"), etag("minecraft:tradeable"), etag("minecraft:on_random_loot"), etag("minecraft:curse")

def items_of(spec):
    if spec is None: return None
    if isinstance(spec, str): spec = [spec]
    out = []
    for s in spec:
        out += resolve(s[1:]) if s.startswith("#") else [s]
    return [x for x in dict.fromkeys(out) if exists(x)]

ench_list = []
untrans = collections.OrderedDict()
for eid, d in sorted(enchants.items()):
    if eid in SKIP_ENCH or not isinstance(d, dict): continue
    ns, p = eid.split(":", 1)
    desc = d.get("description")
    key = desc.get("translate") if isinstance(desc, dict) else f"enchantment.{ns}.{p}"
    name = lang(key) or (desc.get("fallback") if isinstance(desc, dict) else None) or en.get(key) or p.replace("_", " ")
    if not lang(key): untrans[key] = en.get(key) or name
    dk = key + ".desc"
    dtext = lang(dk) or lang(f"enchantment.{ns}.{p}.desc")
    if not dtext:
        e2 = en.get(dk) or en.get(f"enchantment.{ns}.{p}.desc")
        untrans[dk] = e2 or ""
        dtext = e2 or ""
    sup = items_of(d.get("supported_items")) or []
    pri = items_of(d.get("primary_items"))
    ex = d.get("exclusive_set")
    exl = []
    if ex:
        for x in ([ex] if isinstance(ex, str) else ex):
            exl += list(etag(x[1:])) if x.startswith("#") else [x]
    exl = [x for x in dict.fromkeys(exl) if x != eid and x in enchants and x not in SKIP_ENCH]
    lv = d.get("max_level", 1)
    def cost(c, l):
        if isinstance(c, dict): return c.get("base", 0) + c.get("per_level_above_first", 0) * (l - 1)
        return c
    get = []
    if eid in TABLE: get.append("table")
    if eid in TRADE: get.append("trade")
    if eid in RANDOM or eid in TABLE: get.append("loot")
    if eid in TREASURE: get.append("treasure")
    if ns == "dungeons_arise": get.append("wda")
    ench_list.append({"id": eid, "n": name, "en": en.get(key) or name, "m": ns, "lv": lv, "w": d.get("weight", 1), "ac": d.get("anvil_cost", 1),
                      "d": re.sub(r"§.", "", dtext), "curse": int(eid in CURSE), "get": get,
                      "cost": [[cost(d.get("min_cost"), l), cost(d.get("max_cost"), l)] for l in range(1, lv + 1)],
                      "slots": d.get("slots", []), "ex": exl, "sup": sup, "pri": pri if pri is not None else sup})

# ---------------------------------------------------------------- assemble items
items_out = []
for rid, g in gear.items():
    ns, p = rid.split(":", 1)
    tips, ut = tooltip(rid)
    for k in ut: untrans[k] = en.get(k, "")
    nk = f"item.{ns}.{p}"
    if not lang(nk) and not lang(f"block.{ns}.{p}"): untrans[nk] = item_en(rid)
    it = {"id": rid, "n": item_name(rid), "en": item_en(rid), "m": ns, "c": g["cat"], "s": g["sub"], "i": icon(rid)}
    num = numbers(rid)
    if num: it["st"] = num
    if STATS.get(rid, {}).get("rarity") in ("UNCOMMON", "RARE", "EPIC"): it["r"] = STATS[rid]["rarity"].lower()
    if tips: it["tip"] = tips
    rs = recipes_for(rid)
    if rs: it["rc"] = rs
    dr = drops_of.get(rid)
    if dr:
        seen = []
        for x in dr:
            if x not in seen: seen.append(x)
        it["dr"] = seen[:30]
    items_out.append(it)

# enchantments that can go on each item, and each enchantment's gear list
gear_ids = set(gear)
for e in ench_list:
    e["sup"] = [x for x in e["sup"] if x in gear_ids]
    e["pri"] = [x for x in e["pri"] if x in gear_ids]

CAT_ORDER = {"melee": 0, "ranged": 1, "armor": 2, "shield": 3, "tool": 4, "acc": 5}
SUB_ORDER = ["검", "도끼", "단검", "창", "철퇴", "망치", "기타 근접", "활", "석궁", "기타 원거리", "투구", "흉갑", "레깅스", "부츠", "겉날개", "방패",
             "곡괭이", "삽", "괭이"] + [s for s, _ in ACC_SLOTS]
items_out.sort(key=lambda i: (CAT_ORDER[i["c"]], SUB_ORDER.index(i["s"]) if i["s"] in SUB_ORDER else 99, i["m"] != "minecraft", i["m"], -(i.get("st", {}).get("dmg") or i.get("st", {}).get("armor") or 0), i["n"]))
ench_list.sort(key=lambda e: (e["m"] != "minecraft", e["curse"], e["n"]))

used_icons = set()
def collect(v):
    if isinstance(v, list):
        if len(v) >= 2 and isinstance(v[0], str) and isinstance(v[1], str) and v[1] in icons: used_icons.add(v[1])
        for x in v: collect(x)
    elif isinstance(v, dict):
        for x in v.values(): collect(x)
collect(items_out)
for it in items_out:
    if it["i"]: used_icons.add(it["i"])
mods_used = sorted({i["m"] for i in items_out} | {e["m"] for e in ench_list}, key=lambda m: (m != "minecraft", MOD_KO.get(m, m).lower()))
out = {"items": items_out, "ench": ench_list, "mods": {m: MOD_KO.get(m, modnames.get(m, m)) for m in mods_used},
       "icons": {k: icons[k] for k in sorted(used_icons) if icons.get(k)}}
json.dump(out, open(os.path.join(HERE, "wiki.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
json.dump(untrans, open(os.path.join(HERE, "untranslated.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

c = collections.Counter((i["c"], i["s"]) for i in items_out)
print(len(items_out), "items", dict(c))
print(len(ench_list), "enchantments;", len(out["icons"]), "icons;", sum(1 for i in items_out if not i["i"]), "items without icon")
print("no stats:", [i["id"] for i in items_out if "st" not in i and i["c"] in ("melee", "armor")])
print("untranslated strings:", len(untrans))
