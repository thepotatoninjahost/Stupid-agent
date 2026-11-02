"""Vorv's hardened Python sandbox runner (audit finding C-1, remediated).

Called by PythonExecTool to execute LLM-produced Python with captured
stdout/stderr, a real time budget, and layered sandboxing.

SECURITY POSTURE (honest — read before trusting this):
    Layers 0-4 are DEFENSE IN DEPTH, not a formal security boundary. No
    in-process CPython mechanism can intercept attribute-access syntax, so
    a determined walk like ``(0).__class__.__base__.__subclasses__()`` can
    enumerate every loaded class and, through some class's methods, reach
    an un-scrubbed namespace. What this runner DOES guarantee:

      * every namespace reachable FROM THE WHITELISTED MODULES is scrubbed
        of the real ``os``/``sys`` module objects (the turnkey PoC chains
        — ``uuid.uuid4.__globals__['os']``, ``random.urandom.__globals__``,
        ``statistics``'s transitive imports — are closed and self-tested
        at import time);
      * every callable the sandbox exposes to user code (guarded
        getattr/setattr/hasattr, the safe __import__, the meta-path
        finder, the trace watchdog) is defined in a CLEAN namespace whose
        globals contain only safe values, so ``fn.__globals__`` yields
        nothing;
      * a module that cannot be scrubbed or whose canary self-test fails
        is dropped from the whitelist for the session with an honest
        ImportError.

    The residual risk — walking the wider interpreter through
    ``object.__subclasses__()`` to a class defined in an un-scrubbed
    runtime module — has the app process as its blast radius and is the
    reason PythonExecTool additionally routes first use per task through
    the user-consent bridge, and why SECURITY.md documents python_exec as
    a raw-execution surface, not a sandbox. A true barrier needs a process
    boundary (a child interpreter with IPC); that is the documented
    long-term plan.

Layer 0 — namespace scrubbing + transitive verification (C-1 core fix):
    Pre-import the whitelisted modules, then walk every namespace dict
    reachable from them (module dicts, function __globals__, class dicts,
    MROs). Real ``os``/``sys`` module references are replaced with inert
    proxies (only ``os.urandom``/``getpid`` and a handful of sys constants
    survive — all C builtins or plain values, no reachable namespaces).
    Afterwards the walk is repeated in verify mode; any whitelisted module
    whose closure still exposes the real os/sys is dropped, and a canary
    expression per module proves the module still WORKS after scrubbing.

Layer 1 — sys.addaudithook: blocks file I/O, process spawning, sockets,
    env tampering, frame walking and dynamic loading at the C level.

Layer 2 — restricted __builtins__: open/eval/exec/compile/__import__/
    input/breakpoint/exit/quit/help/globals/locals/vars/delattr/super are
    absent; getattr/hasattr/setattr are dunder-guarded AND defined in a
    clean namespace.

Layer 3 — import whitelist: only pre-imported, scrubbed, pure-computation
    stdlib modules are reachable. Everything else raises ImportError.

Layer 4 — time budget: a per-run tracer (defined in a clean namespace)
    raises TimeoutError once the deadline passes; the Kotlin side enforces
    a hard join deadline on top.

Contract with Kotlin (PythonExecTool):
    run_code(code: str, timeout_ms: int) -> dict
        {"ok": bool, "stdout": str, "stderr": str, "error": str|None,
         "timed_out": bool}
"""
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
_MAX_SCRUB_DICTS = 6000

# --------------------------------------------------------------------------
# Layer 3: import whitelist (pre-imported, pure computation only)
# --------------------------------------------------------------------------
_ALL_MODULES = frozenset({
    "json", "math", "re", "datetime", "random", "itertools", "functools",
    "collections", "string", "statistics", "decimal", "fractions", "heapq",
    "bisect", "array", "textwrap", "base64", "binascii", "hashlib",
    "unicodedata", "uuid",
})

