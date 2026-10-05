"""Weapon/armor stats by running item registration code.

    python tools/gear/stats.py [modid ...]   -> tools/gear/stats.json
"""
import glob, json, os, re, sys, time, zipfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from gearvm import GearVM, Obj, Sym, sym_name, ITEM

INST = os.environ.get("MC_INSTANCE", r"C:\Users\Yujin Park\curseforge\minecraft\Instances\Better MC [NEOFORGE] BMC5")
INSTALL = os.environ.get("MC_INSTALL", r"C:\Users\Yujin Park\curseforge\minecraft\Install")
SRG = os.path.join(INSTALL, r"libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar")
NEO = os.path.join(INSTALL, r"libraries\net\neoforged\neoforge\21.1.250\neoforge-21.1.250-universal.jar")
NEO_MC = os.path.join(INSTALL, r"libraries\net\neoforged\neoforge\21.1.250\neoforge-21.1.250-client.jar")   # Minecraft classes as patched by NeoForge
MODJARS = sorted(glob.glob(os.path.join(glob.escape(INST), "mods", "*.jar")))

# mods whose equipment we document -> classes are found automatically (anything whose static init registers items)
MODS = ["aether", "deep_aether", "twilightforest", "cataclysm", "alexsmobs", "mowziesmobs", "deeperdarker", "advancednetherite",
        "dragonloot", "hybrid_aquatic", "farmersdelight", "twilightdelight", "eternalnether", "friendsandfoes",
        "piglinproliferation", "supplementaries", "mynethersdelight", "endersdelight", "oceansdelight", "starcatcher",
        "revampedwolf", "vanillabackport", "inmis", "backpacked", "variantsandventures"]

def mod_of_jar():
    out = {}
    for j in MODJARS:
        try: z = zipfile.ZipFile(j)
        except Exception: continue
        if "META-INF/neoforge.mods.toml" in z.namelist():
            t = z.read("META-INF/neoforge.mods.toml").decode("utf-8", "ignore")
            t = t.split("[[dependencies")[0]       # only the mods this jar declares, not what it depends on
            for m in re.finditer(r'modId\s*=\s*"([^"]+)"', t):
                out.setdefault(m.group(1), j)
    return out

# mods that read item numbers from their config at startup: config holder class -> TOML file in the instance config folder
CONFIGS = {"net/dragonloot/init/ConfigInit": "dragonloot-common.toml",
           "com/bobmowzie/mowziesmobs/server/config/ConfigHandler": "mowziesmobs-common.toml"}
# NeoForge ModifyDefaultComponentsEvent handlers that rewrite item numbers from config
COMPONENT_EVENTS = [("mowziesmobs", "com/bobmowzie/mowziesmobs/server/item/ItemHandler", "modifyComponents")]

def load_toml(path, wrap):
    """TOML -> nested dict with keys normalised the way Java field names compare (attack_damage ~ attackDamage)"""
    import tomllib
    def walk(d):
        out = {"$wrap": wrap}
        for k, v in d.items():
            out[k.replace("_", "").replace("-", "").lower()] = walk(v) if isinstance(v, dict) else (int(v) if isinstance(v, bool) else v)
        return out
    with open(path, "rb") as f: return walk(tomllib.load(f))

def registry_classes(jar):
    """classes whose <clinit> calls DeferredRegister item registration"""
    z = zipfile.ZipFile(jar); out = []
    for n in z.namelist():
        if not n.endswith(".class"): continue
        b = z.read(n)
        if b"<clinit>" in b and b"world/item/Item" in b and (b"DeferredRegister" in b or b"registerItem" in b or
                                                           b"(Ljava/lang/String;Ljava/util/function/Supplier;)" in b or b"tterrag/registrate/builders/ItemBuilder" in b):
            out.append(n[:-6])
    return out

def num(v):
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) else None

