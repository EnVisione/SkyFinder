/*
 * SkyFinder — DungeonMapReader
 *
 * Step A of dungeon-room detection: locate the "Magical Map" item the dungeon
 * gives the player on entry, pull its 128×128 colour array, and expose a
 * decoded ARGB grid plus diagnostic helpers.
 *
 * **Detection strategy (matches SkyHanni / Skytils — NOT DRM's slot-8 scan):**
 * The map can be anywhere in the player's inventory (main grid, hotbar,
 * off-hand), and is OFTEN displaced when the player swaps to a weapon like
 * Terminator. DRM's strategy of only checking hotbar slot 8 silently fails in
 * those cases. Instead we:
 *   1. Scan the WHOLE inventory once per call for any filled-map with a
 *      "Magical Map" / "Magic Map" display name.
 *   2. Cache the discovered MapId so subsequent reads don't have to scan again.
 *   3. Use `MapItem.getSavedData(MapId, level)` for the cached id — this works
 *      even when the player is no longer holding the map (the client keeps the
 *      SavedData alive once the server has sent map update packets).
 *   4. Periodically re-validate the cache by sniffing inventory again, so
 *      cross-dungeon resets pick up the new MapId.
 *
 * Algorithm lifted from DRM's MapUtils.updatedMap() (Quantizr/DungeonRoomsMod,
 * GPL-3.0). Mojang-mapping translation notes:
 *   - Items.filled_map           → DataComponents.MAP_ID on the ItemStack
 *                                  (1.20.5+ moved map id from NBT into the
 *                                  component system — get the MapId, then look
 *                                  up the SavedData via MapItem.getSavedData)
 *   - mc.theWorld.getMapStorage  → Minecraft.level (the ClientLevel).
 *   - mapData.colors             → MapItemSavedData.colors (still a byte[16384])
 *   - MapColor.mapColorArray[j/4]
 *       .getMapColor(j & 3)      → MapColor.getColorFromPackedId(int)   ← Mojang already provides the bit-unpack
 *
 * Output coordinate convention matches DRM: map[x][y] where x ∈ [0,127], y ∈ [0,127],
 * with y=0 at the TOP of the map (north). Pixel value is 0x00RRGGBB.
 */
package com.enviouse.skyfinder.dungeon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.List;

public final class DungeonMapReader {

    private DungeonMapReader() {}

    public static final int MAP_SIZE = 128;        // 128×128 pixels
    private static final int MAP_PIXELS = MAP_SIZE * MAP_SIZE; // 16384

    /**
     * How often to fall back to a full-inventory scan when we already have a
     * cached MapId. Re-scanning keeps the cache fresh across runs (new map
     * issued at the start of each Catacombs entry) without paying the scan
     * cost every single tick. 20 = once per second at 20 TPS.
     */
    private static final long RESCAN_INTERVAL_TICKS = 20L;

    /** Most-recently-observed dungeon map id. Volatile because tick thread writes, render thread may read indirectly. */
    private static volatile int cachedMapId = -1;
    private static volatile long lastRescanTick = -RESCAN_INTERVAL_TICKS;
    private static long tickCounter = 0L;

    /** Cached holder for the most recent successful read — diagnostic only. */
    public static final class Snapshot {
        public final int mapId;
        public final int width;
        public final int height;
        public final int[][] pixels;        // [x][y] = 0x00RRGGBB
        public final int nonZeroPixels;
        public final int checksum;          // simple sum-of-pixels for change detection
        public final String displayName;

        Snapshot(int mapId, int[][] pixels, int nonZero, int checksum, String displayName) {
            this.mapId = mapId;
            this.width = MAP_SIZE;
            this.height = MAP_SIZE;
            this.pixels = pixels;
            this.nonZeroPixels = nonZero;
            this.checksum = checksum;
            this.displayName = displayName;
        }
    }

    /** Called once per client tick by SkyFinderClient. Periodically refreshes the cached MapId from inventory. */
    public static void tick() {
        tickCounter++;
        if (tickCounter - lastRescanTick >= RESCAN_INTERVAL_TICKS) {
            lastRescanTick = tickCounter;
            int found = scanInventoryForMagicalMap();
            if (found >= 0) cachedMapId = found;
            // We deliberately DO NOT clear cachedMapId when the scan fails —
            // the map may be temporarily hidden (Terminator selected, GUI open,
            // etc.) and the SavedData is still valid on the client. Cleared
            // only on world unload (resetForWorldChange()).
        }
    }

    /**
     * Reset the per-world calibration. **DOES NOT clear `cachedMapId`** —
     * Hypixel reuses the same MapId (verified: id=1024) across every dungeon
     * instance in a session, so persisting the id lets us read the new
     * dungeon's SavedData even when:
     *   (a) the player joins with full inventory + Terminator selected, so
     *       Hypixel physically can't place the map item anywhere we can scan
     *   (b) the player never holds the map between two dungeon entries.
     * If the cached id ever becomes stale (different dungeon batch, server
     * restart) `read()` will return null and the next inventory scan that
     * succeeds will overwrite it.
     */
    public static void resetForWorldChange() {
        lastRescanTick = -RESCAN_INTERVAL_TICKS;
    }

    /** True iff we currently believe a Magical Map exists somewhere on the player (cached OR live-scanned). */
    public static boolean mapExists() {
        if (cachedMapId >= 0) return true;
        // First-call path: no cache yet, do an immediate scan instead of waiting for tick().
        int found = scanInventoryForMagicalMap();
        if (found >= 0) {
            cachedMapId = found;
            return true;
        }
        return false;
    }

