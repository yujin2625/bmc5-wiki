"""Render every mob in the wiki from its in-game model + texture.

    python tools/render/render_mobs.py            -> tools/render/out/mobs/<ns>-<path>.png and contact.png
Specs below say where each mob's model comes from:
    ("van", LAYER)                vanilla LayerDefinitions entry (run from the client jar)
    ("static", mod, Class, meth)  static createBodyLayer()-style method in a mod jar
    ("cit", Class)                Citadel model constructor (Alex's Mobs)
    ("geo", path)                 GeckoLib/Bedrock .geo.json (Dragon Mounts)
"""
import json, math, os, sys, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import models, raster, geo
from layers_vanilla import vanilla_layers

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out", "mobs")
WIKI = os.path.join(os.path.dirname(HERE), "mobs", "wiki.json")
SIZE = 96

E = "assets/minecraft/textures/entity/"
HIDE_TACK = ("saddle", "bridle", "rein", "chest", "harness")
V = {  # id: (layer, texture, extra)
    "minecraft:frog": ("FROG", E + "frog/temperate_frog.png"),
    "minecraft:turtle": ("TURTLE", E + "turtle/big_sea_turtle.png"),
    "minecraft:cat": ("CAT", E + "cat/tabby.png"),
    "minecraft:bee": ("BEE", E + "bee/bee.png"),
    "minecraft:camel": ("CAMEL", E + "camel/camel.png"),
    "minecraft:mule": ("MULE", E + "horse/mule.png"),
    "minecraft:snow_golem": ("SNOW_GOLEM", E + "snow_golem.png"),
    "minecraft:wolf": ("WOLF", E + "wolf/wolf.png"),
    "minecraft:chicken": ("CHICKEN", E + "chicken.png"),
    "minecraft:donkey": ("DONKEY", E + "horse/donkey.png"),
    "minecraft:pig": ("PIG", E + "pig/pig.png"),
    "minecraft:llama": ("LLAMA", E + "llama/creamy.png"),
    "minecraft:horse": ("HORSE", E + "horse/horse_chestnut.png"),
    "minecraft:mooshroom": ("MOOSHROOM", E + "cow/red_mooshroom.png"),
    "minecraft:trader_llama": ("TRADER_LLAMA", E + "llama/creamy.png", {"over": [("LLAMA_DECOR", E + "llama/decor/trader_llama.png")]}),
    "minecraft:cow": ("COW", E + "cow/cow.png"),
    "minecraft:sniffer": ("SNIFFER", E + "sniffer/sniffer.png"),
    "minecraft:skeleton_horse": ("SKELETON_HORSE", E + "horse/horse_skeleton.png"),
    "minecraft:strider": ("STRIDER", E + "strider/strider.png"),
    "minecraft:armadillo": ("ARMADILLO", E + "armadillo.png"),
    "minecraft:axolotl": ("AXOLOTL", E + "axolotl/axolotl_lucy.png"),
    "minecraft:allay": ("ALLAY", E + "allay/allay.png"),
    "minecraft:parrot": ("PARROT", E + "parrot/parrot_red_blue.png"),
    "minecraft:sheep": ("SHEEP", E + "sheep/sheep.png", {"over": [("SHEEP_FUR", E + "sheep/sheep_fur.png")]}),
    "minecraft:fox": ("FOX", E + "fox/fox.png"),
    "minecraft:goat": ("GOAT", E + "goat/goat.png"),
    "minecraft:ocelot": ("OCELOT", E + "cat/ocelot.png"),
    "minecraft:villager": ("VILLAGER", E + "villager/villager.png", {"composite": [E + "villager/type/plains.png"]}),
    "minecraft:iron_golem": ("IRON_GOLEM", E + "iron_golem/iron_golem.png"),
    "minecraft:rabbit": ("RABBIT", E + "rabbit/brown.png"),
    "minecraft:panda": ("PANDA", E + "panda/panda.png"),
    "minecraft:hoglin": ("HOGLIN", E + "hoglin/hoglin.png"),
}
VB = "com/blackgear/vanillabackport/client/level/entities/model/"
TF = "twilightforest/client/model/entity/"
AE = "com/aetherteam/aether/client/renderer/entity/model/"
AT = "assets/aether/textures/entity/mobs/"
FF = "com/faboslav/friendsandfoes/common/client/render/entity/model/"
OTHER = {
    "minecraft:happy_ghast": (("static", "vanillabackport", VB + "HappyGhastModel", "createBodyLayer"), E + "ghast/happy_ghast.png"),
    "minecraft:sulfur_cube": (("static", "vanillabackport", VB + "SulfurCubeModel", "createInnerBodyLayer"), E + "sulfur_cube/sulfur_cube_inner.png"),
    "twilightforest:bighorn_sheep": (("static", "twilightforest", TF + "BighornModel", "create"), "assets/twilightforest/textures/entity/bighorn.png"),
    "twilightforest:boar": (("static", "twilightforest", TF + "BoarModel", "create"), "assets/twilightforest/textures/entity/wildboar.png"),
    "twilightforest:deer": (("static", "twilightforest", TF + "DeerModel", "create"), "assets/twilightforest/textures/entity/wilddeer.png"),
    "twilightforest:dwarf_rabbit": (("static", "twilightforest", TF + "BunnyModel", "create"), "assets/twilightforest/textures/entity/bunnybrown.png"),
    "twilightforest:penguin": (("static", "twilightforest", TF + "PenguinModel", "create"), "assets/twilightforest/textures/entity/penguin.png"),
    "aether:moa": (("static", "aether", AE + "BipedBirdModel", "createBodyLayer"), AT + "moa/blue_moa.png", {"texsize": (64, 32)}),
    "aether:sheepuff": (("static", "aether", AE + "SheepuffModel", "createBodyLayer"), AT + "sheepuff/sheepuff.png"),
    "aether:aerbunny": (("static", "aether", AE + "AerbunnyModel", "createBodyLayer"), AT + "aerbunny/aerbunny.png", {"hide": ("puff",)}),
    "aether:phyg": (("van", "PIG"), AT + "phyg/phyg.png"),
    "aether:flying_cow": (("van", "COW"), AT + "flying_cow/flying_cow.png"),
    "deep_aether:quail": (("static", "deep_aether", "io/github/razordevs/deep_aether/client/model/QuailModel", "createBodyLayer"), "assets/deep_aether/textures/entity/quail/quail_copper.png"),
    "friendsandfoes:crab": (("static", "friendsandfoes", FF + "CrabEntityModel", "getTexturedModelData"), "assets/friendsandfoes/textures/entity/crab/crab.png"),
    "friendsandfoes:glare": (("static", "friendsandfoes", FF + "GlareEntityModel", "getTexturedModelData"), "assets/friendsandfoes/textures/entity/glare/glare.png"),
    "friendsandfoes:moobloom": (("van", "COW"), "assets/friendsandfoes/textures/entity/moobloom/moobloom_buttercup.png"),
    # Hybrid Aquatic (GeckoLib .geo.json)
    "hybrid_aquatic:carp": (("geo", "assets/hybrid_aquatic/geo/fish/carp/carp.geo.json"), "assets/hybrid_aquatic/textures/entity/fish/carp/koi_orange.png"),
    "hybrid_aquatic:goldfish": (("geo", "assets/hybrid_aquatic/geo/fish/goldfish/common_goldfish.geo.json"), "assets/hybrid_aquatic/textures/entity/fish/goldfish/common_goldfish.png"),
    "hybrid_aquatic:dugong": (("geo", "assets/hybrid_aquatic/geo/mammal/dugong/dugong.geo.json"), "assets/hybrid_aquatic/textures/entity/mammal/dugong/dugong.png"),
    "hybrid_aquatic:manatee": (("geo", "assets/hybrid_aquatic/geo/mammal/manatee/manatee.geo.json"), "assets/hybrid_aquatic/textures/entity/mammal/manatee/manatee.png"),
    "hybrid_aquatic:orca": (("geo", "assets/hybrid_aquatic/geo/mammal/orca/orca.geo.json"), "assets/hybrid_aquatic/textures/entity/mammal/orca/black_orca.png"),
    "hybrid_aquatic:otter": (("geo", "assets/hybrid_aquatic/geo/mammal/otter/river_otter.geo.json"), "assets/hybrid_aquatic/textures/entity/mammal/otter/river_otter.png"),
}
DRAGON_TEX = {"end": "ender", "sculk": "skulk"}

