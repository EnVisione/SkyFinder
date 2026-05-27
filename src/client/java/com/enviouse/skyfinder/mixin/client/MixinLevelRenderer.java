package com.enviouse.skyfinder.mixin.client;

import com.enviouse.skyfinder.client.render.SkyFinderRenderDispatcher;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks LevelRenderer.renderLevel after the translucent chunk pass so we can draw our path
 * with the camera matrix already pushed. This is a near-verbatim port of SkyHanni's
 * MixinReplacementLevelRenderer for 1.21.11, specialized to the >1.21.10 branch
 * (ChunkSectionsToRender#renderGroup picked up a GpuSampler parameter in 1.21.11).
 *
 * Why a mixin instead of fabric-rendering-v1? Fabric API removed WorldRenderEvents in
 * 1.21.9+, and no first-party replacement has shipped as of 1.21.11. SkyHanni's own
 * hook here is the proven pattern.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Unique
    private PoseStack skyfinder$contextMatrixStack;

    @Unique
    private Camera skyfinder$currentCamera;

    @Unique
    private DeltaTracker skyfinder$currentTickCounter;

    @Final
    @Shadow
    private RenderBuffers renderBuffers;

    @Inject(method = "renderLevel", at = @At(value = "HEAD"))
    private void skyfinder$beginRender(
        GraphicsResourceAllocator allocator,
        DeltaTracker tickCounter,
        boolean renderBlockOutline,
        Camera camera,
        Matrix4f positionMatrix,
        Matrix4f matrix4f,
        Matrix4f projectionMatrix,
        GpuBufferSlice fogBuffer,
        Vector4f fogColor,
        boolean renderSky,
        CallbackInfo ci
    ) {
        skyfinder$currentCamera = camera;
        skyfinder$currentTickCounter = tickCounter;
    }

    @WrapOperation(
        method = "method_62214",
        slice = @Slice(from = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", args = "ldc=translucent")),
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V", ordinal = 0)
    )
    private void skyfinder$onTranslucentRender(
        ChunkSectionsToRender instance,
        ChunkSectionLayerGroup group,
        GpuSampler gpuSampler,
        Operation<Void> original
    ) {
        original.call(instance, group, gpuSampler);
        if (skyfinder$contextMatrixStack == null) return;

        SkyFinderRenderDispatcher.dispatch(
            skyfinder$contextMatrixStack,
            skyfinder$currentCamera,
            renderBuffers.bufferSource(),
            skyfinder$currentTickCounter.getGameTimeDeltaPartialTick(true)
        );
        skyfinder$contextMatrixStack = null;
    }

    @ModifyExpressionValue(method = "method_62214", at = @At(value = "NEW", target = "()Lcom/mojang/blaze3d/vertex/PoseStack;"))
    private PoseStack skyfinder$onCreateMatrixStack(PoseStack matrixStack) {
        skyfinder$contextMatrixStack = matrixStack;
        return matrixStack;
    }
}
