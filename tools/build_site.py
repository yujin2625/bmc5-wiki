"""Build the BMC5 wiki site into docs/.

Each page is an HTML fragment (no <html>/<head>/<body>) that gets wrapped into a full document.
A subset TrueType pixel font is generated from Minecraft's own glyphs (ascii.png + GNU Unifont),
covering every character used on any page, and written to docs/assets/fonts/MCPixel.ttf.

    python tools/build_site.py            build docs/
    python tools/build_site.py --inline   also write single-file copies (font embedded) to tools/out/
"""
import json, os, sys, zipfile, struct, zlib, base64, re

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(TOOLS)
DOCS = os.path.join(ROOT, "docs")
INSTALL = os.environ.get("MC_INSTALL", r"C:\Users\Yujin Park\curseforge\minecraft\Install")
FONT_REL = "assets/fonts/MCPixel.ttf"

def load(p):
    return open(os.path.join(TOOLS, p), encoding="utf-8").read()

# (html fragment, output path under docs/) -- add new wiki pages here
wiki = json.load(open(os.path.join(TOOLS, "mobs", "wiki.json"), encoding="utf-8"))
PAGES = [
    (load("home.html"), "index.html"),
    (load("mobs/template.html").replace("/*__DATA__*/", json.dumps(wiki, ensure_ascii=False, separators=(",", ":"))), "mobs/index.html"),
]

# characters that can appear on screen
chars = set("0123456789+-·")
for frag, _ in PAGES:
    chars |= set(re.sub(r"data:image/png;base64,[A-Za-z0-9+/=]+", "", frag))
chars = {c for c in chars if 32 <= ord(c) < 0x10000}

