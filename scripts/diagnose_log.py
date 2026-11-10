#!/usr/bin/env python3
"""Summarize a MiAIPlus metadata export locally, without extracting or sending it."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

MAX_LOG_BYTES = 4 * 1024 * 1024
MAX_ENV_BYTES = 256 * 1024
MAX_LINE_BYTES = 8192
MAX_RECORDS = 32768
MAX_ZIP_MEMBERS = 16
MAX_DETAILS = 40
ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path('app/src/main/java/io/github/miaiplus')
TOKEN = re.compile(r'[A-Za-z][A-Za-z0-9_.$:-]{0,79}\Z')
REFERENCE = re.compile(r'[0-9a-f]{16}\Z')
EVENTS = {
    'chat_route', 'voice_route', 'host_error', 'host_agent_error', 'host_agent_support',
    'host_hooks', 'module_gate', 'bridge_recovered', 'prompt_policy', 'plugin_catalog',
    'tool_call_rejected', 'model_call', 'failure', 'start_rejected', 'run_start', 'run_end',
    'run_cancel', 'session_start', 'session_close', 'continuation_rejected',
    'host_control_call', 'hot_reload_result', 'bridge_task_missing', 'host_turn_stop',
    'prompt_wait', 'prompt_result', 'plugin_tool_call', 'observation', 'step_start',
    'step_continue', 'image_attached', 'frame_stored', 'frame_read', 'model_request',
    'model_http_response', 'module_process_start', 'native_voice_phone_handoff',
}
DETAIL_EVENTS = EVENTS - {
    'observation', 'step_start', 'step_continue', 'frame_stored', 'frame_read',
    'image_attached', 'model_request', 'run_start', 'session_start', 'module_process_start',
}
DETAIL_FIELDS = (
    'route', 'sinceRouteMs', 'entry', 'rule', 'agent', 'outcome', 'reason', 'state', 'status',
    'phase', 'stage', 'code', 'upstream', 'httpStatus', 'supported', 'forcedAgent',
    'globalModel', 'toolAvailable', 'foregroundTool', 'backgroundTool', 'tools',
    'cancelSource', 'resultKind', 'durationMs', 'polls', 'signal', 'messageRef',
    'exception', 'site', 'causeSite', 'causes', 'binding',
)


class InputError(Exception):
    """An input cannot be safely read; its contents must not appear in the error."""


def integer(value):
    return value if type(value) is int and abs(value) <= 2**63 - 1 else None


def object_value(value):
    return value if isinstance(value, dict) else {}


def bounded_read(stream, limit):
    value = stream.read(limit + 1)
    if len(value) > limit:
        raise InputError('输入超过容量限制')
    return value


def decode_object(data):
    try:
        value = json.loads(data)
    except (ValueError, UnicodeError, RecursionError):
        raise InputError('环境文件不是有效的 JSON 对象') from None
    if not isinstance(value, dict):
        raise InputError('环境文件不是有效的 JSON 对象')
    return value


def load_export(path):
    """Read only the two exact export members; never extract archive paths."""
    environment = {}
    try:
        if path.stat().st_size > 16 * 1024 * 1024:
            raise InputError('输入超过容量限制')
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                members = archive.infolist()
                if len(members) > MAX_ZIP_MEMBERS:
                    raise InputError('ZIP 成员数量超过限制')
                selected = {}
                for name, limit in [('phone-use.jsonl', MAX_LOG_BYTES), ('environment.json', MAX_ENV_BYTES)]:
                    matches = [info for info in members if info.filename == name]
                    if len(matches) > 1:
                        raise InputError('ZIP 含有重复的诊断文件')
                    if matches:
                        info = matches[0]
                        if info.is_dir() or info.flag_bits & 1 or info.file_size > limit:
                            raise InputError('ZIP 诊断文件被加密或超过容量限制')
                        with archive.open(info) as stream:
                            selected[name] = bounded_read(stream, limit)
                if 'phone-use.jsonl' not in selected:
                    raise InputError('ZIP 缺少 phone-use.jsonl')
                if 'environment.json' in selected:
                    environment = decode_object(selected['environment.json'])
                data = selected['phone-use.jsonl']
        else:
            with path.open('rb') as stream:
                data = bounded_read(stream, MAX_LOG_BYTES)
    except (OSError, zipfile.BadZipFile, RuntimeError, NotImplementedError, EOFError):
        raise InputError('无法读取诊断文件') from None
    records, invalid = [], 0
    for line in data.splitlines():
        if not line.strip():
            continue
        if len(records) + invalid >= MAX_RECORDS:
            raise InputError('日志行数超过限制')
        if len(line) > MAX_LINE_BYTES:
            invalid += 1
            continue
        try:
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError()
            records.append(value)
        except (ValueError, UnicodeError, RecursionError):
            invalid += 1
    return environment, records, invalid


def fixed_part(message):
    ends = [position for mark in (':', '：') if (position := message.find(mark)) >= 0]
    end = min(ends) if ends else -1
    return message[:end] if end > 0 else message


def message_reference(message):
    return hashlib.sha256(fixed_part(message).encode('utf-8')).hexdigest()[:16]


class SourceIndex:
    """Only trusted local source contributes names, literals and source locations to output."""

    def __init__(self, root):
        self.messages = {}
        self.tokens = set()
        self.classes = {}
        self.exceptions = set()
        source = root / SOURCE
        for path in sorted(source.rglob('*.java'))[:1024]:
            if path.is_symlink() or path.stat().st_size > 1024 * 1024:
                continue
            text = path.read_text(encoding='utf-8')
            self.classes[path.stem] = (path, text)
            self.exceptions.update(re.findall(r'\b[A-Za-z_$][\w$]*(?:Exception|Error)\b', text))
            for literal in re.finditer(r'"(?:\\.|[^"\\\r\n])*"', text):
                try:
                    value = json.loads(literal.group())
                except ValueError:
                    continue
                if TOKEN.fullmatch(value):
                    self.tokens.add(value)
            # A concatenated dynamic message need not match. Never guess a throw site from a partial match.
            for match in re.finditer(r'\bthrow\s+new\s+[\w.$]+\s*\(\s*("(?:\\.|[^"\\\r\n])*")', text):
                try:
                    value = json.loads(match.group(1))
                except ValueError:
                    continue
                following = text[match.end():].lstrip()
                if fixed_part(value) == value and not following.startswith((')', ',')):
                    continue
                location = f'{path.relative_to(root).as_posix()}:{text.count(chr(10), 0, match.start()) + 1}'
                self.messages.setdefault(message_reference(value), []).append(location)

    def site(self, value):
        if not isinstance(value, str) or len(value) > 500:
            return None
        parts = []
        for frame in value.split('<')[:3]:
            match = re.fullmatch(r'([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*|<init>|<clinit>)(?::(-?\d{1,7}))?', frame)
            if not match:
                return None
            owner, method, line = match.groups()
            source = self.classes.get(owner.split('$')[0])
            if not source or (method not in ('<init>', '<clinit>')
                              and not re.search(r'\b' + re.escape(method) + r'\s*\(', source[1])):
                return None
            parts.append(f'{owner}.{method}' + (f':{line}' if line else ''))
        return '<'.join(parts) or None

    def value(self, key, value):
        if type(value) is bool:
            return str(value).lower()
        number = integer(value)
        if number is not None:
            return str(number)
        if not isinstance(value, str) or len(value) > 500:
            return None
        if key in ('site', 'causeSite'):
            return self.site(value)
        if key == 'route' and re.fullmatch(r'\d{1,10}-\d{1,16}', value):
            return value
        if key == 'messageRef' and REFERENCE.fullmatch(value):
            return value
        if key in ('code', 'upstream') and re.fullmatch(r'-?\d{1,10}', value):
            return value
        if key == 'exception' and value in self.exceptions:
            return value
        if key == 'causes' and all(part in self.exceptions for part in value.split('>')):
            return value
        # Dynamic strings and binding/error messages are never echoed. Known source literals are metadata.
        if TOKEN.fullmatch(value) and value in self.tokens:
            return value
        return None


def stamp(value):
    number = integer(value)
    if number is None or not 0 < number < 32503680000000:
        return '未知'
    return datetime.fromtimestamp(number / 1000, timezone.utc).isoformat(timespec='milliseconds')


def render(environment, records, invalid, index):
    lines = ['MiAIPlus 离线诊断（仅列出元数据与线索）']
    hook = object_value(environment.get('hook'))
    host = object_value(environment.get('host'))
    config = object_value(environment.get('config'))
    app = object_value(environment.get('app'))
    version = integer(environment.get('versionCode', app.get('versionCode')))
    lines.append(f'记录：有效 {len(records)}，损坏/超长 {invalid}；导出模块版本代码：{version if version is not None else "未知"}')
    android_api = integer(environment.get('androidApi', object_value(environment.get('device')).get('androidApi')))
    if android_api is not None:
        lines.append(f'Android API：{android_api}')
    enabled = environment.get('detailedLogEnabled')
    if type(enabled) is bool:
        lines.append(f'导出时详细日志：{"开启" if enabled else "关闭"}（不代表复现期间的状态）')
    if not environment:
        lines.append('缺少环境快照；纯 JSONL 无法确认导出时配置与 Hook 安装状态。')
    for section, values, keys in (
        ('宿主', host, ('installed', 'versionCode', 'profile', 'enabled')),
        ('Hook 报告', hook, ('reported', 'reportKind', 'ageMs', 'statusAgeMs', 'versionSkew', 'reportPredatesModuleUpdate')),
        ('模块配置', config, ('enabled', 'globalModel', 'planner', 'backgroundDisplay')),
        ('无障碍', object_value(environment.get('accessibility')), ('connected', 'preflightEnabled')),
        ('插件目录', object_value(environment.get('plugins')), ('catalogTools', 'catalogForeground', 'catalogBackground')),
    ):
        fields = [f'{key}={rendered}' for key in keys if (rendered := index.value(key, values.get(key))) is not None]
        if fields:
            lines.append(section + '：' + ', '.join(fields))
    for key in ('status', 'lastReload'):
        value = object_value(hook.get(key))
        fields = [f'{field}={rendered}' for field in ('phase', 'stage', 'reason', 'moduleVersionCode', 'hostVersionCode')
                  if (rendered := index.value(field, value.get(field))) is not None]
        if fields:
            lines.append(f'Hook {key}：' + ', '.join(fields))
    sources = Counter(record.get('source') if record.get('source') in ('host', 'module') else '未知' for record in records)
    lines.append('来源：' + (', '.join(f'{source}={count}' for source, count in sorted(sources.items())) or '无'))
    times = [value for record in records if (value := integer(record.get('time'))) is not None and value > 0]
    if times:
        lines.append(f'记录时间（UTC）：{stamp(min(times))} → {stamp(max(times))}')
    versions = sorted({value for record in records if record.get('source') == 'host'
                       and (value := integer(record.get('ver'))) is not None})
    lines.append('宿主写入模块版本：' + (', '.join(map(str, versions)) if versions else '未知（旧记录可能没有 ver）'))
    if version is not None and any(value != version for value in versions):
        lines.append('线索：保留日志含其他模块版本，可能来自更新前的历史记录；不能单据此判断当前进程仍加载旧代码。')
    counters = {key: integer(environment.get(key)) for key in ('moduleDropped', 'moduleWriteErrors')}
    for key in ('hostDropped', 'hostDeliveryErrors'):
        observed = [value for record in records if (value := integer(record.get(key))) is not None and value >= 0]
        counters[key] = max(observed) if observed else None
    lines.append('丢失计数（各来源进程内累计值/最大值，非跨进程总和）：' + ', '.join(
        f'{key}={value if value is not None and value >= 0 else "未报告"}' for key, value in counters.items()))
    if any(value is not None and value > 0 for value in counters.values()):
        lines.append('线索：已有丢弃或写入/传输失败，记录链可能不完整。')
    counts = Counter(record.get('event') if isinstance(record.get('event'), str)
                     and (record['event'] in EVENTS or record['event'] in index.tokens)
                     else '其他事件' for record in records)
    lines.append('事件：' + (', '.join(f'{event}={count}' for event, count in sorted(counts.items())) or '无'))
    details = [record for record in records if isinstance(record.get('event'), str) and record['event'] in DETAIL_EVENTS]
    if details:
        lines.append(f'最近诊断记录（按模块接收顺序，最多 {MAX_DETAILS} 条；异步到达不等于发生顺序）：')
    for record in details[-MAX_DETAILS:]:
        fields = [f'{key}={value}' for key in DETAIL_FIELDS
                  if (value := index.value(key, record.get(key))) is not None]
        lines.append(f'  {stamp(record.get("time"))} {record["event"]} ' + ', '.join(fields))
        reference = record.get('messageRef')
        if isinstance(reference, str) and REFERENCE.fullmatch(reference):
            locations = index.messages.get(reference, [])
            if locations:
                lines.append('    本地源码候选（需核对导出构建版本）：' + ', '.join(locations[:5]))
            else:
                lines.append('    messageRef 在当前本地源码未匹配；不能猜测抛出点。')
    if not sources['host']:
        lines.append('线索：没有宿主记录；可能未启用日志、未进入已覆盖入口、传输失败或记录已轮转，不能断言 Hook 未加载。')
    if any(record.get('event') == 'host_error' and str(record.get('code')) == '-1009' for record in records):
        lines.append('线索：宿主 UI 错误码 -1009；该码本身不足以判定账号、地区、ROM 或服务端的具体原因。')
    lines.append('限制：环境是导出时快照，Hook 报告可能陈旧；日志最多保留 2 MiB，队列未送达和进程退出可丢失记录。')
    lines.append('空日志、零计数或缺少某事件均不能证明链路成功或定位唯一根因；本工具不验证真实模型、服务端收图或业务完成。')
    return '\n'.join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('export', type=Path, help='导出的 ZIP 或 phone-use.jsonl 文件')
    parser.add_argument('--source-root', type=Path, default=ROOT, help='用于 messageRef 候选定位的受信任本地仓库根目录')
    args = parser.parse_args(argv)
    try:
        environment, records, invalid = load_export(args.export)
        index = SourceIndex(args.source_root)
        print(render(environment, records, invalid, index))
    except InputError as failure:
        print(f'无法诊断：{failure}', file=sys.stderr)
        return 2
    except (OSError, UnicodeError):
        print('无法诊断：本地源码不可读', file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
