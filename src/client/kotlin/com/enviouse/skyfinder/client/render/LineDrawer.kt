package com.enviouse.skyfinder.client.render

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.phys.Vec3
import java.awt.Color

/**
 * 3D line batcher. Mirrors SkyHanni's LineDrawer API surface so the path renderer
 * code reads identically, but uses vanilla RenderTypes.LINES instead of SkyHanni's
 * custom render layer chain (no shader/pipeline porting needed).
 *
 * On 1.21.11 the LINES vertex format requires a per-vertex LineWidth element.
 * We set it on every vertex; otherwise BufferBuilder throws on flush.
 *
 * IMPORTANT: use RenderTypes.LINES (not LINES_TRANSLUCENT). LINES is a
 * pre-allocated fixed buffer on the default BufferSource; LINES_TRANSLUCENT is
 * not, and silently fails to render with no error.
 */
class LineDrawer internal constructor(
    private val context: RenderWorldContext,
    private val lineWidth: Float,
) {

    private val queued = ArrayList<QueuedLine>()

    fun draw3DLine(p1: Vec3, p2: Vec3, color: Color) {
        queued.add(QueuedLine(p1, p2, color))
    }

    fun drawBezier2(p1: Vec3, p2: Vec3, p3: Vec3, color: Color, segments: Int = 30) {
        for (i in 0 until segments) {
            val t1 = i.toFloat() / segments
            val t2 = (i + 1).toFloat() / segments
            draw3DLine(bezierPoint(t1, p1, p2, p3), bezierPoint(t2, p1, p2, p3), color)
        }
    }

    fun drawPath(path: List<Vec3>, color: Color) {
        for (i in 0 until path.lastIndex) {
            draw3DLine(path[i], path[i + 1], color)
        }
    }

    private fun bezierPoint(t: Float, p1: Vec3, p2: Vec3, p3: Vec3): Vec3 {
        val u = 1 - t
        val tt = t * t
        val uu = u * u
        return Vec3(
            uu * p1.x + 2 * u * t * p2.x + tt * p3.x,
            uu * p1.y + 2 * u * t * p2.y + tt * p3.y,
            uu * p1.z + 2 * u * t * p2.z + tt * p3.z,
        )
    }

    internal fun flush() {
        if (queued.isEmpty()) return
        val layer: RenderType = RenderTypes.LINES
        val buf: VertexConsumer = context.vertexConsumers.getBuffer(layer)
        val entry = context.matrices.last()
        for (line in queued) {
            val normal = (line.p2 - line.p1).normalize()
            buf.addVertex(
                entry.pose(),
                line.p1.x.toFloat(), line.p1.y.toFloat(), line.p1.z.toFloat()
            )
                .setColor(line.color.red, line.color.green, line.color.blue, line.color.alpha)
                .setNormal(entry, normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
                .setLineWidth(lineWidth)
            buf.addVertex(
                entry.pose(),
                line.p2.x.toFloat(), line.p2.y.toFloat(), line.p2.z.toFloat()
            )
                .setColor(line.color.red, line.color.green, line.color.blue, line.color.alpha)
                .setNormal(entry, normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
                .setLineWidth(lineWidth)
        }
        queued.clear()
    }

    private data class QueuedLine(val p1: Vec3, val p2: Vec3, val color: Color)

    companion object {
        fun draw3D(
            context: RenderWorldContext,
            lineWidth: Float = 8f,
            draws: LineDrawer.() -> Unit,
        ) {
            context.matrices.pushPose()
            val inv = context.viewerPos.negated()
            context.matrices.translate(inv.x, inv.y, inv.z)
            val drawer = LineDrawer(context, lineWidth)
            drawer.draws()
            drawer.flush()
            context.matrices.popPose()
        }
    }
}
