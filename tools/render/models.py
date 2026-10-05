"""Load model geometry for a mob from the jars: vanilla layers, static createBodyLayer() methods, Citadel constructors."""
import glob, os, sys, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from jvm import VM, Obj, Sym, arg_types
from natives import Natives, VAN, Part
import raster

INST = os.environ.get("MC_INSTANCE", r"C:\Users\Yujin Park\curseforge\minecraft\Instances\Better MC [NEOFORGE] BMC5")
INSTALL = os.environ.get("MC_INSTALL", r"C:\Users\Yujin Park\curseforge\minecraft\Install")
SRG = os.path.join(INSTALL, r"libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar")
VANILLA_ASSETS = os.path.join(INSTALL, r"versions\1.21.1\1.21.1.jar")

def mod_jar(pattern):
    hits = glob.glob(os.path.join(glob.escape(INST), "mods", pattern))
    return hits[0] if hits else None

JARS = {
    "minecraft": [SRG], "alexsmobs": [mod_jar("alexsmobs-*.jar"), mod_jar("citadel-*.jar"), SRG],
    "twilightforest": [mod_jar("twilightforest-*.jar"), SRG], "aether": [mod_jar("aether-1.21.1-*.jar"), SRG],
    "deep_aether": [mod_jar("deep_aether-*.jar"), mod_jar("aether-1.21.1-*.jar"), SRG],
    "friendsandfoes": [mod_jar("friendsandfoes-*.jar"), SRG], "vanillabackport": [mod_jar("VanillaBackport-*.jar"), SRG],
    "hybrid_aquatic": [mod_jar("hybrid_aquatic-*.jar"), mod_jar("hapi-*.jar"), SRG],
}
ASSET_JARS = [mod_jar("VanillaBackport-*.jar"), VANILLA_ASSETS, mod_jar("alexsmobs-*.jar"), mod_jar("twilightforest-*.jar"),
              mod_jar("aether-1.21.1-*.jar"), mod_jar("deep_aether-*.jar"), mod_jar("friendsandfoes-*.jar"),
              mod_jar("Dragon Mounts Remastered-*.jar"), mod_jar("hybrid_aquatic-*.jar")]
_zips = [zipfile.ZipFile(j) for j in ASSET_JARS if j]

def read_asset(path):
    for z in _zips:
        try: return z.read(path)
        except KeyError: pass
    return None

def asset_names():
    s = set()
    for z in _zips: s.update(z.namelist())
    return s

def texture(path):
    b = read_asset(path)
    if b is None: raise FileNotFoundError(path)
    return raster.png_decode(b)

_vms = {}
def vm_for(mod):
    if mod not in _vms: _vms[mod] = VM([j for j in JARS[mod] if j], Natives())
    return _vms[mod]

def default_args(desc):
    out = []
    for t in arg_types(desc):
        if t.endswith("CubeDeformation;"): out.append(Obj(VAN + "CubeDeformation", (0.0, 0.0, 0.0)))
        elif t in ("F", "D"): out.append(0.0)
        elif t == "Z": out.append(0)
        elif t in ("I", "J", "S", "B", "C"): out.append(0)
        else: out.append(None)
    return out

def static_layer(mod, cls, method="createBodyLayer", args=None):
    """Run a static method returning LayerDefinition / MeshDefinition; returns (roots, tw, th)."""
    vm = vm_for(mod); vm.steps = 0; cf = vm.load(cls)
    if cf is None: raise LookupError(cls)
    for (n, d), m in cf.methods.items():
        if n == method and m[1] is not None and (args is None or len(arg_types(d)) == len(args)):
            a = args if args is not None else default_args(d)
            r = vm.run(cf, m, vm.widen(a, d, False))
            if isinstance(r, Obj) and isinstance(r.native, dict): return [r.native["root"]], r.native["tw"], r.native["th"]
            if isinstance(r, Obj) and isinstance(r.native, Part): return [r.native], 64, 32
            raise LookupError(f"{cls}.{method} returned {r!r}")
    raise LookupError(f"{cls}.{method} not found")

def citadel_model(cls):
    """Construct a Citadel AdvancedEntityModel; returns (roots, tw, th)."""
    vm = vm_for("alexsmobs"); vm.steps = 0; nat = vm.natives; nat.citadel_parts = []
    cf = vm.load(cls)
    ctor = None
    for (n, d), m in cf.methods.items():
        if n == "<init>" and m[1] is not None and (ctor is None or len(arg_types(d)) < len(arg_types(ctor[0]))): ctor = (d, m)
    d, m = ctor
    this = Obj(cls)
    vm.run(cf, m, vm.widen([this] + default_args(d), d, True))
    tw = this.f.get("texWidth") or this.f.get("textureWidth") or 64
    th = this.f.get("texHeight") or this.f.get("textureHeight") or 32
    parts = nat.citadel_parts
    roots = [p for p in parts if p.parent is None and any(q.cubes for q in p.walk())]
    return roots, tw, th
