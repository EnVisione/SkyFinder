/*
 * Adapted into SkyFinder from yourboykyle/SecretRoutes (BSD-3-Clause).
 * Full license header preserved on the package's source files; see RoomData.java.
 */
package com.enviouse.skyfinder.deps.scanner;

import net.minecraft.core.BlockPos;
import org.joml.Vector2i;

/** One 32x32 tile of a (possibly multi-tile) room. (x, z) is the center of the tile in world coords. */
public record RoomComponent(int x, int z, int core) {
    public Vector2i vec2() {
        return new Vector2i(x, z);
    }

    public BlockPos blockPos() {
        return new BlockPos(x, 70, z);
    }
}
