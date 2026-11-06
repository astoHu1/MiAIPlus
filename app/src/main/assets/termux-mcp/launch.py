"""Run the pinned upstream HTTP server in an isolated Termux home."""
import hmac
import os
import re
from pathlib import Path
import sys

base = Path(sys.argv[1])
expected_uid, port, token = (base / "credentials").read_text(encoding="ascii").splitlines()
if int(expected_uid) != os.getuid() or os.getuid() == 0:
    raise SystemExit("Termux MCP must run as the expected Termux app")
if len(token) != 64 or any(c not in "0123456789abcdef" for c in token):
    raise SystemExit("Invalid Termux MCP credential")

os.umask(0o077)
home = base / "home"
home.mkdir(exist_ok=True)
os.chdir(home)
os.environ.update({
    "HOME": str(home),
    "TERMUX_MCP_CONFIG_DIR": str(base / "config"),
    "TERMUX_MCP_AUTH": "on",
    "TERMUX_MCP_AUTH_TOKEN": token,
    "TERMUX_NATIVE_MCP_AUTH_TOKEN": token,
    "TERMUX_NATIVE_MCP_HOST": "127.0.0.1",
    "TERMUX_NATIVE_MCP_PORT": port,
    "TERMUX_NATIVE_MCP_PATH": "/mcp",
    "TERMUX_NATIVE_MCP_TOOLS": "run,run_digest,out_read",
    "TERMUX_MCP_TIMEOUT": "10",
    "TERMUX_MCP_LOG_EVENTS": "0",
})
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent / "upstream"))

from termux_mcp.mcp_server import MCPHTTPServer, _check_startup_guards
from termux_mcp.mcp_transport_http import MCPRequestHandler


class Handler(MCPRequestHandler):
    def do_GET(self):
        match = re.fullmatch(r"/miaiplus-ready/([0-9a-f]{32})", self.path)
        if not match:
            return super().do_GET()
        message = f"MIAIPLUS_TERMUX_1:{os.getuid()}:{match[1]}".encode("ascii")
        proof = hmac.digest(token.encode("ascii"), message, "sha256").hex()
        self._send_body(200, proof.encode("ascii"), "text/plain")


_check_startup_guards("127.0.0.1")
# Direct bind: upstream's CLI startup clears other processes listening on the selected port.
with MCPHTTPServer(("127.0.0.1", int(port)), Handler) as server:
    server.serve_forever()
