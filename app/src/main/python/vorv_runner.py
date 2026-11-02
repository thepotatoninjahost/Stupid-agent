"""Vorv's hardened Python sandbox runner (audit finding C-2, rebuilt after C-1).

Called by PythonExecTool to execute LLM-produced Python with captured
stdout/stderr, a real time budget, and layered sandboxing:

Layer 1 — sys.addaudithook (CPython audit hooks, Python 3.8+):
    Installed ONCE at module import; user code cannot remove it. Blocks the
    audit events for file I/O (open, os.remove, ...), process spawning
    (os.system, os.exec, subprocess.Popen), sockets (socket.*), and
    environment/user-id tampering — raising PermissionError at the C level.

Layer 2 — AST-level attribute guard (NEW, closes audit finding C-1):
    User code is parsed and its AST is REWRITTEN before compilation:
      - every attribute READ  `obj.name`  becomes `__vorv_getattr__(obj, 'name')`
      - every attribute WRITE `obj.name = v` becomes `__vorv_setattr__(obj, 'name', v)`
      - every `del obj.name`  becomes `__vorv_delattr__(obj, 'name')`
      - every `from X import y` becomes `y = __vorv_getattr__(__vorv_import__('X'), 'y')`
    The guards block, at compile time AND runtime:
      - every dunder attribute name (`__class__`, `__globals__`, `__subclasses__`,
        `__dict__`, ...) in ANY syntax position — the old runner only wrapped the
        getattr() builtin, so `obj.__class__` bypassed it entirely (C-1);
      - reflection helpers that do attribute access inside C code, invisible to
        any Python-level guard: `str.format` / `str.format_map` (use f-strings);
      - any attribute access whose VALUE is a module outside the whitelist
        (e.g. `re.enum` → enum module → `enum.sys`): the module hop itself is
        refused, killing os/sys leaks through transitive imports;
      - writes to / deletes from module objects (prevents poisoning module
        state for subsequent runs of the SAME long-lived interpreter);
      - `from X import *` and relative imports.
    The exposed `getattr`/`setattr`/`delattr` builtins are the SAME guarded
    functions the rewritten code uses, so shadowing them with each other gains
    an attacker nothing, and no unguarded attribute primitive exists in the
    namespace.

Layer 3 — restricted __builtins__ + import whitelist:
    open/eval/exec/compile/input/breakpoint/exit/quit/help/globals/locals/
    vars/super are absent. Only pre-imported pure-computation stdlib modules
    are importable. uuid, random and statistics are DELIBERATELY NOT
    whitelisted (uuid and random import os/sys at module level — audit C-1 —
    and statistics imports random); safe runner-implemented replacements are
    exposed as builtins instead: uuid4(), randint(a, b), random_float()
    (non-cryptographic, time-seeded).

Layer 4 — time budget:
    A sys.settrace watchdog inside the worker thread raises TimeoutError
    once the deadline passes (checked on every line event — C-level calls
    that never return to Python bytecode are bounded by the Kotlin-side
    thread join).

Contract with Kotlin (PythonExecTool):
    run_code(code: str, timeout_ms: int) -> dict
        {"ok": bool, "stdout": str, "stderr": str, "error": str|None,
         "timed_out": bool}

Residual risk (documented in SECURITY.md): an in-process sandbox is
defense-in-depth, not a formal security boundary — a process boundary
would be required for that. The AST guard makes every KNOWN escape route
(dunder syntax, format-string reflection, module hopping, builtin
shadowing, frame walking, bytecode synthesis, ctypes) fail closed, but a
novel CPython-level exploit could still exist. Blast radius is the app
process.
"""
import ast
import builtins
import importlib
import io
import sys
import threading
import time
import types

# --------------------------------------------------------------------------
# Tunables
# --------------------------------------------------------------------------
_TIMEOUT_DEFAULT_MS = 10_000
_MAX_TIMEOUT_MS = 30_000
_MIN_TIMEOUT_MS = 500
_WALL_CLOCK_MARGIN_MS = 2_000

