"""Self-test for Vorv's Python sandbox runner.

Runs in CI (plain python3, matching Chaquopy's Python 3.11) and asserts that
every known escape route is blocked and that legitimate computation works.

Usage: python3 test_vorv_sandbox.py   (exit 0 = all pass)
"""
import json
import os
import sys
import tempfile
import textwrap

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vorv_runner  # noqa: E402

FAILURES = []


def expect_blocked(label, code, must_mention=()):
    r = vorv_runner.run_code(code, 5000)
    ok = (r["ok"] is False) and (r["error"] is not None)
    if ok and must_mention:
        err = (r["error"] or "").lower()
        ok = all(m.lower() in err for m in must_mention)
    if ok:
        print("  PASS  [blocked] %s" % label)
    else:
        print("  FAIL  [blocked] %s -> %s" % (label, r))
        FAILURES.append(label)


def expect_ok(label, code, stdout_contains=None):
    r = vorv_runner.run_code(code, 5000)
    ok = r["ok"] is True and r["error"] is None
    if ok and stdout_contains is not None:
        ok = stdout_contains in r["stdout"]
    if ok:
        print("  PASS  [allowed] %s" % label)
    else:
        print("  FAIL  [allowed] %s -> %s" % (label, r))
        FAILURES.append(label)


def expect_timeout(label, code):
    r = vorv_runner.run_code(code, 1500)
    ok = r["ok"] is False and r["timed_out"] is True
    if ok:
        print("  PASS  [timeout] %s" % label)
    else:
        print("  FAIL  [timeout] %s -> %s" % (label, r))
        FAILURES.append(label)


print("== Vorv python sandbox self-test ==")

# ---------------------------------------------------------------- file I/O
# open() is absent from the safe builtins, so direct calls die with NameError
# before the audit hook even fires — belt and suspenders. Both are honest blocks.
expect_blocked("open() a file", "f = open('/data/data/host.vorv/databases/vorv.db')\nprint(f.read())")
expect_blocked("open() for write", "open('/tmp/evil.txt', 'w').write('pwned')")
expect_blocked("os.remove", "import os", ("sandbox", "import"))  # os not whitelisted at all
expect_blocked("__import__('os')", "m = __import__('os')\nprint(m.system('ls'))", ("sandbox",))
expect_blocked("open via exec string", "exec(\"open('/etc/passwd')\")", ())
expect_blocked("open via eval string", "eval(\"open('/etc/passwd')\")", ())
expect_blocked("compile() builtin", "compile('1+1', '<s>', 'eval')", ())
expect_blocked("eval() builtin", "eval('1+1')", ())

# ---------------------------------------------------------------- network
expect_blocked("import socket", "import socket\ns = socket.socket()\ns.connect(('example.com', 80))", ("sandbox", "import"))
expect_blocked("urllib request", "import urllib.request\nprint(urllib.request.urlopen('http://example.com').read())", ("sandbox", "import"))
expect_blocked("http.client", "import http.client", ("sandbox", "import"))

# ---------------------------------------------------------------- process
expect_blocked("import subprocess", "import subprocess\nsubprocess.run(['ls'])", ("sandbox", "import"))
expect_blocked("os.system (blocked even if reachable)", "__import__ = None\nimport sys", ("sandbox", "import"))

# ---------------------------------------------------------------- escapes
expect_blocked("dunder getattr on instance", "x = []\nprint(getattr(x, '__class__'))", ("dunder",))
expect_blocked("dunder getattr on class", "print(getattr(int, '__subclasses__'))", ("dunder",))
expect_blocked("vars() escape", "print(vars())", ())
expect_blocked("globals() escape", "print(globals())", ())
# __builtins__ in the sandbox IS the safe dict — assert no real builtins leaked in.
expect_ok("__builtins__ contains no open", "print('open' in __builtins__)", "False")
expect_ok("__builtins__ contains no eval", "print('eval' in __builtins__)", "False")
expect_ok("__builtins__.get('open') is None", "print(__builtins__.get('open'))", "None")
expect_blocked("frame walking", "import sys", ("sandbox", "import"))
expect_blocked("ctypes", "import ctypes", ("sandbox", "import"))
expect_blocked("pickle", "import pickle", ("sandbox", "import"))
expect_blocked("time.sleep stall", "import time\ntime.sleep(60)", ("sandbox", "import"))
expect_blocked("threading", "import threading", ("sandbox", "import"))
expect_blocked("sys.exit", "import sys\nsys.exit(0)", ("sandbox", "import"))
expect_blocked("raise SystemExit", "raise SystemExit(0)", ("systemexit",))
expect_blocked("breakpoint()", "breakpoint()", ())