# ---------- glyph sources ----------
jar = zipfile.ZipFile(os.path.join(INSTALL, "versions", "1.21.1", "1.21.1.jar"))
def png_decode(b):
    assert b[:8] == b"\x89PNG\r\n\x1a\n"
    pos, idat, plte, trns = 8, b"", None, None
    while pos < len(b):
        ln, = struct.unpack(">I", b[pos:pos+4]); typ = b[pos+4:pos+8]; d = b[pos+8:pos+8+ln]; pos += 12 + ln
        if typ == b"IHDR": w, h, bd, ct, _, _, il = struct.unpack(">IIBBBBB", d)
        elif typ == b"IDAT": idat += d
        elif typ == b"PLTE": plte = d
        elif typ == b"tRNS": trns = d
    raw = zlib.decompress(idat)
    chans = {6: 4, 2: 3, 4: 2, 0: 1, 3: 1}[ct]
    bpp = max(1, chans * bd // 8); stride = (w * chans * bd + 7) // 8
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i+1:i+1+stride]); i += 1 + stride
        for x in range(stride):
            a = line[x-bpp] if x >= bpp else 0; up = prev[x]; c = prev[x-bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + up) & 255
            elif f == 3: line[x] = (line[x] + ((a + up) >> 1)) & 255
            elif f == 4:
                p = a + up - c; pa, pb, pc = abs(p-a), abs(p-up), abs(p-c)
                pr = a if pa <= pb and pa <= pc else (up if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        rows.append(bytes(line)); prev = line
    if bd < 8:
        per = 8 // bd; mask = (1 << bd) - 1
        rows = [bytes(((r[x // per] >> (8 - bd * (x % per + 1))) & mask) for x in range(w)) for r in rows]
        if ct == 0: rows = [bytes(v * 255 // mask for v in r) for r in rows]
    def alpha(x, y):
        r = rows[y]
        if ct == 6: return r[x*4+3]
        if ct == 4: return r[x*2+1]
        if ct == 3:
            idx = r[x]; return trns[idx] if trns and idx < len(trns) else 255
        if ct == 0: return r[x]
        return 255
    return w, h, alpha

defs = json.loads(jar.read("assets/minecraft/font/include/default.json"))
asc = next(p for p in defs["providers"] if p.get("file") == "minecraft:font/ascii.png")
W, H, alpha = png_decode(jar.read("assets/minecraft/textures/font/ascii.png"))
cell = W // 16
ascii_glyphs = {}
for ry, row in enumerate(asc["chars"]):
    for rx, ch in enumerate(row):
        if ch == "\u0000": continue
        px = [[alpha(rx*cell+x, ry*cell+y) > 127 for x in range(cell)] for y in range(cell)]
        width = max([x+1 for y in range(cell) for x in range(cell) if px[y][x]] or [0])
        ascii_glyphs[ch] = (px, width)

idx = json.load(open(os.path.join(INSTALL, "assets", "indexes", "17.json")))["objects"]
hsh = idx["minecraft/font/unifont.zip"]["hash"]
uz = zipfile.ZipFile(os.path.join(INSTALL, "assets", "objects", hsh[:2], hsh))
hexname = [n for n in uz.namelist() if n.endswith(".hex")][0]
want = {ord(c) for c in chars}
uni = {}
for line in uz.read(hexname).decode().splitlines():
    cp, bits = line.split(":")
    cp = int(cp, 16)
    if cp in want: uni[cp] = bits

# ---------- build glyph outlines ----------
UPM, U = 1024, 64          # unifont pixel = 64 units, ascii pixel = 128
def rects(px, cols, rows):
    """merge pixels into rectangles: horizontal runs, then stack identical runs vertically"""
    runs = []
    for y in range(rows):
        x = 0
        while x < cols:
            if px[y][x]:
                s = x
                while x < cols and px[y][x]: x += 1
                runs.append([y, s, x, 1])
            else: x += 1
    merged = []
    for r in runs:
        for m in merged:
            if m[1] == r[1] and m[2] == r[2] and m[0] + m[3] == r[0]:
                m[3] += 1; break
        else: merged.append(r)
    return merged

glyphs = [(".notdef", 512, [])]
cmap = {}
for c in sorted(chars):
    cp = ord(c)
    if c == " ":
        glyphs.append(("space", 4*128, [])); cmap[cp] = len(glyphs)-1; continue
    if c in ascii_glyphs:
        px, w = ascii_glyphs[c]
        boxes = []
        for (y, x0, x1, h) in rects(px, cell, cell):
            top = (7 - y) * 128; boxes.append((x0*128, top - h*128, x1*128, top))
        glyphs.append((c, (w+1)*128, boxes)); cmap[cp] = len(glyphs)-1; continue
    if cp in uni:
        bits = uni[cp]; gw = 16 if len(bits) == 64 else 8
        rows_ = [int(bits[i*gw//4:(i+1)*gw//4], 16) for i in range(16)]
        px = [[bool(r >> (gw-1-x) & 1) for x in range(gw)] for r in rows_]
        used = [x for y in range(16) for x in range(gw) if px[y][x]]
        boxes = []
        for (y, x0, x1, h) in rects(px, gw, 16):
            top = (14 - y) * U; boxes.append((x0*U, top - h*U, x1*U, top))
        adv = (max(used)+2)*U if used and gw == 8 else gw*U + U
        glyphs.append((c, adv, boxes)); cmap[cp] = len(glyphs)-1

# ---------- TrueType writer ----------
def glyf_bytes(boxes):
    if not boxes: return b""
    xs = [v for b in boxes for v in (b[0], b[2])]; ys = [v for b in boxes for v in (b[1], b[3])]
    out = struct.pack(">hhhhh", len(boxes), min(xs), min(ys), max(xs), max(ys))
    ends, pts = [], []
    for (x0, y0, x1, y1) in boxes:
        pts += [(x0, y0), (x0, y1), (x1, y1), (x1, y0)]   # clockwise
        ends.append(len(pts)-1)
    out += struct.pack(">%dH" % len(ends), *ends) + struct.pack(">H", 0)
    out += bytes([1]) * len(pts)
    px_ = py = 0; xb = yb = b""
    for (x, y) in pts:
        xb += struct.pack(">h", x - px_); yb += struct.pack(">h", y - py); px_, py = x, y
    out += xb + yb
    while len(out) % 4: out += b"\0"
    return out

glyf, loca = b"", []
for _, _, boxes in glyphs:
    loca.append(len(glyf)); glyf += glyf_bytes(boxes)
loca.append(len(glyf))
n = len(glyphs)
allx = [v for g in glyphs for b in g[2] for v in (b[0], b[2])] or [0]
ally = [v for g in glyphs for b in g[2] for v in (b[1], b[3])] or [0]
maxpts = max([len(g[2])*4 for g in glyphs] + [0]); maxcont = max([len(g[2]) for g in glyphs] + [0])
ASC, DESC = 960, -192

head = struct.pack(">IIIIHHqqhhhhHHhhh", 0x00010000, 0x00010000, 0, 0x5F0F3CF5, 0x000B, UPM, 0, 0,
                   min(allx), min(ally), max(allx), max(ally), 0, 8, 2, 1, 0)
hhea = struct.pack(">IhhhHhhhhhhhhhhhH", 0x00010000, ASC, DESC, 0, max(g[1] for g in glyphs), 0, 0, max(allx), 1, 0, 0, 0, 0, 0, 0, 0, n)
hmtx = b"".join(struct.pack(">Hh", g[1], min([b[0] for b in g[2]] or [0])) for g in glyphs)
maxp = struct.pack(">IHHHHHHHHHHHHHH", 0x00010000, n, maxpts, maxcont, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0)
locab = b"".join(struct.pack(">I", v) for v in loca)
# cmap format 4
codes = sorted(cmap)
segs = []
for cp in codes:
    if segs and segs[-1][1] == cp-1 and cmap[cp] - cp == segs[-1][2]:
        segs[-1][1] = cp
    else: segs.append([cp, cp, cmap[cp] - cp])
segs.append([0xFFFF, 0xFFFF, 1])
sc = len(segs); sr = 2 * (1 << (sc.bit_length()-1)); es = (sc.bit_length()-1); rs = 2*sc - sr
sub = struct.pack(">HHHHHHH", 4, 0, 0, 2*sc, sr, es, rs)
sub += b"".join(struct.pack(">H", s[1]) for s in segs) + b"\0\0"
sub += b"".join(struct.pack(">H", s[0]) for s in segs)
sub += b"".join(struct.pack(">H", s[2] & 0xFFFF) for s in segs)
sub += b"".join(struct.pack(">H", 0) for s in segs)
sub = sub[:2] + struct.pack(">H", len(sub)) + sub[4:]
cmapt = struct.pack(">HHHHI", 0, 1, 3, 1, 12) + sub
names = {1: "MCPixel", 2: "Regular", 3: "MCPixel-Regular", 4: "MCPixel", 5: "Version 1.0", 6: "MCPixel-Regular"}
recs, strs = b"", b""
for nid, s in names.items():
    e = s.encode("utf-16-be"); recs += struct.pack(">HHHHHH", 3, 1, 0x409, nid, len(e), len(strs)); strs += e
name = struct.pack(">HHH", 0, len(names), 6 + 12*len(names)) + recs + strs
post = struct.pack(">IIhhIIIII", 0x00030000, 0, -128, 64, 1, 0, 0, 0, 0)
os2 = struct.pack(">HhHHHhhhhhhhhhhh", 4, 600, 400, 5, 0, 512, 512, 0, 128, 512, 512, 0, 512, 64, 300, 0)
os2 += bytes(10) + struct.pack(">IIII", 1, 0x10000000, 0, 0) + b"MCPX"
os2 += struct.pack(">HHHhhhHHIIhhHHH", 0x40, min(codes), min(max(codes), 0xFFFF), ASC, DESC, 0, ASC, -DESC, 1 | (1 << 19), 0, 448, 896, 0, 32, 1)

tables = {b"OS/2": os2, b"cmap": cmapt, b"glyf": glyf, b"head": head, b"hhea": hhea, b"hmtx": hmtx, b"loca": locab, b"maxp": maxp, b"name": name, b"post": post}
def csum(b):
    b += b"\0" * ((4 - len(b) % 4) % 4)
    return sum(struct.unpack(">%dI" % (len(b)//4), b)) & 0xFFFFFFFF
tags = sorted(tables)
nt = len(tags); sr = 16 * (1 << (nt.bit_length()-1)); es = nt.bit_length()-1
font = struct.pack(">IHHHH", 0x00010000, nt, sr, es, nt*16 - sr)
off = 12 + 16*nt; dirs, body = b"", b""
for t in tags:
    d = tables[t]; dirs += struct.pack(">4sIII", t, csum(d), off + len(body), len(d))
    body += d + b"\0" * ((4 - len(d) % 4) % 4)
font += dirs + body
adj = (0xB1B0AFBA - csum(font)) & 0xFFFFFFFF
# patch checkSumAdjustment inside head
hpos = 12 + 16*nt + sum(len(tables[t]) + ((4 - len(tables[t]) % 4) % 4) for t in tags[:tags.index(b"head")])
font = font[:hpos+8] + struct.pack(">I", adj) + font[hpos+12:]

# ---------- write site ----------
import shutil
RENDERS = os.path.join(TOOLS, "render", "out", "mobs")      # made by tools/render/render_mobs.py
if os.path.isdir(RENDERS):
    dst = os.path.join(DOCS, "mobs", "img"); os.makedirs(dst, exist_ok=True)
    for f in os.listdir(RENDERS):
        if f.endswith(".png"): shutil.copyfile(os.path.join(RENDERS, f), os.path.join(dst, f))
os.makedirs(os.path.join(DOCS, "assets", "fonts"), exist_ok=True)
open(os.path.join(DOCS, FONT_REL), "wb").write(font)

def wrap(frag):
    m = re.search(r"<title>.*?</title>", frag, re.S)
    title = m.group(0) if m else "<title>BMC5 위키</title>"
    body = frag.replace(title, "", 1) if m else frag
    return ('<!doctype html>\n<html lang="ko">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
            f"{title}\n</head>\n<body>\n{body.strip()}\n</body>\n</html>\n")

b64 = base64.b64encode(font).decode()
for frag, rel in PAGES:
    url = "../" * rel.count("/") + FONT_REL
    out = os.path.join(DOCS, rel)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, "w", encoding="utf-8", newline="\n").write(wrap(frag.replace("__FONT_URL__", url)))
    if "--inline" in sys.argv:  # single-file copy with the font embedded (for sharing as one file)
        os.makedirs(os.path.join(TOOLS, "out"), exist_ok=True)
        single = frag.replace("url(__FONT_URL__)", f"url(data:font/ttf;base64,{b64})")
        open(os.path.join(TOOLS, "out", rel.replace("/", "_")), "w", encoding="utf-8").write(single)
    print("wrote docs/" + rel)
missing = sorted(c for c in chars if ord(c) not in cmap and c not in " \n\t")
print("glyphs", n, "font KB", len(font)//1024, "missing", len(missing), "".join(missing)[:80])