# --------------------------------------------------------------------------
# Layer 3: import whitelist (pre-imported, pure computation only)
# --------------------------------------------------------------------------
# audit C-1: uuid and random REMOVED (they import os/sys at module level, so
# a two-token attribute chain used to hand user code both module objects);
# statistics REMOVED (it imports random). Safe replacements are provided as
# builtins below (uuid4, randint, random_float).
_WHITELIST = frozenset({
    "json", "math", "re", "datetime", "itertools", "functools",
    "collections", "string", "decimal", "fractions", "heapq",
    "bisect", "array", "textwrap", "base64", "binascii", "hashlib",
    "unicodedata",
})

# Pre-import every whitelisted module NOW, before the audit hook exists.
# Later imports from user code hit sys.modules only — zero file I/O.
for _m in sorted(_WHITELIST):
    try:
        importlib.import_module(_m)
    except Exception:  # pragma: no cover - a module failing to pre-import
        pass            # simply won't be available to user code

# --------------------------------------------------------------------------
# Layer 1: audit hook
# --------------------------------------------------------------------------
_BLOCKED_AUDIT_EVENTS = frozenset({
    # file I/O
    "open", "os.chmod", "os.chown", "os.link", "os.remove", "os.rename",
    "os.rmdir", "os.symlink", "os.truncate", "os.unlink", "os.utime",
    "os.mkfifo", "os.mknod", "os.copy_file_range", "os.sendfile",
    # process control
    "os.system", "os.exec", "os.spawn", "os.fork", "os.forkpty", "os.kill",
    "os.killpg", "os.setsid", "os.setuid", "os.setgid", "os.setreuid",
    "os.setregid", "os.setresuid", "os.setresgid", "os.startfile",
    "os.putenv", "os.unsetenv", "os.chdir", "os.mkdir", "os.rmdir",
    "subprocess.Popen",
    # code synthesis (eval/compile functions are also absent from builtins)
    "builtins.input", "builtins.breakpoint",
    # frame walking (escape route to runner globals)
    "sys._getframe", "sys._current_frames",
    # dynamic loading
    "ctypes.dlopen", "ctypes.dlsym", "ctypes.call_function",
    "ctypes.cdata", "ctypes.create_string_buffer", "ctypes.string_at",
    # winreg (Windows-only events; harmless to list)
    "winreg.ConnectRegistry", "winreg.OpenKey", "winreg.CreateKey",
    "winreg.DeleteKey", "winreg.SetValue", "winreg.DeleteValue",
})


def _audit(event, args):
    if event in _BLOCKED_AUDIT_EVENTS or event.startswith("socket."):
        raise PermissionError(
            "Vorv sandbox: operation '%s' is blocked (file/network/process "
            "access is not allowed in python_exec)" % event
        )


sys.addaudithook(_audit)

# --------------------------------------------------------------------------
# Layer 3 (enforcement side): meta-path finder — belt and braces
# --------------------------------------------------------------------------
class _WhitelistFinder:
    """MetaPathFinder that raises for any non-whitelisted top-level import.

    Returning None would mean 'not my problem' and let the real finders try
    (and hit the audit hook). Raising ImportError gives the user code a
    clean, honest message instead.
    """

    def find_spec(self, fullname, path=None, target=None):
        root = fullname.split(".")[0]
        if root not in _WHITELIST:
            raise ImportError(
                "Vorv sandbox: import of '%s' is not allowed. Allowed: %s"
                % (fullname, ", ".join(sorted(_WHITELIST)))
            )
        return None  # whitelisted: let normal machinery (sys.modules) serve it


sys.meta_path.insert(0, _WhitelistFinder())


def _safe_import(name, globals=None, locals=None, fromlist=(), level=0):
    if level and level > 0:
        raise ImportError(
            "Vorv sandbox: relative imports are not allowed"
        )
    root = name.split(".")[0]
    if root not in _WHITELIST:
        raise ImportError(
            "Vorv sandbox: import of '%s' is not allowed. Allowed: %s"
            % (name, ", ".join(sorted(_WHITELIST)))
        )
    mod = sys.modules.get(name)
    if mod is not None:
        return mod
    if "." in name:
        parent_name, _, child = name.rpartition(".")
        parent = sys.modules.get(parent_name)
        if parent is not None:
            child_mod = getattr(parent, child, None)
            if child_mod is not None:
                return child_mod
    raise ImportError(
        "Vorv sandbox: module '%s' is not currently loaded and dynamic "
        "loading is disabled" % name
    )


