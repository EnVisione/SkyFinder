/*
 * Adapted into SkyFinder from yourboykyle/SecretRoutes (BSD-3-Clause).
 * Full license header preserved on the package's source files; see RoomData.java.
 *
 * Converts between ROOM-LOCAL coords (used in routes.json, recorded against
 * a canonical "south-facing" orientation with corner at (0,0)) and WORLD
 * coords given the room's actual rotation + corner anchor.
 *
 * The "corner" is the world (x, z) of the room's blue-terracotta marker —
 * the unique anchor block that lets us tell which way the room is facing.
 * It's set by DungeonScanner.updateRotation.
 */
package com.enviouse.skyfinder.deps.scanner;

import net.minecraft.core.BlockPos;

import java.awt.Point;

public final class RoomRotationUtils {

    private RoomRotationUtils() {}

    public static BlockPos relativeToActual(BlockPos relative, String rotation, Point corner) {
        if (corner == null) corner = new Point(0, 0);
        if (rotation == null) rotation = "S";

        int cx = (int) corner.getX();
        int cz = (int) corner.getY();

        int rx = relative.getX();
        int ry = relative.getY();
        int rz = relative.getZ();

        return switch (rotation) {
            case "W" -> new BlockPos(cx - rz, ry, cz + rx);
            case "N" -> new BlockPos(cx - rx, ry, cz - rz);
            case "E" -> new BlockPos(cx + rz, ry, cz - rx);
            default  -> new BlockPos(cx + rx, ry, cz + rz);
        };
    }

    /** Double-precision variant for routes that don't quantize to block grid. */
    public static double[] relativeToActual(double rx, double ry, double rz, String rotation, Point corner) {
        if (corner == null) corner = new Point(0, 0);
        if (rotation == null) rotation = "S";

        double cx = corner.getX();
        double cz = corner.getY();

        return switch (rotation) {
            case "W" -> new double[]{cx - rz, ry, cz + rx};
            case "N" -> new double[]{cx - rx, ry, cz - rz};
            case "E" -> new double[]{cx + rz, ry, cz - rx};
            default  -> new double[]{cx + rx, ry, cz + rz};
        };
    }

    public static BlockPos actualToRelative(BlockPos actual, String rotation, Point corner) {
        if (corner == null) corner = new Point(0, 0);
        if (rotation == null) rotation = "S";

        int cx = (int) corner.getX();
        int cz = (int) corner.getY();

        int dx = actual.getX() - cx;
        int dy = actual.getY();
        int dz = actual.getZ() - cz;

        return switch (rotation) {
            case "W" -> new BlockPos(dz, dy, -dx);
            case "N" -> new BlockPos(-dx, dy, -dz);
            case "E" -> new BlockPos(-dz, dy, dx);
            default  -> new BlockPos(dx, dy, dz);
        };
    }

    /** "S" / "W" / "N" / "E" rotation code, or "S" for NONE. */
    public static String rotationCode(Rotations r) {
        if (r == null) return "S";
        return switch (r) {
            case SOUTH -> "S";
            case WEST  -> "W";
            case NORTH -> "N";
            case EAST  -> "E";
            default    -> "S";
        };
    }

    /** Treat the room's clay marker world (x, z) as a 2D anchor for relativeToActual. */
    public static Point cornerOf(DungeonRoom room) {
        if (room == null || room.clayPos == null) return null;
        return new Point(room.clayPos.getX(), room.clayPos.getZ());
    }
}
