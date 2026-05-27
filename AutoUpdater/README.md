# SkyFinder AutoUpdater

This folder hosts the runtime-updatable data files for SkyFinder. The mod
pulls these on launch (5-second timeout) and caches them locally, so the
maintainer can publish route improvements without forcing every player
to download a new jar.

## Files

| File | What it is | Upstream |
|---|---|---|
| `routes.json` | Per-room secret routes (locations, etherwarps, mines, interacts, tnts, enderpearls) | yourboykyle/SecretRoutes, GPL-3.0 |
| `pearlroutes.json` | Per-room enderpearl-specific routes | yourboykyle/SecretRoutes, GPL-3.0 |
| `manifest.json` | File schema + upstream provenance pointers | — |

## How the mod loads these

On client launch:

1. `SecretRouteCatalog.load()` tries `https://raw.githubusercontent.com/<owner>/<repo>/main/AutoUpdater/{routes,pearlroutes}.json` with a 5s timeout.
2. If 200 OK and non-empty body, the response is cached to
   `<minecraft_run_dir>/config/skyfinder/{routes,pearlroutes}.json` and used.
3. If the network call fails (offline / rate-limit / repo down), it falls
   back to the local disk cache from a prior successful pull.
4. If THAT's also unavailable, it falls back to the bundled copy inside
   the jar (`assets/skyfinder/data/{routes,pearlroutes}.json`), which is
   a copy of these files at the time the jar was built.

`/skyfinder routes update` in-game forces a re-pull. `/skyfinder routes count`
shows which source (`remote` / `cached` / `bundled`) supplied each file.

## How to update

1. Replace `routes.json` and/or `pearlroutes.json` with the new content.
2. Commit + push to `main`.
3. Players will pick up the change on their next game launch (or on
   `/skyfinder routes update`).

## License + credit

`routes.json` and `pearlroutes.json` are GPL-3.0 derivatives of
[`yourboykyle/SecretRoutes`](https://github.com/yourboykyle/SecretRoutes).
Routes recorded by Zyra and other community contributors via the
SecretRoutes Discord. The full provenance is preserved inside the JSON
files themselves under the `#origin` and `#copyright` meta keys.
