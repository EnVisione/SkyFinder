package com.enviouse.skyfinder.client.render

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.level.material.FogType
import net.minecraft.world.phys.Vec3

/**
 * Equivalent of SkyHanni's SkyHanniRenderWorldEvent: a small bag of the render context
 * pieces we need (matrices, camera, buffer source, partial tick).
 *
 * The mixin in MixinLevelRenderer constructs one of these every frame, after the translucent
 * chunk pass — that's the moment SkyHanni picked because the camera + view transforms are
 * applied and translucent geometry is already drawn (so lines layer on top correctly).
 */
class RenderWorldContext(
    val matrices: PoseStack,
    val camera: Camera,
    val vertexConsumers: MultiBufferSource.BufferSource,
    val partialTicks: Float,
) {
    val viewerPos: Vec3 get() = camera.position()

    /**
     * Player eye position interpolated for [partialTicks].
     *
     * Used for the "where is the player walking" projection math (nearest point on
     * path, closest waypoint, etc.) — NOT for the line anchor. The anchor uses
     * [cursorPos] so the line starts at the camera (and therefore at the
     * crosshair), regardless of bob / sneak / first-person offsets.
     */
    fun exactPlayerEyeLocation(): Vec3 {
        val mc = Minecraft.getInstance()
        val p = mc.player ?: return camera.position()
        return p.getEyePosition(partialTicks)
    }

    /**
     * Camera position — exactly where the cursor lives in world space. The line's
     * near-end anchor should always start here so it points from the crosshair
     * regardless of view-bobbing, sprint head-tilt, sneak offset, or any other
     * micro-shift between the entity eye and the camera.
     */
    fun cursorPos(): Vec3 = camera.position()

    /**
     * Player view direction (unit vector) interpolated for [partialTicks].
     *
     * Used to anchor the near end of the path in front of the camera rather than
     * in world space — so the start of the line follows where the player is
     * looking, and when they spin around, the line curves around to show them
     * the path is behind them.
     */
    fun playerViewDirection(): Vec3 {
        val p = Minecraft.getInstance().player ?: return Vec3(0.0, 0.0, 1.0)
        return p.getViewVector(partialTicks)
    }

    fun isRenderingUnderwater(): Boolean =
        Minecraft.getInstance().gameRenderer.mainCamera.fluidInCamera == FogType.WATER
}
