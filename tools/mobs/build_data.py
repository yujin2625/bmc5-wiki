"""Turn extracted JEB data into the wiki dataset (with item icons)."""
import json, os, zipfile, glob, base64, struct

HERE = os.path.dirname(os.path.abspath(__file__))
INST = r"C:\Users\Yujin Park\curseforge\minecraft\Instances\Better MC [NEOFORGE] BMC5"
INSTALL = r"C:\Users\Yujin Park\curseforge\minecraft\Install"
raw = json.load(open(os.path.join(HERE, "data.json"), encoding="utf-8"))

MODS = {
    "minecraft": "바닐라", "alexsmobs": "Alex's Mobs", "aether": "에테르", "deep_aether": "Deep Aether",
    "twilightforest": "황혼의 숲", "friendsandfoes": "Friends & Foes", "vanillabackport": "Vanilla Backport",
    "dmr": "Dragon Mounts",
}
AETHER_KO = {"Blue Berry": "블루 베리", "Golden Grass Seeds": "황금 잔디 씨앗", "Aechor Petal": "에이커 꽃잎",
             "Aerbunny": "에어버니", "Flying Cow": "날아다니는 소", "Phyg": "피그", "Sheepuff": "쉽퍼프",
             "Quail": "메추라기", "Moa": "모아", "Caiman": "카이만", "Triops": "투구새우", "Crab": "게"}

# ---------- item lookups ----------
jars = sorted(glob.glob(os.path.join(glob.escape(INST), "mods", "*.jar")))
jars.append(os.path.join(INSTALL, "versions", "1.21.1", "1.21.1.jar"))
zips = []
for j in jars:
    try: zips.append(zipfile.ZipFile(j))
    except Exception: pass
index = {}
for z in zips:
    for n in z.namelist():
        if n.startswith("assets/") and (n.endswith(".png") or n.endswith(".json")) and ("/textures/" in n or "/models/" in n):
            index.setdefault(n, z)

def read(n):
    z = index.get(n)
    return z.read(n) if z else None

def tex_path(ref):
    ns, p = (ref.split(":", 1) if ":" in ref else ("minecraft", ref))
    return f"assets/{ns}/textures/{p}.png"

def model_tex(model, depth=0):
    if depth > 6: return None
    ns, p = (model.split(":", 1) if ":" in model else ("minecraft", model))
    data = read(f"assets/{ns}/models/{p}.json")
    if not data: return None
    try: m = json.loads(data)
    except Exception: return None
    t = m.get("textures", {})
    for k in ("layer0", "cross", "all", "front", "side", "top", "particle", "texture", "plant"):
        v = t.get(k)
        if v and not v.startswith("#"):
            return v
    if "parent" in m:
        return model_tex(m["parent"], depth + 1)
    return None

icon_cache = {}
def icon(item_id):
    if item_id in icon_cache: return icon_cache[item_id]
    ns, p = item_id.split(":", 1)
    cand = [f"assets/{ns}/textures/item/{p}.png"]
    t = model_tex(f"{ns}:item/{p}")
    if t: cand.append(tex_path(t))
    cand.append(f"assets/{ns}/textures/block/{p}.png")
    out = None
    for c in cand:
        b = read(c)
        if b and b[:8] == b"\x89PNG\r\n\x1a\n":
            w, h = struct.unpack(">II", b[16:24])
            if w > 64: continue
            out = "data:image/png;base64," + base64.b64encode(b).decode()
            break
    icon_cache[item_id] = out
    return out

icons = {}       # id -> data uri (deduped, referenced by key)
def items(lst):
    res = []
    for it in lst:
        nm = AETHER_KO.get(it["name"], it["name"])
        ic = icon(it["id"])
        if ic: icons[it["id"]] = ic
        res.append([nm, it["id"] if ic else ""])
    return res

def by_ids(ids, names):
    return items([{"id": i, "name": n} for i, n in zip(ids, names)])

