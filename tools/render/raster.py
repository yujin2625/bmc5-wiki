"""Software renderer: textured model cubes -> small pixel-art PNG (orthographic 3/4 view, nearest sampling)."""
import math, struct, zlib

# ---------------------------------------------------------------- PNG io
def png_decode(b):
    assert b[:8] == b"\x89PNG\r\n\x1a\n"
    pos, idat, plte, trns = 8, b"", None, None
    while pos + 8 <= len(b):
        ln, = struct.unpack(">I", b[pos:pos + 4]); typ = b[pos + 4:pos + 8]; d = b[pos + 8:pos + 8 + ln]; pos += 12 + ln
        if typ == b"IHDR": w, h, bd, ct, _, _, il = struct.unpack(">IIBBBBB", d)
        elif typ == b"IDAT": idat += d
        elif typ == b"PLTE": plte = d
        elif typ == b"tRNS": trns = d
        elif typ == b"IEND": break
    raw = zlib.decompress(idat)
    chans = {6: 4, 2: 3, 4: 2, 0: 1, 3: 1}[ct]
    bpp = max(1, chans * bd // 8); stride = (w * chans * bd + 7) // 8
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i + 1:i + 1 + stride]); i += 1 + stride
        if f:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0; up = prev[x]; c = prev[x - bpp] if x >= bpp else 0
                if f == 1: line[x] = (line[x] + a) & 255
                elif f == 2: line[x] = (line[x] + up) & 255
                elif f == 3: line[x] = (line[x] + ((a + up) >> 1)) & 255
                elif f == 4:
                    p = a + up - c; pa, pb, pc = abs(p - a), abs(p - up), abs(p - c)
                    line[x] = (line[x] + (a if pa <= pb and pa <= pc else up if pb <= pc else c)) & 255
        rows.append(bytes(line)); prev = line
    px = []
    for r in rows:
        if bd < 8:
            per = 8 // bd; mask = (1 << bd) - 1
            vals = [(r[x // per] >> (8 - bd * (x % per + 1))) & mask for x in range(w)]
        elif bd == 16:
            vals = list(r[::2])
        else:
            vals = list(r)
        row = []
        for x in range(w):
            if ct == 6: row.append(tuple(vals[x * 4:x * 4 + 4]))
            elif ct == 2: row.append(tuple(vals[x * 3:x * 3 + 3]) + (255,))
            elif ct == 4: g, a = vals[x * 2], vals[x * 2 + 1]; row.append((g, g, g, a))
            elif ct == 0:
                g = vals[x] * 255 // ((1 << bd) - 1) if bd < 8 else vals[x]; row.append((g, g, g, 255))
            else:
                k = vals[x]; row.append(tuple(plte[k * 3:k * 3 + 3]) + ((trns[k] if trns and k < len(trns) else 255),))
        px.append(row)
    return w, h, px

def png_encode(w, h, px):
    raw = b"".join(b"\x00" + bytes(c for p in row for c in p) for row in px)
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")

# ---------------------------------------------------------------- math
def mat_mul(a, b): return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]
def ident(): return [[1.0 if i == j else 0.0 for j in range(4)] for i in range(4)]
def trans(x, y, z): m = ident(); m[0][3], m[1][3], m[2][3] = x, y, z; return m
def scale(x, y, z): m = ident(); m[0][0], m[1][1], m[2][2] = x, y, z; return m
def rx(a): c, s = math.cos(a), math.sin(a); return [[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1]]
def ry(a): c, s = math.cos(a), math.sin(a); return [[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1]]
def rz(a): c, s = math.cos(a), math.sin(a); return [[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
def apply(m, p): return tuple(m[i][0] * p[0] + m[i][1] * p[1] + m[i][2] * p[2] + m[i][3] for i in range(3))

def part_matrix(p):
    m = trans(p.x, p.y, p.z)
    if p.zr: m = mat_mul(m, rz(p.zr))
    if p.yr: m = mat_mul(m, ry(p.yr))
    if p.xr: m = mat_mul(m, rx(p.xr))
    sx, sy, sz = getattr(p, "scale", (1, 1, 1))
    if (sx, sy, sz) != (1, 1, 1): m = mat_mul(m, scale(sx, sy, sz))
    return m

# ---------------------------------------------------------------- cube faces
def cube_faces(c):
    """Return [(origin, uEnd, vEnd, (us, vs, ue, ve))] in part space, vanilla ModelPart.Cube UV layout."""
    gx, gy, gz = c["grow"]
    x0, y0, z0 = c["x"] - gx, c["y"] - gy, c["z"] - gz
    x1, y1, z1 = c["x"] + c["w"] + gx, c["y"] + c["h"] + gy, c["z"] + c["d"] + gz
    u, v, w, h, d = c["u"], c["v"], c["w"], c["h"], c["d"]
    rects = {
        "top": (u + d, v, u + d + w, v + d), "bottom": (u + d + w, v, u + d + w + w, v + d),
        "right": (u, v + d, u + d, v + d + h), "front": (u + d, v + d, u + d + w, v + d + h),
        "left": (u + d + w, v + d, u + d + w + d, v + d + h), "back": (u + d + w + d, v + d, u + 2 * d + 2 * w, v + d + h),
    }
    if c["mirror"]:
        rects["right"], rects["left"] = rects["left"], rects["right"]
        rects = {k: (r[2], r[1], r[0], r[3]) for k, r in rects.items()}
    if "faces" in c:   # bedrock per-face uv: {north: {uv:[u,v], uv_size:[w,h]}, ...}
        names = {"front": "north", "back": "south", "right": "west", "left": "east", "top": "up", "bottom": "down"}
        for k, n in names.items():
            f = c["faces"].get(n)
            if f is None: rects[k] = None; continue
            (fu, fv), (fw, fh) = f["uv"], f.get("uv_size", [0, 0])
            rects[k] = (fu, fv, fu + fw, fv + fh)
    faces = [
        ((x0, y0, z0), (x1, y0, z0), (x0, y1, z0), rects["front"], (0, 0, -1)),
        ((x0, y0, z1), (x0, y0, z0), (x0, y1, z1), rects["right"], (-1, 0, 0)),
        ((x1, y0, z0), (x1, y0, z1), (x1, y1, z0), rects["left"], (1, 0, 0)),
        ((x1, y0, z1), (x0, y0, z1), (x1, y1, z1), rects["back"], (0, 0, 1)),
        ((x0, y0, z1), (x1, y0, z1), (x0, y0, z0), rects["top"], (0, -1, 0)),
        ((x0, y1, z0), (x1, y1, z0), (x0, y1, z1), rects["bottom"], (0, 1, 0)),
    ]
    return [f for f in faces if f[3] is not None]

def collect(roots, base=None, hide=(), hide_match=()):
    quads = []
    def walk(p, m):
        if not p.visible or p.name in hide or any(h in p.name.lower() for h in hide_match): return
        m = mat_mul(m, part_matrix(p))
        for c in p.cubes:
            for o, ue, ve, uv, n in cube_faces(c):
                O, U, V = apply(m, o), apply(m, ue), apply(m, ve)
                N = tuple(m[i][0] * n[0] + m[i][1] * n[1] + m[i][2] * n[2] for i in range(3))
                quads.append((O, U, V, uv, N, p))
        for k in p.children: walk(k, m)
    for r in roots: walk(r, base or ident())
    return quads

# ---------------------------------------------------------------- render
def render(quads, textures, size=96, yaw=-35, pitch=22, pad=4, outline=(20, 12, 6, 255)):
    """textures: {part_or_None: (img_w, img_h, px, tex_w, tex_h)} - key None is the default texture."""
    view = mat_mul(rx(math.radians(pitch)), ry(math.radians(yaw)))
    L = (0.35, -0.9, -0.55); ln = math.sqrt(sum(a * a for a in L)); L = tuple(a / ln for a in L)
    P = []
    for O, U, V, uv, N, part in quads:
        O2, U2, V2 = apply(view, O), apply(view, U), apply(view, V)
        N2 = apply(view, N); nl = math.sqrt(sum(a * a for a in N2)) or 1
        P.append((O2, U2, V2, uv, tuple(a / nl for a in N2), part))
    xs = [p[i][0] for p in P for i in range(3)] + [p[0][0] + (p[1][0] - p[0][0]) + (p[2][0] - p[0][0]) for p in P]
    ys = [p[i][1] for p in P for i in range(3)] + [p[0][1] + (p[1][1] - p[0][1]) + (p[2][1] - p[0][1]) for p in P]
    if not xs: return None
    minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
    s = min((size - 2 * pad) / max(maxx - minx, 1e-3), (size - 2 * pad) / max(maxy - miny, 1e-3))
    ox = (size - (maxx - minx) * s) / 2 - minx * s
    oy = (size - (maxy - miny) * s) / 2 - miny * s
    buf = [[(0, 0, 0, 0)] * size for _ in range(size)]
    zb = [[math.inf] * size for _ in range(size)]
    for O, U, V, (us, vs, ue, ve), N, part in P:
        tex = getattr(part, "tex_override", None) or textures.get(part.name) or textures[None]
        iw, ih, img, tw, th = tex
        sxu, syu = iw / tw, ih / th
        ax, ay = O[0] * s + ox, O[1] * s + oy
        e1 = ((U[0] - O[0]) * s, (U[1] - O[1]) * s); e2 = ((V[0] - O[0]) * s, (V[1] - O[1]) * s)
        det = e1[0] * e2[1] - e1[1] * e2[0]
        if abs(det) < 1e-6: continue
        shade = 0.62 + 0.38 * max(0.0, -(N[0] * L[0] + N[1] * L[1] + N[2] * L[2]))
        if N[2] > 0.05: shade *= 0.85        # facing away: back faces seen through gaps
        cx = [ax, ax + e1[0], ax + e2[0], ax + e1[0] + e2[0]]; cy = [ay, ay + e1[1], ay + e2[1], ay + e1[1] + e2[1]]
        x0, x1 = max(0, int(math.floor(min(cx)))), min(size - 1, int(math.ceil(max(cx))))
        y0, y1 = max(0, int(math.floor(min(cy)))), min(size - 1, int(math.ceil(max(cy))))
        dz1, dz2 = U[2] - O[2], V[2] - O[2]
        for py in range(y0, y1 + 1):
            for px_ in range(x0, x1 + 1):
                qx, qy = px_ + 0.5 - ax, py + 0.5 - ay
                a = (qx * e2[1] - qy * e2[0]) / det; b = (e1[0] * qy - e1[1] * qx) / det
                if a < -1e-4 or a > 1 + 1e-4 or b < -1e-4 or b > 1 + 1e-4: continue
                z = O[2] + a * dz1 + b * dz2
                if z >= zb[py][px_]: continue
                tu = us + a * (ue - us); tv = vs + b * (ve - vs)
                ix = int(math.floor(min(max(tu, min(us, ue) + 1e-3), max(us, ue) - 1e-3) * sxu))
                iy = int(math.floor(min(max(tv, min(vs, ve) + 1e-3), max(vs, ve) - 1e-3) * syu))
                if not (0 <= ix < iw and 0 <= iy < ih): continue
                r, g, bl, al = img[iy][ix]
                if al < 100: continue
                zb[py][px_] = z
                buf[py][px_] = (min(255, int(r * shade)), min(255, int(g * shade)), min(255, int(bl * shade)), 255)
    if outline:
        out = [row[:] for row in buf]
        for y in range(size):
            for x in range(size):
                if buf[y][x][3]: continue
                if any(0 <= y + dy < size and 0 <= x + dx < size and buf[y + dy][x + dx][3] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    out[y][x] = outline
        buf = out
    return buf
