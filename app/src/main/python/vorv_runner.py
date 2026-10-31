"""Vorv's Python runner. Called by PythonExecTool to capture stdout/stderr."""
import sys
import io


def run_code(code, timeout_ms=10000):
    """Execute user Python code with captured stdout/stderr.

    Returns a dict: {"ok": bool, "stdout": str, "stderr": str, "error": str|None}
    Timeout is best-effort via signal on Unix-like systems.
    """
    import signal

    result = {"ok": True, "stdout": "", "stderr": "", "error": None}
    buf_out = io.StringIO()
    buf_err = io.StringIO()
    old_out, old_err = sys.stdout, sys.stderr
    sys.stdout, sys.stderr = buf_out, buf_err

    def _handler(signum, frame):
        raise TimeoutError(f"Execution exceeded {timeout_ms}ms")

    old_handler = None
    try:
        old_handler = signal.signal(signal.SIGALRM, _handler)
        signal.setitimer(signal.ITIMER_REAL, timeout_ms / 1000.0)
    except (ValueError, OSError, AttributeError):
        pass

    try:
        user_globals = {"__builtins__": __builtins__}
        exec(code, user_globals)
    except TimeoutError as e:
        result["ok"] = False
        result["error"] = str(e)
    except Exception as e:
        result["ok"] = False
        result["error"] = f"{type(e).__name__}: {e}"
    finally:
        try:
            if old_handler is not None:
                signal.setitimer(signal.ITIMER_REAL, 0)
                signal.signal(signal.SIGALRM, old_handler)
        except (ValueError, OSError, AttributeError):
            pass
        sys.stdout, sys.stderr = old_out, old_err
        result["stdout"] = buf_out.getvalue()
        result["stderr"] = buf_err.getvalue()
    return result