# ---------- curated notes, each checked against 1.21.1 bytecode (client-1.21.1-...-srg.jar, Mojang names) ----------
NOTE = {
    "minecraft:wolf": {"tame": "뼈다귀를 줄 때마다 1/3 확률로 길들여집니다(Wolf.tryToTame).",
                       "breed": "길들인 늑대끼리만 번식합니다. 체력이 닳아 있으면 고기를 먹고 회복만 하므로 체력이 가득 찬 상태에서 주세요. Revamped Wolf 모드로 늑대 갑옷도 입힐 수 있습니다."},
    "minecraft:cat": {"tame": "생선을 들고 있으면 다가옵니다. 생선을 줄 때마다 1/3 확률로 길들여집니다(Cat.tryToTame). 마을 고양이와 마녀의 집 고양이 모두 됩니다."},
    "minecraft:parrot": {"tame": "씨앗을 줄 때마다 1/10 확률로 길들여집니다. 쿠키를 주면 독에 걸려 죽으니 주지 마세요.", "breed": "앵무새는 번식할 수 없습니다."},
    "minecraft:horse": {"tame": "빈손으로 여러 번 올라타면 길들여집니다. 이 먹이를 주면 기분(temper)이 올라 더 빨리 길들여집니다: 밀·설탕·사과 +3, 황금 당근 +5, 황금 사과 +10.",
                        "breed": "길들인 어른 말에게 황금 당근, 황금 사과, 마법이 부여된 황금 사과 중 하나를 줍니다. 밀·사과 같은 다른 먹이는 회복과 성장에만 쓰입니다. 말 + 당나귀 = 노새."},
    "minecraft:donkey": {"tame": "말처럼 여러 번 올라타서 길들입니다. 길들인 뒤 상자를 달 수 있습니다.",
                         "breed": "길들인 어른 당나귀에게 황금 당근이나 황금 사과를 줍니다. 말과 짝지으면 노새가 나옵니다."},
    "minecraft:mule": {"tame": "말처럼 여러 번 올라타서 길들입니다.", "breed": "노새는 번식할 수 없습니다. 말과 당나귀를 교배해서 얻습니다."},
    "minecraft:llama": {"tame": "여러 번 올라타서 길들입니다. 밀(+3)이나 건초 더미(+6)를 주면 더 빨리 길들여집니다.",
                        "breed": "길들인 어른 라마에게 건초 더미를 줍니다. 밀로는 번식하지 않습니다."},
    "minecraft:trader_llama": {"tame": "라마처럼 여러 번 올라타서 길들입니다. 길들이거나, 끈으로 직접 묶거나, 플레이어가 타고 있으면 떠돌이 상인과 함께 사라지지 않습니다.",
                               "breed": "길들인 상인 라마에게 건초 더미를 줍니다. 새끼는 일반 라마로 태어납니다."},
    "minecraft:fox": {"trust": "야생 여우 두 마리를 열매로 번식시키면, 새끼는 부모에게 먹이를 준 플레이어를 신뢰해서 도망가지 않고 지켜 줍니다. 어른 여우는 길들일 수 없습니다."},
    "minecraft:ocelot": {"trust": "생선을 들고 있으면 다가옵니다. 3칸 안에 왔을 때 생선을 주면 1/3 확률로 신뢰해서 더는 도망가지 않습니다. 고양이처럼 길들여지지는 않습니다."},
    "minecraft:turtle": {"breed": "번식한 거북이 태어난 해변(집)으로 돌아가 모래 위에 알을 낳습니다."},
    "minecraft:sniffer": {"breed": "번식하면 스니퍼 알을 떨어뜨립니다. 처음 스니퍼 알은 따뜻한 바다 폐허의 수상한 모래를 붓으로 털어서 얻습니다."},
    "minecraft:frog": {"breed": "물에 개구리알을 낳습니다. 올챙이가 개구리로 자라는 곳의 바이옴에 따라 색이 정해집니다(추운 곳·따뜻한 곳·그 외)."},
    "minecraft:axolotl": {"breed": "열대어 양동이를 주면 빈 물 양동이를 돌려받습니다."},
    "minecraft:panda": {"breed": "판다 주변 가로세로 8칸, 높이 3칸 안에 대나무가 하나라도 심어져 있어야 짝을 찾습니다."},
    "minecraft:bee": {"breed": "꽃을 줍니다. 꽃 태그에 들어 있는 모드 꽃도 됩니다."},
    "minecraft:camel": {"tame": "길들일 필요 없이 안장만 얹으면 바로 탈 수 있습니다.", "breed": "선인장을 줍니다."},
    "minecraft:strider": {"tame": "안장을 얹고 뒤틀린 균 낚싯대로 조종합니다."},
    "minecraft:pig": {"tame": "안장을 얹고 당근 낚싯대로 조종합니다."},
    "minecraft:allay": {"tame": "아이템을 건네주면 같은 아이템을 주워서 가져다줍니다.", "dup": "주크박스 음악에 맞춰 춤추는 동안 자수정 조각을 주면 한 마리가 더 생깁니다."},
    "minecraft:hoglin": {"breed": "진홍빛 균으로 번식합니다. 공격적이니 조심하세요."},
}
# breeding items where the JEB data lists the whole food tag but code only allows these (AbstractHorse/Llama.handleEating)
BREED_OVERRIDE = {
    "minecraft:horse": ["minecraft:golden_carrot", "minecraft:golden_apple", "minecraft:enchanted_golden_apple"],
    "minecraft:donkey": ["minecraft:golden_carrot", "minecraft:golden_apple", "minecraft:enchanted_golden_apple"],
    "minecraft:llama": ["minecraft:hay_block"],
    "minecraft:trader_llama": ["minecraft:hay_block"],
}
BREED_OVERRIDE_NAMES = {"minecraft:golden_carrot": "황금 당근", "minecraft:golden_apple": "황금 사과",
                        "minecraft:enchanted_golden_apple": "마법이 부여된 황금 사과", "minecraft:hay_block": "건초 더미"}

