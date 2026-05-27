# SkyFinder

A Fabric 1.21.11 client-side mod that renders smooth, intuitive lines guiding
the player to the next secret in Hypixel SkyBlock Catacombs.

> **Status:** Active development. Detection works. Routes data layer landed.
> Renderer-for-full-route + ClothConfig GUI are next.

## What it does

- Detects the player's current dungeon room by fingerprinting blocks against
  a library of 132 `.skeleton` files (ported from
  [`Quantizr/DungeonRoomsMod`](https://github.com/Quantizr/DungeonRoomsMod),
  GPL-3.0).
- Loads per-secret route data — `locations`, `etherwarps`, `mines`, `interacts`,
  `tnts`, `enderpearls` — from a forked copy of
  [`yourboykyle/SecretRoutes`](https://github.com/yourboykyle/SecretRoutes)
  hosted in this repo under [`AutoUpdater/`](./AutoUpdater/).
- A* pathfinder + Catmull-Rom-flavour bezier renderer (LINES vertex format,
  per-vertex line width) produces SkyHanni-style guidance lines.

## In-game commands

```
/skyfinder status [raw]              — location/Catacombs/floor + scoreboard dump
/skyfinder data dump|stats|room <n>  — local catalogue (139 rooms, 820 secrets)
/skyfinder map raw|grid|scan         — map item diagnostics + ASCII viz
/skyfinder room corner|info          — current-room world coords + skeleton-match name
/skyfinder routes count|dump|update  — SecretRoutes data: count, current-room dump, re-pull
```

## Auto-updating route data

SkyFinder doesn't bake the route data into the jar permanently. On every
launch it tries to pull the latest `routes.json` + `pearlroutes.json` from
[`AutoUpdater/`](./AutoUpdater/) (5-second timeout), caches the result to
`<minecraft>/config/skyfinder/`, and falls back to a bundled copy if the
network is unreachable. The maintainer can ship route fixes by editing
the files in [`AutoUpdater/`](./AutoUpdater/) and pushing — no rebuild
required.

## Building

```
./gradlew build
```

Output jar: `build/libs/SkyFinder-1.0-SNAPSHOT.jar`.

Requires Java 21. Test environment: Prism Launcher with Fabric loader 0.19.2,
fabric-api 0.141.4, fabric-language-kotlin 1.13.11+kotlin.2.3.21.

> **Don't run `./gradlew runClient` on headless Linux** — GLFW will refuse to
> initialize. Build on Linux, run on a real desktop.

## Credits

- **DungeonRoomsMod** (Quantizr, GPL-3.0) — room detection algorithm + the
  132 `.skeleton` fingerprint files + the original DRM data layer.
- **SecretRoutes** (yourboykyle / Zyra + community, GPL-3.0) — the full
  per-secret waypoint chains under `AutoUpdater/`. **Routes recorded by
  Zyra.**
- **SkyHanni** (LGPL-3.0) — renderer architecture inspiration (LINES
  RenderType pattern, per-vertex line width on 1.21+).

## License

SkyFinder's original code is LGPL-3.0-or-later. Bundled GPL-3.0 data files
keep their original license; see each file's `#origin` / `#copyright` meta
keys for full provenance.
