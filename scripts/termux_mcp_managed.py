"""MiAIPlus managed shell adapter, AGPL-3.0-or-later.

The upstream package remains pinned and unmodified. Keep its policy gate and
output store, but execute the original command through Bash without lint rewrites.
"""
import copy
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import threading
import time
import uuid

INLINE = 12000
MAX_OUTPUT = 4 * 1024 * 1024
MAX_JOBS = 16
MAX_RUNNING = 4
JOBS = {}
LOCK = threading.RLock()
SLOTS = threading.BoundedSemaphore(MAX_RUNNING)


def failure(code, message, **extra):
    return dict(ok=False, errors=[dict(code=code, msg=message)], **extra)


def environment(cwd=None):
    return dict(home_dir=os.environ['HOME'], tmp_dir=os.environ['TMPDIR'],
                cwd=str(Path(cwd or os.environ['HOME']).resolve()), shell='bash')


def stop_group(process):
    # The shell may have exited while descendants still own the output file.
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            process.wait(timeout=2)
            return
        except PermissionError:
            # Darwin reports EPERM for an empty group after its leader exits.
            if process.poll() is not None:
                return
            raise
        if sig == signal.SIGTERM:
            time.sleep(0.1)
    process.wait(timeout=2)


def output_body(text):
    from termux_mcp.smart import base
    if len(text) <= INLINE:
        return dict(out=text)
    ref = base.store_output(text)
    if not ref:
        return failure('E_OUTPUT_STORE', 'Could not store full output', out=text[:INLINE], truncated=True)
    return dict(out=text[:6000] + '\n[read full output with out_read]\n' + text[-6000:],
                out_ref=ref, truncated=True)