KIND_TAME = "길들이기"; KIND_BREED = "번식"; KIND_TRUST = "신뢰"; KIND_EGG = "알"; KIND_DUP = "복제"; KIND_HATCH = "부화"; KIND_BUILD = "소환"; KIND_GROW = "새끼 키우기"


# Alex's Mobs / Friends & Foes / Aether taming notes, checked in each entity's mobInteract/tick/onGetItem bytecode.
# "던져 주기" = drop the item on the ground; the mob picks it up, eats it, then rolls the chance.
AM_TAME = {
    "alexsmobs:bald_eagle": "물고기 기름을 손으로 줄 때마다 1/2 확률로 길들여집니다. 매 눈가리개는 길들인 어른에게만 씌울 수 있고, 생선은 다친 독수리 회복용입니다.",
    "alexsmobs:capuchin_monkey": "바나나를 손으로 주거나 던져 주면 1/5 확률로 길들여집니다.",
    "alexsmobs:kangaroo": "당근을 손으로 줍니다. 10번까지는 길들여지지 않고, 그 뒤로는 1/2 확률, 15번을 넘기면 반드시 길들여집니다.",
    "alexsmobs:komodo_dragon": "썩은 살점을 한 묶음째 손으로 주면 묶음 전체를 먹습니다. 묶음 개수가 59~73 사이 무작위 값보다 많아야 길들여지므로, 64개 한 묶음으로 약 37.5% 확률입니다.",
    "alexsmobs:mantis_shrimp": "열대어를 손으로 줍니다. 10번 이후 1/6 확률, 30번을 넘기면 반드시 길들여집니다.",
    "alexsmobs:mimic_octopus": "바닷가재 꼬리를 손으로 줍니다. 5번 이후 1/2 확률, 8번을 넘기면 반드시 길들여집니다. 다른 블록을 흉내 내는 중에는 길들여지지 않습니다.",
    "alexsmobs:mudskipper": "바닷가재 꼬리를 손으로 줄 때마다 1/2 확률로 길들여집니다.",
    "alexsmobs:sugar_glider": "달콤한 열매를 손으로 줄 때마다 1/2 확률로 길들여집니다.",
    "alexsmobs:tarantula_hawk": "거미 눈을 손으로 줍니다. 15번째부터 1/6 확률, 25번을 넘기면 반드시 길들여집니다.",
    "alexsmobs:warped_toad": "진홍빛 모기 유충을 손으로 줄 때마다 1/3 확률로 길들여집니다.",
    "alexsmobs:flutter": "아직 먹지 않은 종류의 꽃을 하나씩 손으로 줍니다. 서로 다른 꽃을 4종류 넘게 먹으면 1/3 확률, 7종류 넘게 먹으면 반드시 길들여집니다.",
    "alexsmobs:crow": "호박씨를 땅에 던져 주면 주워 먹고 30% 확률로 길들여집니다. 손으로는 줄 수 없습니다.",
    "alexsmobs:gorilla": "바나나를 땅에 던져 주면 주워 먹고 30% 확률로 길들여집니다. 손으로는 줄 수 없습니다.",
    "alexsmobs:raccoon": "달걀을 땅에 던져 주면 물가로 가져가 씻은 뒤 먹고 30% 확률로 길들여집니다.",
    "alexsmobs:cosmaw": "우주 대구를 던져 주거나 손으로 건네면 30% 확률로 길들여집니다.",
    "alexsmobs:elephant": "아카시아나무 꽃을 던져 주거나 건네면 1/3 확률로 길들여집니다. 상아가 있는 어른 코끼리는 길들일 수 없고, 상아 없는 코끼리나 새끼만 됩니다.",
    "alexsmobs:grizzly_bear": "먼저 꿀(꿀이 든 병·벌집 조각·꿀 블록 등)을 먹여 꿀에 취한 상태(약 35초)로 만든 뒤, 그동안 연어를 땅에 던져 주면 30% 확률로 길들여집니다.",
}

