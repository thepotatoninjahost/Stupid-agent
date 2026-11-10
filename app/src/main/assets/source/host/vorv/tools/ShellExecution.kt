package host.vorv.tools

/** Termux owns the process: enforcement must survive cancellation or death of Vorv. */
internal object ShellExecution {
    fun wrapper(command: String, basePath: String, timeoutMs: Int, expiresAtMs: Long): String {
        val args = listOf(command, basePath, timeoutMs.toString(), expiresAtMs.toString())
            .joinToString(" ") { ShellQuote.sh(it) }
        return "if command -v python >/dev/null 2>&1; then python -c ${ShellQuote.sh(supervisor)} $args; " +
            "else printf '%s' 'Shell execution requires Python in Termux: pkg install python' > " +
            "${ShellQuote.sh("$basePath.err")}; echo 127 > ${ShellQuote.sh("$basePath.code")}; fi"
    }

    // stdout/stderr are pipes, so the producer never opens an unbounded capture file.
    // A new session lets the supervisor kill the shell and its process group, including
    // background jobs, before publishing completion. Cancellation is a durable file so
    // it also covers a service launch that has not started running yet.
    internal val supervisor = """
import ctypes, os, selectors, signal, subprocess, sys, time
command, base, budget, expires = sys.argv[1:]
remaining = min(int(budget) / 1000, int(expires) / 1000 - time.time())
deadline = time.monotonic() + remaining
code = 125
proc = None
sel = selectors.DefaultSelector()
try:
    with open(base + '.out', 'wb') as out, open(base + '.err', 'wb') as err:
        if remaining <= 0 or os.path.exists(base + '.cancel'):
            code = 124
        else:
            # Linux subreaper adoption also covers descendants that call setsid().
            # Fail before launch if this kernel cannot support tree cleanup.
            libc = ctypes.CDLL(None, use_errno=True)
            if libc.prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER
                raise OSError(ctypes.get_errno(), 'Cannot supervise shell descendants')
            children_path = '/proc/self/task/' + str(os.getpid()) + '/children'
            with open(children_path) as children:
                children.read()
            proc = subprocess.Popen(['bash', '-c', command], stdin=subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    start_new_session=True)
            sel.register(proc.stdout, selectors.EVENT_READ, [out, 400000])
            sel.register(proc.stderr, selectors.EVENT_READ, [err, 80000])
            while sel.get_map():
                if time.monotonic() >= deadline or os.path.exists(base + '.cancel'):
                    code = 124
                    break
                exceeded = False
                for key, _ in sel.select(min(0.05, max(0, deadline - time.monotonic()))):
                    data = os.read(key.fd, 8192)
                    if not data:
                        sel.unregister(key.fileobj)
                        continue
                    target, left = key.data
                    target.write(data[:left])
                    key.data[1] -= min(left, len(data))
                    if len(data) > left:
                        exceeded = True
                if exceeded:
                    code = 125
                    break
            else:
                # A command may close both streams and keep running.
                while proc.poll() is None and time.monotonic() < deadline and not os.path.exists(base + '.cancel'):
                    time.sleep(0.05)
                code = proc.returncode if proc.poll() is not None else 124
finally:
    if proc is not None:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        proc.wait()
        # Killing/reaping parents reparents detached descendants to this subreaper.
        while True:
            with open(children_path) as children:
                pids = children.read().split()
            if not pids:
                break
            for pid in pids:
                try:
                    os.kill(int(pid), signal.SIGKILL)
                except ProcessLookupError:
                    pass
            while True:
                try:
                    pid, _ = os.waitpid(-1, os.WNOHANG)
                    if pid == 0:
                        break
                except ChildProcessError:
                    break
            time.sleep(0.01)
    sel.close()
    # Only this marker permits Vorv to delete capture files: writers have stopped.
    with open(base + '.code', 'w') as result:
        result.write(str(code))
""".trimIndent()
}
