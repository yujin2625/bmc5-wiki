"""A tiny JVM bytecode interpreter, just enough to run Minecraft model-building code.

Model geometry in 1.21.1 is built in plain Java (`createBodyLayer()` with PartDefinition / CubeListBuilder,
or Citadel's AdvancedModelBox constructors in Alex's Mobs). Rather than re-typing hundreds of boxes, we run
that code here: unknown game classes are interpreted from the jars, and the geometry builder APIs are
implemented natively (see natives.py) to record parts, cubes, texture offsets and poses.
"""
import math, struct, zipfile

class JVMError(Exception): pass

class Obj:
    """A Java object. `cls` is the internal class name; fields live in a dict."""
    __slots__ = ("cls", "f", "native")
    def __init__(self, cls, native=None):
        self.cls = cls; self.f = {}; self.native = native
    def __repr__(self): return f"<{self.cls.split('/')[-1]} {self.native!r}>"

class Sym:
    """Placeholder for a value we don't model (e.g. a registry object). Absorbs calls."""
    def __init__(self, what): self.what = what
    def __repr__(self): return f"Sym({self.what})"

# ---------------------------------------------------------------- class file parsing
def u2(b, p): return struct.unpack_from(">H", b, p)[0]
def u4(b, p): return struct.unpack_from(">I", b, p)[0]

class ClassFile:
    def __init__(self, data):
        self.data = data
        n = u2(data, 8); cp = [None] * n; p = 10; i = 1
        while i < n:
            t = data[p]
            if t == 1:
                l = u2(data, p + 1); cp[i] = ("utf", data[p + 3:p + 3 + l].decode("utf-8", "replace")); p += 3 + l
            elif t == 3: cp[i] = ("int", struct.unpack_from(">i", data, p + 1)[0]); p += 5
            elif t == 4: cp[i] = ("float", struct.unpack_from(">f", data, p + 1)[0]); p += 5
            elif t == 5: cp[i] = ("long", struct.unpack_from(">q", data, p + 1)[0]); p += 9; i += 1
            elif t == 6: cp[i] = ("double", struct.unpack_from(">d", data, p + 1)[0]); p += 9; i += 1
            elif t in (7, 8, 16, 19, 20): cp[i] = (t, u2(data, p + 1)); p += 3
            elif t in (9, 10, 11, 12, 17, 18): cp[i] = (t, u2(data, p + 1), u2(data, p + 3)); p += 5
            elif t == 15: cp[i] = (t, data[p + 1], u2(data, p + 2)); p += 4
            else: raise JVMError(f"bad cp tag {t}")
            i += 1
        self.cp = cp
        self.access, this, sup = struct.unpack_from(">HHH", data, p); p += 6
        self.name = self.cls_name(this)
        self.super = self.cls_name(sup) if sup else None
        ic = u2(data, p); p += 2 + 2 * ic
        def attrs(p):
            c = u2(data, p); p += 2; out = {}
            for _ in range(c):
                nm, ln = u2(data, p), u4(data, p + 2); out[self.utf(nm)] = data[p + 6:p + 6 + ln]; p += 6 + ln
            return p, out
        fc = u2(data, p); p += 2
        self.fields = {}
        for _ in range(fc):
            acc, nm, d = struct.unpack_from(">HHH", data, p); p, at = attrs(p + 6)
            cv = at.get("ConstantValue")
            self.fields[self.utf(nm)] = (acc, self.utf(d), self.const(u2(cv, 0)) if cv else None)
        mc = u2(data, p); p += 2
        self.methods = {}
        for _ in range(mc):
            acc, nm, d = struct.unpack_from(">HHH", data, p); p, at = attrs(p + 6)
            code = at.get("Code")
            if code:
                ms, ml, cl = u2(code, 0), u2(code, 2), u4(code, 4)
                self.methods[(self.utf(nm), self.utf(d))] = (acc, code[8:8 + cl], ml)
            else:
                self.methods[(self.utf(nm), self.utf(d))] = (acc, None, 0)
        p, cattrs = attrs(p)
        self.bootstrap = []
        bs = cattrs.get("BootstrapMethods")
        if bs:
            q = 2
            for _ in range(u2(bs, 0)):
                ref, na = u2(bs, q), u2(bs, q + 2); args = [u2(bs, q + 4 + 2 * k) for k in range(na)]
                self.bootstrap.append((ref, args)); q += 4 + 2 * na

    def utf(self, i): return self.cp[i][1]
    def cls_name(self, i): return self.utf(self.cp[i][1])
    def nat(self, i):
        e = self.cp[i]; return self.utf(e[1]), self.utf(e[2])
    def ref(self, i):
        e = self.cp[i]; n, d = self.nat(e[2]); return self.cls_name(e[1]), n, d
    def const(self, i):
        e = self.cp[i]
        if e[0] in ("int", "float", "long", "double"): return e[1]
        if e[0] == 8: return self.utf(e[1])
        if e[0] == 7: return Sym("class " + self.cls_name(i))
        return Sym(f"const{e}")

