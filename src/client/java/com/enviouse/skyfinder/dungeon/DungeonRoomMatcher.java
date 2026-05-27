/*
 * SkyFinder — DungeonRoomMatcher
 *
 * Step D — Identify the SPECIFIC dungeon room name (e.g. "Cobble-Wall-Pillar-2",
 * "Diagonal-4") by fingerprinting observed world blocks against the per-room
 * skeleton arrays in {@link RoomSkeletonRegistry}.
 *
 * Algorithm (direct port of DRM RoomDetection.getPossibleRooms):
 *
 *   1. Determine the candidate rotation directions for the current room from
 *      its multi-tile shape (DungeonMapDecoder.possibleDirections).
 *   2. For each cell in the room's world bounding box at floor levels
 *      Y_MIN..Y_MAX, sample the block. If it's whitelisted, also reject if
 *      it falls in the doorway region (DRM's blockPartOfDoorway math).
 *   3. For each sampled block, for each candidate rotation:
 *         - Translate world XYZ to room-local using the rotation's reference
 *           corner.
 *         - Pack (lx, ly, lz, identifier) into a long.
 *         - Binary-search the skeleton array of every still-possible room.
 *         - Keep only rooms whose array contains this long.
 *   4. When exactly 1 room remains across all directions, stop and return it.
 *      If 0 remain, fail. If >1 remain after scanning everything, return the
 *      most-likely candidate (none ideal but better than nothing).
 *
 * This is SYNCHRONOUS (caller invokes from a slash command). For continuous
 * tick-time use later, wrap in a CompletableFuture.
 */
package com.enviouse.skyfinder.dungeon;