# Canary expression per module: run AFTER scrubbing; a module whose canary
# fails is dropped from the session whitelist (honest degradation rather
# than a silently broken module or a silently open leak).
_CANARIES = {
    "json": "json.loads(json.dumps({'a': [1, 2, 3]}))",
    "math": "math.sqrt(2) > 1.4",
    "re": "re.match(r'\\d+', '123abc').group(0) == '123'",
    "datetime": "str(datetime.date.today()).startswith('20')",
    "random": "0 <= random.uniform(0, 1) <= 1",
    "itertools": "list(itertools.islice(itertools.count(), 3)) == [0, 1, 2]",
    "functools": "functools.reduce(lambda a, b: a + b, [1, 2, 3]) == 6",
    "collections": "collections.Counter('aab')['a'] == 2",
    "string": "string.ascii_lowercase.startswith('abc')",
    "statistics": "statistics.mean([1, 2, 3]) == 2",
    "decimal": "str(decimal.Decimal('1.1') + decimal.Decimal('2.2'))",
    "fractions": "str(fractions.Fraction(1, 2)) == '1/2'",
    "heapq": "heapq.heappushpop([1, 3, 5], 2) == 1",
    "bisect": "bisect.bisect_left([1, 2, 3], 2) == 1",
    "array": "array.array('i', [1, 2]).tolist() == [1, 2]",
    "textwrap": "len(textwrap.fill('aa bb cc', width=2)) > 0",
    "base64": "base64.b64encode(b'hi').decode() == 'aGk='",
    "binascii": "binascii.hexlify(b'hi').decode() == '6869'",
    "hashlib": "hashlib.sha256(b'').hexdigest().startswith('e3b0')",
    "unicodedata": "unicodedata.name('A') == 'LATIN CAPITAL LETTER A'",
    "uuid": "len(str(uuid.uuid4())) == 36",
}

# --------------------------------------------------------------------------
# Layer 0: namespace scrubbing (audit C-1 remediation)
# --------------------------------------------------------------------------
# Real module objects, captured under private names BEFORE scrubbing. The
# runner's own namespace is never handed to user code, and these names are
# not 'os'/'sys', so even a stray reference to the runner's globals does
# not expose them under their canonical names.
_os_module = importlib.import_module("os")
_sys_module = sys

# Pre-import every whitelisted module NOW, before the audit hook exists.
# Later imports from user code hit sys.modules only — zero file I/O.
for _m in sorted(_ALL_MODULES):
    try:
        importlib.import_module(_m)
    except Exception:  # pragma: no cover - a module failing to pre-import
        continue        # simply won't be available to user code


class _InertOs:
    """Minimal os replacement for scrubbed namespaces: urandom/getpid only.

    urandom and getpid are C builtins with no __globals__ and no reachable
    namespace, so exposing them through this inert object is safe.
    Everything else raises — honestly and loudly.
    """
    _allowed = {"urandom": _os_module.urandom, "getpid": _os_module.getpid}

    def __getattr__(self, name):
        if name in _InertOs._allowed:
            return _InertOs._allowed[name]
        raise AttributeError(
            "Vorv sandbox: os.%s is not available inside python_exec" % name)


class _InertSys:
    """Minimal sys replacement: safe constants only. No modules, no path."""
    _constants = {
        "maxsize": _sys_module.maxsize,
        "byteorder": _sys_module.byteorder,
        "platform": "",
        "version": "",
        "hexversion": _sys_module.hexversion,
        "version_info": _sys_module.version_info,
        "intern": _sys_module.intern,
        "argv": [],
        "path": [],
    }

    def __getattr__(self, name):
        if name in _InertSys._constants:
            return _InertSys._constants[name]
        raise AttributeError(
            "Vorv sandbox: sys.%s is not available inside python_exec" % name)


_INERT_OS = _InertOs()
_INERT_SYS = _InertSys()