AM_TEX_OVERRIDE = {"capuchin_monkey": "capuchin_monkey_0", "crocodile": "crocodile_0", "hummingbird": "hummingbird_0",
                   "mantis_shrimp": "mantis_shrimp_0", "rain_frog": "rain_frog_0", "seal": "seal/seal_brown_0",
                   "banana_slug": "banana_slug/banana_slug_0", "elephant": "elephant/elephant", "terrapin": "terrapin/terrapin_green"}

def camel(s): return "".join(w.capitalize() for w in s.split("_"))

_names = None
def find_tex(ns, path):
    global _names
    if _names is None: _names = models.asset_names()
    base = f"assets/{ns}/textures/entity/"
    if ns == "alexsmobs" and path in AM_TEX_OVERRIDE: return base + AM_TEX_OVERRIDE[path] + ".png"
    for c in (f"{path}.png", f"{path}_0.png", f"{path}/{path}.png", f"{path}/{path}_0.png"):
        if base + c in _names: return base + c
    folder = sorted(n for n in _names if n.startswith(base + path + "/") and n.endswith(".png"))
    if folder: return folder[0]
    raise FileNotFoundError(f"texture for {ns}:{path}")

def composite(base, overlays):
    w, h, px = base
    px = [row[:] for row in px]
    for o in overlays:
        ow, oh, opx = o
        for y in range(min(h, oh)):
            for x in range(min(w, ow)):
                r, g, b, a = opx[y][x]
                if a:
                    br, bg, bb, ba = px[y][x]; t = a / 255
                    px[y][x] = (int(r * t + br * (1 - t)), int(g * t + bg * (1 - t)), int(b * t + bb * (1 - t)), max(a, ba))
    return w, h, px

