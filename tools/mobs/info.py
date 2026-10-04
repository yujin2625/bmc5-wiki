"""Per-mob facts from the game files: health/attack (attribute registration code), drops (loot tables),
where it spawns (biome JSON spawners, NeoForge biome modifiers, Alex's Mobs spawn configs).

    python tools/mobs/info.py   -> tools/mobs/info.json
"""
import glob, json, os, re, sys, zipfile, tomllib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "render"))
from jvm import VM, Obj, Sym
from natives import Natives, layer_key
import models

INST = models.INST
wiki = json.load(open(os.path.join(HERE, "wiki.json"), encoding="utf-8"))
MOBS = [m["id"] for m in wiki["mobs"]]

# ---------------------------------------------------------------- jars, lang
jars = sorted(glob.glob(os.path.join(glob.escape(INST), "mods", "*.jar"))) + [models.VANILLA_ASSETS]
zips = []
for j in jars:
    try: zips.append(zipfile.ZipFile(j))
    except Exception: pass
ko, en = {}, {}
for z in zips:
    for n in z.namelist():
        if n.startswith("assets/") and n.endswith(("/lang/ko_kr.json", "/lang/en_us.json")):
            try: d = json.loads(z.read(n).decode("utf-8-sig"))
            except Exception: continue
            (ko if n.endswith("ko_kr.json") else en).update(d)
idx = json.load(open(os.path.join(models.INSTALL, "assets", "indexes", "17.json")))["objects"]
h = idx["minecraft/lang/ko_kr.json"]["hash"]
ko.update(json.load(open(os.path.join(models.INSTALL, "assets", "objects", h[:2], h), encoding="utf-8")))

def name(kind, rid):
    ns, p = rid.split(":", 1)
    for k in (f"{kind}.{ns}.{p}", f"block.{ns}.{p}"):
        if k in ko: return ko[k]
    for k in (f"{kind}.{ns}.{p}", f"block.{ns}.{p}"):
        if k in en: return en[k]
    return None

def data_files(pattern):
    rx = re.compile(pattern); out = {}
    for z in zips:
        for n in z.namelist():
            m = rx.match(n)
            if m and n.endswith(".json"):
                try: out.setdefault(m.groups(), json.loads(z.read(n).decode("utf-8-sig")))
                except Exception: pass
    return out

# ---------------------------------------------------------------- attributes
def attr_events():
    res = {}
    def run(mod, cls, method, args_fn):
        vm = models.vm_for(mod); vm.steps = 0; nat = vm.natives; nat.attr_puts = {}
        cf = vm.load(cls)
        if not cf: return
        for (n, d), m in cf.methods.items():
            if n == method and m[1] is not None:
                try: vm.run(cf, m, vm.widen(args_fn(d), d, False))
                except Exception as e: print("  attr", cls.split("/")[-1], e)
        for k, v in nat.attr_puts.items():
            if isinstance(v, Obj) and isinstance(v.native, dict): res.setdefault((mod, k.lower()), v.native)
    ev = lambda d: [Obj("net/neoforged/neoforge/event/entity/EntityAttributeCreationEvent")]
    # vanilla: DefaultAttributes' static map
    vm = models.vm_for("minecraft"); nat = vm.natives; nat.puts = {}
    cf = vm.load("net/minecraft/world/entity/ai/attributes/DefaultAttributes")
    try: vm.run(cf, cf.methods[("<clinit>", "()V")], [])
    except Exception: pass
    for k, v in nat.puts.items():
        if isinstance(v, Obj) and isinstance(v.native, dict): res[("minecraft", k.lower())] = v.native
    run("alexsmobs", "com/github/alexthe666/alexsmobs/entity/AMEntityRegistry", "initializeAttributes", ev)
    run("twilightforest", "twilightforest/init/RegistrationEvents", "addEntityAttributes", ev)
    run("twilightforest", "twilightforest/events/RegistrationEvents", "addEntityAttributes", ev)
    run("aether", "com/aetherteam/aether/entity/AetherEntityTypes", "registerEntityAttributes", ev)
    run("deep_aether", "io/github/razordevs/deep_aether/init/DAEntities", "registerAttributes", ev)
    def direct(mod, key, cls, method):
        vm = models.vm_for(mod); vm.steps = 0; cf = vm.load(cls)
        if not cf: return
        for (n, d), m in cf.methods.items():
            if n == method and m[1] is not None:
                r = vm.run(cf, m, [])
                if isinstance(r, Obj) and isinstance(r.native, dict): res[(mod, key)] = r.native
    FF = "com/faboslav/friendsandfoes/common/entity/"
    direct("friendsandfoes", "crab", FF + "CrabEntity", "createCrabAttributes")
    direct("friendsandfoes", "glare", FF + "GlareEntity", "createGlareAttributes")
    direct("vanillabackport", "sulfur_cube", "com/blackgear/vanillabackport/common/level/entities/sulfurcube/SulfurCube", "createSulfurCubeAttributes")
    direct("vanillabackport", "happy_ghast", "com/blackgear/vanillabackport/common/level/entities/happyghast/HappyGhast", "createAttributes")
    return res

