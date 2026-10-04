"""Run LayerDefinitions.createRoots() and return {MODEL_LAYER_NAME: {"root","tw","th"}}."""
import os, sys, pickle
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from jvm import VM, Obj
from natives import Natives
SRG = r"C:\Users\Yujin Park\curseforge\minecraft\Install\libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar"

def vanilla_layers():
    nat = Natives(); vm = VM([SRG], nat)
    cf = vm.load("net/minecraft/client/model/geom/LayerDefinitions")
    m = cf.methods[("createRoots", "()Ljava/util/Map;")]
    try: vm.run(cf, m, [])
    except Exception as e: print("createRoots stopped:", e)   # ends with a "missing layers" check we don't satisfy
    out = {}
    for k, v in nat.puts.items():
        if isinstance(v, Obj) and isinstance(v.native, dict) and "root" in v.native:
            out[k] = v.native
    return out

if __name__ == "__main__":
    L = vanilla_layers()
    print(len(L)); print(sorted(L)[:400])
