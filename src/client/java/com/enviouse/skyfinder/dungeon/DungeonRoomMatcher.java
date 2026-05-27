/*
 * SkyFinder — DungeonRoomMatcher (v2, scoring-based)
 *
 * Identify the specific dungeon room name (e.g. "Red-Blue-4", "Diagonal-4")
 * by fingerprinting observed world blocks against the per-room skeleton
 * arrays in {@link RoomSkeletonRegistry}.
 *
 * Algorithm — SCORING (not elimination):
 *
 *   1. From {@link DungeonMapDecoder#possibleDirections(Room)} get the
 *      candidate rotations (1/2/4 depending on room shape).
 *   2. For every cell in the room's world bounding box at the full vertical
 *      band Y_MIN..Y_MAX, sample the block. Skip if it's not on the legacy
 *      block whitelist or it sits in a doorway region.
 *   3. For each sampled block + each rotation: convert to room-local via
 *      {@link DungeonMapDecoder#worldToRoomLocal}, pack to long, and
 *      `Arrays.binarySearch` every candidate room's skeleton. For each room
 *      that contains the long, INCREMENT that room's score. (We never
 *      eliminate; misses just don't add to the score.)
 *   4. After scanning the whole box, find the (room, rotation) pair with
 *      the highest score. If the best score has a clear lead over second
 *      place AND covers a healthy fraction of total scanned blocks, return
 *      MATCHED. If the lead is thin, return AMBIGUOUS with the top
 *      candidates. If almost no blocks matched anything, return
 *      NO_CANDIDATES (probably wrong category guess from the map decoder).
 *
 * Why scoring instead of DRM's eliminate-on-miss:
 *
 *   - DRM raytraced only blocks visible to the player. Their skeleton is a
 *     subset of "all blocks in the room" (raytrace-time blockers, decoration
 *     placed after baseline, etc.). When DRM eliminated on a miss, the miss
 *     could only come from a visible block — guaranteed to be in the
 *     skeleton if the room were a match.
 *   - We brute-scan the whole bounding box including hidden blocks behind
 *     walls. Many of those blocks aren't in any skeleton. Elimination on
 *     miss wipes every candidate within a few hundred blocks → false
 *     NO_CANDIDATES. Especially bad for tall rooms (Y > 73) where Hypixel
 *     may have edited above-floor blocks since the skeleton was recorded.
 *   - Scoring tolerates the noise. Even with 30% of scanned blocks not in
 *     any skeleton, the right room still wins by a wide margin because it
 *     scores on the OTHER 70%.
 *
 * Wider Y band than v1: we now scan Y=50..120 instead of 66..73. The
 * doorway filter still only applies at Y=66..73 (DRM's range), since
 * doorways are a Y-bound structural artifact.
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DungeonRoomMatcher {

    private DungeonRoomMatcher() {}

    /**
     * Vertical scan band. Wider than v1's 66..73 so tall rooms (Red-Blue-4,
     * the upper-floor 1x4 cages, F7 boss-area-adjacent rooms) get enough
     * sample blocks to be uniquely identified. The lower bound covers
     * Cages-2-style deep cellar secrets (Y=57). The upper bound covers
     * Red-Blue-4-style stacked-level rooms (Y up to ~95).
     */
    public static final int Y_MIN = 50;
    public static final int Y_MAX = 120;

    /**
     * Score thresholds for declaring MATCHED. The best room must
     *   (a) have a score that is at least MIN_BEST_FRACTION of scanned blocks; AND
     *   (b) lead the second-best score by at least LEAD_RATIO (e.g. 1.4 = 40% ahead).
     * Otherwise we return AMBIGUOUS with the top candidates so the caller
     * sees what's competing.
     */
    private static final double MIN_BEST_FRACTION = 0.10;  // ≥10% of scanned blocks must hit
    private static final double LEAD_RATIO        = 1.40;  // best must be 40% > 2nd best

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

    /** Convenience entry: decode map → find current room → identify. */
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
        Map<RoomDirection, long[]> dirCorners = new HashMap<>();
        for (RoomDirection d : directions) {
            long[] c = DungeonMapDecoder.cornerWorldXZ(room, entrance, d);
            if (c != null) dirCorners.put(d, c);
        }

        // Score table keyed by (rotation, roomName). All candidates start at 0.
        // We never eliminate — misses just don't add.
        Map<RoomDirection, Map<String, Integer>> scores = new HashMap<>();
        for (RoomDirection d : directions) {
            Map<String, Integer> m = new HashMap<>(categoryData.size());
            for (String name : categoryData.keySet()) m.put(name, 0);
            scores.put(d, m);
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return new MatchResult(Outcome.NOT_READY, category, null, null, 0, 0,
                List.of("no client level"));
        }

        int scanned = 0;
        for (int wy = Y_MIN; wy <= Y_MAX; wy++) {
            for (long wx = box.minX(); wx <= box.maxX(); wx++) {
                for (long wz = box.minZ(); wz <= box.maxZ(); wz++) {
                    BlockPos pos = new BlockPos((int) wx, wy, (int) wz);
                    BlockState state = level.getBlockState(pos);
                    int id = LegacyBlockId.idFor(state.getBlock());
                    if (id < 0) continue;
                    if (blockPartOfDoorway(pos)) continue;

                    scanned++;

                    for (RoomDirection dir : directions) {
                        long[] corner = dirCorners.get(dir);
                        if (corner == null) continue;
                        int[] local = DungeonMapDecoder.worldToRoomLocal(
                            (int) wx, wy, (int) wz, corner[0], corner[1], dir);
                        long packed = RoomSkeletonRegistry.packBlock(local[0], local[1], local[2], id);
                        Map<String, Integer> roomScores = scores.get(dir);
                        for (Map.Entry<String, Integer> e : roomScores.entrySet()) {
                            long[] arr = categoryData.get(e.getKey());
                            if (arr == null) continue;
                            if (Arrays.binarySearch(arr, packed) >= 0) {
                                e.setValue(e.getValue() + 1);
                            }
                        }
                    }
                }
            }
        }

        // Flatten to a sortable list of (room, rotation, score).
        record Scored(String name, RoomDirection dir, int score) {}
        List<Scored> ranked = new ArrayList<>();
        for (RoomDirection d : directions) {
            for (Map.Entry<String, Integer> e : scores.get(d).entrySet()) {
                if (e.getValue() > 0) {
                    ranked.add(new Scored(e.getKey(), d, e.getValue()));
                }
            }
        }
        ranked.sort(Comparator.comparingInt(Scored::score).reversed());

        if (ranked.isEmpty()) {
            return new MatchResult(Outcome.NO_CANDIDATES, category, null, null, scanned, 0,
                List.of("zero blocks matched any skeleton in category " + category,
                        "(probable causes: wrong category, no calibration, or block IDs missing from LegacyBlockId)"));
        }

        // Build a tidy top-N list for diagnostics regardless of outcome.
        List<String> topCandidates = new ArrayList<>();
        for (int i = 0; i < Math.min(6, ranked.size()); i++) {
            Scored s = ranked.get(i);
            double pct = scanned > 0 ? (100.0 * s.score / scanned) : 0;
            topCandidates.add(String.format("%s [%s] score=%d (%.1f%%)", s.name, s.dir, s.score, pct));
        }

        Scored best = ranked.get(0);
        Scored runnerUp = ranked.size() > 1 ? ranked.get(1) : null;
        int bestScore = best.score;
        int secondScore = runnerUp == null ? 0 : runnerUp.score;
        double bestFrac = scanned > 0 ? (double) bestScore / scanned : 0;
        boolean clearLead = secondScore == 0 || ((double) bestScore / Math.max(1, secondScore)) >= LEAD_RATIO;

        if (bestFrac >= MIN_BEST_FRACTION && clearLead) {
            return new MatchResult(Outcome.MATCHED, category, best.name, best.dir, scanned, 1, topCandidates);
        }
        if (bestFrac < 0.02) {
            // Best room couldn't even score 2% — basically nothing matched.
            return new MatchResult(Outcome.NO_CANDIDATES, category, null, null, scanned, ranked.size(), topCandidates);
        }
        return new MatchResult(Outcome.AMBIGUOUS, category, best.name, best.dir, scanned, ranked.size(), topCandidates);
    }

    /**
     * Translate a DungeonMapDecoder Room into the .skeleton category key
     * used by DRM's directory layout. Puzzle/Trap rooms get their colour
     * category; everything else uses the room's size class. All values are
     * lowercase to match the lowercase-on-disk skeleton folders (1.21.11
     * ResourceLocation rules).
     */
    private static String categoryKeyForRoom(Room room) {
        switch (room.color()) {
            case PURPLE: return "puzzle";
            case ORANGE: return "trap";
            default: /* fall through to size */ break;
        }
        // size() is e.g. "1x1", "1x3", "L-shape", "2x2" — lowercase the L-shape one
        return room.size().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Direct port of DRM RoomDetectionUtils.blockPartOfDoorway. Doorways
     * have the SAME blocks across every room and at the SAME positions on
     * the 32-block grid — sampling them adds noise. Only meaningful in the
     * Y=66..73 band where doorways physically exist.
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