# --------------------------------------------------------------------------
# Layer 2: the attribute guard (audit finding C-1 core fix)
# --------------------------------------------------------------------------
def _is_dunder(name):
    return isinstance(name, str) and len(name) > 4 and \
        name.startswith("__") and name.endswith("__")


# Non-dunder attribute names that still lead to escapes and are blocked.
#  - format / format_map: str.format does real attribute access inside C
#    code ("{0.__class__}".format(x)) that NO Python-level guard can see.
#    f-strings are compiled and ARE rewritten by the AST guard — use those.
#  - frame/coroutine/generator/traceback internals (f_back, gi_frame,
#    cr_frame, tb_frame, ...): not dunders, but hand out live frames.
#  - mro: class-walking helper, blocked for hygiene.
_BLOCKED_ATTR_NAMES = frozenset({
    "format", "format_map", "mro",
    "f_back", "f_globals", "f_locals", "f_builtins", "f_code", "f_trace",
    "gi_frame", "gi_code", "gi_yieldfrom",
    "cr_frame", "cr_code", "cr_await", "cr_origin",
    "tb_frame", "tb_next", "tb_lineno",
    "func_globals", "func_code", "func_closure", "im_func", "im_self",
})


def _check_attr_name(name):
    if _is_dunder(name):
        raise PermissionError(
            "Vorv sandbox: dunder attribute access ('%s') is blocked. "
            "Use the builtin equivalent (len/type/iter/next/str) instead." % name
        )
    if name in _BLOCKED_ATTR_NAMES:
        raise PermissionError(
            "Vorv sandbox: attribute '%s' is blocked (reflection route)" % name
        )


def _vorv_getattr(obj, name, *default):
    """The ONLY sanctioned attribute read. Used by the rewritten AST, and
    exposed as the `getattr` builtin (same object — shadowing gains nothing).

    Blocks blocked names, and blocks any read whose value is a module
    outside the whitelist (the `re.enum` -> `enum.sys` chain from C-1).
    """
    _check_attr_name(name)
    if default:
        val = getattr(obj, name, default[0])
    else:
        val = getattr(obj, name)
    if isinstance(val, types.ModuleType):
        mod_name = val.__name__
        # Exact-name check: only the pre-imported whitelist members themselves
        # are allowed as attribute values — NOT their submodules (json.decoder
        # transitively reaches re → enum → sys).
        if mod_name not in _WHITELIST:
            raise PermissionError(
                "Vorv sandbox: attribute '%s' would expose module '%s', "
                "which is outside the import whitelist" % (name, mod_name)
            )
    return val


def _vorv_setattr(obj, name, value):
    """The ONLY sanctioned attribute write. Module objects are read-only —
    user code cannot poison whitelisted module state for later runs of this
    long-lived interpreter.
    """
    _check_attr_name(name)
    if isinstance(obj, types.ModuleType):
        raise PermissionError(
            "Vorv sandbox: module attributes are read-only ('%s')" % name
        )
    return setattr(obj, name, value)


def _vorv_delattr(obj, name):
    _check_attr_name(name)
    if isinstance(obj, types.ModuleType):
        raise PermissionError(
            "Vorv sandbox: module attributes are read-only ('%s')" % name
        )
    return delattr(obj, name)


class _SandboxViolation(Exception):
    """Compile-time rejection of a blocked construct (clear message, no
    partial execution)."""