    /** Currently-known map id, or -1. */
    public static int currentMapId() {
        return cachedMapId;
    }

    /**
     * Scan the player's full inventory (main + hotbar + offhand) for a
     * filled-map with a "Magical Map" / "Magic Map" display name. Returns the
     * MapId, or -1 if none found.
     */
    private static int scanInventoryForMagicalMap() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return -1;
        Inventory inv = player.getInventory();
        // getNonEquipmentItems() returns the 36 main+hotbar slots (no armor).
        List<ItemStack> mainAndHotbar = inv.getNonEquipmentItems();
        int found = scanList(mainAndHotbar);
        if (found >= 0) return found;
        // Also check the off-hand explicitly. Some loadouts park the map there.
        ItemStack off = player.getOffhandItem();
        return idIfMagicalMap(off);
    }

    private static int scanList(List<ItemStack> stacks) {
        if (stacks == null) return -1;
        for (ItemStack s : stacks) {
            int id = idIfMagicalMap(s);
            if (id >= 0) return id;
        }
        return -1;
    }

    private static int idIfMagicalMap(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        if (stack.getItem() != Items.FILLED_MAP) return -1;
        if (!hasMagicalMapName(stack)) return -1;
        MapId mid = stack.get(DataComponents.MAP_ID);
        return mid != null ? mid.id() : -1;
    }

    private static boolean hasMagicalMapName(ItemStack stack) {
        if (stack == null) return false;
        String hover = stack.getHoverName().getString();
        if (hover == null) return false;
        return hover.contains("Magical Map") || hover.contains("Magic Map");
    }

    /**
     * Read the current dungeon map and return a decoded snapshot.
     * Returns null if no Magical Map has ever been seen on this client OR if
     * the SavedData for the cached id hasn't been received yet (1-tick race
     * on first dungeon entry).
     */
    public static Snapshot read() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return null;

        // Make sure we have an id before trying to load SavedData.
        if (cachedMapId < 0 && !mapExists()) return null;
        int id = cachedMapId;
        if (id < 0) return null;

        // MapItem.getSavedData(MapId, Level) reads from the client's already-
        // loaded map storage. Works whether or not the player is holding the
        // map — Hypixel sends map update packets while the map is anywhere on
        // the player, and the client keeps the SavedData alive.
        MapItemSavedData saved = MapItem.getSavedData(new MapId(id), level);
        if (saved == null) return null;

        byte[] raw = saved.colors;
        if (raw == null || raw.length < MAP_PIXELS) return null;

        int[][] pixels = new int[MAP_SIZE][MAP_SIZE];
        int nonZero = 0;
        int checksum = 0;
        for (int i = 0; i < MAP_PIXELS; i++) {
            int packed = raw[i] & 0xFF;
            int argb = MapColor.getColorFromPackedId(packed);
            int rgb = argb & 0x00FFFFFF;
            int x = i % MAP_SIZE;
            int y = i / MAP_SIZE;
            pixels[x][y] = rgb;
            if (rgb != 0) {
                nonZero++;
                checksum = (checksum * 31) + rgb + i;
            }
        }
        // Best-effort display name: only available if the player is currently
        // holding the map (otherwise we don't have access to the ItemStack).
        String displayName = "Magical Map (id " + id + ")";
        return new Snapshot(id, pixels, nonZero, checksum, displayName);
    }

    /** Histogram the top-N most common non-zero pixel colours. Used by /skyfinder map raw. */
    public static int[][] topColours(Snapshot snap, int topN) {
        if (snap == null) return new int[0][];
        java.util.HashMap<Integer, Integer> hist = new java.util.HashMap<>();
        for (int x = 0; x < MAP_SIZE; x++) {
            for (int y = 0; y < MAP_SIZE; y++) {
                int c = snap.pixels[x][y];
                if (c == 0) continue;
                hist.merge(c, 1, Integer::sum);
            }
        }
        return hist.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(topN)
            .map(e -> new int[]{e.getKey(), e.getValue()})
            .toArray(int[][]::new);
    }

    /**
     * Diagnostic for `/skyfinder map scan`. Lists every filled-map item the
     * player is carrying with its slot and MapId, so we can tell why the
     * inventory-based detection might have failed (item missing entirely vs
     * present-but-with-unexpected-name).
     */
    public static List<String> describeInventoryMaps() {
        List<String> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) { out.add("(no player)"); return out; }
        Inventory inv = player.getInventory();
        List<ItemStack> main = inv.getNonEquipmentItems();
        int found = 0;
        if (main != null) {
            for (int i = 0; i < main.size(); i++) {
                ItemStack s = main.get(i);
                if (s == null || s.isEmpty()) continue;
                if (s.getItem() != Items.FILLED_MAP) continue;
                found++;
                MapId mid = s.get(DataComponents.MAP_ID);
                String hover = s.getHoverName().getString();
                out.add("  slot[" + i + "] mapId=" + (mid == null ? "<missing>" : mid.id())
                    + " hover=\"" + hover + "\"");
            }
        }
        ItemStack off = player.getOffhandItem();
        if (off != null && !off.isEmpty() && off.getItem() == Items.FILLED_MAP) {
            found++;
            MapId mid = off.get(DataComponents.MAP_ID);
            String hover = off.getHoverName().getString();
            out.add("  offhand mapId=" + (mid == null ? "<missing>" : mid.id())
                + " hover=\"" + hover + "\"");
        }
        if (found == 0) {
            out.add("  (no filled-map items in inventory or offhand)");
        }
        out.add("  cachedMapId=" + cachedMapId);
        return out;
    }
}
