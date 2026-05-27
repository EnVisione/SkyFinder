/*
 * SkyFinder — DungeonMapDecoder
 *
 * Step B of dungeon-room detection: take the raw 128×128 colour grid from
 * DungeonMapReader, find the entrance room's corners (which calibrate the
 * room scaling for this floor), then walk the grid to discover every room
 * with its colour, multi-tile shape, size class, and category.
 *
 * Algorithm + colour constants lifted from DRM's MapUtils.java
 * (Quantizr/DungeonRoomsMod, GPL-3.0). DRM's 1.8.9 colour constants verified
 * against Leo's live `/skyfinder map raw` dumps on 1.21.11 — palette is
 * identical, so the switch tables and entrance-corner logic port verbatim.
 *
 * Coordinate convention (matches DungeonMapReader.Snapshot):
 *   pixels[x][y]  — x ∈ [0,127], y ∈ [0,127]
 *   y = 0 is the TOP of the in-game map (north).
 */
package com.enviouse.skyfinder.dungeon;

import com.enviouse.skyfinder.dungeon.DungeonMapReader.Snapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.item.MapItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class DungeonMapDecoder {

    private DungeonMapDecoder() {}

    // ============================================================
    // Colour palette (RGB values verified live on Hypixel 1.21.11)
    // ============================================================

    private static final int COLOR_BROWN  = 0x72431B; // regular rooms
    private static final int COLOR_PURPLE = 0xB24CD8; // Puzzle
    private static final int COLOR_YELLOW = 0xE5E533; // Miniboss / Fairy souls
    private static final int COLOR_GREEN  = 0x007C00; // Entrance
    private static final int COLOR_PINK   = 0xF27FA5; // Fairy room
    private static final int COLOR_ORANGE = 0xD87F33; // Trap
    private static final int COLOR_RED    = 0xFF0000; // Blood

    public enum RoomColor {
        BROWN, PURPLE, YELLOW, GREEN, PINK, ORANGE, RED, UNDEFINED;
        public char glyph() {
            switch (this) {
                case BROWN:  return 'B';
                case PURPLE: return 'P';
                case YELLOW: return 'Y';
                case GREEN:  return 'G';
                case PINK:   return 'F';   // Fairy
                case ORANGE: return 'T';   // Trap
                case RED:    return 'X';   // blood / X marks the spot
                default:     return '?';
            }
        }
    }

    public static RoomColor classifyColor(int rgb) {
        switch (rgb) {
            case COLOR_BROWN:  return RoomColor.BROWN;
            case COLOR_PURPLE: return RoomColor.PURPLE;
            case COLOR_YELLOW: return RoomColor.YELLOW;
            case COLOR_GREEN:  return RoomColor.GREEN;
            case COLOR_PINK:   return RoomColor.PINK;
            case COLOR_ORANGE: return RoomColor.ORANGE;
            case COLOR_RED:    return RoomColor.RED;
            default:           return RoomColor.UNDEFINED;
        }
    }

    /** Hypixel SkyBlock dungeon "kind" of a room — purely cosmetic enum. */
    public enum RoomCategory {
        ENTRANCE, PUZZLE, TRAP, FAIRY, BLOOD, MINIBOSS, NORMAL,
        ONE_BY_TWO, ONE_BY_THREE, ONE_BY_FOUR, TWO_BY_TWO, L_SHAPE, UNDEFINED
    }

    // ============================================================
    // Records returned from the decoder
    // ============================================================

    public record MapPoint(int x, int y) {}

    /** Entrance room's left and right NW corners on the map. Calibrates room grid scaling per-floor. */
    public record EntranceCorners(MapPoint left, MapPoint right, int roomWidthAndGap, int roomWidth) {}

    /** A discovered room: its NW map-corner, colour, all multi-tile segments (NW corners of each), size category and Hypixel category. */
    public record Room(
        MapPoint nwMapCorner,
        RoomColor color,
        List<MapPoint> segments,
        String size,           // "1x1", "1x2", "1x3", "1x4", "2x2", "L-shape", "undefined"
        RoomCategory category
    ) {}

    public record DecodedMap(
        EntranceCorners entrance,
        List<Room> rooms,
        MapPoint playerMarker,           // null if no marker on map
        Room currentRoom                 // best-guess room the player is in, null if marker out of range
    ) {}

    // ============================================================
    // Entry point
    // ============================================================

    public static DecodedMap decode(Snapshot snap) {
        if (snap == null) return null;
        int[][] map = snap.pixels;
        EntranceCorners entrance = findEntranceCorners(map);
        if (entrance == null) return new DecodedMap(null, List.of(), null, null);

        List<Room> rooms = findRooms(map, entrance);

        // Try to set the world<->map calibration the first time we see a
        // decoded entrance while the player is in Catacombs. Idempotent
        // after the first successful call.
        tryCalibrate(entrance);

        MapPoint marker = playerMarker(snap.mapId, entrance);
        Room currentRoom = marker == null ? null : roomAt(rooms, marker, entrance);
        return new DecodedMap(entrance, rooms, marker, currentRoom);
    }

    // ============================================================
    // Entrance corner detection
    // ============================================================

    /**
     * Find the two NW corners of the green entrance room. DRM scans for a
     * green pixel that has a blank pixel directly above it, then checks
     * left/right neighbours to disambiguate the two corner pixels. The
     * distance between them gives us the room width on this particular floor.
     *
     * Direct port of MapUtils.entranceMapCorners(map).
     */
    public static EntranceCorners findEntranceCorners(int[][] map) {
        if (map == null) return null;
        MapPoint left = null, right = null;
        outer:
        for (int x = 1; x < 127; x++) {
            for (int y = 1; y < 127; y++) {
                if (map[x][y] != COLOR_GREEN) continue;
                if (map[x][y - 1] != 0) continue; // row above must be blank
                if (map[x - 1][y] == 0) {
                    if (left == null) left = new MapPoint(x, y);
                } else if (map[x + 1][y] == 0) {
                    if (right == null) right = new MapPoint(x, y);
                }
                if (left != null && right != null) break outer;
            }
        }
        if (left == null || right == null) return null;
        int roomWidth = right.x - left.x + 1;   // +1 to include the left corner pixel
        int roomWidthAndGap = roomWidth + 4;     // +4 for the inter-room gap
        return new EntranceCorners(left, right, roomWidthAndGap, roomWidth);
    }

    // ============================================================
    // Per-pixel map → nearest room NW corner snap
    // ============================================================

    /**
     * Given any map pixel coordinate, snap it to the NW corner of the room
     * grid cell it belongs to. Direct port of MapUtils.getClosestNWMapCorner.
     */
    public static MapPoint getClosestNWMapCorner(MapPoint mapPos, EntranceCorners e) {
        int rwag = e.roomWidthAndGap;
        int originX = e.left.x % rwag;
        int originY = e.left.y % rwag;

        int shiftedX = mapPos.x + 2;  // +2 so room borders are evenly split
        int shiftedY = mapPos.y + 2;

        int x = shiftedX - (shiftedX % rwag) + originX;
        int y = shiftedY - (shiftedY % rwag) + originY;

        if (x > shiftedX) x -= rwag;
        if (y > shiftedY) y -= rwag;

        return new MapPoint(x, y);
    }

    // ============================================================
    // Room discovery
    // ============================================================

    /**
     * Walk every grid cell on the map and discover all coloured rooms. Each
     * room is flood-filled through neighbouring brown segments to find its
     * multi-tile shape (1x2, L-shape, 2x2, etc.).
     */
    public static List<Room> findRooms(int[][] map, EntranceCorners e) {
        List<Room> rooms = new ArrayList<>();
        Set<MapPoint> visitedCorners = new HashSet<>();

        // Build a candidate set of NW corners by scanning every pixel and
        // snapping to its grid cell. We dedupe via visitedCorners so each
        // grid cell is only inspected once.
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                int rgb = map[x][y];
                if (rgb == 0) continue;
                RoomColor color = classifyColor(rgb);
                if (color == RoomColor.UNDEFINED) continue;

                MapPoint corner = getClosestNWMapCorner(new MapPoint(x, y), e);
                if (visitedCorners.contains(corner)) continue;

                // Re-check the corner pixel's colour. Some non-room pixels
                // (player marker, room border decoration) snap to a NW corner
                // that doesn't actually hold a coloured room.
                if (corner.x < 0 || corner.y < 0 || corner.x >= 128 || corner.y >= 128) continue;
                int cornerColor = map[corner.x][corner.y];
                RoomColor cornerClassified = classifyColor(cornerColor);
                if (cornerClassified == RoomColor.UNDEFINED) continue;

                // Flood fill brown rooms; coloured non-brown rooms are always
                // 1x1 by Hypixel convention (Puzzle/Trap/Entrance/Fairy/Blood/Miniboss).
                List<MapPoint> segments = new ArrayList<>();
                if (cornerClassified == RoomColor.BROWN) {
                    neighbouringBrownSegments(corner, map, e, segments);
                } else {
                    segments.add(corner);
                }
                segments.forEach(visitedCorners::add);

                String size = roomSize(segments);
                RoomCategory cat = roomCategory(size, cornerClassified);
                rooms.add(new Room(corner, cornerClassified, segments, size, cat));
            }
        }
        return rooms;
    }

    /**
     * Recursive flood fill across brown room segments. Direct port of
     * MapUtils.neighboringSegments. NOT thread-safe (uses recursion with
     * mutable list).
     */
    private static void neighbouringBrownSegments(
        MapPoint origin, int[][] map, EntranceCorners e, List<MapPoint> out
    ) {
        if (out.contains(origin)) return;
        out.add(origin);

        int roomWidth = e.roomWidth;

        int[][] check = {
            {origin.x, origin.y - 1},          // up
            {origin.x, origin.y + roomWidth},  // down
            {origin.x - 1, origin.y},          // left
            {origin.x + roomWidth, origin.y},  // right
        };
        int[][] transform = {
            {origin.x, origin.y - 1 - 4},
            {origin.x, origin.y + roomWidth + 4},
            {origin.x - 1 - 4, origin.y},
            {origin.x + roomWidth + 4, origin.y},
        };

        for (int i = 0; i < 4; i++) {
            int cx = check[i][0], cy = check[i][1];
            if (cx < 0 || cy < 0 || cx >= 128 || cy >= 128) continue;
            if (map[cx][cy] != COLOR_BROWN) continue;
            MapPoint newCorner = getClosestNWMapCorner(new MapPoint(transform[i][0], transform[i][1]), e);
            if (!out.contains(newCorner)) {
                neighbouringBrownSegments(newCorner, map, e, out);
            }
        }
    }

    /** Direct port of MapUtils.roomSize — categorize by segment count + geometry. */
    public static String roomSize(List<MapPoint> segments) {
        if (segments.size() == 1) return "1x1";
        if (segments.size() == 2) return "1x2";
        HashSet<Integer> xs = new HashSet<>();
        HashSet<Integer> ys = new HashSet<>();
        for (MapPoint p : segments) { xs.add(p.x); ys.add(p.y); }
        if (segments.size() == 3) {
            return (xs.size() == 2 && ys.size() == 2) ? "L-shape" : "1x3";
        }
        if (segments.size() == 4) {
            return (xs.size() == 2 && ys.size() == 2) ? "2x2" : "1x4";
        }
        return "undefined";
    }

    /** Direct port of MapUtils.roomCategory + extension for Blood/Fairy. */
    public static RoomCategory roomCategory(String size, RoomColor color) {
        if ("1x1".equals(size)) {
            switch (color) {
                case BROWN:  return RoomCategory.NORMAL;
                case PURPLE: return RoomCategory.PUZZLE;
                case ORANGE: return RoomCategory.TRAP;
                case GREEN:  return RoomCategory.ENTRANCE;
                case RED:    return RoomCategory.BLOOD;
                case PINK:   return RoomCategory.FAIRY;
                case YELLOW: return RoomCategory.MINIBOSS;
                default:     return RoomCategory.UNDEFINED;
            }
        }
        switch (size) {
            case "1x2":     return RoomCategory.ONE_BY_TWO;
            case "1x3":     return RoomCategory.ONE_BY_THREE;
            case "1x4":     return RoomCategory.ONE_BY_FOUR;
            case "2x2":     return RoomCategory.TWO_BY_TWO;
            case "L-shape": return RoomCategory.L_SHAPE;
            default:        return RoomCategory.UNDEFINED;
        }
    }

    // ============================================================
    // World <-> map calibration
    //
    // Hypixel's dungeon map is custom-rendered server-side; its centerX,
    // centerZ, and scale fields are NOT consistent with vanilla MC's
    // "pixel(64,64) = world(centerX,centerZ) at 1<<scale blocks per pixel"
    // contract. So we can't project world->pixel using the map's fields.
    //
    // Instead we do what DRM and SkyHanni effectively do: work in WORLD
    // space using the fact that every Hypixel dungeon room is aligned to a
    // 32-block world grid offset by -8. The map only tells us room colour
    // and multi-tile shape. To bridge world->map for the ASCII viz, we
    // snapshot ONE calibration point (the player's world NW corner the
    // first time we're in Catacombs with a decoded entrance), and from
    // then on all world->pixel conversions are anchored on that.
    // ============================================================

    /** Snap a world (x, z) to its 32-block-grid NW corner. Direct port of DRM's getClosestNWPhysicalCorner. */
    public static long[] worldNwCorner(double worldX, double worldZ) {
        double shiftedX = worldX + 0.5 + 8;
        double shiftedZ = worldZ + 0.5 + 8;
        long x = (long) (shiftedX - Math.floorMod((long) shiftedX, 32L));
        long z = (long) (shiftedZ - Math.floorMod((long) shiftedZ, 32L));
        return new long[]{x - 8, z - 8};
    }

    // Calibration snapshot: world NW corner of the entrance room.
    // Set once per dungeon, cleared on world disconnect.
    private static volatile long calibEntranceWorldNwX = Long.MIN_VALUE;
    private static volatile long calibEntranceWorldNwZ = Long.MIN_VALUE;

    /** Reset world<->map calibration (call on world disconnect). */
    public static void resetCalibration() {
        calibEntranceWorldNwX = Long.MIN_VALUE;
        calibEntranceWorldNwZ = Long.MIN_VALUE;
    }

    public static boolean isCalibrated() {
        return calibEntranceWorldNwX != Long.MIN_VALUE;
    }

    public static long[] calibration() {
        return new long[]{calibEntranceWorldNwX, calibEntranceWorldNwZ};
    }

    /**
     * If we don't yet have a calibration and conditions are right, snapshot
     * the player's current world NW corner as the entrance world NW. Conditions:
     *   - inCatacombs is true (per HypixelLocationDetector),
     *   - we have a decoded entrance on the map,
     *   - player is reasonably close to a known room.
     *
     * Heuristic: the first frame we see the player IN Catacombs with the map
     * showing an entrance, the player is by definition in or extremely near
     * the entrance room (Hypixel spawns you there). Snapshotting that frame's
     * world NW is correct.
     */
    public static void tryCalibrate(EntranceCorners e) {
        if (isCalibrated() || e == null) return;
        if (!HypixelLocationDetector.inCatacombs()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long[] nw = worldNwCorner(mc.player.getX(), mc.player.getZ());
        calibEntranceWorldNwX = nw[0];
        calibEntranceWorldNwZ = nw[1];
    }

    /**
     * Convert a world NW corner (32-grid aligned) into the map pixel of that
     * room's NW corner. Returns null if not calibrated.
     */
    public static MapPoint worldNwToMapNw(long worldNwX, long worldNwZ, EntranceCorners e) {
        if (!isCalibrated() || e == null) return null;
        long dx = (worldNwX - calibEntranceWorldNwX) / 32L; // grid offset east of entrance
        long dz = (worldNwZ - calibEntranceWorldNwZ) / 32L; // grid offset south of entrance
        int rwag = e.roomWidthAndGap;
        int px = (int) (e.left.x + dx * rwag);
        int py = (int) (e.left.y + dz * rwag);
        return new MapPoint(px, py);
    }

    /**
     * Inverse of {@link #worldNwToMapNw}: given a discovered room's NW map
     * pixel, return that room's NW world corner (XZ). Returns null if not
     * calibrated. Useful for handing world coordinates to the renderer /
     * .skeleton fingerprinter (Step D).
     */
    public static long[] mapNwToWorldNw(MapPoint mapNw, EntranceCorners e) {
        if (!isCalibrated() || e == null || mapNw == null) return null;
        int rwag = e.roomWidthAndGap;
        long dx = (mapNw.x - e.left.x) / rwag;
        long dz = (mapNw.y - e.left.y) / rwag;
        return new long[]{
            calibEntranceWorldNwX + dx * 32L,
            calibEntranceWorldNwZ + dz * 32L
        };
    }

    // ============================================================
    // Room world bounding box + rotation transforms (Step C — DRM
    // MapUtils.getPhysicalCornerPos + actualToRelative ports)
    // ============================================================

    /** Cardinal direction of a room — "which corner is its NW in local space". Hypixel rooms can be rotated 0/90/180/270°. */
    public enum RoomDirection { NW, NE, SE, SW }

    /** A room's footprint in WORLD space (XZ inclusive bounds at floor level). */
    public record RoomWorldBox(
        long minX, long maxX, long minZ, long maxZ
    ) {
        public boolean contains(double x, double z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /**
     * Given a discovered room (with all multi-tile segments), return its
     * world-space bounding box. Each segment is a 32×32 grid cell whose
     * room footprint is 31×31 blocks (last block is the gap), but DRM/
     * Hypixel treats the dungeon room as the 31×31 region from world NW
     * corner to world NW + 30 inclusive — see MapUtils.getPhysicalCornerPos
     * where they use `+ 30` to reach the SE corner.
     */
    public static RoomWorldBox worldBoxOf(Room room, EntranceCorners e) {
        if (room == null || e == null || !isCalibrated()) return null;
        long minX = Long.MAX_VALUE, maxX = Long.MIN_VALUE;
        long minZ = Long.MAX_VALUE, maxZ = Long.MIN_VALUE;
        for (MapPoint seg : room.segments) {
            long[] nw = mapNwToWorldNw(seg, e);
            if (nw == null) return null;
            if (nw[0] < minX) minX = nw[0];
            if (nw[1] < minZ) minZ = nw[1];
            // Each segment occupies a 31×31 block region from its NW corner.
            long segMaxX = nw[0] + 30;
            long segMaxZ = nw[1] + 30;
            if (segMaxX > maxX) maxX = segMaxX;
            if (segMaxZ > maxZ) maxZ = segMaxZ;
        }
        return new RoomWorldBox(minX, maxX, minZ, maxZ);
    }

    /**
     * Compute the rotation directions DRM would consider when matching a
     * room. Direct port of MapUtils.possibleDirections.
     *
     *   1x1 / 2x2 → all 4 directions must be tried.
     *   L-shape   → exactly 1 direction (the missing corner uniquely
     *               identifies which way the L faces).
     *   1xN       → 2 directions (depends on whether the segments are
     *               aligned east-west or north-south).
     */
    public static List<RoomDirection> possibleDirections(Room room) {
        List<RoomDirection> out = new ArrayList<>();
        if (room == null) return out;
        String size = room.size;
        if ("1x1".equals(size) || "2x2".equals(size)) {
            out.add(RoomDirection.NW);
            out.add(RoomDirection.NE);
            out.add(RoomDirection.SE);
            out.add(RoomDirection.SW);
            return out;
        }
        TreeSet<Integer> xs = new TreeSet<>();
        TreeSet<Integer> ys = new TreeSet<>();
        for (MapPoint p : room.segments) { xs.add(p.x); ys.add(p.y); }
        if ("L-shape".equals(size)) {
            List<Integer> xList = new ArrayList<>(xs);
            List<Integer> yList = new ArrayList<>(ys);
            MapPoint c00 = new MapPoint(xList.get(0), yList.get(0));
            MapPoint c01 = new MapPoint(xList.get(0), yList.get(1));
            MapPoint c10 = new MapPoint(xList.get(1), yList.get(0));
            MapPoint c11 = new MapPoint(xList.get(1), yList.get(1));
            if (!room.segments.contains(c00))      out.add(RoomDirection.SW);
            else if (!room.segments.contains(c01)) out.add(RoomDirection.SE);
            else if (!room.segments.contains(c10)) out.add(RoomDirection.NW);
            else if (!room.segments.contains(c11)) out.add(RoomDirection.NE);
            return out;
        }
        if (size.startsWith("1x")) {
            if (xs.size() >= 2 && ys.size() == 1) {
                out.add(RoomDirection.NW);
                out.add(RoomDirection.SE);
            } else if (xs.size() == 1 && ys.size() >= 2) {
                out.add(RoomDirection.NE);
                out.add(RoomDirection.SW);
            }
        }
        return out;
    }

    /**
     * Return the world coords of the room's furthest corner in a given
     * cardinal direction. For L-shapes this returns the corner of the
     * containing 2x2 bounding box (which is what DRM does — and what
     * .skeleton-relative coordinates are referenced against).
     */
    public static long[] cornerWorldXZ(Room room, EntranceCorners e, RoomDirection dir) {
        RoomWorldBox box = worldBoxOf(room, e);
        if (box == null) return null;
        switch (dir) {
            case NW: return new long[]{box.minX, box.minZ};
            case NE: return new long[]{box.maxX, box.minZ};
            case SE: return new long[]{box.maxX, box.maxZ};
            case SW: return new long[]{box.minX, box.maxZ};
        }
        return null;
    }

    /**
     * Convert a WORLD block position to its ROOM-LOCAL position, given the
     * room's reference corner and direction (= rotation). Direct port of
     * DRM's MapUtils.actualToRelative.
     *
     * Use this when you have a secret's world coords and want to compare
     * against the room's .skeleton coords (Step D), OR when you have a
     * secret's stored room-local coords and want to render the path to
     * the world position (Step E).
     *
     * NOTE: Y is preserved unchanged — rooms only rotate around the Y axis.
     */
    public static int[] worldToRoomLocal(int worldX, int worldY, int worldZ,
                                          long cornerWorldX, long cornerWorldZ,
                                          RoomDirection dir) {
        long dxw = worldX - cornerWorldX;
        long dzw = worldZ - cornerWorldZ;
        long lx = 0, lz = 0;
        switch (dir) {
            case NW: lx = dxw;  lz = dzw;  break;
            case NE: lx = dzw;  lz = -dxw; break;
            case SE: lx = -dxw; lz = -dzw; break;
            case SW: lx = -dzw; lz = dxw;  break;
        }
        return new int[]{(int) lx, worldY, (int) lz};
    }

    /** Inverse of {@link #worldToRoomLocal}. Port of DRM's MapUtils.relativeToActual. */
    public static int[] roomLocalToWorld(int localX, int localY, int localZ,
                                          long cornerWorldX, long cornerWorldZ,
                                          RoomDirection dir) {
        long wx = 0, wz = 0;
        switch (dir) {
            case NW: wx = cornerWorldX + localX;  wz = cornerWorldZ + localZ; break;
            case NE: wx = cornerWorldX - localZ;  wz = cornerWorldZ + localX; break;
            case SE: wx = cornerWorldX - localX;  wz = cornerWorldZ - localZ; break;
            case SW: wx = cornerWorldX + localZ;  wz = cornerWorldZ - localX; break;
        }
        return new int[]{(int) wx, localY, (int) wz};
    }

    // ============================================================
    // Player marker lookup
    // ============================================================

    /**
     * Return the player's marker position on the map in pixel coordinates
     * (0..127). Strategy chain:
     *
     *  1. **Calibrated world projection** (preferred — DRM/SkyHanni
     *     approach): snap player's world (X, Z) to 32-block grid → use the
     *     entrance calibration to convert to map pixel. ACCURATE because it
     *     works in world space, not on Hypixel's custom-rendered map scale.
     *
     *  2. **Server decoration fallback**: scan `getDecorations()` for a
     *     PLAYER marker. Modern Hypixel doesn't send these, but a future
     *     update might re-enable them.
     *
     * Returns null only if both strategies fail (no calibration AND no
     * decoration, e.g. boss room or fresh-connection race).
     */
    public static MapPoint playerMarker(int mapId, EntranceCorners e) {
        if (mapId < 0) return null;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return null;
        MapItemSavedData saved = MapItem.getSavedData(new MapId(mapId), level);
        if (saved == null) return null;

        // Strategy 1: calibrated world projection. Reports the player's
        // ROOM-NW corner pixel, which is exactly what we need for room
        // lookup.
        if (mc.player != null && isCalibrated() && e != null) {
            long[] worldNw = worldNwCorner(mc.player.getX(), mc.player.getZ());
            MapPoint mp = worldNwToMapNw(worldNw[0], worldNw[1], e);
            if (mp != null && mp.x >= 0 && mp.y >= 0 && mp.x <= 127 && mp.y <= 127) {
                return mp;
            }
        }

        // Strategy 2: server-side decoration (legacy fallback).
        Iterable<MapDecoration> decs = saved.getDecorations();
        if (decs == null) return null;
        for (MapDecoration d : decs) {
            if (d.type() != MapDecorationTypes.PLAYER) continue;
            int px = (d.x() / 2) + 64;
            int py = (d.y() / 2) + 64;
            if (px < 0 || py < 0 || px > 127 || py > 127) return null;
            return new MapPoint(px, py);
        }
        return null;
    }

    /** Back-compat overload (no corners passed → no calibrated lookup, decoration fallback only). */
    public static MapPoint playerMarker(int mapId) {
        return playerMarker(mapId, null);
    }

    /** Diagnostic: list ALL decorations on the cached map (for `/skyfinder map grid`). */
    public static List<String> describeDecorations(int mapId) {
        List<String> out = new ArrayList<>();
        if (mapId < 0) { out.add("(no map id)"); return out; }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) { out.add("(no level)"); return out; }
        MapItemSavedData saved = MapItem.getSavedData(new MapId(mapId), level);
        if (saved == null) { out.add("(no saved data)"); return out; }
        out.add("Map scale=" + saved.scale + " centerX=" + saved.centerX + " centerZ=" + saved.centerZ);
        Iterable<MapDecoration> decs = saved.getDecorations();
        if (decs == null) { out.add("(no decorations iterable)"); return out; }
        int count = 0;
        for (MapDecoration d : decs) {
            String typeName = d.type().unwrapKey().map(k -> k.identifier().toString()).orElse("<unnamed>");
            int px = (d.x() / 2) + 64;
            int py = (d.y() / 2) + 64;
            out.add("  type=" + typeName + " bytePos=(" + d.x() + "," + d.y() + ") pixel=(" + px + "," + py + ") rot=" + d.rot());
            count++;
        }
        if (count == 0) out.add("  (no decorations attached to this map)");
        return out;
    }

    /** Identify which discovered room contains a given pixel-space point. */
    public static Room roomAt(List<Room> rooms, MapPoint p, EntranceCorners e) {
        MapPoint corner = getClosestNWMapCorner(p, e);
        for (Room r : rooms) {
            for (MapPoint seg : r.segments) {
                if (seg.equals(corner)) return r;
            }
        }
        return null;
    }

    // ============================================================
    // ASCII grid rendering (for /skyfinder map grid)
    // ============================================================

    /**
     * Build a small ASCII grid from the decoded rooms. Cells use glyphs from
     * RoomColor (B/P/Y/G/F/T/X). Multi-tile rooms render the SAME glyph in
     * every tile they cover. Empty cells render as `.`. Player position is
     * overlaid with `@`.
     */
    public static List<String> asciiGrid(DecodedMap dm) {
        if (dm == null || dm.entrance == null) {
            return List.of("(no entrance corners detected — not in a dungeon?)");
        }
        EntranceCorners e = dm.entrance;
        // Collect all segments and translate to a small integer grid coord.
        // Grid cell origin = the entrance's left NW corner.
        int rwag = e.roomWidthAndGap;
        Map<String, Character> cells = new LinkedHashMap<>();
        TreeSet<Integer> gxs = new TreeSet<>();
        TreeSet<Integer> gys = new TreeSet<>();
        for (Room r : dm.rooms) {
            char g = r.color.glyph();
            for (MapPoint seg : r.segments) {
                int gx = (seg.x - e.left.x) / rwag;
                int gy = (seg.y - e.left.y) / rwag;
                gxs.add(gx);
                gys.add(gy);
                cells.put(gx + "," + gy, g);
            }
        }
        // Player marker → grid cell (best-effort; may be off the entrance grid for some floors)
        Integer pgx = null, pgy = null;
        if (dm.playerMarker != null) {
            MapPoint corner = getClosestNWMapCorner(dm.playerMarker, e);
            pgx = (corner.x - e.left.x) / rwag;
            pgy = (corner.y - e.left.y) / rwag;
        }
        if (gxs.isEmpty()) return List.of("(no rooms found on map)");

        int minX = gxs.first(), maxX = gxs.last();
        int minY = gys.first(), maxY = gys.last();
        List<String> lines = new ArrayList<>();
        for (int gy = minY; gy <= maxY; gy++) {
            StringBuilder sb = new StringBuilder();
            for (int gx = minX; gx <= maxX; gx++) {
                char ch = cells.getOrDefault(gx + "," + gy, '.');
                if (pgx != null && pgx == gx && pgy != null && pgy == gy && ch != '.') {
                    // Player is in a known room — overlay with lowercase to mark it.
                    sb.append(Character.toLowerCase(ch));
                } else if (pgx != null && pgx == gx && pgy != null && pgy == gy) {
                    sb.append('@');
                } else {
                    sb.append(ch);
                }
                sb.append(' ');
            }
            lines.add(sb.toString().trim());
        }
        return lines;
    }
}
