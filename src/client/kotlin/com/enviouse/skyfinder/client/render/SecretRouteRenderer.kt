/*
 * SkyFinder — SecretRouteRenderer
 *
 * Step B of the SecretRoutes integration: render a full Zyra-style route in
 * the world. Each route has up to 7 distinct visual elements:
 *
 *   - locations[]   → smooth cyan polyline (the movement path)
 *   - etherwarps[]  → teal pillar box (AOTV/Etherwarp anchors)
 *   - mines[]       → orange box (blocks the player must mine: Superboom/Stonk)
 *   - interacts[]   → yellow box (right-click triggers)
 *   - tnts[]        → red box (TNT placements)
 *   - enderpearls[] → magenta box (enderpearl landing points)
 *   - the final secret marker → large pulsing box, color depends on secretType
 *
 * Every element is independently togglable (Step E will wire the toggles to
 * Cloth Config; for now the toggle bools live on this object and default ON).
 *
 * Rendering API:
 *   - `setPresentation(RoutePresentation)` from the main thread/tick to swap
 *     in a new route (or null to clear).
 *   - `render(ctx, drawer)` called by SkyFinderRenderDispatcher per frame
 *     under an active LineDrawer.draw3D scope.
 *
 * Coordinate system: takes WORLD coordinates only. Call sites are
 * responsible for converting room-local (from SecretRouteCatalog) to world
 * (via DungeonMapDecoder.roomLocalToWorld) before constructing a
 * RoutePresentation.
 */
package com.enviouse.skyfinder.client.render

import com.enviouse.skyfinder.dungeon.SecretRouteCatalog
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.util.concurrent.atomic.AtomicReference

object SecretRouteRenderer {

    // --- per-waypoint-type colors (Step E will let users override via config) ---
    private val LOCATIONS_COLOR  = Color(0,   200, 255, 255)  // cyan
    private val ETHERWARP_COLOR  = Color(0,   220, 200, 255)  // teal
    private val MINES_COLOR      = Color(255, 140, 0,   255)  // orange
    private val INTERACT_COLOR   = Color(255, 230, 0,   255)  // yellow
    private val TNT_COLOR        = Color(255, 50,  50,  255)  // red
    private val ENDERPEARL_COLOR = Color(200, 80,  255, 255)  // magenta

    // --- master toggles (Step E wires these to Cloth Config) ---
    @Volatile var renderRoute       = true
    @Volatile var renderLocations   = true
    @Volatile var renderEtherwarps  = true
    @Volatile var renderMines       = true
    @Volatile var renderInteracts   = true
    @Volatile var renderTnts        = true
    @Volatile var renderEnderpearls = true
    @Volatile var renderSecretGoal  = true

    // Pulse animation for the final-secret marker. Cheap sine wave from a
    // tick counter that the dispatcher could feed us; we just use system
    // time so we don't need to wire that up yet.
    private fun pulseAlpha(): Int {
        val t = (System.currentTimeMillis() % 1500L) / 1500.0
        val s = 0.5 * (1 + kotlin.math.sin(t * 2 * Math.PI))
        return (150 + 105 * s).toInt().coerceIn(0, 255)
    }

    /**
     * What we know about the route currently being rendered. All Vec3s are
     * WORLD block-coord centers (we add +0.5 to each int axis when building
     * this so markers center on the block).
     */
    data class RoutePresentation(
        val locations: List<Vec3>,
        val etherwarps: List<Vec3>,
        val mines: List<Vec3>,
        val interacts: List<Vec3>,
        val tnts: List<Vec3>,
        val enderpearls: List<Vec3>,
        val secretGoal: Vec3?,
        val secretType: SecretRouteCatalog.SecretType,
        val roomName: String,
    )

    private val current = AtomicReference<RoutePresentation?>(null)

    fun setPresentation(p: RoutePresentation?) { current.set(p) }
    fun clear()                                 { current.set(null) }
    fun currentRoom(): String?                  = current.get()?.roomName

    /** Convert a `int[3]` room-local (x,y,z) to a world-space Vec3 center. */
    fun localToWorldCenter(
        local: IntArray,
        cornerWorldX: Long,
        cornerWorldZ: Long,
        rotation: com.enviouse.skyfinder.dungeon.DungeonMapDecoder.RoomDirection,
    ): Vec3 {
        val w = com.enviouse.skyfinder.dungeon.DungeonMapDecoder.roomLocalToWorld(
            local[0], local[1], local[2], cornerWorldX, cornerWorldZ, rotation
        )
        return Vec3(w[0] + 0.5, w[1] + 0.5, w[2] + 0.5)
    }