def summarize(vm, rid, it):
    p = it.f.get("$props", {})
    s = {"cls": it.cls.split("/")[-1]}
    if p.get("durability"): s["dur"] = num(p["durability"])
    if p.get("fire"): s["fire"] = True
    if p.get("unbreakable"): s["unbreakable"] = True
    if p.get("rarity"): s["rarity"] = p["rarity"]
    mods = []
    attrs = p.get("attrs")
    # overridden attribute getters (built in the constructor or computed per item)
    for meth, d in (("getDefaultAttributeModifiers", "()Lnet/minecraft/world/item/component/ItemAttributeModifiers;"),
                    ("getDefaultAttributeModifiers", "(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/component/ItemAttributeModifiers;")):
        cf, m = vm.find_method(it.cls, meth, d)
        if cf and not cf.name.startswith("net/minecraft/"):
            vm.steps = 0
            try:
                r = vm.run(cf, m, [it] + ([Sym("stack")] if "ItemStack" in d else []))
                if isinstance(r, Obj) and r.cls == "$Attrs": attrs = r
            except Exception as e: s["err"] = f"{meth}: {e}"
    if isinstance(attrs, Obj) and isinstance(attrs.native, list):
        for (a, mod, slot) in attrs.native:
            amt = mod.native.get("amount") if isinstance(mod, Obj) and isinstance(mod.native, dict) else None
            op = mod.native.get("op") if isinstance(mod, Obj) and isinstance(mod.native, dict) else None
            mods.append([a, amt, op, slot])
    tier = it.f.get("$tier")
    if isinstance(tier, Obj):
        for k, meth, d in (("ench", "getEnchantmentValue", "()I"), ("tdmg", "getAttackDamageBonus", "()F"), ("tuses", "getUses", "()I")):
            try: v = vm.invoke("net/minecraft/world/item/Tier", meth, d, [tier], virtual=True)
            except Exception: v = None
            if num(v) is not None: s[k] = v
        s["tier"] = tier.f.get("$name") or tier.cls.split("/")[-1]
    mat = it.f.get("$material")
    if isinstance(mat, Obj) and mat.cls == "$Holder": mat = mat.native["value"]
    typ = it.f.get("$type")
    if isinstance(mat, Obj) and typ is not None:
        tname = typ.f.get("$name") if isinstance(typ, Obj) else sym_name(typ)
        s["slot"] = tname
        dmap = mat.f.get("defense")
        dv = dmap.native.get(tname) if isinstance(dmap, Obj) and isinstance(dmap.native, dict) else None
        if num(dv) is not None: mods.append(["ARMOR", dv, "ADD_VALUE", tname])
        if num(mat.f.get("toughness")): mods.append(["ARMOR_TOUGHNESS", mat.f["toughness"], "ADD_VALUE", tname])
        if num(mat.f.get("knockbackResistance")): mods.append(["KNOCKBACK_RESISTANCE", mat.f["knockbackResistance"], "ADD_VALUE", tname])
        if num(mat.f.get("enchantmentValue")) is not None: s["ench"] = mat.f["enchantmentValue"]
    # Accessories API (Aether gloves, rings...) and Curios add their bonuses through these hooks
    for meth, d, extra in (("getDynamicModifiers", "(Lnet/minecraft/world/item/ItemStack;Lio/wispforest/accessories/api/slot/SlotReference;Lio/wispforest/accessories/api/attributes/AccessoryAttributeBuilder;)V", "builder"),
                           ("getAttributeModifiers", "(Ltop/theillusivec4/curios/api/SlotContext;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/item/ItemStack;)Lcom/google/common/collect/Multimap;", "curios")):
        cf, m = vm.find_method(it.cls, meth, d)
        if not cf or cf.name.startswith("net/minecraft/"): continue
        vm.steps = 0
        b = Obj("$Attrs", [])
        try:
            r = vm.run(cf, m, [it, Sym("stack"), Sym("slot"), b] if extra == "builder" else [it, Sym("slot"), Obj("$RL", "x:y"), Sym("stack")])
        except Exception as e: s["err"] = f"{meth}: {e}"; continue
        pairs = b.native if extra == "builder" else ([(a, mo, "CURIO") for a, mo in r.native.get("$multi", [])] if isinstance(r, Obj) and isinstance(r.native, dict) else [])
        for (a, mo, slot) in pairs:
            amt = mo.native.get("amount") if isinstance(mo, Obj) and isinstance(mo.native, dict) else None
            op = mo.native.get("op") if isinstance(mo, Obj) and isinstance(mo.native, dict) else None
            if num(amt) is not None: mods.append([a, amt, op, slot])
    if mods: s["attrs"] = mods
    return s

def main(only=None):
    t0 = time.time()
    jm = mod_of_jar()
    vm = GearVM([NEO_MC, SRG, NEO] + MODJARS)
    nat = vm.natives
    for owner, fn in CONFIGS.items():
        p = os.path.join(INST, "config", fn)
        # DragonLoot keeps plain fields; Mowzie's wraps each value in a ModConfigSpec value object
        if os.path.exists(p): nat.configs[owner] = load_toml(p, wrap="mowzie" in owner)
    vm.steps = 0
    if not only or "minecraft" in only:
        vm.clinit(ITEM + "Items")
    print("vanilla items", len(nat.items), round(time.time() - t0, 1), "s")
    for mod in MODS:
        if only and mod not in only: continue
        jar = jm.get(mod)
        if not jar: print("no jar", mod); continue
        before = len(nat.items); nat.current_ns = mod
        for cls in registry_classes(jar):
            vm.clinit(cls)
        print(mod, len(nat.items) - before, "items", round(time.time() - t0, 1), "s")
    for mod, cls, meth in COMPONENT_EVENTS:
        if only and mod not in only: continue
        cf = vm.load(cls)
        for (n, d), m in (cf.methods.items() if cf else []):
            if n == meth and m[1] is not None:
                vm.steps = 0
                try: vm.run(cf, m, [Obj("net/neoforged/neoforge/event/ModifyDefaultComponentsEvent")])
                except Exception as e: print("  component event", cls, e)
    out = {}
    for rid in nat.order:
        try: out[rid] = summarize(vm, rid, nat.items[rid])
        except Exception as e: out[rid] = {"err": str(e)}
    json.dump(out, open(os.path.join(HERE, "stats.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    errs = getattr(vm, "errors", [])
    print(len(out), "items;", len(errs), "errors")
    for e in errs[:40]: print("  ", e)

if __name__ == "__main__":
    main(sys.argv[1:] or None)