def _walk_namespaces(roots, replace_proxies):
    """Walk every namespace dict reachable from ``roots``.

    If ``replace_proxies`` is True, real os/sys module references found in a
    namespace are replaced with the inert proxies (SCRUB mode). Returns
    True if no real os/sys reference remains anywhere in the walked closure
    (VERIFY mode result).

    Reachability covers module dicts, function __globals__, class __dict__
    and MROs — the same routes attribute-syntax walks use.
    """
    seen = set()
    queue = list(roots)
    clean = True
    while queue:
        if len(seen) > _MAX_SCRUB_DICTS:
            return False  # closure unexpectedly huge — fail safe
        ns = queue.pop(0)
        if not isinstance(ns, dict) or id(ns) in seen:
            continue
        seen.add(id(ns))
        try:
            items = list(ns.items())
        except Exception:
            continue
        for name, value in items:
            try:
                if value is _os_module:
                    if replace_proxies:
                        ns[name] = _INERT_OS
                    else:
                        clean = False
                elif value is _sys_module:
                    if replace_proxies:
                        ns[name] = _INERT_SYS
                    else:
                        clean = False
                elif isinstance(value, types.ModuleType):
                    if id(value.__dict__) not in seen:
                        queue.append(value.__dict__)
                elif isinstance(value, types.FunctionType):
                    g = getattr(value, "__globals__", None)
                    if isinstance(g, dict) and id(g) not in seen:
                        queue.append(g)
                elif isinstance(value, type):
                    if id(vars(value)) not in seen:
                        queue.append(vars(value))
                    for base in value.__mro__[1:]:
                        if id(vars(base)) not in seen:
                            queue.append(vars(base))
            except Exception:
                # Un-inspectable member — keep walking; verification will
                # catch anything that actually holds os/sys.
                continue
    return clean


def _run_canary(module_name):
    """Execute the module's canary with the scrubbed module; True if it works.

    The canary strings are OUR OWN trusted code — they test the MODULE's
    post-scrub functionality, not sandbox enforcement, so they run with the
    real builtins. User code never sees this namespace.
    """
    canary = _CANARIES.get(module_name)
    if canary is None:
        return True
    mod = sys.modules.get(module_name)
    if mod is None:
        return False
    ns = {"__builtins__": builtins.__dict__, module_name: mod}
    try:
        exec(compile(canary, "<vorv-canary>", "exec"), ns)
        return True
    except Exception:
        return False


# ---- Phase 1: scrub the entire closure reachable from the whitelist ----
_scrub_roots = []
for _m in sorted(_ALL_MODULES):
    _mod = sys.modules.get(_m)
    if _mod is not None:
        _scrub_roots.append(_mod.__dict__)
_walk_namespaces(_scrub_roots, replace_proxies=True)

# ---- Phase 2: verify + canary each module; drop the ones that fail ----
_active = set()
for _m in sorted(_ALL_MODULES):
    _mod = sys.modules.get(_m)
    if _mod is None:
        continue
    clean = _walk_namespaces([_mod.__dict__], replace_proxies=False)
    if not clean:
        continue  # unreachable leak survived scrubbing — refuse the module
    if not _run_canary(_m):
        continue  # scrubbing broke the module's real functionality — refuse
    _active.add(_m)

_WHITELIST = frozenset(_active)

# Frozen snapshot of the modules user code may receive from __import__
# (kept as a plain dict so the safe importer needs no sys access).
_LOADED_MODULES = {name: sys.modules[name] for name in _WHITELIST
                   if name in sys.modules}

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
# Layer 2: restricted builtins, with every exposed callable defined in a
# CLEAN namespace (its __globals__ holds only safe values — walking
# fn.__globals__ from user code yields nothing).
# --------------------------------------------------------------------------
_UNSAFE_BUILTINS = frozenset({
    "open", "eval", "exec", "compile", "__import__", "input", "breakpoint",
    "exit", "quit", "help", "globals", "locals", "vars", "delattr", "super",
})

