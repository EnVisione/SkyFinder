/*
 * Adapted into SkyFinder from yourboykyle/SecretRoutes (BSD-3-Clause).
 * Full license header preserved on the package's source files; see RoomData.java.
 */
package com.enviouse.skyfinder.deps.scanner;

import net.minecraft.core.BlockPos;

import java.util.Set;

public class DungeonRoom {
    public final RoomData data;
    public final Set<RoomComponent> roomComponents;
    public Rotations rotation = Rotations.NONE;
    /** World position of the room's blue-terracotta rotation anchor (the "clay marker"). */
    public BlockPos clayPos = new BlockPos(0, 0, 0);

    public DungeonRoom(RoomData data, Set<RoomComponent> components) {
        this.data = data;
        this.roomComponents = components;
    }

    public String getName() {
        return data.name();
    }
}
