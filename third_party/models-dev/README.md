# models.dev

- Upstream: https://github.com/anomalyco/models.dev
- Runtime data: https://models.dev/api.json (v1 provider/model catalog)
- License: MIT; reproduced in `LICENSE`.
- License/schema checked on 2026-11-14 against upstream `dev` revision
  `9afe121d524c044965ab0442daac933d99edf010`.

MiAIPlus fetches model capability metadata at runtime and stores its last valid
snapshot in app-private storage. The APK does not contain an upstream catalog
snapshot, executable code, or SDK. The revision above identifies the schema and
license reviewed during implementation, not a pin on future runtime data.

Only metadata understood by MiAIPlus's existing reasoning request adapters is
used. Unknown fields and unsupported request shapes do not install new adapters.
See `docs/models-dev.md` for refresh, matching, and failure behavior.
