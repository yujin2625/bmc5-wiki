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

# ---------- curated notes for vanilla & known mechanics ----------
NOTE = {
    "minecraft:wolf": {"tame": "뼈다귀를 여러 번 주면 빨간 목줄이 생기며 길들여집니다.", "breed": "길들인 늑대끼리, 체력이 가득 찬 상태에서 고기류를 줍니다."},
    "minecraft:cat": {"tame": "생선을 들고 가만히 있으면 다가옵니다. 다가오면 생선을 주세요. 마을 고양이만 길들일 수 있습니다."},
    "minecraft:parrot": {"tame": "씨앗을 줍니다. 쿠키를 주면 죽으니 절대 주지 마세요.", "breed": "앵무새는 번식할 수 없습니다."},
    "minecraft:horse": {"tame": "빈손으로 여러 번 올라타면 됩니다. 먹이를 주면 기분(temper)이 올라 더 빨리 길들여집니다.", "breed": "길들인 말 두 마리에게 황금 당근이나 황금 사과를 줍니다. 말 + 당나귀 = 노새."},
    "minecraft:donkey": {"tame": "말과 같습니다. 여러 번 올라타서 길들이고 상자를 달 수 있습니다.", "breed": "길들인 당나귀끼리 황금 당근이나 황금 사과를 줍니다."},
    "minecraft:mule": {"tame": "말처럼 여러 번 올라타서 길들입니다.", "breed": "노새는 번식할 수 없습니다. 말과 당나귀를 교배해서 얻습니다."},
    "minecraft:llama": {"tame": "여러 번 올라타서 길들입니다. 밀이나 건초 더미를 주면 더 빨리 길들여집니다.", "breed": "길들인 라마끼리 건초 더미를 줍니다."},
    "minecraft:trader_llama": {"tame": "떠돌이 상인이 사라진 뒤 라마처럼 올라타서 길들입니다."},
    "minecraft:fox": {"trust": "야생 여우 두 마리를 달콤한 열매로 번식시키면, 태어난 새끼가 플레이어를 신뢰해 따라옵니다. 어른 여우는 길들일 수 없습니다."},
    "minecraft:ocelot": {"trust": "생선을 들고 천천히 다가가 먹이를 주면 신뢰해서 도망가지 않습니다. 고양이처럼 길들여지지는 않습니다."},
    "minecraft:turtle": {"breed": "번식한 거북이 태어난 해변으로 돌아가 모래 위에 알을 낳습니다."},
    "minecraft:sniffer": {"breed": "알을 낳습니다. 처음 스니퍼 알은 바다 폐허의 수상한 모래를 붓으로 털어서 얻습니다."},
    "minecraft:frog": {"breed": "물에 개구리알을 낳습니다. 올챙이가 자란 바이옴에 따라 개구리 색이 정해집니다."},
    "minecraft:axolotl": {"breed": "열대어 양동이를 주면 빈 물 양동이를 돌려받습니다."},
    "minecraft:panda": {"breed": "근처 5칸 안에 대나무 블록이 8개 이상 있어야 번식합니다."},
    "minecraft:bee": {"breed": "꽃을 줍니다. 모드 꽃 대부분도 됩니다."},
    "minecraft:camel": {"tame": "안장만 얹으면 바로 탈 수 있습니다.", "breed": "선인장을 줍니다."},
    "minecraft:strider": {"tame": "안장을 얹고 뒤틀린 균 낚싯대로 조종합니다."},
    "minecraft:pig": {"tame": "안장을 얹고 당근 낚싯대로 조종합니다."},
    "minecraft:allay": {"tame": "아이템을 건네주면 그 아이템을 모아다 줍니다.", "dup": "주크박스에서 음악이 나오는 동안 자수정 조각을 주면 한 마리가 더 생깁니다."},
    "minecraft:hoglin": {"breed": "진홍빛 균으로 번식합니다. 공격적이니 조심하세요."},
}