import com.enviouse.skyfinder.dungeon.DungeonMapDecoder.EntranceCorners;
import com.enviouse.skyfinder.dungeon.DungeonMapDecoder.Room;
import com.enviouse.skyfinder.dungeon.DungeonMapDecoder.RoomDirection;
import com.enviouse.skyfinder.dungeon.DungeonMapDecoder.RoomWorldBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DungeonRoomMatcher {

    private DungeonRoomMatcher() {}

    // DRM dungeons live in this Y band — floor at 69, doorway top at 73.
    // Scanning a vertical slab through here covers the unique structural
    // features without paying for the whole 70..120 boss-room region.
    public static final int Y_MIN = 66;
    public static final int Y_MAX = 73;

    public enum Outcome { MATCHED, NO_CANDIDATES, AMBIGUOUS, NOT_READY }

    public record MatchResult(
        Outcome outcome,
        String category,
        String roomName,        // null if not matched
        RoomDirection rotation, // null if not matched
        int blocksScanned,
        int remainingCandidates,
        List<String> debugTopCandidates
    ) {}

    /** Convenience entry. Wraps the whole "decode map → find current room → fingerprint" pipeline. */
    public static MatchResult identifyCurrentRoom() {
        if (!RoomSkeletonRegistry.isLoaded()) {
            return new MatchResult(Outcome.NOT_READY, null, null, null, 0, 0, List.of("skeleton registry not loaded"));
        }
        DungeonMapReader.Snapshot snap = DungeonMapReader.read();
        if (snap == null) {
            return new MatchResult(Outcome.NOT_READY, null, null, null, 0, 0, List.of("no map snapshot"));
        }
        DungeonMapDecoder.DecodedMap decoded = DungeonMapDecoder.decode(snap);
        if (decoded == null || decoded.entrance() == null) {
            return new MatchResult(Outcome.NOT_READY, null, null, null, 0, 0, List.of("no entrance decoded"));
        }
        Room room = decoded.currentRoom();
        if (room == null) {
            return new MatchResult(Outcome.NOT_READY, null, null, null, 0, 0, List.of("player not in a discovered room"));
        }
        return identify(room, decoded.entrance());
    }

    public static MatchResult identify(Room room, EntranceCorners entrance) {
        if (!DungeonMapDecoder.isCalibrated()) {
            return new MatchResult(Outcome.NOT_READY, null, null, null, 0, 0,
                List.of("world calibration missing"));
        }
        String category = categoryKeyForRoom(room);
        Map<String, long[]> categoryData = RoomSkeletonRegistry.data().get(category);
        if (categoryData == null || categoryData.isEmpty()) {
            return new MatchResult(Outcome.NOT_READY, category, null, null, 0, 0,
                List.of("no skeleton data for category " + category));
        }
        RoomWorldBox box = DungeonMapDecoder.worldBoxOf(room, entrance);
        if (box == null) {
            return new MatchResult(Outcome.NOT_READY, category, null, null, 0, 0,
                List.of("no world box"));
        }

        List<RoomDirection> directions = DungeonMapDecoder.possibleDirections(room);
        // (direction → reference corner XZ). Computed once, used per block.
        Map<RoomDirection, long[]> dirCorners = new HashMap<>();
        for (RoomDirection d : directions) {
            long[] c = DungeonMapDecoder.cornerWorldXZ(room, entrance, d);
            if (c != null) dirCorners.put(d, c);
        }

        // (direction → remaining candidate room names). Starts with all
        // rooms in the category and gets whittled down each scanned block.
        Map<RoomDirection, List<String>> remaining = new HashMap<>();
        for (RoomDirection d : directions) {
            remaining.put(d, new ArrayList<>(categoryData.keySet()));
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return new MatchResult(Outcome.NOT_READY, category, null, null, 0, 0,
                List.of("no client level"));
        }

        int scanned = 0;
        int doubleCheckedBlocks = 0;
        // Sample the room's full XZ extent at each floor-band Y. We don't
        // need raytracing like DRM did — that was an FPS optimisation for
        // 1.8.9. We're a client mod with cheap chunk access, so scan the
        // whole box exactly once.
        outerLoop:
        for (int wy = Y_MIN; wy <= Y_MAX; wy++) {
            for (long wx = box.minX(); wx <= box.maxX(); wx++) {
                for (long wz = box.minZ(); wz <= box.maxZ(); wz++) {
                    BlockPos pos = new BlockPos((int) wx, wy, (int) wz);
                    BlockState state = level.getBlockState(pos);
                    int id = LegacyBlockId.idFor(state.getBlock());
                    if (id < 0) continue;
                    if (blockPartOfDoorway(pos)) continue;

                    int totalMatching = 0;
                    for (RoomDirection dir : directions) {
                        long[] corner = dirCorners.get(dir);
                        if (corner == null) continue;
                        int[] local = DungeonMapDecoder.worldToRoomLocal(
                            (int) wx, wy, (int) wz, corner[0], corner[1], dir);
                        long packed = RoomSkeletonRegistry.packBlock(local[0], local[1], local[2], id);

                        List<String> candidates = remaining.get(dir);
                        List<String> stillMatching = new ArrayList<>();
                        for (String name : candidates) {
                            long[] arr = categoryData.get(name);
                            if (arr == null) continue;
                            if (Arrays.binarySearch(arr, packed) >= 0) {
                                stillMatching.add(name);
                            }
                        }
                        remaining.put(dir, stillMatching);
                        totalMatching += stillMatching.size();
                    }

                    scanned++;

                    if (totalMatching == 0) {
                        // Every candidate eliminated. This usually means the
                        // category guess was wrong (e.g. a Puzzle room appears
                        // as 1x1 brown on the map for one tick during decode
                        // race) — caller can retry.
                        break outerLoop;
                    }
                    if (totalMatching == 1) {
                        // DRM scans 10 more blocks after a single match to
                        // be sure — copy that behaviour.
                        if (doubleCheckedBlocks >= 10) break outerLoop;
                        doubleCheckedBlocks++;
                    }
                }
            }
        }

        // Find the unique remaining match across all directions.
        String winnerName = null;
        RoomDirection winnerDir = null;
        int totalLeft = 0;
        List<String> topCandidates = new ArrayList<>();
        for (RoomDirection d : directions) {
            List<String> list = remaining.get(d);
            totalLeft += list.size();
            for (String n : list) {
                topCandidates.add(d + ":" + n);
                if (winnerName == null) { winnerName = n; winnerDir = d; }
            }
        }
        if (topCandidates.size() > 6) {
            topCandidates = topCandidates.subList(0, 6);
        }

        if (totalLeft == 0) {
            return new MatchResult(Outcome.NO_CANDIDATES, category, null, null, scanned, 0, topCandidates);
        }
        if (totalLeft == 1) {
            return new MatchResult(Outcome.MATCHED, category, winnerName, winnerDir, scanned, 1, topCandidates);
        }
        return new MatchResult(Outcome.AMBIGUOUS, category, winnerName, winnerDir, scanned, totalLeft, topCandidates);
    }

    /**
     * Translate a DungeonMapDecoder Room into the .skeleton category key
     * used by DRM's directory layout. DRM stores Puzzle/Trap rooms by their
     * specific category names (NOT "1x1") because the rooms themselves are
     * 1x1-shaped but distinguished by colour on the map.
     */
    private static String categoryKeyForRoom(Room room) {
        // Use the colour-derived category when it gives us a more specific
        // skeleton folder than the size.
        switch (room.color()) {
            case PURPLE: return "Puzzle";
            case ORANGE: return "Trap";
            default: /* fall through to size */ break;
        }
        return room.size();  // "1x1" / "1x2" / "L-shape" / etc
    }

    /**
     * Direct port of DRM RoomDetectionUtils.blockPartOfDoorway. Doorways are
     * at fixed positions on the 32-block grid; sampling them confuses the
     * fingerprint because the same doorway blocks appear in every room.
     */
    public static boolean blockPartOfDoorway(BlockPos pos) {
        if (pos.getY() < 66 || pos.getY() > 73) return false;
        int relX = Math.floorMod(pos.getX() - 8, 32);
        int relZ = Math.floorMod(pos.getZ() - 8, 32);
        if (relX >= 13 && relX <= 17) {
            if (relZ <= 2) return true;
            if (relZ >= 28) return true;
        }
        if (relZ >= 13 && relZ <= 17) {
            if (relX <= 2) return true;
            if (relX >= 28) return true;
        }
        return false;
    }
}
