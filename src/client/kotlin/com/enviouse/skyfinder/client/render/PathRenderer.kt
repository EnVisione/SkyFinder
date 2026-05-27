package com.enviouse.skyfinder.client.render

import net.minecraft.world.phys.Vec3
import java.awt.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Smooth path renderer adapted from SkyHanni's PathRenderer + LineDrawer.drawPath.
 *
 * Key difference from the old Catmull-Rom version: we no longer subdivide the
 * polyline. Instead we render each segment as **straight line + quadratic Bezier
 * corner rounding** at every internal waypoint:
 *
 *   For each corner B with neighbors A and C:
 *     p1 = B - normalize(B-A) * cornerRadius   (inset from A toward B)
 *     p3 = B + normalize(C-B) * cornerRadius   (inset from B toward C)
 *     control = B
 *     draw: straight line A-p1, then bezier(p1, control=B, p3)
 *     next iteration starts at p3
 *
 * Crucial property: a quadratic Bezier with control point B and endpoints inset
 * along the two adjacent segments stays **entirely inside the angle** at B —
 * never overshoots into a wall. This is what SkyHanni's drawPath does.
 *
 * Plus the player-anchored Bezier at the near end (curve from below the player
 * up to the path) which we kept from the original.
 */
class PathRenderer(
    private val waypoints: List<Vec3>,
    private val color: Color,
    private val targetLocation: Vec3,
) {

    private val pathPoints: List<Vec3> = waypoints

    private var nearCurveLength: Double = CURVE_RADIUS

    /** Walk the rendered geometry once per frame from inside a LineDrawer.draw3D block. */
    fun render(context: RenderWorldContext, drawer: LineDrawer) {
        if (pathPoints.isEmpty()) return
        val eye = context.exactPlayerEyeLocation()
        updateNearSegment(eye)

        if (pathPoints.size == 1) {
            renderSingleNodeCurve(drawer, eye, pathPoints[0], context.cursorPos())
            return
        }

        val (startPos, nextPathIdx) = projectOntoPath(eye)

        // Build the visible polyline: starts at the player-projection point, then
        // every waypoint from nextPathIdx onward, then the final target.
        val polyline = ArrayList<Vec3>(pathPoints.size - nextPathIdx + 2)
        polyline.add(startPos)
        for (i in nextPathIdx..pathPoints.lastIndex) polyline.add(pathPoints[i])
        if (polyline.last().distanceTo(targetLocation) > 0.01) polyline.add(targetLocation)

        if (polyline.size < 2) return

        // The near-end player-anchored Bezier first.
        //
        // Anchor is now placed at the player's CURSOR (camera eye, in look
        // direction, at eye height) instead of at the chest. The anchor follows
        // the camera so when the player turns the line follows. When the player
        // faces AWAY from the path, the bezier control point is pulled hard to
        // the left or right (whichever side is the shorter turn) so the line
        // visibly wraps around — communicating "you need to do a 180°".
        val curveEnd = findBezierEnd(polyline) ?: return
        val viewFwd = context.playerViewDirection()
        val worldUp = Vec3(0.0, 1.0, 0.0)
        // Right vector in player frame. If the player is looking straight up/down
        // cross with worldUp degenerates — guard with a fallback.
        val rightRaw = viewFwd.cross(worldUp)
        val right = if (rightRaw.lengthSqr() > 1.0e-6) rightRaw.normalize() else Vec3(1.0, 0.0, 0.0)

        // Anchor at cursor: camera position + a short hop along the view direction.
        // We use camera.position(), NOT player.getEyePosition(partialTicks). The
        // entity eye position bobs/jitters with sneak / sprint / view-bobbing
        // micro-offsets, which made the line wander up and down independent of the
        // crosshair. The camera position is exactly where the cursor lives, so the
        // line now starts at the crosshair regardless of player animation state.
        val cursor = context.cursorPos()
        val anchor = cursor + viewFwd * ANCHOR_FORWARD_DIST

        // When the player is very close to (or standing on) the next waypoint, the
        // bezier degenerates to a near-zero-length curve which can look pinched.
        // Skip the wraparound entirely in that case and let the inset-corner path
        // do all the work.
        val distAnchorToCurve = anchor.distanceTo(curveEnd.pos)
        if (distAnchorToCurve < MIN_BEZIER_DIST) {
            val remainingClose = ArrayList<Vec3>(polyline.size + 1)
            remainingClose.add(anchor)
            for (i in curveEnd.nextIdx..polyline.lastIndex) remainingClose.add(polyline[i])
            renderInsetCornerPath(drawer, remainingClose)
            return
        }

        val dirToCurve = (curveEnd.pos - anchor).normalize()
        // forwardness: 1.0 = path is directly ahead, 0.0 = path is 90° to a side,
        // -1.0 = path is directly behind the player.
        val forwardness = viewFwd.x * dirToCurve.x + viewFwd.y * dirToCurve.y + viewFwd.z * dirToCurve.z
        // sideness: + = path is to the player's right, - = to the left. When
        // sideness is near 0 (path directly ahead OR directly behind), default to
        // wrapping right.
        val sideness = right.x * dirToCurve.x + right.y * dirToCurve.y + right.z * dirToCurve.z
        val sideSign = if (sideness >= 0.0) 1.0 else -1.0

        // turnAmount: 0..1. Maps forwardness∈[-1,1] → [0,1] so the swing scales
        // with how far the player needs to turn.
        val turnAmount = ((1.0 - forwardness) * 0.5).coerceIn(0.0, 1.0)
        val deviation = turnAmount * distAnchorToCurve * SIDE_SWING_SCALE

        val midpoint = (anchor + curveEnd.pos) * 0.5
        val controlPoint = midpoint + right * (sideSign * deviation)

        drawer.drawBezier2(anchor, controlPoint, curveEnd.pos, color)

        // From curveEnd onward, render with inset-corner rounding.
        val remaining = ArrayList<Vec3>(polyline.size)
        remaining.add(curveEnd.pos)
        for (i in curveEnd.nextIdx..polyline.lastIndex) remaining.add(polyline[i])
        renderInsetCornerPath(drawer, remaining)
    }

    /**
     * Render a polyline with quadratic-Bezier corner rounding at each internal
     * vertex. Direct port of SkyHanni's LineDrawer.drawPath bezierPoint loop.
     */
    private fun renderInsetCornerPath(drawer: LineDrawer, points: List<Vec3>) {
        if (points.size < 2) return
        if (points.size == 2) {
            drawer.draw3DLine(points[0], points[1], color)
            return
        }
        // Compute ONE inset value per waypoint up front. Both the straight
        // segments and the corner beziers consume from this same array so the
        // straight's end-point and the bezier's start-point land at the EXACT
        // same world position. Previously the straight used per-segment
        // `segLen/3` (capped by CORNER_INSET) while the corner used
        // `min(ab,bc)/3` — when one adjacent segment was long and the other
        // short, the straight inset was capped at CORNER_INSET but the corner
        // inset was the smaller `min(ab,bc)/3`, producing a sub-block gap at
        // the corner that looked transparent.
        val n = points.size
        val insets = DoubleArray(n)
        // Endpoint waypoints have no corner, so their inset is 0 (the straight
        // runs all the way to the endpoint).
        insets[0] = 0.0
        insets[n - 1] = 0.0
        for (i in 1 until n - 1) {
            val ab = points[i - 1].distanceTo(points[i])
            val bc = points[i].distanceTo(points[i + 1])
            insets[i] = min(CORNER_INSET, min(ab, bc) / 3.0)
        }
        // Straight segments now use the per-waypoint inset on EACH end.
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val dir = (b - a).normalize()
            val from = a + dir * insets[i]
            val to = b - dir * insets[i + 1]
            drawer.draw3DLine(from, to, color)
        }
        // Corner beziers — same inset array, so p1/p3 coincide exactly with
        // the adjacent straight segments' to/from points.
        for (i in 1 until points.lastIndex) {
            val b = points[i]
            val dirAB = (b - points[i - 1]).normalize()
            val dirBC = (points[i + 1] - b).normalize()
            val inset = insets[i]
            val p1 = b - dirAB * inset
            val p3 = b + dirBC * inset
            drawer.drawBezier2(p1, b, p3, color, BEZIER_SEGMENTS)
        }
    }

    private fun renderSingleNodeCurve(drawer: LineDrawer, eye: Vec3, node: Vec3, cursor: Vec3) {
        val dir = (node - cursor).normalize()
        // Same cursor-anchored placement as the main path render.
        val anchor = cursor + dir * ANCHOR_FORWARD_DIST
        val scale = anchor.distanceTo(node) * CONTROL_POINT_SCALE
        val control = node - dir * scale
        drawer.drawBezier2(anchor, control, node, color)
    }

    private fun walkTangent(walkPositions: List<Vec3>, startSegIdx: Int, startPos: Vec3): Vec3 {
        var remaining = TANGENT_LOOKAHEAD
        var prev = startPos
        for (i in startSegIdx until walkPositions.size) {
            val next = walkPositions[i]
            val d = prev.distanceTo(next)
            if (d >= remaining) {
                return (prev + (next - prev).normalize() * remaining - startPos).normalize()
            }
            remaining -= d
            prev = next
        }
        return if (prev.distanceToSqr(startPos) > 1.0e-4) (prev - startPos).normalize()
        else (walkPositions.last() - walkPositions[walkPositions.lastIndex - 1]).normalize()
    }

    private data class CurveEnd(val pos: Vec3, val tangent: Vec3, val nextIdx: Int)

    private fun findBezierEnd(walkPositions: List<Vec3>): CurveEnd? {
        var totalDist = 0.0
        var result: CurveEnd? = null
        for (i in 1..walkPositions.lastIndex) {
            val segStart = walkPositions[i - 1]
            val segEnd = walkPositions[i]
            val segLen = segStart.distanceTo(segEnd)
            val remaining = nearCurveLength - totalDist
            if (segLen >= remaining) {
                val endPos = segStart + (segEnd - segStart).normalize() * remaining
                return CurveEnd(endPos, walkTangent(walkPositions, i, endPos), i)
            }
            totalDist += segLen
            result = CurveEnd(segEnd, (segEnd - segStart).normalize(), i + 1)
        }
        return result
    }

    private fun projectOntoPath(eye: Vec3): Pair<Vec3, Int> {
        var bestDistSq = Double.MAX_VALUE
        var bestPos = pathPoints[0]
        var bestNextIdx = 1
        for (i in 0 until pathPoints.lastIndex) {
            val proj = eye.nearestPointOnLine(pathPoints[i], pathPoints[i + 1])
            val distSq = eye.distanceToSqr(proj)
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestPos = proj
                bestNextIdx = i + 1
            }
        }
        return bestPos to bestNextIdx
    }

    private fun updateNearSegment(eye: Vec3) {
        val closestIdx = findClosestIndex(eye)
        var totalDist = 0.0
        for (i in (closestIdx + 1)..pathPoints.lastIndex) {
            totalDist += pathPoints[i - 1].distanceTo(pathPoints[i])
            if (totalDist >= CURVE_RADIUS) {
                totalDist = CURVE_RADIUS
                break
            }
        }
        nearCurveLength = max(totalDist, 1.0)
    }

    private fun findClosestIndex(reference: Vec3): Int =
        pathPoints.indices.minBy { pathPoints[it].distanceTo(reference) }

    companion object {
        private const val CURVE_RADIUS = 8.0
        private const val ANCHOR_FORWARD_DIST = 0.5
        private const val CONTROL_POINT_SCALE = 0.5
        private const val TANGENT_LOOKAHEAD = 1.5

        /**
         * Minimum anchor-to-curve-end distance before we draw the wraparound bezier.
         * Below this, the bezier collapses to a pinched stub that looks bad — skip
         * it and just continue the polyline straight from the anchor.
         */
        private const val MIN_BEZIER_DIST = 1.5

        /**
         * How far back from each corner we inset before starting the rounding bezier.
         * Larger = softer corners that swing farther INSIDE the angle (away from the
         * outside wall of the L-turn). 1.0 gives noticeably rounder corners and pulls
         * the bezier midpoint about 0.35 blocks inside the corner — keeps the curve
         * clear of corner geometry even at close range.
         */
        private const val CORNER_INSET = 1.0
        private const val BEZIER_SEGMENTS = 12

        /**
         * Sideways swing scale for the near-end bezier control point. Multiplied by
         * (1 - forwardness) and by the distance to the curve end, so when the
         * player is facing the path the curve is straight and when the player is
         * 180° off, the control point swings out by ~SIDE_SWING_SCALE * distance,
         * making the line visibly wrap around to indicate "turn around".
         */
        private const val SIDE_SWING_SCALE = 0.6
    }
}
