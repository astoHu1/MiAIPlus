#!/usr/bin/env python3
"""Verify only the fixed owned calculator's current formula/result area; never print OCR text."""
import csv
import io
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys


def jpeg_size(data):
    if data[:2] != b'\xff\xd8':
        raise ValueError('expected owned JPEG frame')
    index = 2
    while index < len(data):
        if data[index] != 0xFF:
            raise ValueError('invalid JPEG segment')
        while index < len(data) and data[index] == 0xFF:
            index += 1
        marker = data[index]
        index += 1
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            continue
        length = struct.unpack_from('>H', data, index)[0]
        if length < 2 or index + length > len(data):
            raise ValueError('invalid JPEG segment length')
        if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            height, width = struct.unpack_from('>HH', data, index + 3)
            if width <= 0 or height <= 0:
                raise ValueError('invalid JPEG dimensions')
            return width, height
        index += length
    raise ValueError('missing JPEG dimensions')


def verified(tsv, width, height, foreground=False):
    lines = {}
    for word in csv.DictReader(io.StringIO(tsv), delimiter='\t', quoting=csv.QUOTE_NONE):
        if word.get('level') != '5' or not word.get('text', '').strip():
            continue
        left, top, word_width, word_height = (int(word[key]) for key in ('left', 'top', 'width', 'height'))
        # The physical scientific layout's result reaches .402; its keypad starts below .43.
        # Keep the existing background region, and exclude history/keypad in both layouts.
        bottom = .42 if foreground else .40
        if (left < width * .10 or left + word_width > width * .98 or top < height * .24
                or top + word_height > height * bottom):
            continue
        key = tuple(word[name] for name in ('page_num', 'block_num', 'par_num', 'line_num'))
        lines.setdefault(key, []).append((left, word['text']))
    normalized = [''.join(text for _, text in sorted(words)).replace(' ', '').replace('\u00a0', '')
                  for words in lines.values()]
    formula = any(re.fullmatch(r'137[xX×*]29', line) for line in normalized)
    result = any(re.fullmatch(r'=?3,?973', line) for line in normalized)
    return formula and result


def main():
    arguments = sys.argv[1:]
    foreground = bool(arguments and arguments[0] == '--foreground')
    if foreground:
        arguments = arguments[1:]
    if len(arguments) not in (1, 2) or (len(arguments) == 2 and arguments[1] != '3973'):
        raise ValueError('invalid owned calculator verifier arguments')
    executable = shutil.which('tesseract')
    if executable is None:
        raise RuntimeError('tesseract is required for owned calculator verification')
    data = Path(arguments[0]).read_bytes()
    width, height = jpeg_size(data)
    process = subprocess.run([executable, 'stdin', 'stdout', '-l', 'eng', '--psm', '11', 'tsv'],
                             input=data, capture_output=True, timeout=10)
    if process.returncode != 0:
        raise RuntimeError('owned calculator OCR failed')
    return 0 if verified(process.stdout.decode('utf-8'), width, height, foreground) else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, IndexError, struct.error, subprocess.TimeoutExpired, RuntimeError):
        print('owned calculator verification unavailable', file=sys.stderr)
        sys.exit(2)