# Minimal C builtins the guarded functions themselves need. C builtins
# carry no __globals__ and no reachable namespace.
_CORE_BUILTINS = {
    "getattr": builtins.getattr,
    "setattr": builtins.setattr,
    "hasattr": builtins.hasattr,
    "isinstance": builtins.isinstance,
    "str": builtins.str,
    "ImportError": builtins.ImportError,
    "TimeoutError": builtins.TimeoutError,
    "__build_class__": builtins.__build_class__,
    "__name__": "__vorv_sandbox__",
    "sorted": builtins.sorted,
}

# --- guarded getattr/setattr/hasattr, defined in a clean namespace ---
_GUARD_SRC = """
def _guarded_getattr(obj, name, *default):
    if isinstance(name, str) and (name.startswith("__") and name.endswith("__")):
        raise PermissionError(
            "Vorv sandbox: dunder attribute access ('%s') is blocked" % name)
    if default:
        return getattr(obj, name, default[0])
    return getattr(obj, name)


def _guarded_setattr(obj, name, value):
    if isinstance(name, str) and (name.startswith("__") and name.endswith("__")):
        raise PermissionError(
            "Vorv sandbox: dunder attribute write ('%s') is blocked" % name)
    return setattr(obj, name, value)


def _guarded_hasattr(obj, name):
    if isinstance(name, str) and (name.startswith("__") and name.endswith("__")):
        return False
    return hasattr(obj, name)
"""
_GUARD_NS = {"__builtins__": dict(_CORE_BUILTINS),
             "PermissionError": builtins.PermissionError}
exec(compile(_GUARD_SRC, "<vorv-guard>", "exec"), _GUARD_NS)

# --- safe __import__, defined in a clean namespace (no sys access) ---
_IMPORT_SRC = """
def _safe_import(name, globals=None, locals=None, fromlist=(), level=0):
    root = name.split(".")[0]
    if root not in _WHITELIST:
        raise ImportError(
            "Vorv sandbox: import of '%s' is not allowed. Allowed: %s"
            % (name, ", ".join(sorted(_WHITELIST))))
    mod = _LOADED.get(name)
    if mod is not None:
        return mod
    if "." in name:
        parent_name, _, child = name.rpartition(".")
        parent = _LOADED.get(parent_name)
        if parent is not None:
            child_mod = getattr(parent, child, None)
            if child_mod is not None:
                return child_mod
    raise ImportError(
        "Vorv sandbox: module '%s' is not currently loaded and dynamic "
        "loading is disabled" % name)
"""
_IMPORT_NS = {"__builtins__": dict(_CORE_BUILTINS),
              "_WHITELIST": _WHITELIST,
              "_LOADED": _LOADED_MODULES}
exec(compile(_IMPORT_SRC, "<vorv-import>", "exec"), _IMPORT_NS)

_safe_builtins = {
    k: v for k, v in builtins.__dict__.items()
    if k not in _UNSAFE_BUILTINS and not k.startswith("__")
}
# Dunder names are stripped above; keep the essential constants.
_safe_builtins["True"] = True
_safe_builtins["False"] = False
_safe_builtins["None"] = None
_safe_builtins["__import__"] = _IMPORT_NS["_safe_import"]
_safe_builtins["__build_class__"] = builtins.__build_class__  # class statements work
_safe_builtins["__name__"] = "__vorv_sandbox__"
_safe_builtins["getattr"] = _GUARD_NS["_guarded_getattr"]
_safe_builtins["setattr"] = _GUARD_NS["_guarded_setattr"]
_safe_builtins["hasattr"] = _GUARD_NS["_guarded_hasattr"]

