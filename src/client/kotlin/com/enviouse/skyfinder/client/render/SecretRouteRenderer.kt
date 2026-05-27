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
    @Volatile var renderSmoothLine  = true
    // Kept for back-compat with command code; chains are no longer drawn.
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
     *
     * As of the "one outline + smooth line" rewrite:
     *   - `locations` is still here but ONLY used as a path-shape hint for the
     *     bezier. We do not draw a polyline through it.
     *   - `activeWaypoint` / `activeType` is the SINGLE waypoint currently
     *     being highlighted (computed by RoutePlayback's sequencer). When null,
     *     the renderer only draws the final secret marker (if any).
     *   - The other chains (etherwarps/mines/interacts/tnts/enderpearls) are
     *     retained on the data class for diagnostics/future use but the
     *     renderer no longer draws them.
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
        /** The one waypoint currently highlighted. NW-bottom integer corner Vec3 (e.g. (45.0, 70.0, 23.0)). */
        val activeWaypoint: Vec3? = null,
        /** What kind of waypoint `activeWaypoint` is — drives color match. */
        val activeType: WaypointType = WaypointType.NONE,
    )

    /** One per visual kind, drives the active-waypoint color + the bezier line color. */
    enum class WaypointType { NONE, ETHERWARP, MINE, INTERACT, TNT, ENDERPEARL, SECRET_GOAL }

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

        // Determine the single highlighted waypoint + its color.
        //   activeWaypoint  → set by RoutePlayback when an intermediate
        //                     waypoint is the current step.
        //   secretGoal      → final block. Used when activeWaypoint == null
        //                     (the last step) OR when activeType is SECRET_GOAL.
        val activePos: Vec3?
        val activeColor: Color
        when {
            p.activeWaypoint != null && p.activeType != WaypointType.NONE -> {
                activePos = p.activeWaypoint
                activeColor = waypointColor(p.activeType, p.secretType)
            }
            p.secretGoal != null -> {
                activePos = p.secretGoal
                activeColor = secretColorFor(p.secretType)
            }
            else -> { activePos = null; activeColor = Color(200, 200, 200) }
        }

        // Smooth bezier line: crosshair (2 blocks in front of eye) → through
        // locations[] as path-shape hint → ending at the active waypoint
        // (block center). Colored to match the active waypoint.
        if (renderSmoothLine && activePos != null) {
            drawSmoothLine(ctx, drawer, p.locations, activePos, activeColor)
        }

        // Single block outline at the active waypoint, type-colored.
        // Pulse the secret-goal one (when activeType is NONE → it's the final).
        if (activePos != null) {
            val color = if (p.activeWaypoint == null) {
                // pulsing for the final goal
                Color(activeColor.red, activeColor.green, activeColor.blue, pulseAlpha())
            } else {
                activeColor
            }
            drawBlockOutline(drawer, activePos, color)
        }
    }

    /** Active-waypoint color per type. SECRET_GOAL defers to per-secret-type color. */
    private fun waypointColor(t: WaypointType, secretType: SecretRouteCatalog.SecretType): Color = when (t) {
        WaypointType.ETHERWARP    -> ETHERWARP_COLOR
        WaypointType.MINE         -> MINES_COLOR
        WaypointType.INTERACT     -> INTERACT_COLOR
        WaypointType.TNT          -> TNT_COLOR
        WaypointType.ENDERPEARL   -> ENDERPEARL_COLOR
        WaypointType.SECRET_GOAL  -> secretColorFor(secretType)
        WaypointType.NONE         -> Color(200, 200, 200)
    }

    /**
     * SkyHanni-style smooth bezier nav line.
     *
     * Starts at the player's CROSSHAIR (eye position + lookAngle × 2 — same
     * formula as SkyHanni's `exactPlayerCrosshairLocation`). Uses the route's
     * `locations[]` as Catmull-Rom control points to give the line a smooth
     * curve through corridors. Terminates at the active waypoint's block
     * center. Colored to match the active waypoint type.
     *
     * Source for the curve math:
     *   - Crosshair anchor: `at.hannibal2.skyhanni.utils.render.WorldRenderUtils.exactPlayerCrosshairLocation`
     *   - Catmull-Rom subdivision: `at.hannibal2.skyhanni.data.navigation.PathRenderer.catmullRomPoint` (LGPL-3.0)
     *   - Quadratic bezier endpoint smoothing: `LineDrawer.drawBezier2` (already in our LineDrawer)
     */
    private fun drawSmoothLine(
        ctx: RenderWorldContext,
        drawer: LineDrawer,
        locations: List<Vec3>,
        target: Vec3,
        color: Color,
    ) {
        val mc = net.minecraft.client.Minecraft.getInstance()
        val player = mc.player ?: return
        val eye = player.getEyePosition(1.0f)
        val look = player.getViewVector(1.0f)
        val crosshair = Vec3(eye.x + look.x * 2.0, eye.y + look.y * 2.0, eye.z + look.z * 2.0)

        // Target center = active waypoint corner + 0.5 in every axis.
        val end = Vec3(target.x + 0.5, target.y + 0.5, target.z + 0.5)

        // Control sequence: crosshair → through each `locations[]` center → end.
        val controlPoints = ArrayList<Vec3>(locations.size + 2)
        controlPoints.add(crosshair)
        controlPoints.addAll(locations)
        controlPoints.add(end)

        // If we only have crosshair → end (no locations to curve through),
        // emit a single quadratic bezier from crosshair through a control
        // point biased forward of the player to the target. That gives the
        // line a "drop forward then arc to the target" feel even on short hops.
        if (controlPoints.size == 2) {
            val mid = Vec3(
                (crosshair.x + end.x) * 0.5,
                kotlin.math.max(crosshair.y, end.y) + 0.5,
                (crosshair.z + end.z) * 0.5,
            )
            drawer.drawBezier2(crosshair, mid, end, color, segments = 30)
            return
        }

        // Otherwise: Catmull-Rom-subdivide through every interior point, then
        // emit the chain as short straight segments (the subdivision is dense
        // enough that they look smooth).
        val smooth = catmullRomSubdivide(controlPoints, stepBlocks = 0.5)
        for (i in 0 until smooth.lastIndex) {
            drawer.draw3DLine(smooth[i], smooth[i + 1], color)
        }
    }

    /** Centripetal Catmull-Rom subdivision over a polyline. Endpoints are
     *  duplicated (Skyhanni's `subdividePositions` trick). */
    private fun catmullRomSubdivide(positions: List<Vec3>, stepBlocks: Double): List<Vec3> {
        if (positions.size < 2) return positions
        val out = ArrayList<Vec3>()
        out.add(positions.first())
        for (i in 0 until positions.lastIndex) {
            val p0 = if (i - 1 >= 0) positions[i - 1] else positions[i]
            val p1 = positions[i]
            val p2 = positions[i + 1]
            val p3 = if (i + 2 <= positions.lastIndex) positions[i + 2] else positions[i + 1]
            val segLen = p1.distanceTo(p2)
            val steps = (segLen / stepBlocks).toInt().coerceAtLeast(1)
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                out.add(catmullRomPoint(p0, p1, p2, p3, t))
            }
        }
        return out
    }

    private fun catmullRomPoint(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Double): Vec3 {
        val t2 = t * t
        val t3 = t2 * t
        val ax = 2.0 * p1.x
        val ay = 2.0 * p1.y
        val az = 2.0 * p1.z
        val bx = (p2.x - p0.x) * t
        val by = (p2.y - p0.y) * t
        val bz = (p2.z - p0.z) * t
        val cx = (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2
        val cy = (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2
        val cz = (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t2
        val dx = (3 * p1.x - p0.x - 3 * p2.x + p3.x) * t3
        val dy = (3 * p1.y - p0.y - 3 * p2.y + p3.y) * t3
        val dz = (3 * p1.z - p0.z - 3 * p2.z + p3.z) * t3
        return Vec3((ax + bx + cx + dx) * 0.5, (ay + by + cy + dy) * 0.5, (az + bz + cz + dz) * 0.5)
    }

    /**
     * Draw a hollow 1x1x1 block outline whose NW-bottom corner is `corner`
     * (an integer block position passed as a Vec3 — x.0, y.0, z.0). This
     * matches SecretRoutes' `RenderTypes.OutlinedBox`: from `(x, y, z)` to
     * `(x+1, y+1, z+1)`, perfectly outlining the single block at that pos.
     */
    private fun drawBlockOutline(drawer: LineDrawer, corner: Vec3, color: Color) {
        val x0 = corner.x;       val x1 = corner.x + 1.0
        val y0 = corner.y;       val y1 = corner.y + 1.0
        val z0 = corner.z;       val z1 = corner.z + 1.0
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
