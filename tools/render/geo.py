"""GeckoLib / Bedrock .geo.json -> Part tree in Java model space (y down).

Bedrock bones use absolute pivots with y up. GeckoLib mirrors x and negates x/y rotations to get Java world space;
Java model space is that rotated 180 degrees about z, which works out to: keep x, negate y, keep the rotation signs.
"""
import json, math
from natives import Part
import models

def load(path):
    g = json.loads(models.read_asset(path))
    geo = (g.get("minecraft:geometry") or [g])[0]
    desc = geo.get("description", {})
    tw, th = desc.get("texture_width", 64), desc.get("texture_height", 64)
    bones = {}; parts = {}
    for b in geo.get("bones", []):
        bones[b["name"]] = b
        p = Part(b["name"])
        px, py, pz = b.get("pivot", [0, 0, 0])
        p.piv = (px, -py, pz)
        rx, ry, rz = [math.radians(a) for a in b.get("rotation", [0, 0, 0])]
        p.xr, p.yr, p.zr = rx, ry, rz
        for c in b.get("cubes", []):
            ox, oy, oz = c["origin"]; w, h, d = c["size"]
            inf = c.get("inflate", 0.0)
            cube = dict(x=ox, y=-(oy + h), z=oz, w=w, h=h, d=d, grow=(inf, inf, inf), mirror=bool(c.get("mirror", b.get("mirror", False))), uvs=(1.0, 1.0))
            uv = c.get("uv", [0, 0])
            if isinstance(uv, dict): cube["faces"] = uv; cube["u"], cube["v"] = 0, 0
            else: cube["u"], cube["v"] = uv
            if c.get("rotation"):
                cp = c.get("pivot", [0, 0, 0])
                sub = Part(b["name"] + "#cube"); sub.piv = (cp[0], -cp[1], cp[2])
                sub.xr, sub.yr, sub.zr = [math.radians(a) for a in c["rotation"]]
                sub.cubes.append(cube); sub.parent_name = b["name"]
                p.children.append(sub); sub.parent = p
            else:
                p.cubes.append(cube)
        parts[b["name"]] = p
    roots = []
    for name, b in bones.items():
        p = parts[name]
        par = parts.get(b.get("parent"))
        if par: par.children.append(p); p.parent = par
        else: roots.append(p)
    # convert absolute pivots into parent-relative offsets; cube coords become relative to their part pivot
    def fix(p, parent_piv):
        px, py, pz = p.piv
        p.x, p.y, p.z = px - parent_piv[0], py - parent_piv[1], pz - parent_piv[2]
        for c in p.cubes:
            c["x"] -= px; c["y"] -= py; c["z"] -= pz
        for k in p.children: fix(k, p.piv)
    for r in roots: fix(r, (0.0, 0.0, 0.0))
    return roots, tw, th
