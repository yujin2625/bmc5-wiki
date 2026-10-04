"""Minimal javap -c: python jdis.py <jar> <class/path/Name> [methodFilter]"""
import sys, zipfile, struct

OPS = {}
def op(code, name, fmt=""): OPS[code] = (name, fmt)
names0 = ("nop aconst_null iconst_m1 iconst_0 iconst_1 iconst_2 iconst_3 iconst_4 iconst_5 lconst_0 lconst_1 fconst_0 fconst_1 fconst_2 dconst_0 dconst_1").split()
for i, n in enumerate(names0): op(i, n)
op(0x10, "bipush", "b"); op(0x11, "sipush", "s"); op(0x12, "ldc", "c1"); op(0x13, "ldc_w", "c2"); op(0x14, "ldc2_w", "c2")
for i, n in enumerate("iload lload fload dload aload".split()): op(0x15+i, n, "B")
for i, n in enumerate("iload lload fload dload aload".split()):
    for k in range(4): op(0x1a+i*4+k, f"{n}_{k}")
for i, n in enumerate("iaload laload faload daload aaload baload caload saload".split()): op(0x2e+i, n)
for i, n in enumerate("istore lstore fstore dstore astore".split()): op(0x36+i, n, "B")
for i, n in enumerate("istore lstore fstore dstore astore".split()):
    for k in range(4): op(0x3b+i*4+k, f"{n}_{k}")
rest = ("iastore lastore fastore dastore aastore bastore castore sastore pop pop2 dup dup_x1 dup_x2 dup2 dup2_x1 dup2_x2 swap "
        "iadd ladd fadd dadd isub lsub fsub dsub imul lmul fmul dmul idiv ldiv fdiv ddiv irem lrem frem drem ineg lneg fneg dneg "
        "ishl lshl ishr lshr iushr lushr iand land ior lor ixor lxor").split()
for i, n in enumerate(rest): op(0x4f+i, n)
op(0x84, "iinc", "BB")
for i, n in enumerate("i2l i2f i2d l2i l2f l2d f2i f2l f2d d2i d2l d2f i2b i2c i2s lcmp fcmpl fcmpg dcmpl dcmpg".split()): op(0x85+i, n)
for i, n in enumerate("ifeq ifne iflt ifge ifgt ifle if_icmpeq if_icmpne if_icmplt if_icmpge if_icmpgt if_icmple if_acmpeq if_acmpne goto jsr".split()): op(0x99+i, n, "j")
op(0xa9, "ret", "B"); op(0xaa, "tableswitch", "T"); op(0xab, "lookupswitch", "L")
for i, n in enumerate("ireturn lreturn freturn dreturn areturn return".split()): op(0xac+i, n)
for i, n in enumerate("getstatic putstatic getfield putfield invokevirtual invokespecial invokestatic".split()): op(0xb2+i, n, "c2")
op(0xb9, "invokeinterface", "c2BB"); op(0xba, "invokedynamic", "c2BB"); op(0xbb, "new", "c2"); op(0xbc, "newarray", "B")
op(0xbd, "anewarray", "c2"); op(0xbe, "arraylength"); op(0xbf, "athrow"); op(0xc0, "checkcast", "c2"); op(0xc1, "instanceof", "c2")
op(0xc2, "monitorenter"); op(0xc3, "monitorexit"); op(0xc4, "wide", "W"); op(0xc5, "multianewarray", "c2B")
op(0xc6, "ifnull", "j"); op(0xc7, "ifnonnull", "j"); op(0xc8, "goto_w", "J")

