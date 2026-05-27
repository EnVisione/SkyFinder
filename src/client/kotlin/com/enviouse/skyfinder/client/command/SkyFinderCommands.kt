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
                .then(ClientCommandManager.literal("recalibrate").executes { ctx -> runRoomRecalibrate(ctx.source); 1 })
            )
            .then(ClientCommandManager.literal("routes")
                .then(ClientCommandManager.literal("count").executes { ctx -> runRoutesCount(ctx.source); 1 })
                .then(ClientCommandManager.literal("dump").executes { ctx -> runRoutesDump(ctx.source); 1 })
                .then(ClientCommandManager.literal("update").executes { ctx -> runRoutesUpdate(ctx.source); 1 })
            )
            .then(ClientCommandManager.literal("route")
                .then(ClientCommandManager.literal("show").executes { ctx -> runRouteShow(ctx.source, 0); 1 }
                    .then(ClientCommandManager.argument("index", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                        .executes { ctx -> runRouteShow(ctx.source, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "index")); 1 })
                )
                .then(ClientCommandManager.literal("clear").executes { ctx -> runRouteClear(ctx.source); 1 })
                .then(ClientCommandManager.literal("legend").executes { ctx -> runRouteLegend(ctx.source); 1 })
            )
            .executes { ctx ->
                ctx.source.sendFeedback(Component.literal(
                    "SkyFinder commands: /skyfinder status [raw] | data dump|stats|room <name> | map raw|grid|scan | room corner|info|recalibrate | routes count|dump|update | route show [n]|clear|legend"
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
            source.sendFeedback(line("Dungeon running (post-countdown): ${flag(HypixelLocationDetector.dungeonRunning())}"))
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

    // ---------- /skyfinder route show / clear ----------

    private fun runRouteClear(source: FabricClientCommandSource) {
        com.enviouse.skyfinder.client.render.SecretRouteRenderer.clear()
        source.sendFeedback(line("Route cleared."))
    }

    private fun runRoomRecalibrate(source: FabricClientCommandSource) {
        com.enviouse.skyfinder.dungeon.DungeonMapDecoder.forceRecalibrate()
        source.sendFeedback(line("World calibration cleared. Will re-snapshot on next map decode (must be in a confirmed dungeon room — roomChangeCounter >= 1)."))
    }

    private fun runRouteLegend(source: FabricClientCommandSource) {
        source.sendFeedback(header("SkyFinder Route Legend"))
        source.sendFeedback(line("§bCYAN§r line       — locations[] (movement path)"))
        source.sendFeedback(line("§3TEAL§r box        — etherwarp / AOTV anchors"))
        source.sendFeedback(line("§6ORANGE§r box      — mines (Superboom / Stonk blocks)"))
        source.sendFeedback(line("§eYELLOW§r box      — interacts (right-click triggers)"))
        source.sendFeedback(line("§cRED§r box         — TNT placements"))
        source.sendFeedback(line("§dMAGENTA§r box     — enderpearl landing points"))
        source.sendFeedback(line(""))
        source.sendFeedback(line("Final-secret marker (pulsing double box):"))
        source.sendFeedback(line("  §6chest§r = gold     §fitem§r = white    §eyellow§r = interact"))
        source.sendFeedback(line("  §6stonk§r = chartreuse §6superboom§r = orange-red  §dpink§r = fairysoul"))
        source.sendFeedback(line("  §agreen§r = entrance §2sea-green§r = exit"))
    }

    private fun runRouteShow(source: FabricClientCommandSource, index: Int) {
        source.sendFeedback(header("SkyFinder Route Show"))
        // 1. Identify the current room via the new BSD scanner.
        val scanned = com.enviouse.skyfinder.deps.scanner.DungeonScanner.currentRoom
        if (scanned == null || scanned.rotation == com.enviouse.skyfinder.deps.scanner.Rotations.NONE) {
            source.sendFeedback(line("Scanner has no current room. Stand inside a dungeon room and try again."))
            return
        }
        val roomName = scanned.name
        val rotation = com.enviouse.skyfinder.deps.scanner.RoomRotationUtils.rotationCode(scanned.rotation)
        val corner = com.enviouse.skyfinder.deps.scanner.RoomRotationUtils.cornerOf(scanned)
            ?: return source.sendFeedback(line("Scanner has no anchor corner."))

        // 2. Look up routes for this room.
        val routes = SecretRouteCatalog.get().routesFor(roomName)
        if (routes.isEmpty()) {
            source.sendFeedback(line("No routes recorded for $roomName."))
            source.sendFeedback(line("(Zyra may not have routed this one yet.)"))
            return
        }
        if (index < 0 || index >= routes.size) {
            source.sendFeedback(line("Index $index out of range. Room has ${routes.size} route(s) (0..${routes.size - 1})."))
            return
        }
        val r = routes[index]

        // 3. Translate every waypoint chain from room-local → world Vec3s via
        //    RoomRotationUtils.relativeToActual (BSD-3-Clause path).
        //    Boxes (etherwarps/mines/interacts/tnts/enderpearls/secret-goal)
        //    use the raw INTEGER block corner — SecretRoutes renders a 1x1x1
        //    box from (x,y,z) to (x+1,y+1,z+1), which is the block's outline.
        //    `locations[]` is a PATH and uses block centers (+0.5).
        fun cornerVec(v: IntArray): net.minecraft.world.phys.Vec3 {
            val pos = com.enviouse.skyfinder.deps.scanner.RoomRotationUtils.relativeToActual(
                net.minecraft.core.BlockPos(v[0], v[1], v[2]), rotation, corner
            )
            return net.minecraft.world.phys.Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
        }
        fun centerVec(v: IntArray): net.minecraft.world.phys.Vec3 {
            val pos = com.enviouse.skyfinder.deps.scanner.RoomRotationUtils.relativeToActual(
                net.minecraft.core.BlockPos(v[0], v[1], v[2]), rotation, corner
            )
            return net.minecraft.world.phys.Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        }
        fun cornerChain(list: List<IntArray>): List<net.minecraft.world.phys.Vec3> = list.map(::cornerVec)
        fun centerChain(list: List<IntArray>): List<net.minecraft.world.phys.Vec3> = list.map(::centerVec)

        val presentation = com.enviouse.skyfinder.client.render.SecretRouteRenderer.RoutePresentation(
            locations   = centerChain(r.locations()),   // path through block centers
            etherwarps  = cornerChain(r.etherwarps()),  // 1-block box at integer corner
            mines       = cornerChain(r.mines()),
            interacts   = cornerChain(r.interacts()),
            tnts        = cornerChain(r.tnts()),
            enderpearls = cornerChain(r.enderpearls()),
            secretGoal  = r.secretLocation()?.let(::cornerVec),
            secretType  = r.secretType(),
            roomName    = roomName,
        )
        com.enviouse.skyfinder.client.render.SecretRouteRenderer.setPresentation(presentation)

        source.sendFeedback(line("Showing route ${index + 1}/${routes.size} for $roomName  (rotation=$rotation)"))
        source.sendFeedback(line("Secret: ${r.secretType().name.lowercase()}"))
        source.sendFeedback(line("Counts: ${r.locations().size} locations, ${r.etherwarps().size} etherwarps, ${r.mines().size} mines, ${r.interacts().size} interacts, ${r.tnts().size} tnts, ${r.enderpearls().size} pearls"))
        source.sendFeedback(line("Use /skyfinder route clear to hide it. Cycle alternates via /skyfinder route show <n>."))
    }

    private fun runRoomInfo(source: FabricClientCommandSource) {
        source.sendFeedback(header("SkyFinder Room Info"))
        source.sendFeedback(line("Scanner loaded: ${com.enviouse.skyfinder.deps.scanner.DungeonScanner.loadedRoomCount()} rooms (${com.enviouse.skyfinder.deps.scanner.DungeonScanner.loadedCoreCount()} cores)"))
        val room = com.enviouse.skyfinder.deps.scanner.DungeonScanner.currentRoom
        if (room == null) {
            source.sendFeedback(line("Not in a recognized room. Either:"))
            source.sendFeedback(line("  - not standing inside a dungeon tile yet (walk a step), OR"))
            source.sendFeedback(line("  - this room's core hash isn't in rooms.json (new Hypixel room?)"))
            return
        }
        source.sendFeedback(line("Room: §a${room.name}§r  type=${room.data.type()}  shape=${room.data.shape()}"))
        source.sendFeedback(line("Rotation: ${com.enviouse.skyfinder.deps.scanner.RoomRotationUtils.rotationCode(room.rotation)} (${room.rotation})"))
        source.sendFeedback(line("Anchor corner (clayPos): (${room.clayPos.x}, ${room.clayPos.y}, ${room.clayPos.z})"))
        source.sendFeedback(line("Components (${room.roomComponents.size}):"))
        var i = 0
        for (c in room.roomComponents) {
            if (i < 6) source.sendFeedback(line("  - centre=(${c.x()}, ${c.z()})  core=${c.core()}"))
            i++
        }
        if (room.data.secrets() > 0)
            source.sendFeedback(line("rooms.json says: ${room.data.secrets()} secrets, ${room.data.crypts()} crypts"))
        val routes = SecretRouteCatalog.get().routesFor(room.name)
        if (routes.isNotEmpty()) {
            source.sendFeedback(line("Routes recorded: ${routes.size}  (try /skyfinder route show 0)"))
        } else {
            source.sendFeedback(line("No SecretRoutes data for this room yet."))
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