def geometry(src, van):
    kind = src[0]
    if kind == "van":
        L = van[src[1]]; return [L["root"]], L["tw"], L["th"]
    if kind == "static": return models.static_layer(src[1], src[2], src[3])
    if kind == "cit": return models.citadel_model(src[1])
    if kind == "geo": return geo.load(src[1])
    if kind == "anaconda":   # multipart snake: head, neck, 2 body, tail segments chained along +z (switch case = part index)
        vm = models.vm_for("alexsmobs"); roots = []
        for i, k in enumerate((1, 2, 99, 99, 3)):
            vm.force_switch = k
            r, tw, th = models.citadel_model("com/github/alexthe666/alexsmobs/client/model/ModelAnaconda")
            for p in r:
                p.z += 16 * i; p.yr = (-0.25, 0.2, 0.35, -0.3, -0.35)[i]; p.x += (0, -1, 2, 2, -1)[i]
            roots += r
        vm.force_switch = None
        return roots, tw, th
    raise ValueError(kind)

def render_mob(mid, van):
    ns, path = mid.split(":")
    extra = {}
    if mid in V:
        spec = V[mid]; src, tex = ("van", spec[0]), spec[1]; extra = spec[2] if len(spec) > 2 else {}
    elif mid in OTHER:
        src, tex = OTHER[mid][:2]; extra = OTHER[mid][2] if len(OTHER[mid]) > 2 else {}
    elif mid == "alexsmobs:anaconda":
        src, tex = ("anaconda",), find_tex(ns, path)
    elif ns == "alexsmobs":
        src, tex = ("cit", "com/github/alexthe666/alexsmobs/client/model/Model" + camel(path)), find_tex(ns, path)
    elif ns == "dmr":
        src, tex = ("geo", "assets/dmr/geo/dragon.geo.json"), f"assets/dmr/textures/entity/dragon/{DRAGON_TEX.get(path, path)}/body.png"
        extra = {"hide": ("left_wing", "right_wing")}   # the geo rest pose has wings fully spread
    else:
        raise LookupError(mid)
    roots, tw, th = geometry(src, van)
    if "texsize" in extra: tw, th = extra["texsize"]
    t = models.texture(tex)
    if extra.get("composite"): t = composite(t, [models.texture(p) for p in extra["composite"]])
    quads = raster.collect(roots, hide=extra.get("hide", ()), hide_match=HIDE_TACK)
    texmap = {None: (t[0], t[1], t[2], tw, th)}
    for layer, otex in extra.get("over", []):
        L = van[layer]; ot = models.texture(otex)
        oq = raster.collect([L["root"]], hide_match=HIDE_TACK)
        for q in oq: q[5].tex_override = (ot[0], ot[1], ot[2], L["tw"], L["th"])
        quads += oq
    return raster.render(quads, texmap, size=SIZE, yaw=extra.get("yaw", -35), pitch=extra.get("pitch", 22))

def main(only=None):
    os.makedirs(OUT, exist_ok=True)
    wiki = json.load(open(WIKI, encoding="utf-8"))
    van = vanilla_layers()
    ok, fail = [], []
    for m in wiki["mobs"]:
        if only and not any(o in m["id"] for o in only): continue
        try:
            img = render_mob(m["id"], van)
            if img is None: raise ValueError("empty render")
            open(os.path.join(OUT, m["id"].replace(":", "-") + ".png"), "wb").write(raster.png_encode(SIZE, SIZE, img))
            ok.append((m["id"], img))
        except Exception as e:
            fail.append((m["id"], f"{type(e).__name__}: {e}"))
    # contact sheet for eyeballing
    cols = 12; rows = (len(ok) + cols - 1) // cols
    sheet = [[(210, 180, 130, 255)] * (cols * SIZE) for _ in range(max(1, rows) * SIZE)]
    for i, (mid, img) in enumerate(ok):
        ox, oy = (i % cols) * SIZE, (i // cols) * SIZE
        for y in range(SIZE):
            for x in range(SIZE):
                if img[y][x][3]: sheet[oy + y][ox + x] = img[y][x]
    open(os.path.join(HERE, "out", "contact.png"), "wb").write(raster.png_encode(cols * SIZE, max(1, rows) * SIZE, sheet))
    print("rendered", len(ok), "failed", len(fail))
    for f in fail: print("  FAIL", *f)
    print("order:", " ".join(m for m, _ in ok))

if __name__ == "__main__":
    main(sys.argv[1:] or None)
