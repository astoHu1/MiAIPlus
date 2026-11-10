#!/usr/bin/env python3
"""Behavior regressions against the exact APK's upstream source plus managed adapter."""
import importlib
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = tempfile.TemporaryDirectory(prefix='miaiplus-managed-tests-')
folder = Path(FIXTURE.name).resolve()
with tarfile.open(ROOT / 'app/src/main/assets/termux-mcp/upstream.bin') as archive:
    archive.extractall(folder / 'source', filter='data')
for name in ('home', 'tmp', 'config'):
    (folder / name).mkdir()
os.environ.update(HOME=str(folder / 'home'), TMPDIR=str(folder / 'tmp'),
                  TERMUX_MCP_CONFIG_DIR=str(folder / 'config'), TERMUX_MCP_LOG_EVENTS='0',
                  TERMUX_NATIVE_MCP_TOOLS='run,run_digest,out_read')
sys.path.insert(0, str(folder / 'source'))
managed = importlib.import_module('miaiplus_managed')
managed.install()
from termux_mcp import mcp_core as core


class ManagedShellTest(unittest.TestCase):
    def run_cmd(self, cmd, **kwargs):
        return managed.run(dict(cmd=cmd, **kwargs))

    def await_job(self, job_id):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            body = managed.job_action(dict(job_id=job_id))
            if not body.get('running'):
                return body
            time.sleep(0.05)
        self.fail('job did not finish')

    def test_command_is_preserved_and_shell_expansions_are_correct(self):
        cmd = "  printf 'literal=$HOME\\n'; printf 'sum=%s sub=%s tick=%s\\n' \"$((3+4))\" \"$(printf yes)\" \"`printf tick`\"  "
        body = self.run_cmd(cmd)
        self.assertTrue(body['ok'], body)
        self.assertEqual(cmd, body['cmd'])
        self.assertEqual('literal=$HOME\nsum=7 sub=yes tick=tick\n', body['out'])
        body = self.run_cmd("printf 'line%d\\n' $(seq 1 2000) | wc -l")
        self.assertEqual('2000', body['out'].strip())

    def test_real_home_tmpdir_and_bash(self):
        body = self.run_cmd('[[ -d "$HOME" && -d "$TMPDIR" ]] && arr=(甲 乙); printf "%s\\n" "$HOME" "$TMPDIR" "${arr[1]}"')
        self.assertTrue(body['ok'], body)
        self.assertEqual([body['home_dir'], body['tmp_dir'], '乙'], body['out'].splitlines())

    def test_cd_quoting_and_environment_are_per_call(self):
        target = folder / 'home' / 'directory with spaces'
        target.mkdir(exist_ok=True)
        body = self.run_cmd('cd "$HOME/directory with spaces" && pwd')
        self.assertEqual(str(target), body['out'].strip())
        self.assertEqual(str(folder / 'home'), self.run_cmd('pwd')['out'].strip())

    def test_redirections_and_stream_order(self):
        body = self.run_cmd('ls /miaiplus_missing 2>/dev/null; echo rc=$?; printf a; printf b >&2; printf c')
        self.assertRegex(body['out'], r'^rc=[12]\nabc$')
        self.assertNotIn('summary', body)
        self.assertNotIn('facts', body)

    def test_syntax_error_executes_nothing(self):
        target = folder / 'syntax-marker'
        body = self.run_cmd(f'touch {target}; if then')
        self.assertFalse(body['ok'])
        self.assertEqual('E_SYNTAX', body['errors'][0]['code'])
        self.assertFalse(body['executed'])
        self.assertFalse(target.exists())

    def test_timeout_and_nonzero_exit(self):
        body = self.run_cmd('printf before; sleep 2; printf after', timeout_ms=150)
        self.assertFalse(body['ok'])
        self.assertEqual(124, body['exit'])
        self.assertEqual('before', body['out'])
        self.assertEqual('E_TIMEOUT', body['errors'][0]['code'])
        body = self.run_cmd('printf error >&2; exit 7')
        self.assertEqual(7, body['exit'])
        self.assertEqual('error', body['out'])

    def test_background_status_cancel_and_descendant_cleanup(self):
        target = folder / 'cancel-marker'
        body = self.run_cmd(f'sleep 2; touch {target}', background=True, timeout_ms=5000)
        job_id = body['job_id']
        self.assertTrue(body['running'])
        managed.job_action(dict(job_id=job_id), cancel=True)
        final = self.await_job(job_id)
        self.assertFalse(final['ok'])
        self.assertEqual('E_CANCELLED', final['errors'][0]['code'])
        time.sleep(2.1)
        self.assertFalse(target.exists())
        body = self.run_cmd('sleep 0.2; printf finished', background=True)
        self.assertEqual('finished', self.await_job(body['job_id'])['out'])

    def test_output_reference_is_exact(self):
        body = self.run_cmd('seq 1 8000')
        self.assertTrue(body['ok'])
        from termux_mcp.smart import base
        self.assertEqual(''.join(str(i) + '\n' for i in range(1, 8001)), base.load_output(body['out_ref']))
        self.assertNotIn('summary', body)

    def test_policy_and_argument_limits_are_not_bypassed(self):
        body = self.run_cmd('apt-get remove busybox', background=True)
        self.assertFalse(body['ok'])
        self.assertEqual('E_CONFIRM', body['errors'][0]['code'])
        self.assertFalse(self.run_cmd('echo no', timeout_ms=120000)['ok'])
        session = core.create_session('http')
        body = core.invoke_tool(session, 'run_digest', dict(cmd='echo no', confirmed=True))['digest']
        self.assertFalse(body['ok'])
        self.assertFalse(body['executed'])

    def test_running_job_limit_and_owner(self):
        jobs = [self.run_cmd('sleep 3', background=True)['job_id'] for _ in range(4)]
        try:
            self.assertEqual('E_BUSY', self.run_cmd('echo no')['errors'][0]['code'])
            from termux_mcp import auth, kernel
            with kernel.acting_as(auth.Principal('other-owner')):
                self.assertEqual('E_JOB_NOT_FOUND', managed.job_action(dict(job_id=jobs[0]))['errors'][0]['code'])
        finally:
            for job_id in jobs:
                managed.job_action(dict(job_id=job_id), cancel=True)
            for job_id in jobs:
                self.await_job(job_id)

    def test_noninteractive_stdin_and_output_limit(self):
        self.assertEqual('', self.run_cmd('cat')['out'])
        body = self.run_cmd('yes output')
        self.assertFalse(body['ok'])
        self.assertEqual('E_OUTPUT_LIMIT', body['errors'][0]['code'])

    def test_tool_list_exposes_jobs_and_timeout(self):
        tools = {tool['name']: tool for tool in core.tool_list()['tools']}
        self.assertEqual({'run', 'run_digest', 'out_read', 'job_status', 'job_cancel'}, set(tools))
        props = tools['run_digest']['inputSchema']['properties']
        self.assertIn('timeout_ms', props)
        self.assertNotIn('confirmed', props)


if __name__ == '__main__':
    try:
        unittest.main(verbosity=2)
    finally:
        FIXTURE.cleanup()
