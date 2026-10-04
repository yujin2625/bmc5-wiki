"""Run item registration code (vanilla Items + each mod's DeferredRegister classes) to read weapon/armor stats.

In 1.21.1 attack damage, attack speed, armor and durability live in code, not data files:
    new SwordItem(Tiers.DIAMOND, new Item.Properties().attributes(SwordItem.createAttributes(Tiers.DIAMOND, 3, -2.4F)))
    new ArmorItem(ArmorMaterials.DIAMOND, ArmorItem.Type.HELMET, new Item.Properties().durability(...))
So we interpret that code with the small VM from tools/render/jvm.py, adding lambdas, enums, collections and
native stand-ins for Item.Properties / ItemAttributeModifiers / DeferredRegister that record values.
"""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "render"))
from jvm import VM, Obj, Sym, JVMError, arg_types, returns_value

ITEM = "net/minecraft/world/item/"

class Lambda:
    def __init__(self, kind, owner, name, desc, captured):
        self.kind, self.owner, self.name, self.desc, self.captured = kind, owner, name, desc, captured
    def __repr__(self): return f"λ{self.owner.split('/')[-1]}.{self.name}"

def sym_name(v):
    """Attributes.ATTACK_DAMAGE / EquipmentSlotGroup.MAINHAND -> last name part"""
    if isinstance(v, Sym): return v.what.replace("()", "").split(".")[-1]
    if isinstance(v, Obj) and "$name" in v.f: return v.f["$name"]
    if isinstance(v, Obj) and isinstance(v.native, dict) and "value" in v.native: return sym_name(v.native["value"])
    return str(v)

class GearVM(VM):
    def __init__(self, jars):
        super().__init__(jars, GearNatives())
        self.natives.vm = self

    def get_static(self, owner, name, desc=""):
        key = (owner, name)
        if key in self.statics: return self.statics[key]
        ok, v = self.natives.static_field(self, owner, name)
        if ok: return v
        self.clinit(owner)
        if key in self.statics: return self.statics[key]
        cf = self.load(owner)
        if cf and name in cf.fields and cf.fields[name][2] is not None: return cf.fields[name][2]
        return Sym(f"{owner.split('/')[-1]}.{name}")

    def clinit(self, owner):
        if not hasattr(self, "_inited"): self._inited = set()
        if owner in self._inited: return
        self._inited.add(owner)
        cf = self.load(owner)
        if cf and ("<clinit>", "()V") in cf.methods and cf.methods[("<clinit>", "()V")][1] is not None:
            steps = self.steps; self.steps = 0
            try: self.run(cf, cf.methods[("<clinit>", "()V")], [])
            except Exception as e: self.errors = getattr(self, "errors", []) + [f"clinit {owner}: {e}"]
            finally: self.steps = steps

    def arith(self, op, a, b):
        if op >= 0x78 and (isinstance(a, float) or isinstance(b, float)):   # bit ops on values we only modelled loosely
            a = int(a) if isinstance(a, float) and a == a and abs(a) != float("inf") else (a if not isinstance(a, float) else 0)
            b = int(b) if isinstance(b, float) and b == b and abs(b) != float("inf") else (b if not isinstance(b, float) else 0)
        return super().arith(op, a, b)

    def indy(self, cp, bsm, name, desc, args):
        ref, bargs = bsm
        if name == "makeConcatWithConstants": return super().indy(cp, bsm, name, desc, args)
        if len(bargs) >= 2 and cp.cp[bargs[1]][0] == 15:
            e = cp.cp[bargs[1]]; kind = e[1]; owner, mname, mdesc = cp.ref(e[2])
            return Obj("$Lambda", Lambda(kind, owner, mname, mdesc, list(args)))
        return Sym(f"lambda {name}")

    def call_lambda(self, lam, args):
        if isinstance(lam, Obj) and isinstance(lam.native, Lambda):
            l = lam.native; a = l.captured + list(args)
            if l.kind == 8:   # Foo::new
                o = Obj(l.owner); self.invoke(l.owner, "<init>", l.desc, [o] + a, static=False); return o
            if l.kind == 6: return self.invoke(l.owner, l.name, l.desc, a[:len(arg_types(l.desc))], static=True)
            n = len(arg_types(l.desc)) + 1
            return self.invoke(l.owner, l.name, l.desc, a[:n], virtual=True)
        if isinstance(lam, Obj) and isinstance(lam.native, dict) and "value" in lam.native: return lam.native["value"]
        return lam