KIND_TAME = "길들이기"; KIND_BREED = "번식"; KIND_TRUST = "신뢰"; KIND_EGG = "알"; KIND_DUP = "복제"; KIND_HATCH = "부화"; KIND_BUILD = "소환"

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
        if not sec["note"] and mid.startswith("alexsmobs:"):
            sec["note"] = "먹이를 여러 번 주면 길들여집니다. 몹에 따라 새끼만 길들일 수 있는 경우가 있습니다."
        secs.append(sec); tags.append(KIND_TAME)
    for e in meth.get("trusting", []):
        secs.append({"k": KIND_TRUST, "items": items(e["inputs"]), "note": note.get("trust", "")}); tags.append(KIND_TRUST)
    for e in meth.get("breeding", []):
        ins = e["inputs"]
        if mid in FIX:
            ins = [{"id": i, "name": n} for i, n in zip(*FIX[mid])]
        sec = {"k": KIND_BREED, "items": items(ins), "note": note.get("breed", "")}
        if not ins:
            if mid == "alexsmobs:rattlesnake": sec["note"] = "고기류를 줍니다."
            elif mid == "alexsmobs:tasmanian_devil": sec["note"] = "고기류를 줍니다."
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
    return [[AETHER_KO.get(i["name"], i["name"]), i["id"]] for i in raw["extra"].get(tag, [])]

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
    {"k": KIND_TAME, "items": [], "note": "뇌우 때 스켈레톤 함정 말이 나타납니다. 다가가면 스켈레톤 기수 4명이 생기고, 기수를 처치하면 남은 말은 이미 길들여진 상태입니다."},
    {"k": KIND_BREED, "items": [], "note": "번식할 수 없습니다.", "no": True}], [KIND_TAME])
add("minecraft:happy_ghast", "행복한 가스트", "Happy Ghast", "vanillabackport", [
    {"k": KIND_HATCH, "items": with_icons([["마른 가스트", "minecraft:dried_ghast"]]),
     "note": "마른 가스트 블록을 물속에 설치하면 시간이 지나 새끼 가스트(가스틀링)가 태어납니다."},
    {"k": KIND_TAME, "items": with_icons(ex("minecraft:happy_ghast_food")),
     "note": "눈덩이를 주면 새끼가 빨리 자랍니다. 다 자라면 하네스를 씌워 최대 4명이 탈 수 있습니다."},
    {"k": KIND_BREED, "items": [], "note": "번식할 수 없습니다.", "no": True}], [KIND_HATCH, KIND_TAME], "하네스")
add("minecraft:sulfur_cube", "유황 큐브", "Sulfur Cube", "vanillabackport", [
    {"k": KIND_TAME, "items": with_icons(ex("minecraft:sulfur_cube_food")), "note": "슬라임볼이 먹이 아이템으로 등록되어 있습니다. 자세한 동작은 게임 안 EMI에서 확인하세요."}],
    [KIND_TAME])
add("aether:moa", "모아", "Moa", "aether", [
    {"k": KIND_HATCH, "items": with_icons([["파란 모아 알", "aether:blue_moa_egg"], ["하얀 모아 알", "aether:white_moa_egg"], ["검은 모아 알", "aether:black_moa_egg"]]),
     "note": "모아 알을 인큐베이터에 넣고 앰브로시움 횃불로 데우면 부화합니다. 부화시킨 플레이어의 모아가 됩니다."},
    {"k": KIND_TAME, "items": with_icons(ex("aether:moa_food_items")), "note": "새끼 모아에게 에이커 꽃잎을 주면 자랍니다. 다 자란 모아에 안장을 얹어 탑니다."},
    {"k": KIND_BREED, "items": [], "note": "번식할 수 없습니다. 알로만 얻습니다.", "no": True}], [KIND_HATCH, KIND_TAME])