mobs = []
def add(id, name, en, mod, sections, tags, extra_search=""):
    mobs.append({"id": id, "n": name, "en": en, "m": mod, "s": sections, "t": sorted(set(tags)), "q": extra_search})

FIX = {  # JEB tags that resolved empty in this pack
    "alexsmobs:anaconda": (["minecraft:chicken", "minecraft:cooked_chicken"], ["익히지 않은 닭고기", "익힌 닭고기"]),
    "alexsmobs:grizzly_bear": (["minecraft:salmon"], ["익히지 않은 연어"]),
}
SKIP = {"minecraft:brown_mooshroom", "alexsmobs:orca"}

for m in raw["mobs"]:
    mid = m["id"]
    if mid in SKIP: continue
    meth = m["methods"]
    if not any(k in meth for k in ("breeding", "taming", "temper", "trusting", "allay_duplication")): continue
    secs, tags = [], []
    note = NOTE.get(mid, {})
    tame_items, tame_extra = [], []
    for k in ("taming", "temper"):
        for e in meth.get(k, []):
            tame_items += e["inputs"]; tame_extra += e["extra"]
    if mid == "alexsmobs:warped_toad":
        tame_items = [{"id": "alexsmobs:mosquito_larva", "name": "진홍빛 모기 유충"}]
    if tame_items or "tame" in note:
        sec = {"k": KIND_TAME, "items": items(tame_items), "note": note.get("tame", "")}
        if tame_extra:
            sec["extra"] = items(tame_extra)
            sec["extraLabel"] = "추가로 필요"
        if "temper" in meth and "tame" not in note:
            sec["note"] = "여러 번 올라타서 길들입니다. 이 먹이를 주면 기분이 올라 더 빨리 길들여집니다."
        if mid in AM_TAME:
            sec["note"] = AM_TAME[mid]
        if mid == "alexsmobs:grizzly_bear":  # JEB has these reversed: salmon tames, honey is the prerequisite
            sec["items"], sec["extra"] = items(tame_extra), items(tame_items)
            sec["extraLabel"] = "먼저 먹일 것"
        secs.append(sec); tags.append(KIND_TAME)
    for e in meth.get("trusting", []):
        secs.append({"k": KIND_TRUST, "items": items(e["inputs"]), "note": note.get("trust", "")}); tags.append(KIND_TRUST)
    for e in meth.get("breeding", []):
        ins = e["inputs"]
        if mid in FIX:
            ins = [{"id": i, "name": n} for i, n in zip(*FIX[mid])]
        if mid in BREED_OVERRIDE:
            ins = [{"id": i, "name": BREED_OVERRIDE_NAMES[i]} for i in BREED_OVERRIDE[mid]]
        sec = {"k": KIND_BREED, "items": items(ins), "note": note.get("breed", "")}
        if not ins:
            if mid == "alexsmobs:rattlesnake": sec["note"] = "음식 아이템이면 무엇이든 됩니다."
            elif mid == "alexsmobs:tasmanian_devil": sec["note"] = "썩은 살점을 뺀 음식 아이템이면 무엇이든 됩니다. 썩은 살점을 주면 울부짖기만 합니다."
            else: continue
        if e["extra"]:
            sec["extra"] = items(e["extra"]); sec["extraLabel"] = "근처에 필요"
        if e.get("tamed"): sec["req"] = "길들인 상태에서만 번식"
        if e.get("trusting"): sec["req"] = "새끼가 플레이어를 신뢰함"
        if e.get("outputs"):
            o = e["outputs"][0]["item"]
            nm = {"alexsmobs:caiman_egg": "카이만 알", "alexsmobs:crocodile_egg": "악어 알", "alexsmobs:platypus_egg": "오리너구리 알",
                  "alexsmobs:terrapin_egg": "늪거북 알", "alexsmobs:triops_egg": "투구새우 알", "friendsandfoes:crab_egg": "게 알",
                  "minecraft:frogspawn": "개구리알", "minecraft:sniffer_egg": "스니퍼 알", "minecraft:turtle_egg": "거북 알"}.get(o, o)
            ic = icon(o)
            if ic: icons[o] = ic
            sec["egg"] = [nm, o if ic else ""]; tags.append(KIND_EGG)
        secs.append(sec); tags.append(KIND_BREED)
    for e in meth.get("allay_duplication", []):
        secs.append({"k": KIND_DUP, "items": items(e["inputs"]), "note": note.get("dup", "")}); tags.append(KIND_DUP)
        if "tame" in note: secs.insert(0, {"k": KIND_TAME, "items": [], "note": note["tame"]}); tags.append(KIND_TAME)
    if "breed" in note and not any(s["k"] == KIND_BREED for s in secs):
        secs.append({"k": KIND_BREED, "items": [], "note": note["breed"], "no": True})
    name = AETHER_KO.get(m["name"], m["name"])
    add(mid, name, m["en"], m["mod"], secs, tags)

