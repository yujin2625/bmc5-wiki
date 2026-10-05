import json, zipfile, glob, os, re, sys
from collections import defaultdict

INST = os.environ.get("MC_INSTANCE", r"C:\Users\Yujin Park\curseforge\minecraft\Instances\Better MC [NEOFORGE] BMC5")
INSTALL = os.environ.get("MC_INSTALL", r"C:\Users\Yujin Park\curseforge\minecraft\Install")
OUT = os.path.join(os.path.dirname(__file__), "data.json")

jars = sorted(glob.glob(os.path.join(glob.escape(INST), "mods", "*.jar")))
jars.append(os.path.join(INSTALL, "versions", "1.21.1", "1.21.1.jar"))
jars += glob.glob(os.path.join(INSTALL, "libraries", "net", "neoforged", "neoforge", "21.1.250", "*universal*.jar"))

tags = defaultdict(list)      # "ns:path" -> list of entries
replace = {}
ko, en = {}, {}
jeb = []
modids = set()
tagre = re.compile(r"^data/([^/]+)/tags/items?/(.+)\.json$")

def loadjson(z, n):
    try:
        return json.loads(z.read(n).decode("utf-8-sig"))
    except Exception:
        return None

for j in jars:
    try:
        z = zipfile.ZipFile(j)
    except Exception:
        continue
    for n in z.namelist():
        m = tagre.match(n)
        if m:
            d = loadjson(z, n)
            if not d: continue
            key = f"{m.group(1)}:{m.group(2)}"
            if d.get("replace"): tags[key] = []
            tags[key] += d.get("values", [])
        elif n.endswith("/lang/ko_kr.json") and n.startswith("assets/"):
            d = loadjson(z, n)
            if isinstance(d, dict): ko.update(d)
        elif n.endswith("/lang/en_us.json") and n.startswith("assets/"):
            d = loadjson(z, n)
            if isinstance(d, dict): en.update(d)
        elif n.startswith("data/justenoughbreeding/recipe/") and n.endswith(".json"):
            d = loadjson(z, n)
            if d: jeb.append((n, d))
        elif n == "META-INF/neoforge.mods.toml":
            for mm in re.finditer(r'modId\s*=\s*"([^"]+)"', z.read(n).decode("utf-8", "ignore")):
                modids.add(mm.group(1))
        elif n == "fabric.mod.json":
            d = loadjson(z, n)
            if d and "id" in d: modids.add(d["id"])
modids.add("minecraft")

# vanilla ko_kr from assets
idx = json.load(open(os.path.join(INSTALL, "assets", "indexes", "17.json")))["objects"]
h = idx["minecraft/lang/ko_kr.json"]["hash"]
vko = json.load(open(os.path.join(INSTALL, "assets", "objects", h[:2], h), encoding="utf-8"))
for k, v in vko.items():
    ko.setdefault(k, v)
ko.update({k: v for k, v in vko.items() if k.startswith(("item.minecraft", "block.minecraft", "entity.minecraft"))})

def resolve(tag, seen=None):
    seen = seen or set()
    if tag in seen: return []
    seen.add(tag)
    out = []
    for v in tags.get(tag, []):
        if isinstance(v, dict):
            if not v.get("required", True) and False: pass
            v = v.get("id")
        if not v: continue
        if v.startswith("#"):
            out += resolve(v[1:], seen)
        else:
            out.append(v)
    return out

def name(kind, rid):
    ns, p = rid.split(":", 1)
    keys = [f"{kind}.{ns}.{p}"]
    if kind == "item": keys.append(f"block.{ns}.{p}")
    for k in keys:
        if k in ko: return ko[k]
    for k in keys:
        if k in en: return en[k]
    return None

def items_of(lst):
    res = []
    for e in lst or []:
        if "tag" in e:
            res += resolve(e["tag"])
        elif "item" in e:
            res.append(e["item"])
    seen, out = set(), []
    for r in res:
        ns = r.split(":")[0]
        if ns not in modids or r in seen: continue
        n = name("item", r)
        if n is None: continue
        seen.add(r); out.append({"id": r, "name": n})
    return out

ALIAS = {"aeather": "aether"}
mobs = {}
for path, d in jeb:
    parts = path.split("/")
    rtype, folder, fname = parts[3], parts[4], parts[5][:-5]
    mod = ALIAS.get(folder, folder)
    cond = [c.get("modid") for c in d.get("neoforge:conditions", []) if "modid" in c]
    if mod not in modids or any(c not in modids for c in cond): continue
    ent = f"{d.get('mod', mod)}:{fname}"
    if rtype == "transformation":
        ent = d.get("input_entity", ent)
        if ent.split(":")[0] not in modids: ent = f"{mod}:{fname}"
    m = mobs.setdefault(ent, {"id": ent, "mod": mod, "name": name("entity", ent) or fname, "en": en.get(f"entity.{ent.replace(':', '.')}", fname), "methods": {}})
    entry = {"inputs": items_of(d.get("inputs")), "extra": items_of(d.get("extra_inputs"))}
    for k, v in d.items():
        if k not in ("neoforge:conditions", "type", "mod", "input_entity", "inputs", "extra_inputs", "spawn_eggs"):
            entry[k] = v
    m["methods"].setdefault(rtype, []).append(entry)

# extra tags for mobs JEB doesn't cover
extra = {}
for t in ["hybrid_aquatic:small_fish", "minecraft:fishes", "minecraft:meat", "minecraft:happy_ghast_food", "minecraft:happy_ghast_tempt_items", "minecraft:sulfur_cube_food",
          "aether:moa_food_items", "aether:moa_temptation_items", "deep_aether:quail_food",
          "friendsandfoes:crab_tempt_items", "friendsandfoes:glare_food_items", "friendsandfoes:glare_tempt_items",
          "minecraft:armadillo_food", "minecraft:sniffer_food", "minecraft:villager_plantable_seeds"]:
    extra[t] = items_of([{"tag": t}])
ents = {}
for k in list(ko.keys()) + list(en.keys()):
    if k.startswith("entity.") and k.count(".") == 2:
        _, ns, p = k.split(".")
        if ns in ("minecraft", "vanillabackport", "friendsandfoes", "deep_aether", "aether", "hybrid_aquatic", "dragonmounts", "dragon_mounts", "dmr", "twilightforest", "revampedwolf"):
            ents[f"{ns}:{p}"] = name("entity", f"{ns}:{p}")

json.dump({"mobs": list(mobs.values()), "extra": extra, "ents": ents, "modids": sorted(modids)}, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(len(mobs), "mobs")
from collections import Counter
print(Counter(m["mod"] for m in mobs.values()))
print(Counter(k for m in mobs.values() for k in m["methods"]))
