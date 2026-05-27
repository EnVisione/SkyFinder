/*
 * Adapted into SkyFinder from yourboykyle/SecretRoutes (BSD-3-Clause).
 * Full license header preserved on the package's source files; see RoomData.java.
 */
package com.enviouse.skyfinder.deps.scanner;

/**
 * Rotation enum carrying the (x, z) offset from a room component center to
 * the unique blue terracotta marker block that anchors that rotation.
 *
 * For a 1x1 room (31x31 inner footprint), the room components are at the
 * center of the room (xCenter, zCenter). The blue terracotta sits at one of
 * 4 corners — (±15, ±15) from the center. Which corner determines the
 * rotation, and from that we derive the world reference corner for
 * room-local ↔ world coord conversion.
 */
public enum Rotations {
    NORTH(15, 15),
    SOUTH(-15, -15),
    WEST(15, -15),
    EAST(-15, 15),
    NONE(0, 0);

    public final int x;
    public final int z;

    Rotations(int x, int z) {
        this.x = x;
        this.z = z;
    }
}
