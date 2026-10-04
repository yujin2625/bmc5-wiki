"""Native stand-ins for the model-building APIs, recording geometry instead of building GPU meshes.

Supported:
  * vanilla 1.21 builders: MeshDefinition, PartDefinition, CubeListBuilder, CubeDeformation, PartPose, LayerDefinition
  * Citadel (Alex's Mobs): AdvancedModelBox / BasicModelPart with setTextureOffset/addBox/setRotationPoint/addChild
  * Mth / Math helpers
Everything else is interpreted from the jars by jvm.VM.
"""
import math
from jvm import Obj, Sym, arg_types

VAN = "net/minecraft/client/model/geom/builders/"
CIT = "com/github/alexthe666/citadel/client/model/"

class Part:
    def __init__(self, name):
        self.name = name
        self.x = self.y = self.z = 0.0
        self.xr = self.yr = self.zr = 0.0
        self.scale = (1.0, 1.0, 1.0)
        self.cubes = []          # dicts: u v x y z w h d grow(dx,dy,dz) mirror uvs(sx,sy)
        self.children = []
        self.parent = None
        self.visible = True
        # citadel state
        self.tex = (0, 0); self.mirror = False
    def __repr__(self): return f"Part({self.name}, {len(self.cubes)} cubes, {len(self.children)} kids)"
    def walk(self):
        yield self
        for c in self.children: yield from c.walk()

def layer_key(k):
    if isinstance(k, Sym):
        parts = [p for p in k.what.replace("()", "").split(".") if p and p not in ("get", "value")]
        return parts[-1] if parts else k.what
    if isinstance(k, Obj) and isinstance(k.native, str): return k.native
    return str(k)

class Builder:  # CubeListBuilder
    def __init__(self): self.cubes = []; self.tex = (0, 0); self.mirror = False

class Pose:
    def __init__(self, x=0.0, y=0.0, z=0.0, xr=0.0, yr=0.0, zr=0.0): self.v = (x, y, z, xr, yr, zr)