def ex(tag):
    # vanilla items first so the familiar ones show before the "+N more" cut
    lst = sorted(raw["extra"].get(tag, []), key=lambda i: not i["id"].startswith("minecraft:"))
    return [[AETHER_KO.get(i["name"], i["name"]), i["id"]] for i in lst]

def with_icons(lst):
    out = []
    for nm, iid in lst:
        ic = icon(iid)
        if ic: icons[iid] = ic
        out.append([nm, iid if ic else ""])
    return out

# --- manual entries ---
add("minecraft:villager", "주민", "Villager", "minecraft", [
    {"k": KIND_BREED, "items": with_icons([["빵", "minecraft:bread"], ["당근", "minecraft:carrot"], ["감자", "minecraft:potato"], ["비트", "minecraft:beetroot"]]),
     "note": "주민에게 음식을 던져 주세요. 한 마리당 빵 3개나 당근·감자·비트 12개가 필요합니다. 마을에 주인 없는 침대가 있어야 하고, 아기 한 명당 빈 침대가 하나 더 필요합니다."}],
    [KIND_BREED], "침대 마을")
add("minecraft:skeleton_horse", "스켈레톤 말", "Skeleton Horse", "minecraft", [
    {"k": KIND_TAME, "items": [], "note": "뇌우 때 스켈레톤 함정 말이 나타납니다. 10칸 안으로 다가가면 번개가 치면서 스켈레톤 기수가 탄 말 4마리로 늘어납니다. 이 말들은 모두 이미 길들여진 상태라서 기수를 처치하면 안장을 얹고 바로 탈 수 있습니다(SkeletonTrapGoal)."},
    {"k": KIND_BREED, "items": [], "note": "번식할 수 없습니다.", "no": True}], [KIND_TAME])
add("minecraft:happy_ghast", "행복한 가스트", "Happy Ghast", "vanillabackport", [
    {"k": KIND_HATCH, "items": with_icons([["마른 가스트", "minecraft:dried_ghast"]]),
     "note": "마른 가스트 블록을 물속에 설치하면 4단계에 걸쳐 물을 머금은 뒤 새끼 행복한 가스트가 태어납니다. 단계마다 5000틱이 걸려서 최소 약 17분이 걸립니다. 물 밖으로 꺼내면 한 단계씩 다시 마릅니다."},
    {"k": KIND_TAME, "items": with_icons(ex("minecraft:happy_ghast_food")),
     "note": "눈덩이를 주면 새끼가 빨리 자랍니다. 다 자라면 하네스를 씌워 최대 4명이 탈 수 있습니다."},
    {"k": KIND_BREED, "items": [], "note": "번식할 수 없습니다.", "no": True}], [KIND_HATCH, KIND_TAME], "하네스")