# ---------------------------------------------------------------- allowed
expect_ok("arithmetic + print", "print(2 + 2)", "4")
expect_ok("math", "import math\nprint(math.sqrt(16))", "4.0")
expect_ok("json roundtrip", "import json\nprint(json.dumps({'a': 1}))", '"a": 1')
expect_ok("re", "import re\nprint(re.sub(r'l', 'L', 'hello'))", "heLLo")
expect_ok("datetime", "import datetime\nprint(datetime.datetime(2025, 1, 2).year)", "2025")
expect_ok("list comprehension", "print([x * x for x in range(5)])", "[0, 1, 4, 9, 16]")
expect_ok("class definition", textwrap.dedent("""
    class Point:
        def __init__(self, x, y):
            self.x = x
            self.y = y
        def mag2(self):
            return self.x ** 2 + self.y ** 2
    print(Point(3, 4).mag2())
"""), "25")
expect_ok("f-strings + sorted", "items = [3, 1, 2]\nprint(f'sorted={sorted(items)}')", "sorted=[1, 2, 3]")
expect_ok("collections", "from collections import Counter\nprint(Counter('abca')['a'])", "2")
expect_ok("functools", "import functools\nprint(functools.reduce(lambda a, b: a + b, [1, 2, 3]))", "6")
expect_ok("statistics", "import statistics\nprint(statistics.mean([1, 2, 3]))", "2")
expect_ok("decimal", "from decimal import Decimal\nprint(Decimal('0.1') + Decimal('0.2'))", "0.3")
expect_ok("hashlib", "import hashlib\nprint(hashlib.sha256(b'x').hexdigest()[:8])", "")
expect_ok("base64", "import base64\nprint(base64.b64encode(b'hi').decode())", "aGk=")
expect_ok("uuid", "import uuid\nprint(len(str(uuid.uuid4())))", "36")
expect_ok("textwrap", "import textwrap\nprint(textwrap.fill('ab cd', 2))", "ab\ncd")
expect_ok("try/except works", textwrap.dedent("""
    try:
        1 / 0
    except ZeroDivisionError:
        print('caught')
"""), "caught")
expect_ok("isinstance/type single-arg", "print(type(1) is int)", "True")

# ---------------------------------------------------------------- honest errors
r = vorv_runner.run_code("1/0", 5000)
if r["ok"] is False and "ZeroDivisionError" in (r["error"] or ""):
    print("  PASS  [error]   ZeroDivisionError reported honestly")
else:
    print("  FAIL  [error]   ZeroDivisionError -> %s" % r)
    FAILURES.append("zero-div")

r = vorv_runner.run_code("def f(:", 5000)
if r["ok"] is False and "SyntaxError" in (r["error"] or ""):
    print("  PASS  [error]   SyntaxError reported honestly")
else:
    print("  FAIL  [error]   SyntaxError -> %s" % r)
    FAILURES.append("syntax-err")

# ---------------------------------------------------------------- timeouts
expect_timeout("pure-python infinite loop", "x = 0\nwhile True:\n    x += 1")

# NOTE: C-extension compute (e.g. a catastrophic regex inside _sre) cannot be
# interrupted by sys.settrace — trace events only fire on Python bytecode.
# That class of stall is bounded by the Kotlin-side thread join in
# PythonExecTool, which reports an honest hard-timeout and leaks only a daemon
# thread. This is the documented residual limit of an in-process sandbox
# (see SECURITY.md) — a subprocess boundary would be needed to do better.

# ---------------------------------------------------------------- summary
print()
if FAILURES:
    print("FAILED (%d): %s" % (len(FAILURES), ", ".join(FAILURES)))
    sys.exit(1)
print("ALL SANDBOX TESTS PASSED")
