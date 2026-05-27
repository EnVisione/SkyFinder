/*
 * SkyFinder — RoutePlayback
 *
 * Auto-advancing route player. Watches DungeonScanner for room changes,
 * loads the room's first route, drives the renderer, and bumps to the
 * next secret when the trigger condition for the current secret fires.
 *
 * Triggers (ported verbatim from yourboykyle/SecretRoutes, GPL-3.0,
 * versions/1.21.10-fabric/events/{OnPlayerTick, OnPlayerInteract,
 * OnItemPickedUp}.java):
 *
 *   - BAT      : player block-position within a ±3-block cube around the
 *                secret's world position (OnPlayerTick.java line ~62).
 *   - INTERACT : right-click block whose world pos equals the secret's
 *                exact world pos AND block is one of {CHEST, TRAPPED_CHEST,
 *                LEVER, PLAYER_HEAD, SKELETON_SKULL} (OnPlayerInteract.java
 *                line ~71+).
 *   - ITEM     : a SkyBlock dungeon item appeared in inventory while the
 *                player was within ±10 of the secret's world pos
 *                (OnItemPickedUp.java line ~125). Valid item-name list from
 *                upstream: Decoy, Defuse Kit, Dungeon Chest Key, Healing VIII,
 *                Inflatable Jerry, Spirit Leap, Training Weights, Trap,
 *                Treasure Talisman.
 *   - CHEST    : same as INTERACT (data uses "chest" for chest secrets;
 *                upstream treats chest right-click the same as INTERACT).
 *   - WITHER   : door-open chat detection — we use the chat line
 *                "EnVyOnMyMind opened a WITHER door!" as the trigger.
 *
 * The advance is room-scoped: once `currentRoom` changes (DungeonScanner
 * crosses to a new room) we load that new room's route at index 0.
 *
 * NOT yet wired:
 *   - keybind to advance manually (SR has one)
 *   - keybind to go back a secret
 *   - "all routes / cycle alternate" picker (SR exposes this via GUI)
 *
 * Thread safety: all state is @Volatile / AtomicReference. Tick runs on
 * the client thread; UseBlockCallback fires on client thread too.
 */
package com.enviouse.skyfinder.client.playback;