# ---------------------------------------------------------------- descriptors
def arg_count(desc):
    i, n = 1, 0
    while desc[i] != ")":
        c = desc[i]
        if c == "L": i = desc.index(";", i) + 1
        elif c == "[":
            while desc[i] == "[": i += 1
            i = desc.index(";", i) + 1 if desc[i] == "L" else i + 1
        else: i += 1
        n += 1
    return n

def arg_types(desc):
    out, i = [], 1
    while desc[i] != ")":
        s = i
        while desc[i] == "[": i += 1
        i = desc.index(";", i) + 1 if desc[i] == "L" else i + 1
        out.append(desc[s:i])
    return out

def returns_value(desc): return not desc.endswith(")V")

def f32(x):
    try: return struct.unpack(">f", struct.pack(">f", x))[0]
    except (OverflowError, struct.error): return x

def i32(x):
    x &= 0xFFFFFFFF
    return x - (1 << 32) if x & 0x80000000 else x

# ---------------------------------------------------------------- interpreter
class VM:
    def __init__(self, jars, natives):
        self.zips = [zipfile.ZipFile(j) for j in jars]
        self.classes = {}
        self.natives = natives          # natives.handle(vm, owner, name, desc, args) -> (handled, value)
        self.statics = {}
        self.steps = 0

    def load(self, name):
        if name in self.classes: return self.classes[name]
        path = name + ".class"
        cf = None
        for z in self.zips:
            try: cf = ClassFile(z.read(path)); break
            except KeyError: continue
        self.classes[name] = cf
        return cf

    def find_method(self, cls, name, desc):
        while cls:
            cf = self.load(cls)
            if not cf: return None, None
            m = cf.methods.get((name, desc))
            if m and m[1] is not None: return cf, m
            cls = cf.super
        return None, None

    def clinit(self, owner):
        """Run a class's static initializer once (model classes keep constants like CubeDeformations there)."""
        if not hasattr(self, "_inited"): self._inited = set()
        if owner in self._inited: return
        self._inited.add(owner)
        cf = self.load(owner)
        if cf and ("<clinit>", "()V") in cf.methods and cf.methods[("<clinit>", "()V")][1] is not None:
            steps = self.steps
            try: self.run(cf, cf.methods[("<clinit>", "()V")], [])
            except Exception: pass
            finally: self.steps = steps

    def get_static(self, owner, name, desc=""):
        key = (owner, name)
        if key in self.statics: return self.statics[key]
        # registry handles (DeferredHolder, RegistryObject, Supplier...) -> keep the field name for later matching
        if any(t in desc for t in ("DeferredHolder", "RegistryObject", "RegistryEntry", "Supplier", "DeferredEntityType", "Holder;")):
            return Sym(f"{owner.split('/')[-1]}.{name}")
        ok, v = self.natives.static_field(self, owner, name)
        if ok: return v
        self.clinit(owner)
        if key in self.statics: return self.statics[key]
        cf = self.load(owner)
        if cf and name in cf.fields and cf.fields[name][2] is not None:
            return cf.fields[name][2]
        ok, v = self.natives.static_field(self, owner, name)
        if ok: return v
        return Sym(f"{owner.split('/')[-1]}.{name}")

    def invoke(self, owner, name, desc, args, virtual=False, static=False):
        ok, v = self.natives.handle(self, owner, name, desc, args)
        if ok: return v
        args = self.widen(args, desc, not static)
        target = owner
        if virtual and args and isinstance(args[0], Obj):
            target = args[0].cls
        cf, m = self.find_method(target, name, desc)
        if cf is None and virtual: cf, m = self.find_method(owner, name, desc)
        if cf is None:
            if not returns_value(desc): return None
            if args and isinstance(args[0], Sym) and not static:   # keep the receiver's name: AMEntityRegistry.GORILLA.get()
                return Sym(f"{args[0].what}.{name}()")
            return Sym(f"{owner.split('/')[-1]}.{name}()")
        try:
            return self.run(cf, m, args)
        except JVMError as e:
            # a failing helper (validation throw, unsupported op) shouldn't kill the whole model build
            self.errors = getattr(self, "errors", []) + [f"{cf.name}.{name}: {e}"]
            return Sym(f"failed {name}") if returns_value(desc) else None

    def run(self, cf, m, args):
        acc, code, max_locals = m
        return self._exec(cf, code, args, max_locals)

    def _exec(self, cf, code, args, max_locals):
        loc = [None] * (max_locals + 8)
        for i, a in enumerate(args): loc[i] = a   # caller lays out wide slots (see _call)
        st = []; pc = 0; cp = cf
        while True:
            self.steps += 1
            if self.steps > 3_000_000: raise JVMError("step limit")
            op = code[pc]
            if op == 0x00: pc += 1
            elif op == 0x01: st.append(None); pc += 1
            elif 0x02 <= op <= 0x08: st.append(op - 3); pc += 1
            elif op in (0x09, 0x0a): st.append(op - 9); pc += 1
            elif 0x0b <= op <= 0x0d: st.append(float(op - 0x0b)); pc += 1
            elif op in (0x0e, 0x0f): st.append(float(op - 0x0e)); pc += 1
            elif op == 0x10: st.append(struct.unpack_from(">b", code, pc + 1)[0]); pc += 2
            elif op == 0x11: st.append(struct.unpack_from(">h", code, pc + 1)[0]); pc += 3
            elif op == 0x12: st.append(cp.const(code[pc + 1])); pc += 2
            elif op in (0x13, 0x14): st.append(cp.const(u2(code, pc + 1))); pc += 3
            elif 0x15 <= op <= 0x19: st.append(loc[code[pc + 1]]); pc += 2
            elif 0x1a <= op <= 0x2d: st.append(loc[(op - 0x1a) % 4]); pc += 1
            elif 0x2e <= op <= 0x35:
                i = st.pop(); a = st.pop()
                if not isinstance(i, int): i = 0
                st.append(a[i] if isinstance(a, list) and 0 <= i < len(a) else Sym("aload")); pc += 1
            elif 0x36 <= op <= 0x3a: loc[code[pc + 1]] = st.pop(); pc += 2
            elif 0x3b <= op <= 0x4e: loc[(op - 0x3b) % 4] = st.pop(); pc += 1
            elif 0x4f <= op <= 0x56:
                v = st.pop(); i = st.pop(); a = st.pop()
                if isinstance(a, list) and isinstance(i, int) and 0 <= i < len(a): a[i] = v
                pc += 1
            elif op == 0x57: st.pop(); pc += 1
            elif op == 0x58: st.pop(); pc += 1   # pop2: wide values use one stack entry here
            elif op == 0x59: st.append(st[-1]); pc += 1
            elif op == 0x5a: v = st.pop(); w = st.pop(); st += [v, w, v]; pc += 1
            elif op == 0x5b: v = st.pop(); w = st.pop(); x = st.pop(); st += [v, x, w, v]; pc += 1
            elif op == 0x5c: st += st[-2:]; pc += 1
            elif op == 0x5f: st[-1], st[-2] = st[-2], st[-1]; pc += 1
            elif 0x60 <= op <= 0x83:
                b = st.pop(); a = st.pop() if op not in (0x74, 0x75, 0x76, 0x77) else None
                if op in (0x74, 0x75, 0x76, 0x77): st.append(-b if not isinstance(b, Sym) else b)
                else: st.append(self.arith(op, a, b))
                pc += 1
            elif op == 0x84:
                loc[code[pc + 1]] = i32((loc[code[pc + 1]] or 0) + struct.unpack_from(">b", code, pc + 2)[0]); pc += 3
            elif 0x85 <= op <= 0x93:
                v = st.pop()
                if isinstance(v, Sym): st.append(v)
                elif op in (0x86, 0x87, 0x89, 0x8a, 0x8d): st.append(float(v))
                elif op == 0x85: st.append(int(v))
                elif op in (0x88, 0x8b, 0x8c, 0x8e, 0x8f):
                    st.append(int(math.trunc(v)) if math.isfinite(v) else 0)
                elif op == 0x90: st.append(f32(v))
                elif op == 0x91: st.append(((int(v) + 128) & 255) - 128)
                elif op == 0x92: st.append(int(v) & 0xFFFF)
                elif op == 0x93: st.append(((int(v) + 32768) & 0xFFFF) - 32768)
                pc += 1
            elif 0x94 <= op <= 0x98:
                b = st.pop(); a = st.pop()
                if isinstance(a, Sym) or isinstance(b, Sym): st.append(0)
                else: st.append((a > b) - (a < b))
                pc += 1
            elif 0x99 <= op <= 0x9e:
                v = st.pop(); off = struct.unpack_from(">h", code, pc + 1)[0]
                v = 0 if isinstance(v, Sym) or v is None else v
                cond = [v == 0, v != 0, v < 0, v >= 0, v > 0, v <= 0][op - 0x99]
                pc = pc + off if cond else pc + 3
            elif 0x9f <= op <= 0xa6:
                b = st.pop(); a = st.pop(); off = struct.unpack_from(">h", code, pc + 1)[0]
                if op in (0xa5, 0xa6): cond = (a is b) if op == 0xa5 else (a is not b)
                else:
                    a = 0 if isinstance(a, Sym) else a; b = 0 if isinstance(b, Sym) else b
                    cond = [a == b, a != b, a < b, a >= b, a > b, a <= b][op - 0x9f]
                pc = pc + off if cond else pc + 3
            elif op == 0xa7: pc += struct.unpack_from(">h", code, pc + 1)[0]
            elif op == 0xaa:
                k = st.pop(); q = (pc + 4) & ~3
                if not isinstance(k, int) and getattr(self, "force_switch", None) is not None: k = self.force_switch
                d, lo, hi = struct.unpack_from(">iii", code, q)
                pc += struct.unpack_from(">i", code, q + 12 + 4 * (k - lo))[0] if isinstance(k, int) and lo <= k <= hi else d
            elif op == 0xab:
                k = st.pop(); q = (pc + 4) & ~3
                d, n = struct.unpack_from(">ii", code, q); tgt = d
                for j in range(n):
                    kk, off = struct.unpack_from(">ii", code, q + 8 + 8 * j)
                    if kk == k: tgt = off; break
                pc += tgt
            elif 0xac <= op <= 0xb0: return st.pop()
            elif op == 0xb1: return None
            elif op == 0xb2:
                owner, name, d = cp.ref(u2(code, pc + 1)); st.append(self.get_static(owner, name, d)); pc += 3
            elif op == 0xb3:
                owner, name, d = cp.ref(u2(code, pc + 1)); self.statics[(owner, name)] = st.pop(); pc += 3
            elif op == 0xb4:
                owner, name, d = cp.ref(u2(code, pc + 1)); o = st.pop()
                if isinstance(o, Obj):
                    ok, v = self.natives.get_field(self, o, name)
                    st.append(v if ok else o.f.get(name, 0 if d in "IFDJZBSC" else None))
                else: st.append(0 if d in "IFDJZBSC" else Sym("field " + name))
                pc += 3
            elif op == 0xb5:
                owner, name, d = cp.ref(u2(code, pc + 1)); v = st.pop(); o = st.pop()
                if isinstance(o, Obj):
                    if not self.natives.put_field(self, o, name, v): o.f[name] = v
                pc += 3
            elif 0xb6 <= op <= 0xb9:
                owner, name, d = cp.ref(u2(code, pc + 1))
                n = arg_count(d) + (0 if op == 0xb8 else 1)
                args = st[len(st) - n:] if n else []
                del st[len(st) - n:]
                r = self.invoke(owner, name, d, args, virtual=op in (0xb6, 0xb9), static=op == 0xb8)
                if returns_value(d): st.append(r)
                pc += 5 if op == 0xb9 else 3
            elif op == 0xba:
                idx = u2(code, pc + 1); e = cp.cp[idx]; bsm, (name, d) = cp.bootstrap[e[1]], cp.nat(e[2])
                n = arg_count(d); args = st[len(st) - n:] if n else []
                del st[len(st) - n:]
                st.append(self.indy(cp, bsm, name, d, args)); pc += 5
            elif op == 0xbb:
                st.append(Obj(cp.cls_name(u2(code, pc + 1)))); pc += 3
            elif op == 0xbc: n = st.pop(); st.append([0] * n if isinstance(n, int) else []); pc += 2
            elif op == 0xbd: n = st.pop(); st.append([None] * n if isinstance(n, int) else []); pc += 3
            elif op == 0xbe: a = st.pop(); st.append(len(a) if isinstance(a, list) else 0); pc += 1
            elif op == 0xbf: raise JVMError("athrow")
            elif op == 0xc0: pc += 3
            elif op == 0xc1:
                o = st.pop(); want = cp.cls_name(u2(code, pc + 1))
                st.append(1 if isinstance(o, Obj) and self.is_a(o.cls, want) else 0); pc += 3
            elif op in (0xc2, 0xc3): st.pop(); pc += 1
            elif op == 0xc4:
                op2 = code[pc + 1]; idx = u2(code, pc + 2)
                if op2 == 0x84: loc[idx] = i32((loc[idx] or 0) + struct.unpack_from(">h", code, pc + 4)[0]); pc += 6
                elif 0x15 <= op2 <= 0x19: st.append(loc[idx]); pc += 4
                else: loc[idx] = st.pop(); pc += 4
            elif op == 0xc5:
                dims = code[pc + 3]; del st[len(st) - dims:]; st.append([]); pc += 4
            elif op in (0xc6, 0xc7):
                v = st.pop(); off = struct.unpack_from(">h", code, pc + 1)[0]
                isnull = v is None
                pc = pc + off if (isnull if op == 0xc6 else not isnull) else pc + 3
            elif op == 0xc8: pc += struct.unpack_from(">i", code, pc + 1)[0]
            else: raise JVMError(f"opcode {op:#x} at {pc} in {cf.name}")

    def widen(self, args, desc, has_this):
        """Insert padding after long/double args so they occupy two local slots in the callee."""
        types = (["L"] if has_this else []) + arg_types(desc)
        out = []
        for t, a in zip(types, args):
            out.append(a)
            if t in ("J", "D"): out.append(None)
        return out

    def is_a(self, cls, want):
        while cls:
            if cls == want: return True
            cf = self.load(cls)
            if not cf: return False
            cls = cf.super
        return False

    def arith(self, op, a, b):
        if isinstance(a, Sym) or isinstance(b, Sym) or a is None or b is None: return 0.0
        k = (op - 0x60) // 4; t = (op - 0x60) % 4   # 0 int 1 long 2 float 3 double
        if op >= 0x78:   # shifts / logic
            k2 = op - 0x78
            ops = [lambda: a << (b & 31), lambda: a << (b & 63), lambda: a >> (b & 31), lambda: a >> (b & 63),
                   lambda: (a & 0xFFFFFFFF) >> (b & 31), lambda: (a & 0xFFFFFFFFFFFFFFFF) >> (b & 63),
                   lambda: a & b, lambda: a & b, lambda: a | b, lambda: a | b, lambda: a ^ b, lambda: a ^ b]
            r = ops[k2](); return i32(r) if k2 % 2 == 0 else r
        if k == 0: r = a + b
        elif k == 1: r = a - b
        elif k == 2: r = a * b
        elif k == 3:
            if t < 2: r = int(a / b) if b else 0
            else: r = a / b if b else (math.inf if a > 0 else -math.inf if a < 0 else math.nan)
        elif k == 4:
            if t < 2: r = int(math.fmod(a, b)) if b else 0
            else: r = math.fmod(a, b) if b else math.nan
        else: r = a
        if t == 0: return i32(int(r))
        if t == 2: return f32(r)
        return r

    def indy(self, cp, bsm, name, desc, args):
        ref, bargs = bsm
        if name == "makeConcatWithConstants":
            recipe = cp.const(bargs[0]); out = []; it = iter(args)
            for ch in recipe:
                out.append(str(fmt(next(it))) if ch == "\u0001" else ch)
            return "".join(out)
        return Sym(f"lambda {name}")

def fmt(v):
    if isinstance(v, float) and v.is_integer(): return str(v)
    return v