def find_classes():
    pass

# ---------------------------------------------------------------- drops
def loot_items(node, out):
    if isinstance(node, dict):
        if node.get("type") in ("minecraft:item", "item") and "name" in node: out.append(node["name"])
        elif node.get("type") in ("minecraft:loot_table",) and isinstance(node.get("value") or node.get("name"), str):
            out.append("table:" + (node.get("value") or node.get("name")))
        for v in node.values(): loot_items(v, out) if isinstance(v, (dict, list)) else None
    elif isinstance(node, list):
        for v in node: loot_items(v, out)
    return out

# ---------------------------------------------------------------- spawns
biomes = data_files(r"data/([^/]+)/worldgen/biome/(.+)\.json$")
biome_ids = {f"{ns}:{p}" for ns, p in biomes}
def merged_tags(pattern):
    """Tags merge across every jar (unless one sets replace:true), unlike other data files."""
    rx = re.compile(pattern); out = {}
    zs = zips + [zipfile.ZipFile(j) for j in glob.glob(os.path.join(glob.escape(models.INSTALL), "libraries", "net", "neoforged", "neoforge", "21.1.250", "*universal*.jar"))]
    for z in zs:
        for n in z.namelist():
            m = rx.match(n)
            if not m or not n.endswith(".json"): continue
            try: d = json.loads(z.read(n).decode("utf-8-sig"))
            except Exception: continue
            cur = out.setdefault(m.groups(), {"values": []})
            if d.get("replace"): cur["values"] = []
            cur["values"] += d.get("values", [])
    return out
btags_raw = merged_tags(r"data/([^/]+)/tags/worldgen/biome/(.+)\.json$")
def btag(t, seen=None):
    seen = seen or set()
    if t in seen: return set()
    seen.add(t); ns, p = t.split(":", 1); out = set()
    for v in (btags_raw.get((ns, p)) or {}).get("values", []):
        v = v["id"] if isinstance(v, dict) else v
        out |= btag(v[1:], seen) if v.startswith("#") else {v}
    return out
def biome_set(spec):
    if isinstance(spec, str): return btag(spec[1:]) if spec.startswith("#") else {spec}
    if isinstance(spec, list):
        s = set()
        for x in spec: s |= biome_set(x)
        return s
    return set()

