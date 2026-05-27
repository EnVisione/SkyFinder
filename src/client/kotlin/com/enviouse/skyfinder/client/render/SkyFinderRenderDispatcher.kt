package com.enviouse.skyfinder.client.render

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.rendertype.RenderTypes

/**
 * Single entry point called from MixinLevelRenderer after the translucent pass.
 * Anything that wants to draw in the world registers a [Renderer] here and gets
 * called once per frame with a [RenderWorldContext].
 */
object SkyFinderRenderDispatcher {

    fun interface Renderer {
        fun render(context: RenderWorldContext)
    }

    private val renderers = mutableListOf<Renderer>()

    @JvmStatic
    fun register(renderer: Renderer) {
        renderers.add(renderer)
    }

    @JvmStatic
    fun dispatch(
        matrices: PoseStack,
        camera: Camera,
        vertexConsumers: MultiBufferSource.BufferSource,
        partialTicks: Float,
    ) {
        if (renderers.isEmpty()) return
        val ctx = RenderWorldContext(matrices, camera, vertexConsumers, partialTicks)
        for (r in renderers) {
            try {
                r.render(ctx)
            } catch (t: Throwable) {
                org.slf4j.LoggerFactory.getLogger("SkyFinder").error("Renderer threw", t)
            }
        }
        // Has to match the RenderType LineDrawer submits to (RenderTypes.LINES).
        vertexConsumers.endBatch(RenderTypes.LINES)
    }
}