class Natives:
    def __init__(self):
        self.citadel_parts = []
        self.puts = {}
        self.attr_puts = {}

    # ---- statics -------------------------------------------------------
    def static_field(self, vm, owner, name):
        if owner.endswith("/ModelLayers"): return True, Sym("ModelLayers." + name)
        if owner.endswith("/EntityType"): return True, Sym("EntityType." + name)
        if owner.endswith("ai/attributes/Attributes"): return True, Sym("Attributes." + name)
        if owner.endswith("PartPose") and name == "ZERO": return True, Obj(owner, Pose())
        if owner.endswith("CubeDeformation") and name == "NONE": return True, Obj(owner, (0.0, 0.0, 0.0))
        if owner == "net/minecraft/util/Mth":
            v = {"PI": math.pi, "TWO_PI": 2 * math.pi, "HALF_PI": math.pi / 2, "DEG_TO_RAD": math.pi / 180,
                 "RAD_TO_DEG": 180 / math.pi, "SQRT_OF_TWO": math.sqrt(2)}.get(name)
            if v is not None: return True, float(v)
        if owner == "java/lang/Math" and name == "PI": return True, math.pi
        return False, None

    def get_field(self, vm, o, name):
        p = o.native
        if isinstance(p, Part):
            m = {"rotateAngleX": "xr", "rotateAngleY": "yr", "rotateAngleZ": "zr", "xRot": "xr", "yRot": "yr", "zRot": "zr",
                 "rotationPointX": "x", "rotationPointY": "y", "rotationPointZ": "z", "x": "x", "y": "y", "z": "z",
                 "defaultRotationX": "xr", "defaultRotationY": "yr", "defaultRotationZ": "zr",
                 "defaultPositionX": "x", "defaultPositionY": "y", "defaultPositionZ": "z", "mirror": "mirror",
                 "showModel": "visible", "visible": "visible"}
            if name in m: return True, getattr(p, m[name])
        return False, None

    def put_field(self, vm, o, name, v):
        p = o.native
        if isinstance(p, Part):
            m = {"rotateAngleX": "xr", "rotateAngleY": "yr", "rotateAngleZ": "zr", "xRot": "xr", "yRot": "yr", "zRot": "zr",
                 "rotationPointX": "x", "rotationPointY": "y", "rotationPointZ": "z", "x": "x", "y": "y", "z": "z",
                 "mirror": "mirror", "showModel": "visible", "visible": "visible"}
            if name in m:
                if isinstance(v, Sym): v = 0.0
                setattr(p, m[name], v); return True
        return False

    # ---- calls ---------------------------------------------------------
    ATTR_DEFAULT = {"MAX_HEALTH": 20.0, "ATTACK_DAMAGE": 2.0, "MOVEMENT_SPEED": 0.7, "ARMOR": 0.0, "FLYING_SPEED": 0.4}
    def attributes(self, vm, owner, name, desc, args):
        """AttributeSupplier.Builder and EntityAttributeCreationEvent: record attribute values per entity type."""
        if owner.endswith("attributes/AttributeSupplier") and name == "builder":
            return Obj(owner + "$Builder", {})
        if owner.endswith("AttributeSupplier$Builder"):
            b = args[0]
            if name == "<init>": b.native = {}; return None
            if not isinstance(b, Obj) or not isinstance(b.native, dict): return NotImplemented
            if name == "add":
                key = args[1].what.split(".")[-1] if isinstance(args[1], Sym) else str(args[1])
                key = key.replace("()", "").split(".")[-1]
                val = args[2] if len(args) > 2 and isinstance(args[2], (int, float)) else self.ATTR_DEFAULT.get(key)
                b.native[key] = val; return b
            if name in ("build", "combine"): return b
        if owner.endswith("EntityAttributeCreationEvent") and name == "put":
            self.attr_puts[layer_key(args[1])] = args[2]; return None
        return NotImplemented

    def handle(self, vm, owner, name, desc, args):
        if "attributes/AttributeSupplier" in owner or owner.endswith("EntityAttributeCreationEvent"):
            r = self.attributes(vm, owner, name, desc, args)
            if r is not NotImplemented: return True, r
        h = getattr(self, "_" + owner.split("/")[-1].replace("$", "_"), None)
        if owner.startswith(VAN) or owner.startswith("net/minecraft/client/model/geom/") or owner in (
                "net/minecraft/util/Mth", "java/lang/Math", "java/lang/Float", "java/lang/Double", "java/lang/Integer"):
            if h:
                r = h(vm, name, desc, args)
                if r is not NotImplemented: return True, r
        if owner.startswith(CIT) and owner.split("/")[-1] in ("AdvancedModelBox", "BasicModelPart", "AdvancedEntityModel", "BasicEntityModel"):
            r = self._citadel(vm, owner, name, desc, args)
            if r is not NotImplemented: return True, r
        if name == "<init>" and args and isinstance(args[0], Obj) and vm.is_a(args[0].cls, CIT + "AdvancedModelBox"):
            r = self._citadel(vm, CIT + "AdvancedModelBox", name, desc, args)
            if r is not NotImplemented: return True, r
        if owner.startswith("com/google/common/collect/ImmutableMap") or owner in ("java/util/Map", "java/util/HashMap"):
            if name in ("builder", "<init>"):
                if name == "<init>" and args: args[0].native = {}
                return True, Obj(owner, {}) if name == "builder" else None
            if name == "put" and args and isinstance(args[0], Obj) and isinstance(args[0].native, dict):
                args[0].native[layer_key(args[1])] = args[2]; self.puts[layer_key(args[1])] = args[2]; return True, args[0]
            if name in ("build", "buildOrThrow"): return True, args[0]
        if owner.startswith("java/") or owner.startswith("com/google/") or owner.startswith("it/unimi/"):
            return True, (Sym(f"{owner}.{name}") if not desc.endswith(")V") else None)
        return False, None

    # vanilla ------------------------------------------------------------
    def _MeshDefinition(self, vm, name, desc, args):
        if name == "<init>": args[0].native = Part("root"); return None
        if name == "getRoot":
            return Obj(VAN + "PartDefinition", args[0].native)
        return NotImplemented

    def _PartDefinition(self, vm, name, desc, args):
        p = args[0].native
        if name == "addOrReplaceChild":
            nm, b, pose = args[1], args[2].native, args[3].native
            child = Part(nm if isinstance(nm, str) else "?")
            child.cubes = list(b.cubes) if isinstance(b, Builder) else []
            if isinstance(pose, Pose):
                child.x, child.y, child.z, child.xr, child.yr, child.zr = pose.v
            p.children = [c for c in p.children if c.name != child.name] + [child]
            child.parent = p
            return Obj(VAN + "PartDefinition", child)
        if name == "getChild":
            for c in p.children:
                if c.name == args[1]: return Obj(VAN + "PartDefinition", c)
            c = Part(args[1]); c.parent = p; p.children.append(c); return Obj(VAN + "PartDefinition", c)
        if name == "clearChild":
            p.children = [c for c in p.children if c.name != args[1]]; return Obj(VAN + "PartDefinition", p)
        if name == "addOrReplaceChild" or name == "<init>": return None
        return NotImplemented

    def _CubeListBuilder(self, vm, name, desc, args):
        if name == "create": return Obj(VAN + "CubeListBuilder", Builder())
        if name == "<init>": args[0].native = Builder(); return None
        b = args[0].native
        if name == "texOffs": b.tex = (args[1], args[2]); return args[0]
        if name == "mirror":
            b.mirror = bool(args[1]) if len(args) > 1 else True; return args[0]
        if name == "addBox":
            types = arg_types(desc); vals = args[1:]
            i = 0; tex = b.tex
            if types[0] == "Ljava/lang/String;": i = 1
            x, y, z, w, h, d = [float(v) if not isinstance(v, Sym) else 0.0 for v in vals[i:i + 6]]
            rest = list(zip(types[i + 6:], vals[i + 6:]))
            grow = (0.0, 0.0, 0.0); mirror = b.mirror; uvs = (1.0, 1.0)
            ints = [v for t, v in rest if t == "I"]
            for t, v in rest:
                if t.endswith("CubeDeformation;") and isinstance(v, Obj) and isinstance(v.native, tuple): grow = v.native
                elif t == "Z": mirror = bool(v)
            fl = [v for t, v in rest if t == "F"]
            if len(fl) == 2: uvs = (fl[0], fl[1])
            if len(ints) == 2: tex = (ints[0], ints[1])
            b.cubes.append(dict(u=tex[0], v=tex[1], x=x, y=y, z=z, w=w, h=h, d=d, grow=grow, mirror=mirror, uvs=uvs))
            return args[0]
        return NotImplemented

    def _CubeDeformation(self, vm, name, desc, args):
        if name == "<init>":
            v = [float(a) for a in args[1:]]
            args[0].native = tuple(v * 3) if len(v) == 1 else tuple(v); return None
        if name == "extend":
            g = args[0].native if isinstance(args[0], Obj) and isinstance(args[0].native, tuple) else (0.0, 0.0, 0.0); e = [float(a) for a in args[1:]]
            e = e * 3 if len(e) == 1 else e
            return Obj(VAN + "CubeDeformation", tuple(a + b for a, b in zip(g, e)))
        return NotImplemented

    def _PartPose(self, vm, name, desc, args):
        f = [float(a) if not isinstance(a, Sym) else 0.0 for a in args]
        if name == "offset": return Obj(VAN + "PartPose", Pose(*f[:3]))
        if name == "rotation": return Obj(VAN + "PartPose", Pose(0, 0, 0, *f[:3]))
        if name == "offsetAndRotation": return Obj(VAN + "PartPose", Pose(*f[:6]))
        return NotImplemented

    def _LayerDefinition(self, vm, name, desc, args):
        if name == "create":
            o = Obj(VAN + "LayerDefinition", {"root": args[0].native, "tw": args[1], "th": args[2]}); return o
        if name == "apply": return args[0]
        return NotImplemented

    def _Mth(self, vm, name, desc, args):
        a = [x if not isinstance(x, Sym) else 0.0 for x in args]
        fn = {"sin": math.sin, "cos": math.cos, "sqrt": math.sqrt, "abs": abs, "floor": math.floor,
              "clamp": lambda v, lo, hi: max(lo, min(hi, v)), "lerp": lambda t, a0, b0: a0 + t * (b0 - a0),
              "wrapDegrees": lambda v: (v + 180) % 360 - 180}.get(name)
        if fn: return fn(*a)
        return NotImplemented

    def _Math(self, vm, name, desc, args):
        a = [x if not isinstance(x, Sym) else 0.0 for x in args]
        fn = {"sin": math.sin, "cos": math.cos, "sqrt": math.sqrt, "abs": abs, "toRadians": math.radians,
              "toDegrees": math.degrees, "min": min, "max": max, "atan2": math.atan2, "pow": math.pow,
              "floor": math.floor, "ceil": math.ceil, "round": round}.get(name)
        if fn: return fn(*a)
        return NotImplemented

    def _Float(self, vm, name, desc, args):
        if name in ("valueOf", "floatValue"): return args[0]
        return NotImplemented
    _Double = _Float; _Integer = _Float

    # citadel ------------------------------------------------------------
    def _citadel(self, vm, owner, name, desc, args):
        o = args[0] if args else None
        if name == "<init>" and owner.endswith("Model"):
            return None   # AdvancedEntityModel ctor: nothing to record
        if name == "<init>":
            p = Part("box"); o.native = p; self.citadel_parts.append(p)
            types = arg_types(desc); rest = list(zip(types[1:], args[2:]))
            ints = [v for t, v in rest if t == "I"]
            strs = [v for t, v in rest if t == "Ljava/lang/String;"]
            if strs: p.name = strs[0]
            if len(ints) >= 2: p.tex = (ints[0], ints[1])
            p.model = args[1]
            return None
        p = o.native if isinstance(o, Obj) else None
        if not isinstance(p, Part):
            return NotImplemented if owner.endswith("Model") else (o if not desc.endswith(")V") else None)
        if name in ("setTextureOffset", "texOffs"): p.tex = (args[1], args[2]); return o
        if name == "setTextureSize": return o
        if name in ("setRotationPoint", "setPos"): p.x, p.y, p.z = [float(a) for a in args[1:4]]; return None
        if name == "addChild":
            c = args[1].native
            if isinstance(c, Part) and c is not p:
                if c.parent: c.parent.children = [k for k in c.parent.children if k is not c]
                c.parent = p; p.children.append(c)
            return None
        if name == "addBox":
            types = arg_types(desc); vals = args[1:]; i = 0
            if types and types[0] == "Ljava/lang/String;": i = 1
            x, y, z, w, h, d = [float(v) if not isinstance(v, Sym) else 0.0 for v in vals[i:i + 6]]
            rest = list(zip(types[i + 6:], vals[i + 6:]))
            grow = 0.0; mirror = p.mirror; tex = p.tex
            fl = [v for t, v in rest if t == "F"]; ints = [v for t, v in rest if t == "I"]
            if fl: grow = fl[0]
            for t, v in rest:
                if t == "Z": mirror = bool(v)
            if len(ints) == 2: tex = (ints[0], ints[1])
            p.cubes.append(dict(u=tex[0], v=tex[1], x=x, y=y, z=z, w=w, h=h, d=d, grow=(grow, grow, grow), mirror=mirror, uvs=(1.0, 1.0)))
            return o
        if name in ("setScale", "setScaleX"): return None
        if name in ("updateDefaultPose", "resetToDefaultPose", "setShouldScaleChildren", "setHidden"): return None
        return NotImplemented