def spawns():
    res = {}
    for (ns, p), b in biomes.items():
        for cat, lst in (b.get("spawners") or {}).items():
            for e in lst if isinstance(lst, list) else []:
                if e.get("weight", 1) > 0: res.setdefault(e["type"], set()).add(f"{ns}:{p}")
    mods_ = data_files(r"data/([^/]+)/neoforge/biome_modifier/(.+)\.json$")
    for _, m in mods_.items():
        if m.get("type") != "neoforge:add_spawns": continue
        bs = biome_set(m.get("biomes"))
        sp = m.get("spawners"); sp = sp if isinstance(sp, list) else [sp]
        for e in sp:
            if e and e.get("weight", 1) > 0: res.setdefault(e["type"], set()).update(bs)
    # Alex's Mobs: config/alexsmobs/<mob>_spawns.json (OR of AND-groups) + spawn weight in alexsmobs-common.toml
    cfg_dir = os.path.join(INST, "config", "alexsmobs")
    try: am_toml = tomllib.load(open(os.path.join(INST, "config", "alexsmobs-common.toml"), "rb"))
    except Exception: am_toml = {}
    flat = {}
    def flatten(d):
        for k, v in d.items():
            if isinstance(v, dict): flatten(v)
            else: flat[k.lower()] = v
    flatten(am_toml)
    btags_of = {}
    for (ns, p) in btags_raw:
        for b in btag(f"{ns}:{p}"): btags_of.setdefault(b, set()).add(f"{ns}:{p}")
    for f in glob.glob(os.path.join(glob.escape(cfg_dir), "*_spawns.json")):
        mob = os.path.basename(f)[:-len("_spawns.json")]
        weight = flat.get(mob.replace("_", "") + "spawnweight", flat.get(mob + "spawnweight"))
        if weight == 0: continue
        cond = json.load(open(f, encoding="utf-8")).get("biomes", [])
        hit = set()
        for b in biome_ids:
            for group in cond:
                ok = True
                for c in group:
                    if c["type"] == "BIOME_TAG": m = c["value"] in btags_of.get(b, set())
                    elif c["type"] == "REGISTRY_NAME": m = c["value"] == b
                    elif c["type"] == "BIOME_CATEGORY": m = False
                    else: m = False
                    if m == c.get("negate", False): ok = False; break
                if ok and group: hit.add(b); break
        res.setdefault("alexsmobs:" + mob, set()).update(hit)
    # Friends & Foes: its own "mob_spawns" biome modifier reads has_<mob> biome tags + weights in config/friendsandfoes.json
    try: ff = json.load(open(os.path.join(INST, "config", "friendsandfoes.json"), encoding="utf-8"))
    except Exception: ff = {}
    for mob, tag in (("crab", "has_crab"), ("glare", "has_glare"), ("moobloom", "has_moobloom/any")):
        cap = mob.capitalize()
        if ff.get(f"enable{cap}Spawn", True) and ff.get(f"{mob}SpawnWeight", 1) > 0:
            res.setdefault("friendsandfoes:" + mob, set()).update(btag("friendsandfoes:" + tag))
    # Vanilla Backport sulfur cubes: spawned by code in the Sulfur Caves biome when has_sulfur_cubes = true
    try: vb = tomllib.load(open(os.path.join(INST, "config", "vanillabackport-common.toml"), "rb"))
    except Exception: vb = {}
    flatv = {}
    def fl(d):
        for k, v in d.items():
            fl(v) if isinstance(v, dict) else flatv.__setitem__(k, v)
    fl(vb)
    if flatv.get("has_sulfur_cubes", True): res.setdefault("minecraft:sulfur_cube", set()).add("minecraft:sulfur_caves")
    return res

def main():
    attrs = attr_events()
    sp = spawns()
    loot = data_files(r"data/([^/]+)/loot_tables?/entities/(.+)\.json$")
    dmr = tomllib.load(open(os.path.join(INST, "config", "dmr-server.toml"), "rb"))
    out = {}
    for mid in MOBS:
        ns, p = mid.split(":")
        mod = next(m["m"] for m in wiki["mobs"] if m["id"] == mid)
        a = attrs.get((mod, p)) or attrs.get((ns, p)) or attrs.get((mod, p.replace("_", "")))
        if mid == "friendsandfoes:moobloom": a = attrs.get(("minecraft", "cow"))   # MoobloomEntity extends Cow
        info = {}
        if ns == "dmr":
            info["hp"] = dmr.get("base_stats", {}).get("base_health", 60.0); info["atk"] = dmr.get("base_stats", {}).get("base_damage", 8.0)
        elif a:
            if a.get("MAX_HEALTH"): info["hp"] = a["MAX_HEALTH"]
            if a.get("ATTACK_DAMAGE"): info["atk"] = a["ATTACK_DAMAGE"]
        drops = []
        lt = loot.get((ns, p))
        for it in loot_items(lt or {}, []):
            if it.startswith("table:"): continue
            n = name("item", it)
            if n and [n, it] not in drops: drops.append([n, it])
        info["drops"] = drops
        bs = sorted(sp.get(mid, set()), key=lambda b: (not b.startswith("minecraft:"), name("biome", b) or b))
        info["biomes"] = [[name("biome", b) or b.split(":")[1].replace("_", " "), b] for b in bs]
        if not bs:
            if ns == "dmr": info["spawn_note"] = "이 서버 설정에서는 야생 드래곤이 자연 생성되지 않습니다(enable_natural_dragon_spawns = false). 알로만 얻습니다."
            elif ns == "alexsmobs": info["spawn_note"] = "이 PC의 Alex's Mobs 스폰 설정은 1.21에 없는 옛 바이옴 태그(예: c:is_dry_overworld)를 가리켜서, 조건에 맞는 바이옴이 없습니다. 서버 설정이 다르면 생성될 수 있습니다."
            else: info["spawn_note"] = "바이옴에서 저절로 생성되지 않습니다. 구조물이나 특수한 조건으로만 나타납니다."
        out[mid] = info
    json.dump(out, open(os.path.join(HERE, "info.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    no_hp = [m for m in MOBS if "hp" not in out[m]]; no_b = [m for m in MOBS if not out[m]["biomes"]]
    print("hp missing:", no_hp); print("no biomes:", no_b)

if __name__ == "__main__":
    main()
