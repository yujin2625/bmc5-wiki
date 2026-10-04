"""Find spawn egg colours for every mob in wiki data by reading the bytecode that constructs each SpawnEggItem.

Every mod here uses the vanilla template egg model, so a picture = template + overlay tinted with two colours.
Colours come from `new (Deferred)SpawnEggItem(<EntityType field>, bg, hl, props)` calls; the field name
(e.g. AMEntityRegistry.BALD_EAGLE) lower-cased is matched to the entity path. Dragons use their breed json colours.
Writes eggs.json next to this file: {"colors": {entity_id: [bg, hl]}, "template": dataURI, "overlay": dataURI}
"""
import base64, glob, json, os, re, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import jdis

INST = r"C:\Users\Yujin Park\curseforge\minecraft\Instances\Better MC [NEOFORGE] BMC5"
INSTALL = os.environ.get("MC_INSTALL", r"C:\Users\Yujin Park\curseforge\minecraft\Install")
SRG = os.path.join(INSTALL, r"libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar")
MODS = {"alexsmobs": "alexsmobs-*.jar", "friendsandfoes": "friendsandfoes-*.jar", "twilightforest": "twilightforest-*.jar",
        "aether": "aether-1.21.1-*.jar", "deep_aether": "deep_aether-*.jar", "vanillabackport": "VanillaBackport-*.jar"}

INT = re.compile(r"(?:ldc(?:_w)?|sipush|bipush) (-?\d+)$")
FIELD = re.compile(r"getstatic \S+\.([A-Z0-9_]+):")
ICONST = {"iconst_m1": -1, "iconst_0": 0, "iconst_1": 1, "iconst_2": 2, "iconst_3": 3, "iconst_4": 4, "iconst_5": 5}

def scan(jar_path, prefix=""):
    found = {}
    z = zipfile.ZipFile(jar_path)
    for n in z.namelist():
        if not n.endswith(".class") or not n.startswith(prefix): continue
        data = z.read(n)
        if b"SpawnEgg" not in data: continue
        try: s, _, methods, _, _ = jdis.parse(data)
        except Exception: continue
        for _, _, code in methods:
            if not code: continue
            L = [l.strip() for l in jdis.dis(code, s)]
            for i, l in enumerate(L):
                if "SpawnEgg" in l and "<init>" in l and l.split()[1].startswith("invokespecial"):
                    ints, field = [], None
                    for back in reversed(L[max(0, i - 14):i]):
                        op = back.split()[1] if len(back.split()) > 1 else ""
                        m = INT.search(back)
                        if m and len(ints) < 2: ints.insert(0, int(m.group(1)))
                        elif op in ICONST and len(ints) < 2: ints.insert(0, ICONST[op])
                        f = FIELD.search(back)
                        if f and len(ints) == 2 and f.group(1) not in ("EMPTY",):
                            field = f.group(1); break
                    if field and len(ints) == 2:
                        found.setdefault(field.lower(), [ints[0] & 0xFFFFFF, ints[1] & 0xFFFFFF])
                # helper style: registerSpawnEgg("x_spawn_egg", Types.X, bg, hl) / TFEntities.make(Names.X, ..., bg, hl)
                elif l.split()[1].startswith("invoke") and "II)" in l and i >= 2:
                    a, b = INT.search(L[i - 2]), INT.search(L[i - 1])
                    if a and b:
                        for back in reversed(L[max(0, i - 9):i - 2]):
                            f = FIELD.search(back)
                            if f and "MobCategory" not in back:
                                found.setdefault(f.group(1).lower(), [int(a.group(1)) & 0xFFFFFF, int(b.group(1)) & 0xFFFFFF]); break
    return found

def main():
    wiki = json.load(open(os.path.join(HERE, "wiki.json"), encoding="utf-8"))
    tables = {"minecraft": scan(SRG, "net/minecraft/world/item/")}
    for ns, pat in MODS.items():
        hits = glob.glob(os.path.join(glob.escape(INST), "mods", pat))
        tables[ns] = scan(hits[0]) if hits else {}
    tables["vanillabackport"].update({k: v for k, v in tables["minecraft"].items() if k not in tables["vanillabackport"]})
    dmr = zipfile.ZipFile(glob.glob(os.path.join(glob.escape(INST), "mods", "Dragon Mounts Remastered-*.jar"))[0])
    colors = {}
    for m in wiki["mobs"]:
        ns, path = m["id"].split(":")
        if ns == "dmr":
            b = json.loads(dmr.read(f"data/dmr/dmr/breeds/{path}.json"))
            colors[m["id"]] = [int(b["primary_color"], 16), int(b["secondary_color"], 16)]
            continue
        t = tables.get(m["m"], {}) | tables.get(ns, {})
        for key in (path, path.replace("_", ""), {"trader_llama": "trader_llama"}.get(path, path)):
            if key in t: colors[m["id"]] = t[key]; break
    jar = zipfile.ZipFile(os.path.join(INSTALL, "versions", "1.21.1", "1.21.1.jar"))
    uri = lambda p: "data:image/png;base64," + base64.b64encode(jar.read(p)).decode()
    # Vanilla Backport ships finished egg textures for its newer mobs
    vb = zipfile.ZipFile(glob.glob(os.path.join(glob.escape(INST), "mods", MODS["vanillabackport"]))[0])
    pngs = {}
    for m in wiki["mobs"]:
        p = f"assets/minecraft/textures/item/{m['id'].split(':')[1]}_spawn_egg.png"
        if m["m"] == "vanillabackport" and p in vb.namelist():
            pngs[m["id"]] = "data:image/png;base64," + base64.b64encode(vb.read(p)).decode()
            colors.pop(m["id"], None)
    out = {"colors": colors, "pngs": pngs, "template": uri("assets/minecraft/textures/item/spawn_egg.png"),
           "overlay": uri("assets/minecraft/textures/item/spawn_egg_overlay.png")}
    json.dump(out, open(os.path.join(HERE, "eggs.json"), "w"), separators=(",", ":"))
    missing = [m["id"] for m in wiki["mobs"] if m["id"] not in colors and m["id"] not in pngs]
    print(len(colors), "egg colours; missing:", missing)

if __name__ == "__main__":
    main()