class _AstGuard(ast.NodeTransformer):
    """Rewrites user code so every attribute operation routes through the
    guarded helpers, and statically rejects what cannot be rewritten.
    """

    def visit_Attribute(self, node):
        self.generic_visit(node)
        if isinstance(node.ctx, ast.Load):
            return ast.copy_location(
                ast.Call(
                    func=ast.Name(id="__vorv_getattr__", ctx=ast.Load()),
                    args=[node.value, ast.Constant(value=node.attr)],
                    keywords=[],
                ),
                node,
            )
        # Store / Del contexts are rewritten at statement level (below);
        # a bare Attribute in those contexts inside this method means an
        # unhandled position — fail closed.
        return node

    # ---- obj.attr = value  /  del obj.attr ------------------------------
    def visit_Assign(self, node):
        self.generic_visit(node)
        new_targets = []
        rewritten_calls = []
        for target in node.targets:
            if isinstance(target, ast.Attribute):
                if _is_dunder(target.attr) or target.attr in _BLOCKED_ATTR_NAMES:
                    raise _SandboxViolation(
                        "Vorv sandbox: attribute write '%s' is blocked" % target.attr
                    )
                rewritten_calls.append(ast.copy_location(
                    ast.Expr(value=self._attr_store_call(target, node.value)), node
                ))
            elif isinstance(target, (ast.Tuple, ast.List)):
                if any(isinstance(t, ast.Attribute) for t in target.elts):
                    raise _SandboxViolation(
                        "Vorv sandbox: tuple-unpacking into object attributes "
                        "is not supported"
                    )
                new_targets.append(target)
            else:
                new_targets.append(target)
        if not rewritten_calls:
            return node
        if new_targets:
            # e.g. `a = obj.x = 5` — keep the plain assignments, then the call
            return [ast.copy_location(
                ast.Assign(targets=new_targets, value=node.value), node
            )] + rewritten_calls
        return rewritten_calls
    def visit_AnnAssign(self, node):
        self.generic_visit(node)
        target = node.target
        if isinstance(target, ast.Attribute) and node.value is not None:
            if _is_dunder(target.attr) or target.attr in _BLOCKED_ATTR_NAMES:
                raise _SandboxViolation(
                    "Vorv sandbox: attribute write '%s' is blocked" % target.attr
                )
            return ast.copy_location(
                ast.Expr(value=self._attr_store_call(target, node.value)), node
            )
        return node
    def visit_AugAssign(self, node):
        self.generic_visit(node)
        target = node.target
        if isinstance(target, ast.Attribute):
            if _is_dunder(target.attr) or target.attr in _BLOCKED_ATTR_NAMES:
                raise _SandboxViolation(
                    "Vorv sandbox: attribute write '%s' is blocked" % target.attr
                )
            read = self._attr_load_expr(target)
            return ast.copy_location(
                ast.Expr(value=ast.Call(
                    func=ast.Name(id="__vorv_setattr__", ctx=ast.Load()),
                    args=[target.value, ast.Constant(value=target.attr),
                          ast.BinOp(left=read, op=node.op, right=node.value)],
                    keywords=[],
                )),
                node,
            )
        return node

    def visit_Delete(self, node):
        self.generic_visit(node)
        kept, calls = [], []
        for target in node.targets:
            if isinstance(target, ast.Attribute):
                if _is_dunder(target.attr) or target.attr in _BLOCKED_ATTR_NAMES:
                    raise _SandboxViolation(
                        "Vorv sandbox: attribute delete '%s' is blocked" % target.attr
                    )
                calls.append(ast.copy_location(
                    ast.Expr(value=ast.Call(
                        func=ast.Name(id="__vorv_delattr__", ctx=ast.Load()),
                        args=[target.value, ast.Constant(value=target.attr)],
                        keywords=[],
                    )), target))
            else:
                kept.append(target)
        if not calls:
            return node
        if kept:
            return [ast.copy_location(ast.Delete(targets=kept), node)] + calls
        return calls

    # ---- from X import y, z ---------------------------------------------
    def visit_ImportFrom(self, node):
        if node.level and node.level > 0:
            raise _SandboxViolation(
                "Vorv sandbox: relative imports are not allowed"
            )
        if any(alias.name == "*" for alias in node.names):
            raise _SandboxViolation(
                "Vorv sandbox: 'from X import *' is not allowed"
            )
        module = node.module or ""
        stmts = []
        for alias in node.names:
            bind = alias.asname or alias.name
            stmts.append(ast.copy_location(
                ast.Assign(
                    targets=[ast.Name(id=bind, ctx=ast.Store())],
                    value=ast.Call(
                        func=ast.Name(id="__vorv_getattr__", ctx=ast.Load()),
                        args=[
                            ast.Call(
                                func=ast.Name(id="__vorv_import__", ctx=ast.Load()),
                                args=[ast.Constant(value=module)],
                                keywords=[],
                            ),
                            ast.Constant(value=alias.name),
                        ],
                        keywords=[],
                    ),
                ), node))
        return stmts

    # ---- helpers ----------------------------------------------------------
    def _attr_load_expr(self, attr):
        return ast.copy_location(
            ast.Call(
                func=ast.Name(id="__vorv_getattr__", ctx=ast.Load()),
                args=[attr.value, ast.Constant(value=attr.attr)],
                keywords=[],
            ),
            attr,
        )

    def _attr_store_call(self, attr, value):
        """Returns the bare __vorv_setattr__ CALL (not wrapped in Expr)."""
        return ast.copy_location(
            ast.Call(
                func=ast.Name(id="__vorv_setattr__", ctx=ast.Load()),
                args=[attr.value, ast.Constant(value=attr.attr), value],
                keywords=[],
            ), attr)


