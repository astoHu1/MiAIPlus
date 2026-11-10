#!/usr/bin/env python3
"""Rebuild the APK's Termux MCP asset from the exact upstream source archive."""
import argparse
import gzip
import hashlib
import io
from pathlib import Path, PurePosixPath
import tarfile

SOURCE_SHA256 = "aca762de76a4cbe7d929e0f47512eb7484eca62bf1645375aae68a7665ffc5ec"
ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app/src/main/assets/termux-mcp/upstream.bin"


def build(source):
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError("Unexpected Termux MCP source archive SHA-256")
    with tarfile.open(fileobj=io.BytesIO(source)) as original:
        files = {}
        for member in original.getmembers():
            parts = PurePosixPath(member.name).parts[1:]
            if not parts or not member.isfile():
                continue
            if parts[0] not in ("termux_mcp", "LICENSE", "README.md", "pyproject.toml"):
                continue
            files["/".join(parts)] = original.extractfile(member).read()
        files["miaiplus_managed.py"] = (ROOT / "scripts/termux_mcp_managed.py").read_bytes()
        if "LICENSE" not in files or "termux_mcp/mcp_server.py" not in files:
            raise ValueError("Missing Termux MCP source or license")
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, mode="wb", filename="", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.USTAR_FORMAT) as result:
            for name, data in sorted(files.items()):
                member = tarfile.TarInfo(name)
                member.mode = 0o644
                member.size = len(data)
                result.addfile(member, io.BytesIO(data))
    return out.getvalue()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    asset = build(args.source.read_bytes())
    if args.check:
        if asset != DEST.read_bytes():
            parser.exit(1, "Termux MCP asset differs from the pinned source\n")
    else:
        DEST.write_bytes(asset)
    print(hashlib.sha256(asset).hexdigest(), DEST.name)
