#!/usr/bin/env python3
"""Behavior checks for offline diagnostic exports, using isolated synthetic metadata."""

import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile

import diagnose_log as diagnostic


class DiagnoseLogTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        source = self.root / diagnostic.SOURCE
        source.mkdir(parents=True)
        (source / 'Example.java').write_text('''package io.github.miaiplus;
class Example {
    String rule = "foreground";
    String phase = "rolled_back";
    String profile = "unsupported";
    String stage = "model";
    void execute() {
        throw new IllegalStateException("固定错误：动态信息");
    }
}
''', encoding='utf-8')
        self.index = diagnostic.SourceIndex(self.root)

    def archive(self, environment=None, records=None, raw=None, extra=None):
        path = self.root / 'diagnostic.zip'
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
            if environment is not None:
                archive.writestr('environment.json', json.dumps(environment))
            data = raw if raw is not None else '\n'.join(json.dumps(record) for record in (records or []))
            archive.writestr('phone-use.jsonl', data)
            for name, value in (extra or {}).items():
                archive.writestr(name, value)
        return path

    def report(self, path):
        environment, records, invalid = diagnostic.load_export(path)
        return diagnostic.render(environment, records, invalid, self.index)

    def test_empty_export_describes_limits_without_guessing_root_cause(self):
        output = self.report(self.archive({'detailedLogEnabled': False, 'hook': {'reported': False}}))
        self.assertIn('有效 0', output)
        self.assertIn('不代表复现期间的状态', output)
        self.assertIn('不能断言 Hook 未加载', output)
        self.assertIn('零计数', output)

    def test_routing_host_refusal_and_failure_are_correlated_without_messages(self):
        reference = diagnostic.message_reference('固定错误：动态信息')
        path = self.archive({'versionCode': 10, 'androidApi': 37, 'moduleDropped': 2,
                             'hook': {'status': {'phase': 'rolled_back', 'stage': 'model'}}}, [
            {'event': 'chat_route', 'source': 'host', 'time': 1000, 'ver': 10,
             'route': '123-1', 'rule': 'foreground', 'query': 'PRIVATE QUERY'},
            {'event': 'host_error', 'source': 'host', 'time': 1200, 'code': -1009,
             'route': '123-1', 'hostDropped': 3, 'hostDeliveryErrors': 1,
             'message': 'PRIVATE ERROR'},
            {'event': 'failure', 'source': 'module', 'time': 1300, 'messageRef': reference,
             'exception': 'IllegalStateException', 'site': 'Example.execute:8'},
        ])
        output = self.report(path)
        self.assertIn('route=123-1', output)
        self.assertIn('code=-1009', output)
        self.assertIn('不足以判定账号', output)
        self.assertIn('moduleDropped=2', output)
        self.assertIn('hostDropped=3', output)
        self.assertIn('phase=rolled_back', output)
        self.assertIn('Example.java:8', output)
        self.assertIn('构建版本', output)
        self.assertNotIn('PRIVATE', output)
        self.assertNotIn('动态信息', output)

    def test_unknown_text_fields_and_untrusted_metadata_values_are_not_echoed(self):
        secret = 'sk-never-show-this-value'
        path = self.archive({'hook': {'report': secret, 'status': {'stage': secret}},
                             'config': {'planner': secret}}, [
            {'event': 'failure', 'exception': secret, 'site': secret, 'binding': secret,
             'stage': secret, 'message': secret, 'code': secret, 'causes': secret},
            {'event': secret, 'query': secret},
        ], extra={'../../outside.txt': secret})
        output = self.report(path)
        self.assertNotIn(secret, output)
        self.assertIn('其他事件=1', output)
        self.assertFalse((self.root.parent / 'outside.txt').exists())

    def test_malformed_and_oversized_lines_do_not_hide_valid_later_records(self):
        raw = '{bad\n' + ('x' * (diagnostic.MAX_LINE_BYTES + 1)) + '\n[]\n'
        raw += json.dumps({'event': 'run_end', 'status': 'not-in-source'}) + '\n'
        environment, records, invalid = diagnostic.load_export(self.archive(raw=raw))
        self.assertEqual(3, invalid)
        self.assertEqual(1, len(records))
        self.assertIn('run_end=1', diagnostic.render(environment, records, invalid, self.index))

    def test_unexpected_field_types_are_ignored(self):
        output = self.report(self.archive({'hook': [], 'config': 'not an object'}, [
            {'event': {}, 'source': [], 'time': [], 'ver': {}, 'hostDropped': []},
            {'event': 'host_error', 'code': {}, 'site': [], 'reason': {}, 'time': 10**100},
        ]))
        self.assertIn('有效 2', output)
        self.assertIn('其他事件=1', output)
        self.assertNotIn('code=', output)

    def test_jsonl_without_environment_remains_useful(self):
        path = self.root / 'phone-use.jsonl'
        path.write_text(json.dumps({'event': 'voice_route', 'source': 'host', 'route': '3-4'}))
        output = self.report(path)
        self.assertIn('缺少环境快照', output)
        self.assertIn('route=3-4', output)
        self.assertIn('moduleDropped=未报告', output)
        self.assertIn('hostDeliveryErrors=未报告', output)

    def test_dynamic_message_prefix_without_delimiter_is_not_a_throw_site(self):
        path = self.root / diagnostic.SOURCE / 'Dynamic.java'
        path.write_text('class Dynamic { void run() { throw new IllegalStateException("动态" + value); } }')
        index = diagnostic.SourceIndex(self.root)
        self.assertNotIn(diagnostic.message_reference('动态'), index.messages)

    def test_old_host_versions_are_historical_evidence(self):
        output = self.report(self.archive({'versionCode': 20}, [
            {'event': 'host_hooks', 'source': 'host', 'ver': 10},
            {'event': 'host_hooks', 'source': 'host', 'ver': 20},
        ]))
        self.assertIn('历史记录', output)
        self.assertIn('不能单据此判断当前进程仍加载旧代码', output)

    def test_only_fixed_exception_prefix_is_hashed(self):
        self.assertEqual(diagnostic.message_reference('固定错误：动态甲'),
                         diagnostic.message_reference('固定错误:动态乙'))
        self.assertEqual('Example.execute:8', self.index.site('Example.execute:8'))
        self.assertIsNone(self.index.site('Unknown.secret:8'))
        self.assertIsNone(self.index.site('Example.unknown:8'))

    def test_unmatched_digest_does_not_guess_throw_site(self):
        output = self.report(self.archive(records=[{'event': 'failure', 'messageRef': 'a' * 16}]))
        self.assertIn('未匹配', output)
        self.assertNotIn('Example.java:', output)

    def test_duplicate_diagnostic_member_is_rejected(self):
        path = self.archive()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(path, 'a') as archive:
                archive.writestr('phone-use.jsonl', '')
        with self.assertRaisesRegex(diagnostic.InputError, '重复'):
            diagnostic.load_export(path)

    def test_archive_limits_and_exact_member_names_are_enforced(self):
        too_many = self.archive(extra={str(index): '' for index in range(diagnostic.MAX_ZIP_MEMBERS)})
        with self.assertRaisesRegex(diagnostic.InputError, '成员数量'):
            diagnostic.load_export(too_many)
        oversized = self.archive(raw='x' * (diagnostic.MAX_LOG_BYTES + 1))
        with self.assertRaisesRegex(diagnostic.InputError, '容量'):
            diagnostic.load_export(oversized)
        path = self.root / 'nested.zip'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('nested/phone-use.jsonl', '')
        with self.assertRaisesRegex(diagnostic.InputError, '缺少'):
            diagnostic.load_export(path)

    def test_invalid_environment_is_reported_without_its_content(self):
        path = self.archive()
        with zipfile.ZipFile(path, 'a') as archive:
            archive.writestr('environment.json', '{PRIVATE INVALID')
        output = io.StringIO()
        with contextlib.redirect_stderr(output):
            code = diagnostic.main([str(path), '--source-root', str(self.root)])
        self.assertEqual(2, code)
        self.assertIn('有效的 JSON', output.getvalue())
        self.assertNotIn('PRIVATE', output.getvalue())

    def test_main_prints_a_report_and_exit_code(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = diagnostic.main([str(self.archive()), '--source-root', str(self.root)])
        self.assertEqual(0, code)
        self.assertIn('MiAIPlus 离线诊断', output.getvalue())


if __name__ == '__main__':
    unittest.main()
