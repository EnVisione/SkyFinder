/*
 * SkyFinder — RoomSkeletonRegistry
 *
 * Loads DRM's .skeleton files at startup. Each .skeleton file is:
 *   InflaterInputStream(InputStream) → ObjectInputStream.readObject() → long[]
 *
 * Each long packs a block's (x, y, z, identifier) via DRM's shortToLong:
 *   ((x << 16) | (y & 0xFFFF)) << 32  |  ((z << 16) | (identifier & 0xFFFF))
 *
 * where x/y/z are ROOM-LOCAL block coords and identifier = blockId * 100 + meta
 * using 1.8.9-era numeric block IDs. We translate modern 1.21.11 blocks to
 * those legacy identifiers in {@link LegacyBlockId}.
 *
 * Arrays are sorted ascending so binary search ($matchingRoom$) is O(log n).
 *
 * Files live at `assets/skyfinder/skeletons/{category}/{roomName}.skeleton`,
 * matching DRM's directory layout exactly (preserved in Phase 1.1 data copy).
 */
package com.enviouse.skyfinder.dungeon;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.InflaterInputStream;

public final class RoomSkeletonRegistry {

    private RoomSkeletonRegistry() {}

    /** "1x1" → ("Cobble-Wall-Pillar-2" → long[]). Mirrors DRM's ROOM_DATA shape. */
    private static final Map<String, Map<String, long[]>> ROOM_DATA = new HashMap<>();

    /** All known DRM category folders. Must match the directory names under skeletons/. */
    private static final String[] CATEGORIES = {
        "1x1", "1x2", "1x3", "1x4", "2x2", "L-shape", "Puzzle", "Trap"
    };

    private static volatile boolean loaded = false;

    public static boolean isLoaded() { return loaded; }
    public static Map<String, Map<String, long[]>> data() { return ROOM_DATA; }

    /**
     * Idempotently load every .skeleton under the mod's assets. Safe to call
     * from a tick — does nothing after the first successful load.
     *
     * Returns the number of rooms loaded, or -1 if load failed.
     */
    public static synchronized int ensureLoaded() {
        if (loaded) return totalRooms();
        Minecraft mc = Minecraft.getInstance();
        ResourceManager rm = mc.getResourceManager();
        if (rm == null) return -1;

        int total = 0;
        for (String category : CATEGORIES) {
            Map<String, long[]> rooms = loadCategory(rm, category);
            ROOM_DATA.put(category, rooms);
            total += rooms.size();
        }
        loaded = true;
        return total;
    }

    private static Map<String, long[]> loadCategory(ResourceManager rm, String category) {
        Map<String, long[]> out = new HashMap<>();
        // Walk every resource under skeletons/{category}/. Modern API:
        // ResourceManager.listResources(prefix, filter) returns map of all
        // matching resources.
        // 1.21.11 rejects trailing slashes on listResources prefix
        // ("Trailing slash in path skeletons/1x1/" IllegalArgumentException).
        String prefix = "skeletons/" + category;
        Map<Identifier, Resource> matches = rm.listResources(prefix, id ->
            id.getNamespace().equals("skyfinder") && id.getPath().endsWith(".skeleton")
        );
        for (Map.Entry<Identifier, Resource> entry : matches.entrySet()) {
            Identifier id = entry.getKey();
            String path = id.getPath();
            // Trim "skeletons/{category}/" prefix and ".skeleton" suffix.
            int slash = path.lastIndexOf('/');
            String fileName = path.substring(slash + 1);
            String roomName = fileName.substring(0, fileName.length() - ".skeleton".length());
            try (InputStream raw = entry.getValue().open();
                 InflaterInputStream zip = new InflaterInputStream(raw);
                 ObjectInputStream ois = new ObjectInputStream(zip)) {
                Object obj = ois.readObject();
                if (obj instanceof long[]) {
                    long[] arr = (long[]) obj;
                    // DRM stores already-sorted, but we sort defensively in
                    // case data is regenerated or hand-edited.
                    java.util.Arrays.sort(arr);
                    out.put(roomName, arr);
                }
            } catch (IOException | ClassNotFoundException e) {
                // Bad single file — skip, keep going.
                System.err.println("[SkyFinder] Failed to load " + path + ": " + e.getMessage());
            }
        }
        return out;
    }

    public static long[] skeletonFor(String category, String roomName) {
        Map<String, long[]> cat = ROOM_DATA.get(category);
        if (cat == null) return null;
        return cat.get(roomName);
    }

    public static int totalRooms() {
        int n = 0;
        for (Map<String, long[]> cat : ROOM_DATA.values()) n += cat.size();
        return n;
    }

    /** DRM's shortToLong — pack (x, y, z, identifier) into a single long. */
    public static long packBlock(int x, int y, int z, int identifier) {
        short a = (short) x;
        short b = (short) y;
        short c = (short) z;
        short d = (short) identifier;
        return ((long) ((a << 16) | (b & 0xFFFF)) << 32)
            | (((c << 16) | (d & 0xFFFF)) & 0xFFFFFFFFL);
    }

    /**
     * Strip the identifier (lower 16 bits) from a packed long, so we can
     * look up "is there ANY block at this XYZ in this skeleton?" via a
     * range search. (Not used by the primary matcher — that uses an exact
     * binarySearch since legacy DRM packs identifier IN the key — but
     * useful for diagnostics like "what was the closest matching block".)
     */
    public static int extractIdentifier(long packed) {
        return ((int) packed) & 0xFFFF;
    }
}