class GearNatives:
    def __init__(self):
        self.items = {}         # "ns:path" -> item Obj
        self.order = []
        self.current_ns = None  # mod being registered (for registries that don't carry a namespace)
        self.configs = {}       # owner class -> {field: value} read from the instance's config TOML (see stats.py)

    # ---------------------------------------------------------------- statics
    SYM_OWNERS = ("/Blocks", "/BlockTags", "/ItemTags", "/SoundEvents", "/Attributes", "/MobEffects", "/DataComponents",
                  "/EntityType", "/ParticleTypes", "/CreativeModeTabs", "/Enchantments", "/EquipmentSlotGroup", "/Rarity",
                  "/BuiltInRegistries", "/Registries", "/EquipmentSlot", "/ChatFormatting", "/UseAnim", "/Fluids")
    def static_field(self, vm, owner, name):
        if owner in self.configs: return True, Obj("$Config", self.configs[owner])
        if owner.endswith(self.SYM_OWNERS) or owner.startswith(("java/", "com/google/", "com/mojang/", "it/unimi/")):
            return True, Sym(f"{owner.split('/')[-1]}.{name}")
        return False, None

    @staticmethod
    def norm(k): return k.replace("_", "").replace("-", "").lower()

    def cfg_lookup(self, d, key):
        """config field -> TOML value: same level first, then anywhere below (flat config classes)"""
        if key in d: return d[key]
        for v in d.values():
            if isinstance(v, dict):
                r = self.cfg_lookup(v, key)
                if r is not None: return r
        return None

    def get_field(self, vm, o, name):
        if o.cls == "$Config":
            v = self.cfg_lookup(o.native, self.norm(name))
            if isinstance(v, dict): return True, Obj("$Config", v)
            return True, (Obj("$CfgVal", v) if v is not None else 0) if o.native.get("$wrap") else (v if v is not None else 0)
        return False, None
    def put_field(self, vm, o, name, v): return False

    # ---------------------------------------------------------------- helpers
    def record(self, ns, path, item):
        if not isinstance(item, Obj): return
        rid = f"{ns}:{path}"
        if rid not in self.items: self.order.append(rid)
        self.items[rid] = item
        item.f.setdefault("$id", rid)

    def holder(self, value, rid=None):
        return Obj("$Holder", {"value": value, "id": rid})

    def rl(self, v):
        if isinstance(v, Obj) and v.cls == "$RL": return v.native
        if isinstance(v, str): return v if ":" in v else "minecraft:" + v
        return None

    # ---------------------------------------------------------------- calls
    def handle(self, vm, owner, name, desc, args):
        a0 = args[0] if args else None
        # lambdas and our holders called through their functional interface
        if isinstance(a0, Obj) and isinstance(a0.native, Lambda) and name != "<init>":
            if name in ("equals", "hashCode", "toString"): return True, 0
            return True, vm.call_lambda(a0, args[1:])
        if isinstance(a0, Obj) and a0.cls == "$Holder":
            v = a0.native["value"]
            if name in ("get", "value", "asItem", "getDelegate", "delegate", "invoke"): return True, v
            if name in ("getId", "unwrapKey", "getKey"): return True, a0.native["id"]
            if name in ("isBound",): return True, 1
            if isinstance(v, Obj) and not desc.endswith(")V") is None: pass
            return True, (Sym(f"holder.{name}") if returns_value(desc) else None)
        if name == "wrapAsHolder" or (owner == "net/minecraft/core/Holder" and name == "direct"):
            return True, self.holder(args[-1])
        if isinstance(a0, Obj) and a0.cls == "$CfgVal" and ("Config" in owner or owner.startswith("java/")): return True, a0.native     # ModConfigSpec value .get()/getAsInt()
        if isinstance(a0, Obj) and a0.cls == "$Config" and "Config" in owner: return True, (1 if returns_value(desc) else None)   # isLoaded()...
        if owner == "java/lang/Boolean" and name in ("booleanValue", "valueOf"): return True, int(bool(a0)) if not isinstance(a0, Sym) else 0
        if owner.endswith("ModifyDefaultComponentsEvent") and name == "modify":
            item = args[1].native["value"] if isinstance(args[1], Obj) and args[1].cls == "$Holder" else args[1]
            if isinstance(item, Obj): vm.call_lambda(args[2], [Obj("$Patch", item)])
            return True, None
        if isinstance(a0, Obj) and a0.cls == "$Patch" and owner.endswith("DataComponentPatch$Builder"):
            item = a0.native; p = item.f.setdefault("$props", {}); k = sym_name(args[1]) if len(args) > 1 else ""
            if name == "set":
                if k == "ATTRIBUTE_MODIFIERS": p["attrs"] = args[2]
                if k == "MAX_DAMAGE": p["durability"] = args[2]
            if name == "remove" and k == "MAX_DAMAGE": p["durability"] = None; p["unbreakable"] = True
            return True, (a0 if returns_value(desc) else None)
        if "AccessoryAttributeBuilder" in owner and isinstance(a0, Obj) and a0.cls == "$Attrs":
            if name.startswith("add"): a0.native.append((sym_name(args[1]), args[2], "ACCESSORY"))
            return True, (a0 if returns_value(desc) else None)
        # enums: keep names
        if owner == "java/lang/Enum" and name == "<init>" and isinstance(a0, Obj):
            a0.f["$name"] = args[1]; a0.f["$ord"] = args[2]; return True, None
        if owner == "java/lang/Enum" and name in ("name", "toString") and isinstance(a0, Obj): return True, a0.f.get("$name", "?")
        if owner == "java/lang/Enum" and name == "ordinal" and isinstance(a0, Obj): return True, a0.f.get("$ord", 0)
        if name == "values" and desc.startswith("()[") and vm.load(owner) and vm.load(owner).super == "java/lang/Enum":
            vm.clinit(owner); cf = vm.load(owner)
            return True, [vm.statics.get((owner, f)) for f, (acc, d, _) in cf.fields.items() if acc & 0x4000]
        # collections
        r = self.collections(vm, owner, name, desc, args)
        if r is not NotImplemented: return True, r
        if owner == "net/minecraft/Util" and name == "make":
            if len(args) == 2: vm.call_lambda(args[1], [args[0]]); return True, args[0]
            return True, vm.call_lambda(args[0], [])
        if owner == "com/google/common/base/Suppliers" and name == "memoize": return True, a0
        if owner in ("java/lang/Float", "java/lang/Integer", "java/lang/Double") and name == "valueOf": return True, a0
        if owner in ("java/lang/Float", "java/lang/Integer", "java/lang/Double") and name.endswith("Value"): return True, a0
        if owner == "java/lang/Math":
            import math
            f = {"max": max, "min": min, "abs": abs, "round": round, "floor": math.floor, "ceil": math.ceil, "sqrt": math.sqrt}.get(name)
            if f and all(isinstance(x, (int, float)) for x in args):
                try: return True, f(*args)
                except Exception: pass
            return True, 0
        # resource locations
        if owner == "net/minecraft/resources/ResourceLocation":
            if name in ("fromNamespaceAndPath", "tryBuild"): return True, Obj("$RL", f"{args[0]}:{args[1]}")
            if name in ("withDefaultNamespace",): return True, Obj("$RL", f"minecraft:{args[0]}")
            if name in ("parse", "tryParse", "of"): return True, Obj("$RL", args[0] if ":" in str(args[0]) else f"minecraft:{args[0]}")
            if name in ("getPath",) and isinstance(a0, Obj): return True, a0.native.split(":", 1)[1]
            if name in ("getNamespace",) and isinstance(a0, Obj): return True, a0.native.split(":", 1)[0]
        # registration
        r = self.registration(vm, owner, name, desc, args)
        if r is not NotImplemented: return True, r
        # item building blocks
        r = self.item_parts(vm, owner, name, desc, args)
        if r is not NotImplemented: return True, r
        if owner.startswith(("java/", "com/google/", "com/mojang/", "it/unimi/", "org/slf4j", "org/apache")):
            return True, (Sym(f"{owner.split('/')[-1]}.{name}") if returns_value(desc) else None)
        # client-only / world stuff we never need
        if owner.startswith(("net/minecraft/client/", "net/minecraft/world/level/block/", "net/minecraft/core/dispenser/",
                             "net/minecraft/world/level/Level", "net/minecraft/network/", "net/minecraft/sounds/",
                             "net/minecraft/world/entity/EntityType", "net/minecraft/world/effect/",
                             "net/minecraft/world/item/crafting/", "net/minecraft/tags/", "net/minecraft/core/component/",
                             "net/neoforged/fml/", "net/neoforged/bus/", "net/neoforged/neoforge/common/NeoForge")):
            return True, (Sym(f"{owner.split('/')[-1]}.{name}") if returns_value(desc) else None)
        return False, None

    def collections(self, vm, owner, name, desc, args):
        if owner == "kotlin/TuplesKt" and name == "to": return Obj("$Pair", (args[0], args[1]))
        if owner == "kotlin/collections/MapsKt" and name in ("mapOf", "mutableMapOf", "hashMapOf", "linkedMapOf", "enumMapOf"):
            pairs = args[0] if args and isinstance(args[0], list) else list(args)
            return Obj("java/util/HashMap", {self.key(p.native[0]): p.native[1] for p in pairs if isinstance(p, Obj) and p.cls == "$Pair"})
        if owner == "kotlin/collections/CollectionsKt" and name in ("listOf", "mutableListOf", "arrayListOf", "emptyList"):
            return Obj("java/util/ArrayList", list(args[0]) if args and isinstance(args[0], list) else list(args))
        if owner == "kotlin/enums/EnumEntriesKt" and name == "enumEntries":
            return Obj("java/util/ArrayList", list(args[0]) if args and isinstance(args[0], list) else [])
        a0 = args[0] if args else None
        if isinstance(a0, Obj) and a0.cls == "$Iter":
            it = a0.native
            if name == "hasNext": return int(it["i"] < len(it["l"]))
            if name == "next":
                it["i"] += 1; return it["l"][it["i"] - 1] if it["i"] <= len(it["l"]) else None
        if isinstance(a0, Obj) and name == "iterator" and isinstance(a0.native, (list, dict)):
            return Obj("$Iter", {"l": list(a0.native) if isinstance(a0.native, list) else list(a0.native.values()), "i": 0})
        if owner.startswith("kotlin/jvm/internal/Intrinsics"): return None if not returns_value(desc) else (args[0] if args else 0)
        if not owner.startswith(("java/util/", "com/google/common/collect/", "it/unimi/", "kotlin/collections/")) and not (
                isinstance(a0, Obj) and a0.cls in ("java/util/ArrayList", "java/util/HashMap")): return NotImplemented
        is_map = "Map" in owner
        if name == "<init>" and isinstance(a0, Obj):
            src = args[1] if len(args) > 1 and isinstance(args[1], Obj) else None
            if is_map: a0.native = dict(src.native) if src is not None and isinstance(src.native, dict) else {}
            else: a0.native = list(src.native) if src is not None and isinstance(src.native, list) else []
            return None
        if name in ("of", "copyOf", "asList", "newArrayList", "newHashMap", "builder", "newEnumMap", "ofEntries", "create"):
            if is_map:
                d = {}
                if name == "of":
                    for i in range(0, len(args) - 1, 2): d[self.key(args[i])] = args[i + 1]
                return Obj("java/util/HashMap", d)
            items = a0 if len(args) == 1 and isinstance(a0, list) else list(args)
            if name == "builder": items = []
            return Obj("java/util/ArrayList", items)
        if isinstance(a0, Obj) and isinstance(a0.native, dict):
            d = a0.native
            if name == "put":
                if "Multimap" in owner: d.setdefault("$multi", []).append((sym_name(args[1]), args[2]))
                d[self.key(args[1])] = args[2]; return a0 if "Builder" in owner else (1 if "Multimap" in owner else None)
            if name in ("get", "getOrDefault"): return d.get(self.key(args[1]), args[2] if len(args) > 2 else None)
            if name in ("build", "buildOrThrow", "copyOf"): return a0
            if name == "containsKey": return int(self.key(args[1]) in d)
            if name == "putAll" and isinstance(args[1], Obj) and isinstance(args[1].native, dict): d.update(args[1].native); return None
            if name == "size": return len(d)
            if name in ("values",): return Obj("java/util/ArrayList", list(d.values()))
            return Sym("map." + name) if returns_value(desc) else None
        if isinstance(a0, Obj) and isinstance(a0.native, list):
            l = a0.native
            if name in ("add", "addAll"):
                if name == "add": l.append(args[-1])
                elif isinstance(args[-1], Obj) and isinstance(args[-1].native, list): l.extend(args[-1].native)
                return a0 if "Builder" in owner else 1
            if name == "get" and len(args) > 1 and isinstance(args[1], int) and 0 <= args[1] < len(l): return l[args[1]]
            if name == "size": return len(l)
            if name == "build": return a0
            return Sym("list." + name) if returns_value(desc) else None
        return Sym(owner.split("/")[-1] + "." + name) if returns_value(desc) else None

    def key(self, k):
        if isinstance(k, Obj) and "$name" in k.f: return k.f["$name"]
        if isinstance(k, Sym): return sym_name(k)
        return k if isinstance(k, (str, int, float)) else id(k)

    def registration(self, vm, owner, name, desc, args):
        a0 = args[0] if args else None
        if owner.startswith("net/neoforged/neoforge/registries/DeferredRegister"):
            if name in ("create", "createItems", "createBlocks", "createDataComponents", "createEntities"):
                ns = args[-1] if isinstance(args[-1], str) else "?"
                return Obj("$DR", {"ns": ns, "kind": name})
            if name in ("register", "registerItem", "registerSimpleItem", "registerBlock", "registerSimpleBlock") and isinstance(a0, Obj) and a0.cls == "$DR":
                if name == "register" and "Lnet/neoforged/bus" in desc: return None   # register(IEventBus)
                ns, path = a0.native["ns"], args[1]
                rid = f"{ns}:{path}"
                try:
                    if name == "registerSimpleItem":
                        props = args[2] if len(args) > 2 and isinstance(args[2], Obj) else self.new_props()
                        v = Obj(ITEM + "Item"); self.item_init(vm, v, props)
                    elif name == "registerItem":
                        fn = args[2]
                        props = args[3] if len(args) > 3 else self.new_props()
                        v = vm.call_lambda(fn, [props])
                    else:
                        fn = args[2]
                        lam = fn.native if isinstance(fn, Obj) and isinstance(fn.native, Lambda) else None
                        wants_rl = lam is not None and ((lam.kind == 8 and "ResourceLocation" in lam.desc) or
                                                        (lam.kind != 8 and len(arg_types(lam.desc)) > len(lam.captured)))
                        v = vm.call_lambda(fn, [Obj("$RL", rid)] if wants_rl else [])
                except Exception as e:
                    import traceback; vm.tb = getattr(vm, "tb", None) or traceback.format_exc()
                    vm.errors = getattr(vm, "errors", []) + [f"register {rid}: {type(e).__name__} {e}"]; v = Sym("failed " + rid)
                if isinstance(v, Obj) and vm.is_a(v.cls, ITEM + "Item"): self.record(ns, path, v)
                return self.holder(v, rid)
            if name in ("getNamespace",): return a0.native["ns"]
            return Sym("DR." + name) if returns_value(desc) else None
        # mod-specific helpers: register("name", () -> new Item(...)), Registrate's item("name", Foo::new), PuzzlesLib registerItem...
        if (self.current_ns and name in ("register", "registerItem", "item", "registerItemNoLang") and desc.startswith("(Ljava/lang/String;")
                and args and isinstance(args[-1], Obj) and isinstance(args[-1].native, Lambda)
                and not owner.startswith("net/neoforged/neoforge/registries/DeferredRegister")):
            path = next((x for x in args if isinstance(x, str)), None)
            fn = args[-1]; lam = fn.native
            want = arg_types(lam.desc)[len(lam.captured):] if lam.kind != 8 else arg_types(lam.desc)
            if lam.kind in (5, 9) and lam.kind != 8: want = ([None] + want)[:len(want)]
            call = [self.new_props() if t.endswith("Item$Properties;") else Obj("$RL", f"{self.current_ns}:{path}") if t.endswith("ResourceLocation;") else None
                    for t in want]
            try: v = vm.call_lambda(fn, call)
            except Exception as e:
                vm.errors = getattr(vm, "errors", []) + [f"register {path}: {type(e).__name__} {e}"]; v = Sym("failed " + path)
            if isinstance(v, Obj) and vm.is_a(v.cls, ITEM + "Item"):
                self.record(self.current_ns, path, v)
                return self.holder(v, f"{self.current_ns}:{path}")
            if name == "item": return Obj("$ItemBuilder", {"item": v, "path": path})
            if not (isinstance(v, Obj) and vm.load(owner) is not None and vm.find_method(owner, name, desc)[0] is not None):
                return self.holder(v, f"{self.current_ns}:{path}")
            # the lambda made something else (e.g. the Properties for a helper) -> run the helper itself
        if isinstance(a0, Obj) and a0.cls == "$ItemBuilder":    # Registrate: .properties(p -> ...).tag(...).register()
            b = a0.native
            if name == "properties" and isinstance(args[1], Obj) and isinstance(args[1].native, Lambda):
                b.setdefault("props", []).append(args[1])
            if name == "register":
                return self.holder(b.get("item"), b.get("path"))
            return a0 if returns_value(desc) else None
        if owner in ("net/minecraft/core/Registry",) and name in ("register", "registerForHolder"):
            rid = args[1]; v = args[2]
            if isinstance(rid, Obj) and rid.cls == "$RL": rid = rid.native
            elif isinstance(rid, str): rid = rid if ":" in rid else "minecraft:" + rid
            else: rid = None
            if rid and isinstance(v, Obj) and vm.is_a(v.cls, ITEM + "Item"): self.record(*rid.split(":", 1), v)
            return self.holder(v, rid) if name == "registerForHolder" else v
        if owner == ITEM + "Items" and name == "registerItem":
            # registerItem(String, Item) / (ResourceLocation, Item) / (ResourceKey, Item)
            rid = self.rl(a0) or (a0.native if isinstance(a0, Obj) else None)
            v = args[-1]
            if isinstance(rid, str) and isinstance(v, Obj): self.record(*rid.split(":", 1), v)
            return v
        if owner == ITEM + "Items" and name in ("registerBlock",): return Sym("blockitem")
        return NotImplemented

    def new_props(self):
        return Obj(ITEM + "Item$Properties", {"durability": None, "attrs": None, "stack": 64, "fire": False, "rarity": None})

    def item_init(self, vm, this, props):
        p = props.native if isinstance(props, Obj) and isinstance(props.native, dict) else {}
        this.f["$props"] = dict(p)

    def item_parts(self, vm, owner, name, desc, args):
        a0 = args[0] if args else None
        if owner == ITEM + "Item$Properties":
            if name == "<init>": a0.native = self.new_props().native; return None
            p = a0.native if isinstance(a0, Obj) and isinstance(a0.native, dict) else None
            if p is None: return a0
            if name == "durability": p["durability"] = args[1]; p["stack"] = 1
            elif name == "defaultDurability":
                if p["durability"] is None: p["durability"] = args[1]
                p["stack"] = 1
            elif name == "stacksTo": p["stack"] = args[1]
            elif name == "fireResistant": p["fire"] = True
            elif name == "rarity": p["rarity"] = sym_name(args[1])
            elif name == "attributes": p["attrs"] = args[1]
            elif name == "component":
                k = sym_name(args[1])
                if k == "MAX_DAMAGE": p["durability"] = args[2]
                if k == "ATTRIBUTE_MODIFIERS": p["attrs"] = args[2]
                if k == "FIRE_RESISTANT": p["fire"] = True
            return a0 if returns_value(desc) else None
        if owner == ITEM + "component/ItemAttributeModifiers":
            if name == "builder": return Obj("$Attrs", [])
            if name == "<init>": a0.native = list(args[1].native) if isinstance(args[1], Obj) and isinstance(args[1].native, list) else []; return None
            if name in ("withModifierAdded",) and isinstance(a0, Obj):
                return Obj("$Attrs", (a0.native or []) + [(sym_name(args[1]), args[2], sym_name(args[3]))])
            if name == "modifiers" and isinstance(a0, Obj): return Obj("java/util/ArrayList", list(a0.native or []))
            return Sym("attrs." + name) if returns_value(desc) else None
        if owner == ITEM + "component/ItemAttributeModifiers$Builder":
            if isinstance(a0, Obj) and a0.cls == "$Attrs":
                if name == "add": a0.native.append((sym_name(args[1]), args[2], sym_name(args[3]))); return a0
                if name == "build": return a0
            return Sym("attrs." + name) if returns_value(desc) else None
        if owner == ITEM + "component/ItemAttributeModifiers$Entry" and name == "<init>":
            a0.native = (sym_name(args[1]), args[2], sym_name(args[3])); return None
        if owner == "net/minecraft/world/entity/ai/attributes/AttributeModifier":
            if name == "<init>":
                a0.native = {"amount": args[2] if isinstance(args[2], (int, float)) else 0.0, "op": sym_name(args[-1])}; return None
            if name == "amount" and isinstance(a0, Obj) and isinstance(a0.native, dict): return a0.native["amount"]
        # item constructors: record the parts we read later, skip the game-side wiring
        if name == "<init>" and isinstance(a0, Obj):
            if owner == ITEM + "Item":
                self.item_init(vm, a0, args[1]); return None
            if owner == ITEM + "TieredItem":
                tier = args[1]; a0.f["$tier"] = tier; a0.f["tier"] = tier
                props = args[2]
                uses = vm.invoke("net/minecraft/world/item/Tier", "getUses", "()I", [tier], virtual=True) if isinstance(tier, Obj) else None
                if isinstance(props, Obj) and isinstance(props.native, dict) and isinstance(uses, int):
                    props.native["durability"] = uses; props.native["stack"] = 1
                self.item_init(vm, a0, props); return None
            if owner == ITEM + "ArmorItem":
                a0.f["$material"] = args[1]; a0.f["$type"] = args[2]
                self.item_init(vm, a0, args[3]); return None
        if owner == "net/minecraft/world/item/ArmorMaterial" and name == "<init>" and isinstance(a0, Obj):
            for f, v in zip(("defense", "enchantmentValue", "equipSound", "repairIngredient", "layers", "toughness", "knockbackResistance"), args[1:]):
                a0.f[f] = v
            return None
        return NotImplemented