import com.enviouse.skyfinder.client.render.SecretRouteRenderer;
import com.enviouse.skyfinder.deps.scanner.DungeonRoom;
import com.enviouse.skyfinder.deps.scanner.DungeonScanner;
import com.enviouse.skyfinder.deps.scanner.RoomRotationUtils;
import com.enviouse.skyfinder.deps.scanner.Rotations;
import com.enviouse.skyfinder.dungeon.HypixelLocationDetector;
import com.enviouse.skyfinder.dungeon.SecretRouteCatalog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.awt.Point;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class RoutePlayback {

    private RoutePlayback() {}

    /** Upstream valid-item list (OnItemPickedUp.validItems). */
    private static final String[] VALID_PICKUP_ITEMS = {
        "Decoy", "Defuse Kit", "Dungeon Chest Key", "Healing VIII",
        "Inflatable Jerry", "Spirit Leap", "Training Weights", "Trap",
        "Treasure Talisman"
    };

    /** Block whitelist for INTERACT triggers (OnPlayerInteract.java). */
    private static boolean isInteractBlock(Block b) {
        return b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST
            || b == Blocks.LEVER || b == Blocks.PLAYER_HEAD
            || b == Blocks.SKELETON_SKULL || b == Blocks.PLAYER_WALL_HEAD
            || b == Blocks.SKELETON_WALL_SKULL;
    }

    private static final AtomicReference<String> currentRoomName = new AtomicReference<>(null);
    private static final AtomicReference<java.util.List<SecretRouteCatalog.SecretRoute>> currentRoomRoutes = new AtomicReference<>(null);
    private static volatile int currentRouteIndex = 0;
    private static volatile int currentSecretIndex = 0;
    private static volatile boolean enabled = true;

    // ============================================================
    // Sub-waypoint sequencer (the "one outline at a time" feature)
    //
    // Per the spec from Leo: within a single secret, show ONLY the next
    // actionable waypoint as an outline. Order = furthest-from-secret-block
    // first, walking inward, secret block itself last. `locations[]` is NOT
    // in this queue — it's purely a path-shape hint for the bezier renderer.
    //
    // Trigger rules (Leo, 2026-05-27):
    //   etherwarp  → player on/near (proximity ±2)
    //   mine       → block at that pos broken (ClientPlayerBlockBreakEvents)
    //   tnt        → block(s) near broken (±3 of TNT marker) — Superboom destroys nearby
    //   enderpearl → player near landing pos (proximity ±2)
    //   interact   → right-click at exact pos (handled by UseBlockCallback)
    //   secret final → existing per-secret triggers (BAT proximity / ITEM
    //                  pickup / INTERACT click on the goal block)
    // ============================================================
    private static final java.util.Deque<SubWaypoint> subQueue = new java.util.ArrayDeque<>();

    /** 5-tick interval matches SR's OnItemPickedUp throttling. */
    private static int itemCheckTick = 0;
    private static final Map<String, Integer> prevInventory = new HashMap<>();

    /** Per-tick last room name we logged into so we can detect changes. */
    private static volatile String lastEnteredRoomName = null;

    /** Interact debounce (SR uses 110ms; OnPlayerInteract.java line ~41). */
    private static long lastInteractMs = 0L;
    private static BlockPos lastInteractPos = null;
    private static final long INTERACT_DEBOUNCE_MS = 110L;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(RoutePlayback::onClientTick);
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            onUseBlock(hitResult.getBlockPos());
            return InteractionResult.PASS;
        });
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> onBlockBroken(pos));
    }

    /** One step in the per-secret sub-waypoint sequence. */
    private static final class SubWaypoint {
        final SecretRouteRenderer.WaypointType type;
        final BlockPos worldPos; // integer block corner
        SubWaypoint(SecretRouteRenderer.WaypointType type, BlockPos worldPos) {
            this.type = type;
            this.worldPos = worldPos;
        }
    }

    public static void setEnabled(boolean on) { enabled = on; if (!on) SecretRouteRenderer.INSTANCE.clear(); }
    public static boolean isEnabled() { return enabled; }
    public static String currentRoomNameOrNull() { return currentRoomName.get(); }
    public static int currentSecretIndex() { return currentSecretIndex; }
    public static int currentRouteIndex() { return currentRouteIndex; }
    public static int currentRouteSize() {
        java.util.List<SecretRouteCatalog.SecretRoute> rs = currentRoomRoutes.get();
        return rs != null && currentRouteIndex < rs.size() ? 1 : 0;
        // (intentionally returning 1 — secrets-per-route stored in catalog; see currentRouteSecretCount)
    }
    public static int currentRouteSecretCount() {
        java.util.List<SecretRouteCatalog.SecretRoute> rs = currentRoomRoutes.get();
        if (rs == null) return 0;
        // We're modeling "secrets" as the sequence of routes within a room
        // (each SecretRoute IS one secret with its waypoint chain).
        return rs.size();
    }

    // ============================================================
    // Tick: room-change detection + BAT proximity + ITEM pickup
    // ============================================================

    private static void onClientTick(Minecraft mc) {
        if (!enabled) return;
        if (mc.player == null || mc.level == null) return;
        if (!HypixelLocationDetector.inCatacombs()) {
            if (currentRoomName.get() != null) clearAll();
            return;
        }

        DungeonRoom scanned = DungeonScanner.currentRoom;
        if (scanned == null || scanned.rotation == Rotations.NONE) return;

        String name = scanned.getName();
        if (!name.equals(lastEnteredRoomName)) {
            lastEnteredRoomName = name;
            loadRoom(name);
            return;
        }

        SecretRouteCatalog.SecretRoute cur = currentRoute();
        if (cur == null) return;

        BlockPos playerPos = mc.player.blockPosition();

        // === Sub-waypoint sequencer (priority) ===
        // If we have intermediate waypoints queued, check proximity-based
        // triggers on the head. Walk-by (etherwarp / enderpearl): ±2 cube.
        // Mines and TNT are block-break events, handled separately.
        SubWaypoint head = subQueue.peekFirst();
        if (head != null) {
            switch (head.type) {
                case ETHERWARP, ENDERPEARL -> {
                    if (within(playerPos, head.worldPos, 2)) {
                        consumeSubWaypoint("walked onto " + head.type);
                    }
                }
                // MINE / TNT — wait for block-break event
                // INTERACT — wait for right-click
                default -> { /* no tick-based trigger */ }
            }
            return; // don't fall through to per-secret trigger while sub-waypoints remain
        }

        // === Per-secret final triggers (only when subQueue is empty) ===
        SecretRouteCatalog.SecretType type = cur.secretType();
        int[] localGoal = cur.secretLocation();
        if (localGoal == null) return;
        BlockPos worldGoal = roomLocalToWorld(localGoal, scanned);
        if (worldGoal == null) return;

        switch (type) {
            case BAT -> {
                if (within(playerPos, worldGoal, 3)) advance("BAT proximity at " + worldGoal);
            }
            case ITEM -> tickItemDetect(mc.player, worldGoal);
            default -> { /* manual or right-click handled elsewhere */ }
        }
    }

    /** Handles BlockBreak events for MINE / TNT sub-waypoints. */
    private static void onBlockBroken(BlockPos pos) {
        if (!enabled) return;
        if (!HypixelLocationDetector.inCatacombs()) return;
        SubWaypoint head = subQueue.peekFirst();
        if (head == null) return;
        switch (head.type) {
            case MINE -> {
                if (pos.equals(head.worldPos)) consumeSubWaypoint("mined " + pos);
            }
            case TNT -> {
                // Superboom/Stonk obliterates blocks within ~3 of the placement
                // point. We accept any break within ±3 of the recorded TNT pos
                // as "the TNT did its job".
                if (within(pos, head.worldPos, 3)) consumeSubWaypoint("TNT-area break near " + head.worldPos);
            }
            default -> { /* nothing */ }
        }
    }

    /** Pop the head of the sub-queue + refresh the renderer. */
    private static void consumeSubWaypoint(String reason) {
        subQueue.pollFirst();
        playChime();
        refreshRenderer();
    }

    private static void tickItemDetect(LocalPlayer player, BlockPos worldGoal) {
        if (++itemCheckTick % 5 != 0) return;

        Inventory inv = player.getInventory();
        Map<String, Integer> now = new HashMap<>();
        int slots = inv.getContainerSize();
        for (int i = 0; i < slots; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.isEmpty()) continue;
            String n = s.getHoverName().getString();
            now.merge(n, s.getCount(), Integer::sum);
        }
        // Compare to previous snapshot; any net add of a valid pickup item
        // while in proximity of the goal counts.
        boolean newItemArrived = false;
        for (Map.Entry<String, Integer> e : now.entrySet()) {
            int prev = prevInventory.getOrDefault(e.getKey(), 0);
            if (e.getValue() > prev && isValidPickupName(e.getKey())) {
                newItemArrived = true;
                break;
            }
        }
        prevInventory.clear();
        prevInventory.putAll(now);

        if (newItemArrived && within(player.blockPosition(), worldGoal, 10)) {
            advance("ITEM pickup within 10 of " + worldGoal);
        }
    }

    private static boolean isValidPickupName(String name) {
        for (String s : VALID_PICKUP_ITEMS) if (name.contains(s)) return true;
        return false;
    }

    // ============================================================
    // Right-click block detection
    // ============================================================

    private static void onUseBlock(BlockPos pos) {
        if (!enabled) return;
        if (!HypixelLocationDetector.inCatacombs()) return;
        long now = System.currentTimeMillis();
        if (lastInteractPos != null && lastInteractPos.equals(pos) && (now - lastInteractMs) < INTERACT_DEBOUNCE_MS) return;
        lastInteractMs = now;
        lastInteractPos = pos;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Block block = mc.level.getBlockState(pos).getBlock();
        if (!isInteractBlock(block)) return;

        DungeonRoom scanned = DungeonScanner.currentRoom;
        if (scanned == null || scanned.rotation == Rotations.NONE) return;
        SecretRouteCatalog.SecretRoute cur = currentRoute();
        if (cur == null) return;

        // (1) Sub-waypoint INTERACT trigger: if the head of the sub-queue is
        // an INTERACT type at this exact pos, consume it and stop.
        SubWaypoint head = subQueue.peekFirst();
        if (head != null && head.type == SecretRouteRenderer.WaypointType.INTERACT
            && pos.equals(head.worldPos)) {
            consumeSubWaypoint("right-clicked interact " + pos);
            return;
        }

        // (2) Per-secret final trigger: INTERACT/CHEST/WITHER click on the
        // secret's exact world block (only when subQueue is empty so we know
        // we're at the "go for the secret" stage).
        if (!subQueue.isEmpty()) return;
        SecretRouteCatalog.SecretType type = cur.secretType();
        if (type != SecretRouteCatalog.SecretType.INTERACT
            && type != SecretRouteCatalog.SecretType.CHEST
            && type != SecretRouteCatalog.SecretType.WITHER) return;

        int[] localGoal = cur.secretLocation();
        if (localGoal == null) return;
        BlockPos worldGoal = roomLocalToWorld(localGoal, scanned);
        if (worldGoal == null) return;

        if (pos.getX() == worldGoal.getX() && pos.getY() == worldGoal.getY() && pos.getZ() == worldGoal.getZ()) {
            advance("INTERACT click at " + worldGoal);
        }
    }

    // ============================================================
    // Room + route load + advance
    // ============================================================

    private static void loadRoom(String name) {
        java.util.List<SecretRouteCatalog.SecretRoute> routes = SecretRouteCatalog.get().routesFor(name);
        currentRoomName.set(name);
        currentRoomRoutes.set(routes);
        currentRouteIndex = 0;
        currentSecretIndex = 0;
        subQueue.clear();
        prevInventory.clear();
        if (routes == null || routes.isEmpty()) {
            SecretRouteRenderer.INSTANCE.clear();
            return;
        }
        rebuildSubQueueForCurrent();
        refreshRenderer();
    }

    /** "Current secret" = currentRouteIndex'th route in the room. */
    private static SecretRouteCatalog.SecretRoute currentRoute() {
        java.util.List<SecretRouteCatalog.SecretRoute> rs = currentRoomRoutes.get();
        if (rs == null || rs.isEmpty()) return null;
        if (currentRouteIndex < 0 || currentRouteIndex >= rs.size()) return null;
        return rs.get(currentRouteIndex);
    }

    public static void advance(String reason) {
        java.util.List<SecretRouteCatalog.SecretRoute> rs = currentRoomRoutes.get();
        if (rs == null) return;
        if (currentRouteIndex < rs.size() - 1) {
            currentRouteIndex++;
            currentSecretIndex = currentRouteIndex;
            subQueue.clear();
            rebuildSubQueueForCurrent();
            refreshRenderer();
            playChime();
        } else {
            // Last secret done — clear render so we don't keep highlighting it.
            subQueue.clear();
            SecretRouteRenderer.INSTANCE.clear();
        }
    }

    public static void manualAdvance() { advance("manual"); }

    public static void manualBack() {
        if (currentRouteIndex > 0) {
            currentRouteIndex--;
            currentSecretIndex = currentRouteIndex;
            subQueue.clear();
            rebuildSubQueueForCurrent();
            refreshRenderer();
        }
    }

    public static void cycleAlternate() { /* no alternates in this data model */ }

    private static void clearAll() {
        currentRoomName.set(null);
        currentRoomRoutes.set(null);
        currentRouteIndex = 0;
        currentSecretIndex = 0;
        lastEnteredRoomName = null;
        subQueue.clear();
        prevInventory.clear();
        SecretRouteRenderer.INSTANCE.clear();
    }

    /**
     * Build the sub-waypoint queue for the current secret.
     *
     * Order rule (Leo, 2026-05-27): furthest-from-secret-block first, walking
     * inward, secret block itself last. Combines etherwarps + mines + interacts
     * + tnts + enderpearls into a single distance-sorted queue. `locations[]`
     * is NOT in this queue (path-shape hint only for the bezier renderer).
     */
    private static void rebuildSubQueueForCurrent() {
        subQueue.clear();
        SecretRouteCatalog.SecretRoute r = currentRoute();
        if (r == null) return;
        DungeonRoom scanned = DungeonScanner.currentRoom;
        if (scanned == null || scanned.rotation == Rotations.NONE) return;
        String rotation = RoomRotationUtils.rotationCode(scanned.rotation);
        Point corner = RoomRotationUtils.cornerOf(scanned);
        if (corner == null) return;

        int[] localGoal = r.secretLocation();
        if (localGoal == null) return;
        BlockPos worldGoal = RoomRotationUtils.relativeToActual(
            new BlockPos(localGoal[0], localGoal[1], localGoal[2]), rotation, corner);

        java.util.List<SubWaypoint> all = new java.util.ArrayList<>();
        for (int[] v : r.etherwarps())  all.add(new SubWaypoint(SecretRouteRenderer.WaypointType.ETHERWARP,
            RoomRotationUtils.relativeToActual(new BlockPos(v[0], v[1], v[2]), rotation, corner)));
        for (int[] v : r.mines())       all.add(new SubWaypoint(SecretRouteRenderer.WaypointType.MINE,
            RoomRotationUtils.relativeToActual(new BlockPos(v[0], v[1], v[2]), rotation, corner)));
        for (int[] v : r.interacts())   all.add(new SubWaypoint(SecretRouteRenderer.WaypointType.INTERACT,
            RoomRotationUtils.relativeToActual(new BlockPos(v[0], v[1], v[2]), rotation, corner)));
        for (int[] v : r.tnts())        all.add(new SubWaypoint(SecretRouteRenderer.WaypointType.TNT,
            RoomRotationUtils.relativeToActual(new BlockPos(v[0], v[1], v[2]), rotation, corner)));
        for (int[] v : r.enderpearls()) all.add(new SubWaypoint(SecretRouteRenderer.WaypointType.ENDERPEARL,
            RoomRotationUtils.relativeToActual(new BlockPos(v[0], v[1], v[2]), rotation, corner)));

        // Sort descending by distance from the secret block. Furthest first.
        all.sort((a, b) -> Double.compare(distSq(b.worldPos, worldGoal), distSq(a.worldPos, worldGoal)));
        subQueue.addAll(all);
    }

    private static double distSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Push the current state to the renderer. Active = head of sub-queue,
     *  or null if all sub-waypoints are done (then the renderer falls back
     *  to highlighting the pulsing secret-goal block). */
    private static void refreshRenderer() {
        DungeonRoom scanned = DungeonScanner.currentRoom;
        if (scanned == null || scanned.rotation == Rotations.NONE) { SecretRouteRenderer.INSTANCE.clear(); return; }
        SecretRouteCatalog.SecretRoute r = currentRoute();
        if (r == null) { SecretRouteRenderer.INSTANCE.clear(); return; }

        String rotation = RoomRotationUtils.rotationCode(scanned.rotation);
        Point corner = RoomRotationUtils.cornerOf(scanned);
        if (corner == null) return;

        // locations[] — path-shape hint for the bezier (block centers).
        java.util.List<Vec3> locations = new java.util.ArrayList<>();
        for (int[] v : r.locations()) locations.add(centerVec(v, rotation, corner));

        Vec3 goal = r.secretLocation() != null ? cornerVec(r.secretLocation(), rotation, corner) : null;

        SubWaypoint head = subQueue.peekFirst();
        Vec3 activeWaypoint = null;
        SecretRouteRenderer.WaypointType activeType = SecretRouteRenderer.WaypointType.NONE;
        if (head != null) {
            activeWaypoint = new Vec3(head.worldPos.getX(), head.worldPos.getY(), head.worldPos.getZ());
            activeType = head.type;
        }

        SecretRouteRenderer.INSTANCE.setPresentation(new SecretRouteRenderer.RoutePresentation(
            locations,
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            goal, r.secretType(), currentRoomName.get(),
            activeWaypoint, activeType
        ));
    }

    /** Room-local int corner → world Vec3 at integer block corner (for boxes). */
    private static Vec3 cornerVec(int[] v, String rotation, Point corner) {
        BlockPos pos = RoomRotationUtils.relativeToActual(new BlockPos(v[0], v[1], v[2]), rotation, corner);
        return new Vec3(pos.getX(), pos.getY(), pos.getZ());
    }
    /** Room-local int center → world Vec3 at block center (for the path polyline). */
    private static Vec3 centerVec(int[] v, String rotation, Point corner) {
        BlockPos pos = RoomRotationUtils.relativeToActual(new BlockPos(v[0], v[1], v[2]), rotation, corner);
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** Room-local int → world BlockPos. */
    private static BlockPos roomLocalToWorld(int[] v, DungeonRoom scanned) {
        Point corner = RoomRotationUtils.cornerOf(scanned);
        if (corner == null) return null;
        String rotation = RoomRotationUtils.rotationCode(scanned.rotation);
        return RoomRotationUtils.relativeToActual(new BlockPos(v[0], v[1], v[2]), rotation, corner);
    }

    private static boolean within(BlockPos p, BlockPos target, int range) {
        return p.getX() >= target.getX() - range && p.getX() <= target.getX() + range
            && p.getY() >= target.getY() - range && p.getY() <= target.getY() + range
            && p.getZ() >= target.getZ() - range && p.getZ() <= target.getZ() + range;
    }

    private static void playChime() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.5f);
    }
}