# Sulfur Cube: checked in VanillaBackport SulfurCube/AbstractCubeMob bytecode + config/vanillabackport-common.toml
add("minecraft:sulfur_cube", "유황 큐브", "Sulfur Cube", "vanillabackport", [
    {"k": KIND_GROW, "items": with_icons(ex("minecraft:sulfur_cube_food")),
     "note": "새끼 유황 큐브에게 슬라임볼을 주면 빨리 자랍니다. 슬라임볼을 들고 있으면 8칸 안의 새끼가 따라옵니다. 길들이는 기능은 없습니다."},
    {"k": KIND_BREED, "items": [], "no": True,
     "note": "먹이로 번식시킬 수 없습니다. 대신 큰 유황 큐브가 죽으면 새끼 2마리로 갈라집니다. 점화된 상태로 죽으면 갈라지지 않습니다."},
    {"k": "블록 먹이기", "items": with_icons([["참나무 판자", "minecraft:oak_planks"], ["TNT", "minecraft:tnt"], ["푸른얼음", "minecraft:blue_ice"],
                                              ["하얀색 양털", "minecraft:white_wool"], ["마그마 블록", "minecraft:magma_block"], ["벌집 블록", "minecraft:honeycomb_block"],
                                              ["철 블록", "minecraft:iron_block"], ["영혼 모래", "minecraft:soul_sand"]]),
     "note": "다 자란 큐브에게 블록을 주면 몸 안에 삼켜서 성질이 바뀝니다. 판자·원목은 잘 튀고, 얼음류는 미끄러지고, 양털은 가벼워지고, 금속 블록은 무겁고 납작해집니다. 마그마 블록은 닿으면 뜨겁고, 벌집 블록은 끈적입니다. 가위로 우클릭하면 삼킨 블록을 뱉습니다. 삼킬 수 있는 블록을 들고 있으면 어른이 따라옵니다."},
    {"k": "폭발", "items": with_icons([["부싯돌과 부시", "minecraft:flint_and_steel"], ["화염구", "minecraft:fire_charge"]]),
     "note": "TNT를 삼킨 큐브만 터집니다. 부싯돌과 부시나 화염구로 우클릭하거나 레드스톤 신호를 받으면 점화됩니다. 서버 설정에서 폭발이 켜져 있습니다(do_sulfur_cubes_explode)."},
    {"k": "포획", "items": with_icons([["양동이", "minecraft:bucket"]]),
     "note": "다 자란 큐브는 빈 양동이로 담아 옮길 수 있습니다. 새끼는 담을 수 없습니다. 유황 동굴 바이옴에서 자연 생성됩니다."}],
    [KIND_GROW], "슬라임볼 양동이 유황 동굴")
add("aether:moa", "모아", "Moa", "aether", [
    {"k": KIND_HATCH, "items": with_icons([["파란 모아 알", "aether:blue_moa_egg"], ["하얀 모아 알", "aether:white_moa_egg"], ["검은 모아 알", "aether:black_moa_egg"]]),
     "note": "모아 알을 인큐베이터에 넣고 앰브로시움 횃불을 연료로 넣으면 1000틱(약 50초) 뒤 부화합니다. 길들이는 개념은 없고, 인큐베이터에서 나온 모아는 '플레이어가 키운 모아'로 표시됩니다."},
    {"k": KIND_GROW, "items": with_icons(ex("aether:moa_food_items")),
     "note": "새끼는 저절로 자라지 않습니다. 가끔 배고픈 상태가 되는데, 그때마다 에이커 꽃잎을 주면 한 단계씩 자라고 3번 먹으면 어른이 됩니다. 다친 어른에게 주면 체력을 5 회복합니다."},
    {"k": "타기", "items": with_icons([["안장", "minecraft:saddle"]]),
     "note": "플레이어가 키운 어른 모아만 안장을 얹을 수 있습니다. 야생 모아에는 안장을 얹을 수 없습니다. 자연의 지팡이로 앉기·따라오기를 바꿉니다. Protect Your Moa 애드온으로 모아 갑옷과 상자도 달 수 있습니다."},
    {"k": KIND_BREED, "items": [], "no": True,
     "note": "번식할 수 없습니다. 대신 아무도 타지 않은 어른 모아가 6000~12000틱(5~10분)마다 자기 색 알을 낳습니다."}], [KIND_HATCH, KIND_GROW, KIND_EGG], "인큐베이터 앰브로시움 횃불")
