package com.enviouse.skyfinder.client.command

import com.enviouse.skyfinder.data.DungeonRoomCatalog
import com.enviouse.skyfinder.dungeon.DungeonMapDecoder
import com.enviouse.skyfinder.dungeon.DungeonMapReader
import com.enviouse.skyfinder.dungeon.DungeonRoomMatcher
import com.enviouse.skyfinder.dungeon.HypixelLocationDetector
import com.enviouse.skyfinder.dungeon.RoomSkeletonRegistry
import com.enviouse.skyfinder.dungeon.SecretRouteCatalog
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component

/**
 * Client commands for SkyFinder. Currently only `/skyfinder data ...`.
 *
 * `/skyfinder data dump`    — prints catalog summary + first 5 rooms with secret counts
 * `/skyfinder data room <n>` — prints all secrets of one specific room
 * `/skyfinder data stats`   — totals + category breakdown across all rooms
 *
 * Brigadier-routed, no string parsing. Chat output is local-only (FabricClientCommandSource).
 */
object SkyFinderCommands {

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(buildRoot())
        }
    }

    private fun buildRoot(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return ClientCommandManager.literal("skyfinder")
            .then(ClientCommandManager.literal("status")
                .then(ClientCommandManager.literal("raw").executes { ctx -> runStatusRaw(ctx.source); 1 })
                .executes { ctx -> runStatus(ctx.source); 1 })
            .then(ClientCommandManager.literal("data")
                .then(ClientCommandManager.literal("dump").executes { ctx -> runDump(ctx.source); 1 })
                .then(ClientCommandManager.literal("stats").executes { ctx -> runStats(ctx.source); 1 })
                .then(ClientCommandManager.literal("room")
                    .then(ClientCommandManager.argument(
                        "name",
                        com.mojang.brigadier.arguments.StringArgumentType.greedyString()
                    ).executes { ctx ->
                        val name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")
                        runRoom(ctx.source, name); 1
                    })
                )
            )
            .then(ClientCommandManager.literal("map")
                .then(ClientCommandManager.literal("raw").executes { ctx -> runMapRaw(ctx.source); 1 })
                .then(ClientCommandManager.literal("grid").executes { ctx -> runMapGrid(ctx.source); 1 })
                .then(ClientCommandManager.literal("scan").executes { ctx -> runMapScan(ctx.source); 1 })
            )
            .then(ClientCommandManager.literal("room")
                .then(ClientCommandManager.literal("corner").executes { ctx -> runRoomCorner(ctx.source); 1 })
                .then(ClientCommandManager.literal("info").executes { ctx -> runRoomInfo(ctx.source); 1 })
            )
            .then(ClientCommandManager.literal("routes")
                .then(ClientCommandManager.literal("count").executes { ctx -> runRoutesCount(ctx.source); 1 })
                .then(ClientCommandManager.literal("dump").executes { ctx -> runRoutesDump(ctx.source); 1 })
                .then(ClientCommandManager.literal("update").executes { ctx -> runRoutesUpdate(ctx.source); 1 })
            )
            .executes { ctx ->
                ctx.source.sendFeedback(Component.literal(
                    "SkyFinder commands: /skyfinder status [raw] | data dump|stats|room <name> | map raw|grid|scan | room corner|info | routes count|dump|update"
                ).withStyle(ChatFormatting.AQUA))
                1
            }
    }

    private fun runStatus(source: FabricClientCommandSource) {
        source.sendFeedback(header("SkyFinder Status"))
        val inSb   = HypixelLocationDetector.inSkyblock()
        val inCata = HypixelLocationDetector.inCatacombs()
        source.sendFeedback(line("In SkyBlock:  ${flag(inSb)}"))
        source.sendFeedback(line("In Catacombs: ${flag(inCata)}"))
        if (inCata) {
            source.sendFeedback(line("Floor: ${HypixelLocationDetector.floorLabel()}"))
            source.sendFeedback(line("Sidebar line: \"${HypixelLocationDetector.rawLocationLine()}\""))
            source.sendFeedback(line("Room coord token: \"${HypixelLocationDetector.roomCoordToken()}\"  (change #${HypixelLocationDetector.roomChangeCounter()})"))
        } else if (!inSb) {
            source.sendFeedback(line("(scoreboard objective title does not contain 'SKYBLOCK' — are you on Hypixel?)"))
        }
    }

    private fun flag(b: Boolean): String = if (b) "yes" else "no"

    // ---------- /skyfinder routes ----------

    private fun runRoutesCount(source: FabricClientCommandSource) {
        source.sendFeedback(header("SkyFinder Routes (Zyra / SecretRoutes)"))
        val cat = SecretRouteCatalog.get()
        source.sendFeedback(line("routes.json:      ${cat.totalRoutes()} routes across ${cat.roomsWithRoutes()} rooms  [source: ${cat.routesSource()}]"))
        source.sendFeedback(line("pearlroutes.json: ${cat.totalPearlRoutes()} routes across ${cat.roomsWithPearlRoutes()} rooms  [source: ${cat.pearlSource()}]"))
        source.sendFeedback(line(""))
        source.sendFeedback(line("Secret-type histogram:"))
        cat.secretTypeHistogram()
            .entries
            .sortedByDescending { it.value }
            .forEach { (type, count) ->
                source.sendFeedback(line("  ${type.name.lowercase().padEnd(10)} = $count"))
            }
    }

    private fun runRoutesDump(source: FabricClientCommandSource) {
        source.sendFeedback(header("SkyFinder Routes Dump (current room)"))
        // Use the matcher to identify the current room first.
        if (!RoomSkeletonRegistry.isLoaded()) RoomSkeletonRegistry.ensureLoaded()
        val match = DungeonRoomMatcher.identifyCurrentRoom()
        if (match.outcome != DungeonRoomMatcher.Outcome.MATCHED && match.outcome != DungeonRoomMatcher.Outcome.AMBIGUOUS) {
            source.sendFeedback(line("No room matched (outcome: ${match.outcome}). Walk inside a dungeon room and try again."))
            return
        }
        val roomName = match.roomName ?: return run { source.sendFeedback(line("Matched but no room name?")) }
        source.sendFeedback(line("Room: $roomName  (rotation=${match.rotation})"))

        val cat = SecretRouteCatalog.get()
        val routes = cat.routesFor(roomName)
        val pearls = cat.pearlRoutesFor(roomName)
        source.sendFeedback(line("routes.json:      ${routes.size} routes"))
        source.sendFeedback(line("pearlroutes.json: ${pearls.size} routes"))
        if (routes.isEmpty() && pearls.isEmpty()) {
            source.sendFeedback(line("(No routes recorded for this room. Zyra may add them in a future update — try /skyfinder routes update.)"))
            return
        }
        routes.forEachIndexed { i, r ->
            source.sendFeedback(line(""))
            val secretCoord = r.secretLocation()?.let { "(${it[0]}, ${it[1]}, ${it[2]})" } ?: "(?)"
            source.sendFeedback(line("[Route ${i + 1}] secret=${r.secretType().name.lowercase()} @ $secretCoord  total waypoints=${r.totalWaypoints()}"))
            if (r.locations().isNotEmpty())   source.sendFeedback(line("  locations:   ${r.locations().size}  first=${vec(r.locations().first())}  last=${vec(r.locations().last())}"))
            if (r.etherwarps().isNotEmpty())  source.sendFeedback(line("  etherwarps:  ${r.etherwarps().size}  ${r.etherwarps().joinToString(", ") { vec(it) }}"))
            if (r.mines().isNotEmpty())       source.sendFeedback(line("  mines:       ${r.mines().size}  ${r.mines().joinToString(", ") { vec(it) }}"))
            if (r.interacts().isNotEmpty())   source.sendFeedback(line("  interacts:   ${r.interacts().size}  ${r.interacts().joinToString(", ") { vec(it) }}"))
            if (r.tnts().isNotEmpty())        source.sendFeedback(line("  tnts:        ${r.tnts().size}  ${r.tnts().joinToString(", ") { vec(it) }}"))
            if (r.enderpearls().isNotEmpty()) source.sendFeedback(line("  enderpearls: ${r.enderpearls().size}  ${r.enderpearls().joinToString(", ") { vec(it) }}"))
        }
    }

    private fun runRoutesUpdate(source: FabricClientCommandSource) {
        source.sendFeedback(header("SkyFinder Routes Update"))
        source.sendFeedback(line("Fetching latest routes from GitHub (yourboykyle/SecretRoutes)..."))
        Thread({
            val cat = SecretRouteCatalog.reload()
            source.sendFeedback(line(""))
            source.sendFeedback(line("Done."))
            source.sendFeedback(line("  routes.json:      ${cat.totalRoutes()} routes  [source: ${cat.routesSource()}]"))
            source.sendFeedback(line("  pearlroutes.json: ${cat.totalPearlRoutes()} routes  [source: ${cat.pearlSource()}]"))
            if (cat.routesSource() == "remote" || cat.pearlSource() == "remote") {
                source.sendFeedback(line("Cached to local config dir for next launch."))
            } else if (cat.routesSource() == "bundled") {
                source.sendFeedback(line("Network unreachable; using bundled fallback."))
            }
        }, "SkyFinder-RoutesUpdate").start()
    }

    private fun vec(v: IntArray): String = "(${v[0]},${v[1]},${v[2]})"

    private fun runRoomInfo(source: FabricClientCommandSource) {
        source.sendFeedback(header("SkyFinder Room Info"))
        // Lazy-load skeletons on first call. Cheap after the initial scan.
        if (!RoomSkeletonRegistry.isLoaded()) {
            val n = RoomSkeletonRegistry.ensureLoaded()
            source.sendFeedback(line("Loaded $n skeleton rooms (${RoomSkeletonRegistry.data().size} categories)."))
        }
        val t0 = System.nanoTime()
        val result = DungeonRoomMatcher.identifyCurrentRoom()
        val ms = (System.nanoTime() - t0) / 1_000_000.0

        source.sendFeedback(line("Outcome: ${result.outcome}  (scanned ${result.blocksScanned} blocks in ${"%.1f".format(ms)}ms)"))
        if (result.category != null) {
            source.sendFeedback(line("Category: ${result.category}"))
        }
        when (result.outcome) {
            DungeonRoomMatcher.Outcome.MATCHED -> {
                source.sendFeedback(line("Room: ${result.roomName}   rotation=${result.rotation}"))
                printSecrets(source, result.roomName)
            }
            DungeonRoomMatcher.Outcome.AMBIGUOUS -> {
                source.sendFeedback(line("Best guess: ${result.roomName}  rotation=${result.rotation}  (${result.remainingCandidates} candidates remain)"))
                source.sendFeedback(line("Top candidates:"))
                result.debugTopCandidates.forEach { source.sendFeedback(line("  $it")) }
                source.sendFeedback(line(""))
                printSecrets(source, result.roomName)
            }
            DungeonRoomMatcher.Outcome.NO_CANDIDATES -> {
                source.sendFeedback(line("No rooms matched. Possible causes:"))
                source.sendFeedback(line("  - Map category mis-classified (decode race; retry in a sec)"))
                source.sendFeedback(line("  - Room not in DRM dataset (139 rooms known; some new Hypixel rooms not covered)"))
            }
            DungeonRoomMatcher.Outcome.NOT_READY -> {
                source.sendFeedback(line("Not ready. Debug info:"))
                result.debugTopCandidates.forEach { source.sendFeedback(line("  $it")) }
            }
        }
    }

    private fun printSecrets(source: FabricClientCommandSource, roomName: String?) {
        if (roomName == null) return
        val secrets = DungeonRoomCatalog.get().secretsFor(roomName)
        if (secrets == null || secrets.isEmpty()) {
            source.sendFeedback(line("Secrets: 0  (no entry in DRM secretlocations.json)"))
            return
        }
        // Bucket by category for the count summary.
        val byCat = secrets.groupBy { it.category().name.lowercase() }
        val summary = byCat.entries.joinToString(", ") { "${it.value.size} ${it.key}" }
        source.sendFeedback(line("Secrets: ${secrets.size}  ($summary)"))
        // Each secret line shows its number, type, and room-local coords.
        // Step E will translate these to world via roomLocalToWorld so the
        // pathfinder can target them — for now we just dump them for verify.
        secrets.forEachIndexed { idx, s ->
            source.sendFeedback(line("  ${idx + 1}. ${s.secretName()}  [${s.category().name.lowercase()}]  local=(${s.x()}, ${s.y()}, ${s.z()})"))
        }
    }

    private fun runRoomCorner(source: FabricClientCommandSource) {
        source.sendFeedback(header("SkyFinder Room Corner"))
        if (!DungeonMapReader.mapExists()) {
            source.sendFeedback(line("No Magical Map detected — enter a dungeon first."))
            return
        }
        val snap = DungeonMapReader.read()
        if (snap == null) { source.sendFeedback(line("Map SavedData not loaded yet.")); return }
        val decoded = DungeonMapDecoder.decode(snap)
        if (decoded == null || decoded.entrance == null) {
            source.sendFeedback(line("No entrance detected on the map."))
            return
        }
        val r = decoded.currentRoom
        if (r == null) {
            source.sendFeedback(line("Player is not inside any discovered room (boss room / outside the dungeon grid?)."))
            return
        }
        if (!DungeonMapDecoder.isCalibrated()) {
            source.sendFeedback(line("World-map calibration not set yet. Walk around for a tick and retry."))
            return
        }
        val e = decoded.entrance
        val box = DungeonMapDecoder.worldBoxOf(r, e)
        if (box == null) { source.sendFeedback(line("Could not compute room world box.")); return }

        source.sendFeedback(line("Current room: ${r.category.name} [${r.color.name.lowercase()}] size=${r.size} segs=${r.segments.size}"))
        source.sendFeedback(line("World box: X[${box.minX}..${box.maxX}]  Z[${box.minZ}..${box.maxZ}]  (size ${box.maxX - box.minX + 1}x${box.maxZ - box.minZ + 1})"))

        // Print each candidate rotation's reference corner. Step D will
        // narrow this to exactly one by skeleton-matching.
        val dirs = DungeonMapDecoder.possibleDirections(r)
        source.sendFeedback(line("Candidate rotations (${dirs.size}): ${dirs.joinToString(", ") { it.name }}"))
        dirs.forEach { dir ->
            val c = DungeonMapDecoder.cornerWorldXZ(r, e, dir)
            if (c != null) {
                source.sendFeedback(line("  $dir corner = world (${c[0]}, ${c[1]})"))
            }
        }

        // Show what the player's current world position maps to as a
        // ROOM-LOCAL coord under each candidate rotation. This is what
        // .skeleton files store and what Step D will match against.
        val mc = net.minecraft.client.Minecraft.getInstance()
        val player = mc.player
        if (player != null) {
            val px = player.x.toInt()
            val py = player.y.toInt()
            val pz = player.z.toInt()
            source.sendFeedback(line(""))
            source.sendFeedback(line("You at world ($px, $py, $pz) → room-local under each rotation:"))
            dirs.forEach { dir ->
                val c = DungeonMapDecoder.cornerWorldXZ(r, e, dir) ?: return@forEach
                val local = DungeonMapDecoder.worldToRoomLocal(px, py, pz, c[0], c[1], dir)
                source.sendFeedback(line("  $dir → local (${local[0]}, ${local[1]}, ${local[2]})"))
            }
        }
    }

    private fun runMapScan(source: FabricClientCommandSource) {
        source.sendFeedback(header("SkyFinder Map Inventory Scan"))
        source.sendFeedback(line("Filled-map items currently in player inventory:"))
        DungeonMapReader.describeInventoryMaps().forEach { source.sendFeedback(line(it)) }
        source.sendFeedback(line(""))
        source.sendFeedback(line("Live `mapExists()`: ${DungeonMapReader.mapExists()}"))
        source.sendFeedback(line("Current cached MapId: ${DungeonMapReader.currentMapId()}"))
    }

    private fun runMapGrid(source: FabricClientCommandSource) {
        source.sendFeedback(header("SkyFinder Map Grid"))
        if (!DungeonMapReader.mapExists()) {
            source.sendFeedback(line("No Magical Map detected."))
            return
        }
        val snap = DungeonMapReader.read()
        if (snap == null) {
            source.sendFeedback(line("Map id ${DungeonMapReader.currentMapId()} cached but SavedData not loaded yet."))
            return
        }
        val decoded = DungeonMapDecoder.decode(snap)
        if (decoded == null || decoded.entrance == null) {
            source.sendFeedback(line("Could not find entrance corners on the map."))
            source.sendFeedback(line("Are you in a dungeon? Try running this AFTER you spawn into Catacombs."))
            return
        }
        val e = decoded.entrance
        source.sendFeedback(line("Entrance NW corners: left=(${e.left.x},${e.left.y}) right=(${e.right.x},${e.right.y})"))
        source.sendFeedback(line("Room width=${e.roomWidth}px  width+gap=${e.roomWidthAndGap}px (per-floor scaling)"))
        source.sendFeedback(line("Rooms found: ${decoded.rooms.size}"))
        if (DungeonMapDecoder.isCalibrated()) {
            val cal = DungeonMapDecoder.calibration()
            source.sendFeedback(line("World calibration: entrance world NW = (${cal[0]}, ${cal[1]})"))
        } else {
            source.sendFeedback(line("World calibration: NOT YET CALIBRATED (need to be in Catacombs with map decoded)"))
        }
        if (decoded.playerMarker != null) {
            source.sendFeedback(line("Player marker @ map pixel (${decoded.playerMarker.x},${decoded.playerMarker.y})"))
        } else {
            source.sendFeedback(line("Player marker: NOT on map (outside dungeon area? boss room?)"))
            source.sendFeedback(line("Raw decoration dump:"))
            DungeonMapDecoder.describeDecorations(DungeonMapReader.currentMapId()).forEach { source.sendFeedback(line("  $it")) }
        }
        if (decoded.currentRoom != null) {
            val r = decoded.currentRoom
            source.sendFeedback(line("Current room: ${r.category.name} [${r.color.name.lowercase()}] size=${r.size} segments=${r.segments.size}"))
        } else {
            source.sendFeedback(line("Current room: <unknown>"))
        }
        source.sendFeedback(line("Legend: B=brown G=entrance P=puzzle T=trap Y=miniboss F=fairy X=blood  (lowercase = player in that cell)"))
        source.sendFeedback(line(""))
        val gridLines = DungeonMapDecoder.asciiGrid(decoded)
        gridLines.forEach { source.sendFeedback(line("  $it")) }
        source.sendFeedback(line(""))
        source.sendFeedback(line("Room list (first 10):"))
        decoded.rooms.take(10).forEach { r ->
            val isCurrent = if (decoded.currentRoom != null && decoded.currentRoom.nwMapCorner == r.nwMapCorner) " <-- HERE" else ""
            source.sendFeedback(line("  ${r.color.name.padEnd(8)} ${r.size.padEnd(8)} segs=${r.segments.size} @ map(${r.nwMapCorner.x},${r.nwMapCorner.y})$isCurrent"))
        }
    }

    private fun runMapRaw(source: FabricClientCommandSource) {
        source.sendFeedback(header("SkyFinder Map Raw"))
        if (!DungeonMapReader.mapExists()) {
            source.sendFeedback(line("No Magical Map detected in inventory."))
            source.sendFeedback(line("(scan covers all 36 main+hotbar slots + offhand; map is cached after first sight"))
            source.sendFeedback(line(" so swapping to a Terminator etc. won't lose it.)"))
            return
        }
        val snap = DungeonMapReader.read()
        if (snap == null) {
            source.sendFeedback(line("Map id ${DungeonMapReader.currentMapId()} cached, but SavedData not loaded yet — try again in a tick."))
            return
        }
        source.sendFeedback(line("Display name: \"${snap.displayName}\""))
        source.sendFeedback(line("Map id: ${snap.mapId}  size: ${snap.width}x${snap.height}"))
        source.sendFeedback(line("Non-zero pixels: ${snap.nonZeroPixels} / ${snap.width * snap.height}"))
        source.sendFeedback(line("Checksum: ${"0x%08x".format(snap.checksum)}"))
        source.sendFeedback(line("Top 10 colour histogram (RGB hex → pixel count):"))
        val top = DungeonMapReader.topColours(snap, 10)
        if (top.isEmpty()) {
            source.sendFeedback(line("  <map appears fully empty>"))
        } else {
            top.forEach { entry ->
                val rgb = entry[0]
                val count = entry[1]
                source.sendFeedback(line("  0x%06x  count=%d".format(rgb, count)))
            }
        }
    }

    private fun runStatusRaw(source: FabricClientCommandSource) {
        source.sendFeedback(header("SkyFinder Raw Scoreboard"))
        source.sendFeedback(line("Objective title: \"${HypixelLocationDetector.debugObjectiveTitle()}\""))
        val lines = HypixelLocationDetector.debugSidebarLines()
        source.sendFeedback(line("Sidebar lines (${lines.size}):"))
        if (lines.isEmpty()) {
            source.sendFeedback(line("  <none>"))
            return
        }
        lines.forEachIndexed { i, l ->
            val clean = HypixelLocationDetector.stripFormatting(l)
            source.sendFeedback(line("  [$i] raw=\"$l\" clean=\"$clean\""))
        }
    }

    private fun runDump(source: FabricClientCommandSource) {
        val cat = DungeonRoomCatalog.get()
        source.sendFeedback(header("SkyFinder Dungeon Catalog"))
        source.sendFeedback(line("Rooms loaded: ${cat.roomCountTotal()}"))
        source.sendFeedback(line("Rooms with secret coords: ${cat.roomCountWithSecrets()}"))
        source.sendFeedback(line("Total secrets indexed: ${cat.totalSecrets()}"))
        source.sendFeedback(line("First 5 rooms with secrets:"))
        cat.rooms().values
            .filter { cat.secretsFor(it.name).isNotEmpty() }
            .take(5)
            .forEach { meta ->
                val secrets = cat.secretsFor(meta.name)
                source.sendFeedback(line("  - ${meta.name}  [${meta.category}]  ${secrets.size} secret(s)"))
            }
    }

    private fun runStats(source: FabricClientCommandSource) {
        val cat = DungeonRoomCatalog.get()
        // Category histogram of room types
        val typeHist = HashMap<String, Int>()
        for (m in cat.rooms().values) typeHist[m.category] = (typeHist[m.category] ?: 0) + 1
        // Category histogram of secrets
        val secretHist = HashMap<DungeonRoomCatalog.SecretCategory, Int>()
        for (room in cat.rooms().keys) {
            for (s in cat.secretsFor(room)) secretHist[s.category] = (secretHist[s.category] ?: 0) + 1
        }
        source.sendFeedback(header("SkyFinder Catalog Stats"))
        source.sendFeedback(line("Room types:"))
        typeHist.entries.sortedByDescending { it.value }
            .forEach { source.sendFeedback(line("  ${it.key.padEnd(12)} ${it.value}")) }
        source.sendFeedback(line("Secret categories:"))
        secretHist.entries.sortedByDescending { it.value }
            .forEach { source.sendFeedback(line("  ${it.key.name.padEnd(12)} ${it.value}")) }
    }

    private fun runRoom(source: FabricClientCommandSource, name: String) {
        val cat = DungeonRoomCatalog.get()
        val meta = cat.roomMeta(name).orElse(null)
        if (meta == null) {
            source.sendError(Component.literal("Room not found: $name"))
            // Suggest close matches
            val candidates = cat.rooms().keys.filter { it.contains(name, ignoreCase = true) }.take(5)
            if (candidates.isNotEmpty()) {
                source.sendFeedback(line("Did you mean:"))
                candidates.forEach { source.sendFeedback(line("  $it")) }
            }
            return
        }
        val secrets = cat.secretsFor(name)
        source.sendFeedback(header(meta.name))
        source.sendFeedback(line("Category: ${meta.category} | declared secrets: ${meta.secrets} | fairy soul: ${meta.fairysoul}"))
        source.sendFeedback(line("Indexed secrets (${secrets.size}):"))
        secrets.forEachIndexed { i, s ->
            source.sendFeedback(line("  ${i + 1}. ${s.secretName.padEnd(22)} [${s.category.name.lowercase()}]  @ (${s.x},${s.y},${s.z})"))
        }
    }

    private fun header(text: String): Component = Component.literal("== $text ==").withStyle(ChatFormatting.AQUA)
    private fun line(text: String): Component   = Component.literal(text).withStyle(ChatFormatting.GRAY)
}