for mm in mobs:
    if mm["id"] == "deep_aether:quail":
        mm["s"][0]["items"] = with_icons(ex("deep_aether:quail_food"))
    if mm["id"] == "friendsandfoes:glare":
        mm["s"].insert(0, {"k": KIND_TAME, "items": with_icons(ex("friendsandfoes:glare_food_items")),
                           "note": "발광 열매를 주면 길들여집니다. 길들인 글레어는 주변의 어두운 곳을 찾아 알려 줍니다."})
        mm["t"] = sorted(set(mm["t"] + [KIND_TAME]))

for g in [("minecraft:iron_golem", "철 골렘", "Iron Golem", "철 블록 4개를 T자로 쌓고 머리에 조각된 호박을 올립니다.", [["철 블록", "minecraft:iron_block"], ["조각된 호박", "minecraft:carved_pumpkin"]]),
          ("minecraft:snow_golem", "눈 골렘", "Snow Golem", "눈 블록 2개를 세로로 쌓고 위에 조각된 호박을 올립니다.", [["눈 블록", "minecraft:snow_block"], ["조각된 호박", "minecraft:carved_pumpkin"]])]:
    add(g[0], g[1], g[2], "minecraft", [{"k": KIND_BUILD, "items": with_icons(g[4]), "note": g[3]}], [KIND_BUILD])

BREEDS = [("fire", "불 드래곤", "사막 피라미드", "6.5%", "용암 속이나 마그마 블록 근처"), ("forest", "숲 드래곤", "정글 사원", "20%", "정글이나 숲 바이옴"),
          ("ice", "얼음 드래곤", "이글루", "15%", "추운 바이옴, 얼음·눈 블록 근처"), ("lush", "무성한 드래곤", "보물 상자, 정글 사원, 삼림 대저택", "10~20%", "무성한 동굴이나 정글"),
          ("end", "엔드 드래곤", "요새 복도", "10%", "엔드 바이옴, 드래곤의 숨결 근처"), ("sculk", "스컬크 드래곤", "고대 도시", "10%", "깊은 어둠 바이옴, 스컬크 블록 근처"),
          ("nether", "네더 드래곤", "보루 잔해 보물, 네더 요새", "35% / 10%", "네더"), ("aether", "에테르 드래곤", "던전", "15%", "높이 200 이상, 산이나 에테르"),
          ("ghost", "유령 드래곤", "폐광", "15%", "Y 0 아래, 밝기 3 이하"), ("water", "물 드래곤", "묻힌 보물", "17.5%", "물속, 바다·강 바이옴"),
          ("amethyst", "자수정 드래곤", "요새 도서관", "10%", "자수정 블록 근처")]
for key, nm, where, pct, hab in BREEDS:
    add(f"dmr:{key}", nm, f"{key.title()} Dragon", "dmr", [
        {"k": KIND_HATCH, "items": [], "note": f"드래곤 알은 {where} 상자에서 {pct} 확률로 나옵니다. 알을 설치하고 우클릭하면 부화가 시작되고, 부화시킨 플레이어의 드래곤이 됩니다."},
        {"k": "서식지", "items": [], "note": f"부화 중인 알 주변 환경이 {hab}이면 이 종으로 자랍니다. 빈 드래곤 알은 설치한 곳의 환경에 맞춰 종이 정해집니다."}],
        [KIND_HATCH], "드래곤 알 dragon egg")

order = {"minecraft": 0, "vanillabackport": 1, "alexsmobs": 2, "friendsandfoes": 3, "twilightforest": 4, "aether": 5, "deep_aether": 6, "dmr": 7}
mobs.sort(key=lambda x: (order.get(x["m"], 9), x["n"]))
out = {"mods": MODS, "mobs": mobs, "icons": icons}
json.dump(out, open(os.path.join(HERE, "wiki.json"), "w", encoding="utf-8"), ensure_ascii=False)
print(len(mobs), "mobs,", len(icons), "icons,", sum(len(v) for v in icons.values()) // 1024, "KB icons")
missing = sorted({i[0] for m in mobs for s in m["s"] for i in s["items"] if not i[1]})
print("no icon:", len(missing), missing[:60])