    fun render(ctx: RenderWorldContext, drawer: LineDrawer) {
        if (!renderRoute) return
        val p = current.get() ?: return

        if (renderLocations && p.locations.size >= 2) {
            drawer.drawPath(p.locations, LOCATIONS_COLOR)
        }
        if (renderEtherwarps) {
            for (v in p.etherwarps) drawBox(drawer, v, 0.6, ETHERWARP_COLOR)
        }
        if (renderMines) {
            for (v in p.mines) drawBox(drawer, v, 0.55, MINES_COLOR)
        }
        if (renderInteracts) {
            for (v in p.interacts) drawBox(drawer, v, 0.55, INTERACT_COLOR)
        }
        if (renderTnts) {
            for (v in p.tnts) drawBox(drawer, v, 0.6, TNT_COLOR)
        }
        if (renderEnderpearls) {
            for (v in p.enderpearls) drawBox(drawer, v, 0.55, ENDERPEARL_COLOR)
        }
        if (renderSecretGoal && p.secretGoal != null) {
            val base = secretColorFor(p.secretType)
            val pulsed = Color(base.red, base.green, base.blue, pulseAlpha())
            drawBox(drawer, p.secretGoal, 0.75, pulsed)
            drawBox(drawer, p.secretGoal, 0.78, pulsed) // double outline = "this is the goal"
        }
    }

    /**
     * Draw a hollow axis-aligned box centered on `center` with half-extent
     * `r` along every axis. 12 line segments — same primitive SkyHanni uses
     * for waypoint markers.
     */
    private fun drawBox(drawer: LineDrawer, center: Vec3, r: Double, color: Color) {
        val x0 = center.x - r; val x1 = center.x + r
        val y0 = center.y - r; val y1 = center.y + r
        val z0 = center.z - r; val z1 = center.z + r
        // 4 bottom edges
        drawer.draw3DLine(Vec3(x0, y0, z0), Vec3(x1, y0, z0), color)
        drawer.draw3DLine(Vec3(x1, y0, z0), Vec3(x1, y0, z1), color)
        drawer.draw3DLine(Vec3(x1, y0, z1), Vec3(x0, y0, z1), color)
        drawer.draw3DLine(Vec3(x0, y0, z1), Vec3(x0, y0, z0), color)
        // 4 top edges
        drawer.draw3DLine(Vec3(x0, y1, z0), Vec3(x1, y1, z0), color)
        drawer.draw3DLine(Vec3(x1, y1, z0), Vec3(x1, y1, z1), color)
        drawer.draw3DLine(Vec3(x1, y1, z1), Vec3(x0, y1, z1), color)
        drawer.draw3DLine(Vec3(x0, y1, z1), Vec3(x0, y1, z0), color)
        // 4 vertical edges
        drawer.draw3DLine(Vec3(x0, y0, z0), Vec3(x0, y1, z0), color)
        drawer.draw3DLine(Vec3(x1, y0, z0), Vec3(x1, y1, z0), color)
        drawer.draw3DLine(Vec3(x1, y0, z1), Vec3(x1, y1, z1), color)
        drawer.draw3DLine(Vec3(x0, y0, z1), Vec3(x0, y1, z1), color)
    }

    private fun secretColorFor(t: SecretRouteCatalog.SecretType): Color = when (t) {
        SecretRouteCatalog.SecretType.CHEST     -> Color(255, 215, 0)    // gold
        SecretRouteCatalog.SecretType.ITEM      -> Color(255, 255, 255)  // white
        SecretRouteCatalog.SecretType.BAT       -> Color(140, 80,  40)   // brown
        SecretRouteCatalog.SecretType.LEVER     -> Color(120, 180, 255)  // light blue
        SecretRouteCatalog.SecretType.WITHER    -> Color(40,  40,  40)   // black-ish
        SecretRouteCatalog.SecretType.INTERACT  -> Color(255, 230, 0)    // yellow
        SecretRouteCatalog.SecretType.SUPERBOOM -> Color(255, 100, 30)   // orange-red
        SecretRouteCatalog.SecretType.STONK     -> Color(180, 220, 0)    // chartreuse
        SecretRouteCatalog.SecretType.ENTRANCE  -> Color(0,   255, 0)    // bright green
        SecretRouteCatalog.SecretType.EXIT      -> Color(0,   220, 120)  // sea green
        SecretRouteCatalog.SecretType.EXITROUTE -> Color(0,   220, 120)
        SecretRouteCatalog.SecretType.FAIRYSOUL -> Color(255, 130, 200)  // pink
        SecretRouteCatalog.SecretType.UNKNOWN   -> Color(200, 200, 200)
    }
}