def _rewrite(code_str):
    """Parse, statically check and rewrite user code. Raises _SandboxViolation
    (compile-time, before ANY user code runs) or SyntaxError.
    """
    tree = ast.parse(code_str, mode="exec")

    # Static pass: reject blocked attribute names in ANY context. The
    # transformer enforces runtime semantics; this pass gives an immediate,
    # clear refusal for `x.__class__`, `f.__globals__`, `.format`, ...
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute):
            try:
                _check_attr_name(node.attr)
            except PermissionError as e:
                raise _SandboxViolation(str(e)) from None

    tree = _AstGuard().visit(tree)
    ast.fix_missing_locations(tree)
    return tree


# --------------------------------------------------------------------------
# Layer 2/3: safe builtins assembly
# --------------------------------------------------------------------------
_UNSAFE_BUILTINS = frozenset({
    "open", "eval", "exec", "compile", "__import__", "input", "breakpoint",
    "exit", "quit", "help", "globals", "locals", "vars", "delattr", "super",
    "getattr", "setattr",  # re-added below as the GUARDED versions
})

_safe_builtins = {
    k: v for k, v in builtins.__dict__.items()
    if k not in _UNSAFE_BUILTINS and not k.startswith("__")
}
# Dunder names are stripped above; keep the essential constants.
_safe_builtins["True"] = True
_safe_builtins["False"] = False
_safe_builtins["None"] = None
_safe_builtins["__import__"] = _safe_import
_safe_builtins["__build_class__"] = builtins.__build_class__  # class statements work
_safe_builtins["__name__"] = "__vorv_sandbox__"

# The guarded attribute helpers — exposed BOTH as the builtins the user sees
# AND as the names the rewritten AST calls. Same objects on purpose: an
# attacker can shadow the names with each other but there is no unguarded
# attribute primitive anywhere in the namespace.
_safe_builtins["getattr"] = _vorv_getattr
_safe_builtins["setattr"] = _vorv_setattr
_safe_builtins["delattr"] = _vorv_delattr
_safe_builtins["__vorv_getattr__"] = _vorv_getattr
_safe_builtins["__vorv_setattr__"] = _vorv_setattr
_safe_builtins["__vorv_delattr__"] = _vorv_delattr
_safe_builtins["__vorv_import__"] = _safe_import

def _guarded_hasattr(obj, name):
    if _is_dunder(name) or name in _BLOCKED_ATTR_NAMES:
        return False
    return hasattr(obj, name)


_safe_builtins["hasattr"] = _guarded_hasattr

# --------------------------------------------------------------------------
# Safe replacements for the de-whitelisted modules (uuid / random).
# Non-cryptographic by design and by construction: no entropy source exists
# inside the sandbox (os.urandom would require os). Seeded from the clock.
# --------------------------------------------------------------------------
_rng_state = [ (time.monotonic_ns() ^ (id(_safe_builtins) << 17)) & 0xFFFFFFFFFFFF ]


def _rng_next():
    # xorshift64* — small, fast, decent distribution for non-crypto use.
    x = _rng_state[0]
    x ^= (x << 13) & 0xFFFFFFFFFFFFFFFF
    x ^= x >> 7
    x ^= (x << 17) & 0xFFFFFFFFFFFFFFFF
    _rng_state[0] = x
    return (x * 0x2545F4914F6CDD1D) & 0xFFFFFFFFFFFFFFFF