for mm in mobs:
    if mm["id"] == "deep_aether:quail":
        mm["s"][0]["items"] = with_icons(ex("deep_aether:quail_food"))
        mm["s"][0]["note"] = "씨앗류를 줍니다. 닭처럼 5~10분마다 메추라기 알을 낳고, 알을 던지면 1/8 확률로 새끼가 나옵니다."
    if mm["id"] in ("aether:phyg", "aether:flying_cow"):
        mm["s"][0]["note"] = "다 자란 개체에 안장을 얹어 탈 수 있습니다(날개 달린 탈것)."
    if mm["id"] == "friendsandfoes:crab":
        mm["s"][0]["note"] = "번식하면 굴 자리에 게 알 블록(알 1~4개)을 낳고, 거북 알처럼 단계적으로 부화합니다."
    if mm["id"] == "twilightforest:bighorn_sheep":
        mm["s"][0]["note"] = "양과 같은 방식이라 새끼 털 색이 부모 색을 섞어서 나옵니다."
    if mm["id"] == "friendsandfoes:glare":
        mm["s"].insert(0, {"k": KIND_TAME, "items": with_icons(ex("friendsandfoes:glare_food_items")),
                           "note": "야생 글레어에게 발광 열매를 줄 때마다 1/3 확률로 길들여집니다. 길들인 어른 글레어는 밤이나 하늘이 안 보이는 곳에서 주변의 어두운 곳을 찾아 날아가 알려 주고, 24칸 안의 몬스터를 10초 동안 발광 상태로 만듭니다. 번식은 길들인 글레어끼리만 되고, 새끼도 같은 주인에게 길들여진 채로 태어납니다."})
        mm["t"] = sorted(set(mm["t"] + [KIND_TAME]))

for g in [("minecraft:iron_golem", "철 골렘", "Iron Golem", "철 블록 4개를 T자로 쌓고 머리에 조각된 호박을 올립니다.", [["철 블록", "minecraft:iron_block"], ["조각된 호박", "minecraft:carved_pumpkin"]]),
          ("minecraft:snow_golem", "눈 골렘", "Snow Golem", "눈 블록 2개를 세로로 쌓고 위에 조각된 호박을 올립니다.", [["눈 블록", "minecraft:snow_block"], ["조각된 호박", "minecraft:carved_pumpkin"]])]:
    add(g[0], g[1], g[2], "minecraft", [{"k": KIND_BUILD, "items": with_icons(g[4]), "note": g[3]}], [KIND_BUILD])

