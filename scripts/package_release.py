#!/usr/bin/env python3
"""Package only distributable project files, never the reverse-engineering workspace."""
from pathlib import Path
import hashlib
import re
import shutil
import zipfile

root = Path(__file__).resolve().parents[1]
version = re.search(r"versionName '([^']+)'", (root / 'app/build.gradle').read_text()).group(1)
dist = root / 'dist'
dist.mkdir(exist_ok=True)
shutil.copyfile(root / 'app/build/outputs/apk/release/app-release-unsigned.apk', dist / f'MiAIPlus-{version}-unsigned.apk')
preview = root / 'app/build/outputs/apk/preview/app-preview.apk'
if preview.is_file():
    shutil.copyfile(preview, dist / f'MiAIPlus-{version}-preview.apk')
files = ['.gitignore', 'AGENTS.md', 'README.md', 'LICENSE', 'NOTICE', 'settings.gradle', 'build.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'app/build.gradle', 'app/proguard-rules.pro']
for folder in ['app/src', 'gradle/wrapper', 'docs', 'scripts', 'third_party']:
    files.extend(str(p.relative_to(root)) for p in (root / folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc' and not p.is_relative_to(root / 'docs/promo'))
with zipfile.ZipFile(dist / f'MiAIPlus-{version}-source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for name in sorted(files):
        archive.write(root / name, f'MiAIPlus-{version}/{name}')
checks = []
for artifact in sorted(dist.iterdir()):
    if artifact.name == 'SHA256SUMS.txt' or artifact.suffix == '.idsig':
        continue
    checks.append(f'{hashlib.sha256(artifact.read_bytes()).hexdigest()}  {artifact.name}')
(dist / 'SHA256SUMS.txt').write_text('\n'.join(checks) + '\n')
print(f'Packaged {version}; release signing remains the publisher’s responsibility.')