def _uuid4():
    """Non-cryptographic UUID4-shaped value (16 hex groups). Fine for ids
    and labels inside a computation; NOT for secrets."""
    x = _rng_next()
    y = _rng_next()
    return "%08x-%04x-4%03x-%04x-%012x" % (
        x & 0xFFFFFFFF,
        (x >> 32) & 0xFFFF,
        (x >> 48) & 0x0FFF,
        (0x8000 | ((y >> 16) & 0x3FFF)),
        (y >> 32) & 0xFFFFFFFFFFFF,
    )


def _randint(a, b):
    import operator
    if not (isinstance(a, int) and isinstance(b, int)):
        raise TypeError("randint(a, b) requires integers")
    if b < a:
        raise ValueError("randint: b must be >= a")
    return a + (_rng_next() % (b - a + 1))


def _random_float():
    return _rng_next() / float(0xFFFFFFFFFFFFFFFF)


_safe_builtins["uuid4"] = _uuid4
_safe_builtins["randint"] = _randint
_safe_builtins["random_float"] = _random_float


# --------------------------------------------------------------------------
# Layer 4: trace watchdog
# --------------------------------------------------------------------------
class _Watchdog:
    __slots__ = ("deadline",)

    def __init__(self, deadline):
        self.deadline = deadline

    def __call__(self, frame, event, arg):
        if time.monotonic() > self.deadline:
            raise TimeoutError("Execution exceeded its time budget")
        return self


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------
def run_code(code, timeout_ms=_TIMEOUT_DEFAULT_MS):
    """Execute user Python code with captured stdout/stderr and a time budget."""
    try:
        timeout_ms = int(timeout_ms)
    except (TypeError, ValueError):
        timeout_ms = _TIMEOUT_DEFAULT_MS
    timeout_ms = max(_MIN_TIMEOUT_MS, min(timeout_ms, _MAX_TIMEOUT_MS))

    result = {"ok": True, "stdout": "", "stderr": "", "error": None, "timed_out": False}

    try:
        # compile() is fine here: it runs BEFORE user code, and user code
        # cannot reach the builtin (removed from _safe_builtins) nor trigger
        # the "compile" audit event (not in the block list — deliberately,
        # so this very call works). The AST is rewritten first (Layer 2).
        code_obj = compile(_rewrite(code), "<vorv-sandbox>", "exec")
    except _SandboxViolation as e:
        result["ok"] = False
        result["error"] = "SandboxRefused: %s" % e
        return result
    except SyntaxError as e:
        result["ok"] = False
        result["error"] = "SyntaxError: %s" % e
        return result

    buf_out = io.StringIO()
    buf_err = io.StringIO()
    old_out, old_err = sys.stdout, sys.stderr
    sys.stdout, sys.stderr = buf_out, buf_err

    state = {"error": None, "timed_out": False}

    def _worker():
        deadline = time.monotonic() + (timeout_ms / 1000.0)
        try:
            sys.settrace(_Watchdog(deadline))
            try:
                exec(code_obj, {"__builtins__": _safe_builtins, "__name__": "__vorv_sandbox__"})
            finally:
                sys.settrace(None)
        except TimeoutError as e:
            state["timed_out"] = True
            state["error"] = str(e)
        except BaseException as e:  # SystemExit, KeyboardInterrupt, everything
            state["error"] = "%s: %s" % (type(e).__name__, e)

    t = threading.Thread(target=_worker, daemon=True)
    t.start()
    t.join((timeout_ms + _WALL_CLOCK_MARGIN_MS) / 1000.0)

    if t.is_alive():
        # Uninterruptible C-level call (shouldn't happen — time/os/socket are
        # blocked — but honest reporting beats a hang).
        result["ok"] = False
        result["timed_out"] = True
        result["error"] = (
            "Execution hard-timed-out after %dms (thread could not be "
            "interrupted)" % timeout_ms
        )
    elif state["error"] is not None:
        result["ok"] = False
        result["timed_out"] = state["timed_out"]
        result["error"] = state["error"]

    sys.stdout, sys.stderr = old_out, old_err
    result["stdout"] = buf_out.getvalue()
    result["stderr"] = buf_err.getvalue()
    return result