def parse(data):
    p = 10; n, = struct.unpack(">H", data[8:10]); cp = [None] * n; i = 1
    while i < n:
        t = data[p]
        if t == 1:
            l, = struct.unpack(">H", data[p+1:p+3]); cp[i] = ("utf", data[p+3:p+3+l].decode("utf-8", "replace")); p += 3 + l
        elif t in (3, 4): cp[i] = ("num", struct.unpack(">i" if t == 3 else ">f", data[p+1:p+5])[0]); p += 5
        elif t in (5, 6): cp[i] = ("num", struct.unpack(">q" if t == 5 else ">d", data[p+1:p+9])[0]); p += 9; i += 1
        elif t in (7, 8, 16, 19, 20): cp[i] = (t, struct.unpack(">H", data[p+1:p+3])[0]); p += 3
        elif t in (9, 10, 11, 12, 17, 18): cp[i] = (t,) + struct.unpack(">HH", data[p+1:p+5]); p += 5
        elif t == 15: cp[i] = (t, data[p+1], struct.unpack(">H", data[p+2:p+4])[0]); p += 4
        else: raise ValueError(t)
        i += 1
    def s(k):
        e = cp[k]
        if e is None: return "?"
        if e[0] == "utf": return e[1]
        if e[0] == "num": return repr(e[1])
        t = e[0]
        if t == 7: return s(e[1]).split("/")[-1]
        if t == 8: return '"' + s(e[1]) + '"'
        if t in (9, 10, 11): return s(e[1]) + "." + s(e[2])
        if t == 12: return s(e[1]) + ":" + s(e[2])
        if t == 15: return "MH " + s(e[2])
        if t == 16: return s(e[1])
        if t in (17, 18): return "indy#" + s(e[2])
        return str(e)
    p += 6; ic, = struct.unpack(">H", data[p:p+2]); p += 2 + 2*ic
    def attrs(p):
        c, = struct.unpack(">H", data[p:p+2]); p += 2; out = []
        for _ in range(c):
            nm, ln = struct.unpack(">HI", data[p:p+6]); out.append((s(nm), data[p+6:p+6+ln])); p += 6 + ln
        return p, out
    fc, = struct.unpack(">H", data[p:p+2]); p += 2
    fields = []
    for _ in range(fc):
        a, nm, d = struct.unpack(">HHH", data[p:p+6]); p, _ = attrs(p+6); fields.append(s(nm) + " " + s(d))
    mc, = struct.unpack(">H", data[p:p+2]); p += 2; methods = []
    for _ in range(mc):
        a, nm, d = struct.unpack(">HHH", data[p:p+6]); p, at = attrs(p+6)
        code = dict(at).get("Code"); methods.append((s(nm), s(d), code))
    p, cattrs = attrs(p)
    bsm = dict(cattrs).get("BootstrapMethods")
    return s, fields, methods, cp, bsm

def dis(code, s):
    ml, = struct.unpack(">H", code[4:6]) if False else (0,)
    clen, = struct.unpack(">I", code[4:8]); bc = code[8:8+clen]; pc = 0; out = []
    while pc < len(bc):
        o = bc[pc]; name, fmt = OPS.get(o, (f"op{o:#x}", "")); start = pc; pc += 1; arg = ""
        if fmt == "b": arg = str(struct.unpack(">b", bc[pc:pc+1])[0]); pc += 1
        elif fmt == "s": arg = str(struct.unpack(">h", bc[pc:pc+2])[0]); pc += 2
        elif fmt == "B": arg = str(bc[pc]); pc += 1
        elif fmt == "BB": arg = f"{bc[pc]} {struct.unpack('>b', bc[pc+1:pc+2])[0]}"; pc += 2
        elif fmt == "c1": arg = s(bc[pc]); pc += 1
        elif fmt.startswith("c2"):
            arg = s(struct.unpack(">H", bc[pc:pc+2])[0]); pc += 2 + (len(fmt) - 2)
        elif fmt == "j": arg = str(start + struct.unpack(">h", bc[pc:pc+2])[0]); pc += 2
        elif fmt == "J": arg = str(start + struct.unpack(">i", bc[pc:pc+4])[0]); pc += 4
        elif fmt == "T":
            pc += (4 - pc % 4) % 4; d, lo, hi = struct.unpack(">iii", bc[pc:pc+12]); pc += 12
            tg = struct.unpack(">%di" % (hi-lo+1), bc[pc:pc+4*(hi-lo+1)]); pc += 4*(hi-lo+1)
            arg = f"default {start+d} " + " ".join(f"{lo+i}:{start+t}" for i, t in enumerate(tg))
        elif fmt == "L":
            pc += (4 - pc % 4) % 4; d, n = struct.unpack(">ii", bc[pc:pc+8]); pc += 8
            pr = [struct.unpack(">ii", bc[pc+8*i:pc+8*i+8]) for i in range(n)]; pc += 8*n
            arg = f"default {start+d} " + " ".join(f"{k}:{start+t}" for k, t in pr)
        elif fmt == "W":
            o2 = bc[pc]; pc += 3 + (2 if o2 == 0x84 else 0); arg = "wide"
        out.append(f"  {start:4d} {name} {arg}")
    return out

if __name__ == "__main__":
    jar, cls = sys.argv[1], sys.argv[2]; flt = sys.argv[3] if len(sys.argv) > 3 else None
    z = zipfile.ZipFile(jar); s, fields, methods, cp, bsm = parse(z.read(cls + ".class"))
    if not flt:
        print("FIELDS:", *fields, sep="\n  ")
    for nm, d, code in methods:
        if flt and flt not in nm: continue
        print(f"\n== {nm}{d}")
        if code and flt: print("\n".join(dis(code, s)))
