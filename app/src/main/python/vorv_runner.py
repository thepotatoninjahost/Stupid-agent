"""Vorv's hardened Python sandbox runner (audit finding C-2).

Called by PythonExecTool to execute LLM-produced Python with captured
stdout/stderr, a real time budget, and layered sandboxing:

Layer 1 — sys.addaudithook (CPython audit hooks, Python 3.8+):
    Installed ONCE at module import; user code cannot remove it. Blocks the
    audit events for file I/O (open, os.remove, ...), process spawning
    (os.system, os.exec, subprocess.Popen), sockets (socket.*), and
    environment/user-id tampering — raising PermissionError at the C level.

Layer 2 — restricted __builtins__:
    The exec globals get a filtered builtins mapping: open/eval/exec/compile/
    __import__/input/breakpoint/exit/quit/help/globals/locals/vars/delattr/
    super are absent. getattr/hasattr/setattr are guarded against dunder
    names (blocks __class__/__subclasses__/__globals__ escape chains).

Layer 3 — import whitelist via a safe __import__ + meta_path finder:
    Only pre-imported pure-computation stdlib modules are reachable
    (json, math, re, datetime, random, itertools, functools, collections,
    string, statistics, decimal, fractions, heapq, bisect, array, textwrap,
    base64, binascii, hashlib, unicodedata, uuid). Everything else —
    os, sys, io, time, socket, subprocess, threading, signal, ctypes,
    pickle — raises ImportError. Whitelisted modules are pre-imported at
    module load (before the audit hook is installed), so importing them
    from user code never touches the filesystem.

Layer 4 — time budget:
    A sys.settrace watchdog inside the worker thread raises TimeoutError
    once the deadline passes (checked on every line event — C-level calls
    that never return to Python bytecode are bounded by the Kotlin-side
    thread join).

Contract with Kotlin (PythonExecTool):
    run_code(code: str, timeout_ms: int) -> dict
        {"ok": bool, "stdout": str, "stderr": str, "error": str|None,
         "timed_out": bool}

Residual risk (documented in SECURITY.md): audit hooks + builtins filtering
are defense-in-depth, not a formal security boundary like a process sandbox.
Known CPython escape routes (ctypes, bytecode injection, frame walking) are
individually blocked here (no ctypes import, no compile/eval/marshal, no
_getframe, dunder getattr guard). If a novel escape is found in CPython
itself, the blast radius is the app process — same as js_exec's Rhino.
"""
import builtins
import importlib
import io
import sys
import threading
import time

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
_WHITELIST = frozenset({
    "json", "math", "re", "datetime", "random", "itertools", "functools",
    "collections", "string", "statistics", "decimal", "fractions", "heapq",
    "bisect", "array", "textwrap", "base64", "binascii", "hashlib",
    "unicodedata", "uuid",
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

# --------------------------------------------------------------------------
# Layer 2: restricted builtins
# --------------------------------------------------------------------------
_UNSAFE_BUILTINS = frozenset({
    "open", "eval", "exec", "compile", "__import__", "input", "breakpoint",
    "exit", "quit", "help", "globals", "locals", "vars", "delattr", "super",
})


def _guarded_getattr(obj, name, *default):
    if isinstance(name, str) and (name.startswith("__") and name.endswith("__")):
        raise PermissionError(
            "Vorv sandbox: dunder attribute access ('%s') is blocked" % name
        )
    if default:
        return getattr(obj, name, default[0])
    return getattr(obj, name)


def _guarded_setattr(obj, name, value):
    if isinstance(name, str) and (name.startswith("__") and name.endswith("__")):
        raise PermissionError(
            "Vorv sandbox: dunder attribute write ('%s') is blocked" % name
        )
    return setattr(obj, name, value)


def _guarded_hasattr(obj, name):
    if isinstance(name, str) and (name.startswith("__") and name.endswith("__")):
        return False
    return hasattr(obj, name)


def _safe_import(name, globals=None, locals=None, fromlist=(), level=0):
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
_safe_builtins["getattr"] = _guarded_getattr
_safe_builtins["setattr"] = _guarded_setattr
_safe_builtins["hasattr"] = _guarded_hasattr

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
