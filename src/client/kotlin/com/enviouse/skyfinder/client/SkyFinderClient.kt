package com.enviouse.skyfinder.client

import com.enviouse.skyfinder.client.command.SkyFinderCommands
import com.enviouse.skyfinder.client.pathfind.SkyFinderPathfinder
import com.enviouse.skyfinder.client.render.LineDrawer
import com.enviouse.skyfinder.dungeon.DungeonMapReader
import com.enviouse.skyfinder.dungeon.HypixelLocationDetector
import com.enviouse.skyfinder.dungeon.MapMissingHud
import com.enviouse.skyfinder.client.render.PathRenderer
import com.enviouse.skyfinder.client.render.SkyFinderRenderDispatcher
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.KeyMapping
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.awt.Color
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SkyFinder client entrypoint.
 *
 * Test harness for the renderer + A* pathfinder:
 *  - Press R to set a new target 30 blocks east + 1 up of your current position
 *  - Press H to toggle the path display on/off
 *
 * Background behavior:
 *  - The path is computed asynchronously on a daemon thread (never freezes the client)
 *  - **Every second** we re-run the pathfinder to react to placed/broken blocks
 *  - If the new path is ≥80% similar to the current displayed path, we swap it in
 *    (keeps the line responsive to obstacles without abruptly jumping to a shortcut
 *    on a route the player isn't currently on)
 *  - If the current path is *invalid* (something got placed across it), we always
 *    swap regardless of similarity
 */
class SkyFinderClient : ClientModInitializer {

    private val log = LoggerFactory.getLogger("SkyFinder")

    private var showPath = true

    @Volatile
    private var pathRenderer: PathRenderer? = null

    @Volatile
    private var currentRawPath: List<BlockPos>? = null

    @Volatile
    private var currentTarget: BlockPos? = null

    private val pathfindInFlight = AtomicBoolean(false)
    private var lastRecomputeMs = 0L

    private val pathfindExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SkyFinder-Pathfind").apply { isDaemon = true }
    }

    override fun onInitializeClient() {
        log.info("SkyFinder initializing")
        SkyFinderCommands.register()

        // Reset cached map id on world disconnect so the next dungeon entry
        // scans fresh. Without this we'd cling to the previous run's MapId,
        // which would either silently return stale colours or fail silently
        // when Hypixel's new map hasn't been broadcast yet.
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            DungeonMapReader.resetForWorldChange()
            com.enviouse.skyfinder.dungeon.DungeonMapDecoder.resetCalibration()
            MapMissingHud.reset()
        }

        val toggleKey: KeyMapping = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.skyfinder.toggle_path",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_H,
                KeyMapping.Category.MISC,
            )
        )

        val rebuildKey: KeyMapping = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.skyfinder.rebuild_path",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_R,
                KeyMapping.Category.MISC,
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Hypixel location detection runs every tick. Cheap (a few string
            // scans against the scoreboard). All other tick logic only matters
            // when we're actually testing the renderer, but we always want a
            // fresh in/out-of-catacombs flag available for `/skyfinder status`.
            HypixelLocationDetector.tick(client)
            DungeonMapReader.tick()
            MapMissingHud.tick()

            while (toggleKey.consumeClick()) {
                showPath = !showPath
                log.info("SkyFinder path display: {}", if (showPath) "ON" else "OFF")
            }
            while (rebuildKey.consumeClick()) {
                // Manual re-pathfind to the current target (set by external
                // callers — Phase 1.5 will wire this to the next-secret world
                // coord). If nothing has set a target yet, do nothing.
                val target = currentTarget ?: continue
                val player = client.player ?: continue
                val level = client.level ?: continue
                currentRawPath = null
                kickOffPathfind(player.blockPosition(), target, level, forceSwap = true)
            }

            // Periodic re-pathfind every PATH_REFRESH_MS to react to placed/broken
            // blocks. We only swap in the new path when:
            //   - similarity to current is >= 80%  OR
            //   - the current path is invalidated (placed block crosses it)
            //
            // No auto-pathfind on world join anymore — earlier dev builds set a
            // hardcoded target at `start + (30, 1, 0)` so the renderer always
            // had something to draw, but that test fixture is no longer needed
            // and was actively confusing on Hypixel hub / private island where
            // it would silently fire A* every second toward nothing.
            val player = client.player ?: return@register
            val level = client.level ?: return@register
            val now = System.currentTimeMillis()
            if (currentTarget != null && now - lastRecomputeMs >= PATH_REFRESH_MS) {
                lastRecomputeMs = now
                kickOffPathfind(player.blockPosition(), currentTarget!!, level, forceSwap = false)
            }
        }

        SkyFinderRenderDispatcher.register(SkyFinderRenderDispatcher.Renderer { context ->
            if (!showPath) return@Renderer
            val pr = pathRenderer ?: return@Renderer
            LineDrawer.draw3D(context) {
                pr.render(context, this)
            }
        })
    }

    /**
     * Kick off an async A* pathfind, then post-process with string-pull and
     * decide whether to swap it in.
     *
     * @param forceSwap if true, always replace the current displayed path. Used
     *   for the very first pathfind and for explicit user-triggered rebuilds.
     *   if false, apply the similarity / validity gate so periodic background
     *   refreshes don't yank the line onto a different route.
     */
    private fun kickOffPathfind(start: BlockPos, target: BlockPos, level: Level, forceSwap: Boolean) {
        // Don't pile up pathfinds — one in flight at a time.
        if (!pathfindInFlight.compareAndSet(false, true)) return

        CompletableFuture.runAsync({
            try {
                val t0 = System.nanoTime()
                val raw = SkyFinderPathfinder.findPath(level, start, target)
                if (raw == null) {
                    val ms = (System.nanoTime() - t0) / 1_000_000.0
                    log.warn("SkyFinder A* failed for {} -> {} in {}ms, HIDING line (no fallback)", start, target, ms)
                    // No fallback: better to render NOTHING than to draw a
                    // straight line through air/void when A* can't find a real
                    // route. Catches the "showing a path over the void on my
                    // island" bug — the fallback red line was beelining
                    // through open sky and confusing the player.
                    if (forceSwap) {
                        currentRawPath = null
                        pathRenderer = null
                    }
                    return@runAsync
                }
                val pulled = SkyFinderPathfinder.stringPull(level, raw)
                val ms = (System.nanoTime() - t0) / 1_000_000.0

                val previous = currentRawPath
                val playerPos = net.minecraft.client.Minecraft.getInstance().player?.position()
                val shouldSwap = when {
                    forceSwap || previous == null || playerPos == null -> true
                    !SkyFinderPathfinder.pathStillValid(level, previous) -> {
                        log.info("SkyFinder current path invalidated by world change, swapping")
                        true
                    }
                    else -> SkyFinderPathfinder.shouldReplacePath(playerPos, pulled, previous)
                }

                if (!shouldSwap) return@runAsync

                log.info(
                    "SkyFinder pathfind {} -> {} produced {} waypoints, string-pulled to {} in {}ms",
                    start, target, raw.size, pulled.size, ms,
                )
                currentRawPath = pulled
                val waypoints = pulled.map { SkyFinderPathfinder.visualWaypointPosition(level, it) }
                val targetVec = SkyFinderPathfinder.visualWaypointPosition(level, target)
                pathRenderer = PathRenderer(waypoints, Color(0, 200, 255, 255), targetVec)
            } finally {
                pathfindInFlight.set(false)
            }
        }, pathfindExecutor)
    }

    companion object {
        private const val PATH_REFRESH_MS = 1000L
    }
}