# Dragon Mounts Remastered: checked in TameableDragonEntity / DragonBreedableComponent / DragonOwnershipComponent /
# DMREggBlock(Entity) / DragonBreedsRegistry bytecode, data/dmr/dmr/breeds/*.json, data/dmr/tags/block/*, config/dmr-server.toml.
# No breed json sets taming_items/breeding_items, so every breed falls back to #minecraft:fishes for both.
BREEDS = [
    ("fire", "불 드래곤", "사막 피라미드 상자 6.5%", "용암 속에 있으면서 오버월드일 것(둘 다 필요). 불·용암·마그마 블록·모닥불 근처"),
    ("forest", "숲 드래곤", "정글 사원 상자 20%", "정글이나 숲 바이옴. 나뭇잎·묘목·꽃·덩굴 근처"),
    ("ice", "얼음 드래곤", "이글루 상자 15%", "추운 오버월드 바이옴. 얼음·눈 블록 근처"),
    ("lush", "무성한 드래곤", "삼림 대저택 상자 20%, 정글 사원 상자 10%, 진달래 잎을 부술 때 0.01%", "무성한 동굴이나 정글 바이옴. 이끼·동굴 덩굴·발광 이끼 근처"),
    ("end", "엔드 드래곤", "요새 복도 상자 10%. 엔더 드래곤 알도 부화시킬 수 있습니다(allow_egg_override)", "엔드 바이옴, 드래곤의 숨결. 엔드 돌·퍼퍼·엔드 막대기·후렴 식물 근처"),
    ("sculk", "스컬크 드래곤", "고대 도시 상자 10%", "깊은 어둠 바이옴. 스컬크 블록 근처"),
    ("nether", "네더 드래곤", "보루 잔해 보물 상자 35%, 네더 요새 상자 10%", "네더 바이옴. 네더랙·영혼 모래·발광석·네더 사마귀 블록 같은 네더 블록 근처"),
    ("aether", "에테르 드래곤", "던전 상자 15%", "높이 200 이상, 산 바이옴이나 에테르. 하얀색 양털 근처"),
    ("ghost", "유령 드래곤", "폐광 상자 15%", "Y 0 아래이면서 밝기 3 이하일 것(둘 다 필요). 흑요석·우는 흑요석"),
    ("water", "물 드래곤", "묻힌 보물 상자 17.5%", "물속, 바다·강·해변 바이옴. 산호·해초·켈프·프리즈머린·바다 랜턴 근처"),
    ("amethyst", "자수정 드래곤", "요새 도서관 상자 10%", "자수정 블록·싹트는 자수정·자수정 송이 근처"),
]
fish = with_icons(ex("minecraft:fishes"))
meat = with_icons(ex("minecraft:meat"))
for key, nm, where, hab in BREEDS:
    add(f"dmr:{key}", nm, f"{key.title()} Dragon", "dmr", [
        {"k": KIND_HATCH, "items": [],
         "note": f"알 얻는 곳: {where}. 알을 설치하고 우클릭하면 부화가 시작되고, 이 서버 설정으로 600초(10분) 뒤 새끼가 나옵니다. 깨어난 새끼는 아직 야생 상태라서 길들여야 합니다. 새끼는 600초 뒤 어른이 됩니다."},
        {"k": KIND_TAME, "items": fish,
         "note": "물고기를 줄 때마다 1/5 확률로 길들여집니다. 이 서버는 야생 드래곤이 자연 생성되지 않으므로(enable_natural_dragon_spawns = false) 알에서 깨어난 새끼를 길들이면 됩니다. 길들인 뒤 안장을 얹어 타고, 갑옷과 상자를 달 수 있으며, 웅크리고 우클릭하면 인벤토리가 열립니다."},
        {"k": KIND_BREED, "items": fish, "req": "길들인 어른 드래곤 두 마리",
         "note": "둘 다 물고기를 주면 번식하고, 그 자리에 이미 부화 중인 알이 생깁니다. 알의 종은 부모 종, 부모가 있는 곳 환경에 맞는 종, 이들을 섞은 하이브리드 중 무작위로 정해집니다(habitat_offspring, allow_hybridization 켜짐)."},
        {"k": "서식지", "items": [], "note": f"번식할 때 부모 주변이 이 조건에 맞으면 이 종의 알이 나올 수 있습니다: {hab}."},
        {"k": "회복", "items": meat, "note": "길들인 드래곤이 다쳤을 때 고기류를 주면 음식 포만도만큼 체력이 찹니다."}],
        [KIND_HATCH, KIND_TAME, KIND_BREED, KIND_EGG], "드래곤 알 dragon egg 안장 하이브리드")

order = {"minecraft": 0, "vanillabackport": 1, "alexsmobs": 2, "friendsandfoes": 3, "twilightforest": 4, "aether": 5, "deep_aether": 6, "dmr": 7}
mobs.sort(key=lambda x: (order.get(x["m"], 9), x["n"]))
out = {"mods": MODS, "mobs": mobs, "icons": icons}
json.dump(out, open(os.path.join(HERE, "wiki.json"), "w", encoding="utf-8"), ensure_ascii=False)
print(len(mobs), "mobs,", len(icons), "icons,", sum(len(v) for v in icons.values()) // 1024, "KB icons")
missing = sorted({i[0] for m in mobs for s in m["s"] for i in s["items"] if not i[1]})
print("no icon:", len(missing), missing[:60])
