MiAIPlus distributes Termux MCP 0.15.0 as a separate Python program under the
GNU Affero General Public License v3. It runs in the Termux app and communicates
with MiAIPlus over HTTP. Upstream Python code is unmodified.

- Upstream: https://github.com/termuxgpt/termux-mcp
- Revision: `48e3e58b3c8c5d66b97e79c0fa6473d126508f46`
- Complete corresponding upstream source: https://github.com/termuxgpt/termux-mcp/tree/48e3e58b3c8c5d66b97e79c0fa6473d126508f46
- Source archive: https://codeload.github.com/termuxgpt/termux-mcp/tar.gz/48e3e58b3c8c5d66b97e79c0fa6473d126508f46
- Source archive SHA-256: `aca762de76a4cbe7d929e0f47512eb7484eca62bf1645375aae68a7665ffc5ec`

The APK asset includes the upstream package source, license, and build metadata.
Run `python3 scripts/vendor_termux_mcp.py <source-archive>` to regenerate it;
use `--check` to compare it. MiAIPlus's launcher and provisioning scripts are
separate files alongside the asset.