class Job:
    def __init__(self, cmd, cwd, timeout, env, principal):
        self.id = uuid.uuid4().hex
        self.cmd, self.cwd, self.timeout = cmd, cwd, timeout
        self.env, self.principal = env, principal
        self.cancel = threading.Event()
        self.done = threading.Event()
        self.result = None
        self.process = None
        self.stopped = False
        self.output_limit = threading.Event()
        self.log = tempfile.TemporaryFile()
        self.started = time.monotonic()
        self.finished = None

    def stop_process(self):
        if self.process is not None and not self.stopped:
            stop_group(self.process)
            self.stopped = True

    def read_output(self):
        retained = 0
        try:
            with self.process.stdout as pipe:
                while True:
                    chunk = os.read(pipe.fileno(), 65536)
                    if not chunk:
                        break
                    available = MAX_OUTPUT - retained
                    self.log.write(chunk[:available])
                    self.log.flush()
                    retained += min(len(chunk), available)
                    if len(chunk) > available:
                        self.output_limit.set()
                        break
        except OSError:
            self.output_limit.set()

    def execute(self):
        from termux_mcp import kernel
        code = None
        try:
            # Admission reserved a slot; no unbounded executor queue.
            with kernel.acting_as(self.principal):
                decision = kernel.gate(self.cmd, tool='run_digest', cwd=self.cwd)
                if not decision['allow']:
                    self.result = failure(decision['code'], decision['message'], cmd=self.cmd,
                                          executed=False, **environment(self.cwd))
                    return
                if self.cancel.is_set():
                    self.result = failure('E_CANCELLED', 'Cancelled before execution', cmd=self.cmd, executed=False)
                    return
                kernel.prepare(self.cmd, echo=False, record=False)
                self.process = subprocess.Popen(
                    [self.env['SHELL'], '--noprofile', '--norc', '-c', self.cmd],
                    cwd=self.cwd, env=self.env, stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
                reader = threading.Thread(target=self.read_output, daemon=True)
                reader.start()
                while self.process.poll() is None:
                    if self.cancel.is_set():
                        code = 'E_CANCELLED'
                    elif time.monotonic() - self.started >= self.timeout:
                        code = 'E_TIMEOUT'
                    elif self.output_limit.is_set():
                        code = 'E_OUTPUT_LIMIT'
                    if code:
                        self.stop_process()
                        break
                    self.cancel.wait(0.05)
                self.stop_process()
                reader.join(timeout=2)
                raw = os.pread(self.log.fileno(), MAX_OUTPUT, 0).decode('utf-8', 'replace')
                if self.output_limit.is_set():
                    code = code or 'E_OUTPUT_LIMIT'
                exit_code = 124 if code == 'E_TIMEOUT' else self.process.returncode
                self.result = dict(ok=code is None and exit_code == 0, exit=exit_code, cmd=self.cmd,
                                   elapsed_ms=round((time.monotonic() - self.started) * 1000),
                                   **environment(self.cwd))
                self.result.update(output_body(raw))
                if code or exit_code:
                    self.result['errors'] = [dict(code=code or 'E_EXIT', msg=(
                        'Command exceeded its time limit' if code == 'E_TIMEOUT' else
                        'Command cancelled' if code == 'E_CANCELLED' else
                        'Output exceeded 4 MiB; process stopped' if code == 'E_OUTPUT_LIMIT' else
                        f'Command exited with status {exit_code}'))]
        except Exception as error:
            self.result = failure('E_EXEC', str(error), cmd=self.cmd, **environment(self.cwd))
        finally:
            try:
                if self.process is not None:
                    self.stop_process()
            finally:
                self.finished = time.monotonic()
                self.done.set()
                SLOTS.release()

    def snapshot(self):
        with LOCK:
            if self.done.is_set():
                return dict(self.result, job_id=self.id, running=False)
            raw = os.pread(self.log.fileno(), INLINE, 0).decode('utf-8', 'replace')
            return dict(ok=True, job_id=self.id, running=True, cmd=self.cmd, out=raw,
                        **environment(self.cwd))


def prune_jobs(evict=False):
    for key, job in list(JOBS.items()):
        if job.finished and time.monotonic() - job.finished > 1800:
            job.log.close()
            del JOBS[key]
    if evict and len(JOBS) >= MAX_JOBS:
        completed = [job for job in JOBS.values() if job.done.is_set()]
        if completed:
            oldest = min(completed, key=lambda job: job.finished)
            oldest.log.close()
            del JOBS[oldest.id]


def run(params):
    from termux_mcp import kernel
    cmd = params.get('cmd')
    if not isinstance(cmd, str) or not cmd.strip() or '\0' in cmd:
        return failure('E_ARGS', 'cmd must be a nonempty string without NUL', executed=False)
    background = params.get('background', False)
    timeout = params.get('timeout_ms', 120000 if background else 10000)
    maximum = 120000 if background else 40000
    if not isinstance(background, bool) or type(timeout) is not int or not 100 <= timeout <= maximum:
        return failure('E_ARGS', f'timeout_ms must be 100..{maximum}; use background=true for longer jobs',
                       cmd=cmd, executed=False)
    cwd = params.get('cwd') or os.environ['HOME']
    if not isinstance(cwd, str) or not os.path.isabs(cwd) or not os.path.isdir(cwd):
        return failure('E_CWD', 'cwd must be an existing absolute directory', cmd=cmd, executed=False)
    decision = kernel.gate(cmd, tool='run_digest', cwd=cwd)
    if not decision['allow']:
        return failure(decision['code'], decision['message'], cmd=cmd, executed=False,
                       needs_confirmation=decision['status'] == 'confirm')
    env = dict(os.environ)
    env.pop('BASH_ENV', None)
    env.pop('ENV', None)
    env.update(SHELL=shutil.which('bash') or '/bin/bash', PAGER='cat', DEBIAN_FRONTEND='noninteractive')
    # Parse without executing any prefix; syntax failure has a precise no-execution guarantee.
    parsed = subprocess.run([env['SHELL'], '--noprofile', '--norc', '-n'], input=cmd,
                            capture_output=True, text=True, env=env, timeout=5)
    if parsed.returncode:
        return failure('E_SYNTAX', parsed.stderr, cmd=cmd, exit=parsed.returncode,
                       executed=False, **environment(cwd))
    with LOCK:
        prune_jobs(evict=True)
        if len(JOBS) >= MAX_JOBS or not SLOTS.acquire(blocking=False):
            return failure('E_BUSY', 'Four commands are already running; wait for their results', cmd=cmd, executed=False)
        try:
            job = Job(cmd, cwd, timeout / 1000, env, kernel.current_principal())
            JOBS[job.id] = job
            threading.Thread(target=job.execute, name='managed-shell-' + job.id[:8], daemon=True).start()
        except Exception:
            SLOTS.release()
            raise
    if background:
        return dict(ok=True, job_id=job.id, running=True, cmd=cmd, **environment(cwd))
    job.done.wait(timeout / 1000 + 8)
    return job.snapshot()


def job_action(params, cancel=False):
    with LOCK:
        prune_jobs()
        job_id = params.get('job_id')
        if not isinstance(job_id, str):
            return failure('E_ARGS', 'job_id must be a string')
        job = JOBS.get(job_id)
        from termux_mcp import kernel
        principal = kernel.current_principal()
        if job is None or getattr(principal, 'id', None) != getattr(job.principal, 'id', None):
            return failure('E_JOB_NOT_FOUND', 'Unknown or expired job_id; jobs do not survive service restart')
        if cancel:
            job.cancel.set()
        return job.snapshot()


def install():
    from termux_mcp import mcp_core as core
    from termux_mcp.smart import base
    names = ('run', 'run_digest', 'out_read', 'job_status', 'job_cancel')
    definitions = []
    for name in ('run', 'run_digest'):
        definitions.append(dict(name=name, description=(
            'Execute the exact cmd with Bash as the Termux app. stdout/stderr merge in write order. '
            'HOME, TMPDIR and cwd are returned. timeout_ms: 100..40000 synchronously; '
            'background=true: up to 120000 ms, returns job_id; use job_status/job_cancel. '
            'No terminal input. Policy refusals still apply; never bypass confirmation.'), inputSchema=dict(
                type='object', properties=dict(cmd=dict(type='string'), cwd=dict(type='string'),
                    timeout_ms=dict(type='integer', minimum=100, maximum=120000), background=dict(type='boolean')),
                required=['cmd'], additionalProperties=False)))
    old_list, old_invoke = core.tool_list, core.invoke_tool
    out_read = next(d for d in old_list()['tools'] if d['name'] == 'out_read')
    definitions.append(out_read)
    for name in ('job_status', 'job_cancel'):
        definitions.append(dict(name=name, description=(
            'Read current output and completion status of a background job. Poll after a useful interval; '
            'completed large output has out_ref for out_read. Jobs expire after 30 minutes or service restart.'
            if name == 'job_status' else
            'Cancel this managed job and its process group. Poll job_status until running=false; side effects remain.'),
            inputSchema=dict(type='object', properties=dict(job_id=dict(type='string')), required=['job_id'],
                             additionalProperties=False)))

    def invoke(session, name, params, on_progress=None):
        if name not in names or name == 'out_read':
            return old_invoke(session, name, params, on_progress)
        if not isinstance(params, dict):
            return base.result(failure('E_ARGS', 'arguments must be an object'))
        from termux_mcp import kernel
        refusal = kernel.authorize_tool(name, params)
        if refusal:
            return base.result(failure('E_POLICY', refusal))
        allowed = set(next(d for d in definitions if d['name'] == name)['inputSchema']['properties'])
        if set(params) - allowed:
            return base.result(failure('E_ARGS', 'Unknown parameters; confirmed is not accepted', executed=False))
        try:
            body = run(params) if name in ('run', 'run_digest') else job_action(params, name == 'job_cancel')
            return base.result(body)
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            return base.result(failure('E_ARGS', str(error), executed=False))

    core.tool_list = lambda: dict(tools=copy.deepcopy(definitions))
    core.invoke_tool = invoke
    def check_tool_allowed(name):
        if name not in names:
            raise core.RpcError(core.INVALID_PARAMS, 'Unknown managed tool')

    core.check_tool_allowed = check_tool_allowed
