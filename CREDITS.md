# SkyFinder Credits

SkyFinder stands on the shoulders of three projects. Every line of code in
this mod that was lifted, ported, or adapted from one of them retains its
original license header in-file. This document is the consolidated
acknowledgement.

## Room detection — `com.enviouse.skyfinder.deps.scanner`

**Source**: [`yourboykyle/SecretRoutes`](https://github.com/yourboykyle/SecretRoutes), the `versions/1.21.10-fabric/src/main/java/xyz/yourboykyle/secretroutes/utils/dungeon/` package, plus `RoomRotationUtils.java` from the same version.

**Original author**: **odtheking** (per the BSD-3-Clause notice carried at the top of each upstream file).

**License**: BSD-3-Clause.

**Why we use it**: this is the room-detection algorithm. It replaces the
older DRM-derived approach (raytraced block skeletons + per-room fingerprint
matching) with a much cheaper and more accurate technique:

1. Project the player's (x, z) onto a fixed 32-block dungeon grid anchored at
   `(-185, -185)`. No per-dungeon calibration step needed.
2. At each new grid cell, hash the column of blocks at the cell center going
   down from the room ceiling to bedrock. That hash becomes the "core" of
   the room.
3. Look up `core → RoomData` in a precomputed table (`AutoUpdater/rooms.json`).
   Multi-tile rooms have several valid cores; BFS to expand.
4. Find the unique blue terracotta marker at one of the four canonical
   corners — that determines rotation + the world reference anchor for
   converting room-local coords (used in route waypoints) to world coords.

The result is microseconds-per-tick detection instead of hundreds-of-ms
brute scans, and zero "ambiguous" cases.

Ports for SkyFinder kept the algorithm identical and changed only:

- yarn mappings → Mojang official mappings (e.g. `MinecraftClient` → `Minecraft`, `world.isAir` → `state.isAir()`, `getOffsetX/Z` → `getStepX/Z`, `BlockPos.Mutable` → `BlockPos.MutableBlockPos`)
- package rename to `com.enviouse.skyfinder.deps.scanner`
- gated detection on SkyFinder's existing `HypixelLocationDetector` instead of upstream's `LocationUtils`
- dropped the upstream `Main.currentRoom` / `OnEnterNewRoom` event plumbing (we have our own event surface)

## Secret routes — `AutoUpdater/routes.json` + `AutoUpdater/pearlroutes.json`

**Source**: [`yourboykyle/SecretRoutes`](https://github.com/yourboykyle/SecretRoutes).

**Recorded by**: **Zyra** and other contributors via the SecretRoutes Discord. Zyra is currently #1 in dungeons; these routes are the best publicly available.

**License**: GPL-3.0.

SkyFinder redistributes a forked copy under `AutoUpdater/` so the maintainer can audit / iterate without waiting for upstream merges. Files preserve upstream `#origin` + `#copyright` meta keys. The mod pulls them at runtime from `https://raw.githubusercontent.com/EnVisione/SkyFinder/main/AutoUpdater/`.

## Renderer / pathfinder inspiration

**Source**: [`hannibal002/SkyHanni`](https://github.com/hannibal2/SkyHanni). LGPL-3.0.

SkyHanni's `LineDrawer` / `RenderTypes.LINES` pattern (per-vertex `setLineWidth`, camera-position anchor, pose-stack inversion) informed our renderer architecture. No code lifted; conventions adopted. Per the user's request, SkyHanni's nav-line VISUAL style was NOT directly copied.

## SkyFinder original code

Everything else (path A*, the secret route catalog wrapper, the auto-update HTTP fetcher, command surface, mixin into LevelRenderer, etc.) is original to SkyFinder and licensed **LGPL-3.0-or-later**.

## License chain summary

| Component | Upstream license | SkyFinder ships as |
|---|---|---|
| `deps/scanner/*.java` | BSD-3-Clause (odtheking) | BSD-3-Clause (preserved) |
| `AutoUpdater/*.json` | GPL-3.0 (SecretRoutes contributors) | GPL-3.0 (preserved) |
| original code | — | LGPL-3.0-or-later |

The aggregate work is distributable: BSD-3-Clause and GPL-3.0 are compatible with LGPL-3.0-or-later when the GPL/BSD components retain their respective notices, which they do.