# --------------------------------------------------------------------------
# Layer 3 (enforcement side): meta-path finder, defined in a clean namespace
# --------------------------------------------------------------------------
_FINDER_SRC = """
class _WhitelistFinder:
    def find_spec(self, fullname, path=None, target=None):
        root = fullname.split(".")[0]
        if root not in _WHITELIST:
            raise ImportError(
                "Vorv sandbox: import of '%s' is not allowed. Allowed: %s"
                % (fullname, ", ".join(sorted(_WHITELIST))))
        return None
"""
_FINDER_NS = {"__builtins__": dict(_CORE_BUILTINS),
              "_WHITELIST": _WHITELIST}
exec(compile(_FINDER_SRC, "<vorv-finder>", "exec"), _FINDER_NS)
sys.meta_path.insert(0, _FINDER_NS["_WhitelistFinder"]())

# --------------------------------------------------------------------------
# Layer 4: trace watchdog — a closure defined in a clean namespace (no
# module-level class whose methods would expose the runner's globals).
# --------------------------------------------------------------------------
_TRACER_SRC = """
def _make_tracer(_monotonic, _deadline):
    def _tracer(frame, event, arg):
        if _monotonic() > _deadline:
            raise TimeoutError("Execution exceeded its time budget")
        return _tracer
    return _tracer
"""
_TRACER_NS = {"__builtins__": dict(_CORE_BUILTINS)}
exec(compile(_TRACER_SRC, "<vorv-tracer>", "exec"), _TRACER_NS)

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
        # so this very call works).
        code_obj = compile(code, "<vorv-sandbox>", "exec")
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
        tracer = _TRACER_NS["_make_tracer"](time.monotonic, deadline)
        try:
            sys.settrace(tracer)
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


# --------------------------------------------------------------------------
# Final re-verification: run every canary through the REAL sandbox path.
#
# The early canary (real builtins) proves the module survived scrubbing; this
# late pass proves it also works under the sandbox's own import gate — e.g.
# a pure-Python datetime fallback that lazily imports 'time' works with real
# builtins but cannot be offered to user code. Modules failing here are
# pruned and the gates are rebuilt with the final whitelist.
# --------------------------------------------------------------------------
def _prune_to_final_whitelist():
    global _WHITELIST
    bad = set()
    for name in sorted(_WHITELIST):
        canary = _CANARIES.get(name)
        if canary is None:
            continue
        # Inside the sandbox the module is not pre-bound — import it first,
        # exactly like user code would.
        code = "import %s\n%s" % (name, canary)
        try:
            r = run_code(code, 3_000)
            if not r.get("ok", False):
                bad.add(name)
        except Exception:
            bad.add(name)
    if not bad:
        return
    _WHITELIST = frozenset(_WHITELIST - bad)
    for name in bad:
        _LOADED_MODULES.pop(name, None)

    # Rebuild every gate that captured the whitelist. The finder instance
    # registered in sys.meta_path is replaced wholesale.
    import_ns = {"__builtins__": dict(_CORE_BUILTINS),
                 "_WHITELIST": _WHITELIST,
                 "_LOADED": _LOADED_MODULES}
    exec(compile(_IMPORT_SRC, "<vorv-import>", "exec"), import_ns)
    finder_ns = {"__builtins__": dict(_CORE_BUILTINS),
                 "_WHITELIST": _WHITELIST}
    exec(compile(_FINDER_SRC, "<vorv-finder>", "exec"), finder_ns)
    try:
        sys.meta_path[:] = [m for m in sys.meta_path
                            if not isinstance(m, _FINDER_NS_TYPE)]
    except Exception:
        pass
    sys.meta_path.insert(0, finder_ns["_WhitelistFinder"]())
    _safe_builtins["__import__"] = import_ns["_safe_import"]
    return


# The finder's type from the first exec — used to strip the old instance
# from sys.meta_path during the rebuild above. Resolved after first build.
_FINDER_NS_TYPE = type(_FINDER_NS["_WhitelistFinder"]())

_prune_to_final_whitelist()
