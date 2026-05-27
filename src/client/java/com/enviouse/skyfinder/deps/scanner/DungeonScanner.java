/*
 * Adapted into SkyFinder from yourboykyle/SecretRoutes versions/1.21.10-fabric.
 *
 * BSD 3-Clause License
 * Copyright (c) 2025, odtheking
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *
 * SkyFinder porting notes (Mojang-mappings translation from yarn):
 *   - MinecraftClient → Minecraft
 *   - client.world → mc.level (ClientLevel)
 *   - world.isAir(pos) → state.isAir()
 *   - world.getChunkManager().isChunkLoaded(cx, cz) → mc.level.hasChunk(cx, cz)
 *   - BlockPos.Mutable → BlockPos.MutableBlockPos
 *   - Direction.getOffsetX/Z → getStepX/Z
 *
 * Algorithm (unchanged from upstream):
 *   1. Every tick, compute the room-center grid cell from player (x, z).
 *      Dungeons live on a 32-block grid starting at world (-185, -185).
 *   2. If unchanged from last tick → done. Otherwise, check if this cell is
 *      a known component of a previously-entered room (multi-tile rooms have
 *      multiple components) — if yes, re-fire "entered" for that room.
 *   3. Otherwise: hash the room's BLOCK COLUMN from the room center going
 *      down. The hash is the "core" identifier. Look up the room in
 *      rooms.json by core. If matched, BFS-find all adjacent tiles that
 *      share a core in the same room's `cores` list (multi-tile expansion).
 *      Then find the unique blue-terracotta marker block at one of 4
 *      corners — that gives the rotation + anchor (clayPos).
 */
package com.enviouse.skyfinder.deps.scanner;

import com.enviouse.skyfinder.dungeon.HypixelLocationDetector;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector2i;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.*;

public final class DungeonScanner {

    private DungeonScanner() {}

    private static final Gson GSON = new Gson();
    private static final Map<Integer, RoomData> CORE_TO_ROOM_MAP = new HashMap<>();

    public static volatile DungeonRoom currentRoom = null;
    public static final Set<DungeonRoom> passedRooms = new HashSet<>();
    private static Vector2i lastRoomCentre = new Vector2i(0, 0);

    private static final int ROOM_SIZE_SHIFT = 5;
    private static final int START_COORDINATE = -185;
    private static final List<Direction> HORIZONTALS = Arrays.stream(Direction.values())
        .filter(d -> d.getAxis().isHorizontal()).toList();

    private static volatile boolean inited = false;

    /** Idempotent. Call once from the client mod initializer. */
    public static synchronized void init() {
        if (inited) return;
        inited = true;
        ClientLifecycleEvents.CLIENT_STARTED.register(c -> loadResources());
        ClientPlayConnectionEvents.JOIN.register((h, s, c) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> reset());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;
            if (!HypixelLocationDetector.inCatacombs()) {
                if (currentRoom != null) currentRoom = null;
                return;
            }
            tick(client);
        });
    }

    /** Force a resource reload — useful after a hot-swap of the rooms.json bundle. */
    public static void reloadResources() {
        loadResources();
    }

    public static int loadedRoomCount() {
        Set<String> uniqueNames = new HashSet<>();
        for (RoomData r : CORE_TO_ROOM_MAP.values()) uniqueNames.add(r.name());
        return uniqueNames.size();
    }

    public static int loadedCoreCount() { return CORE_TO_ROOM_MAP.size(); }

    private static void loadResources() {
        CORE_TO_ROOM_MAP.clear();
        Minecraft client = Minecraft.getInstance();
        if (client.getResourceManager() == null) return;
        client.getResourceManager()
            .listResources("data", id -> id.getPath().endsWith("rooms.json"))
            .forEach((id, resource) -> {
                try (Reader reader = new InputStreamReader(resource.open())) {
                    List<RoomData> data = GSON.fromJson(reader, new TypeToken<List<RoomData>>(){}.getType());
                    if (data == null) return;
                    for (RoomData room : data) {
                        if (room.cores() != null) {
                            for (Integer core : room.cores()) CORE_TO_ROOM_MAP.put(core, room);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[SkyFinder] Failed to load " + id + ": " + e.getMessage());
                }
            });
    }

    private static void tick(Minecraft client) {
        int playerX = (int) client.player.getX();
        int playerZ = (int) client.player.getZ();
        Vector2i roomCentre = getRoomCentre(playerX, playerZ);

        if (roomCentre.equals(lastRoomCentre)) return;
        lastRoomCentre = roomCentre;

        // If we've already seen this room (multi-tile), re-enter it cheaply.
        for (DungeonRoom passed : passedRooms) {
            for (RoomComponent comp : passed.roomComponents) {
                if (comp.vec2().equals(roomCentre)) {
                    if (currentRoom == null || !hasComponent(currentRoom, roomCentre)) enterRoom(passed);
                    return;
                }
            }
        }

        ClientLevel level = client.level;
        if (level == null) return;
        if (!level.hasChunk(roomCentre.x >> 4, roomCentre.y >> 4)) return;

        DungeonRoom newRoom = scanRoom(client, roomCentre);
        if (newRoom != null && newRoom.rotation != Rotations.NONE) enterRoom(newRoom);
    }

    private static void enterRoom(DungeonRoom room) {
        currentRoom = room;
        boolean exists = false;
        for (DungeonRoom r : passedRooms) {
            if (r.data.name().equals(room.data.name())) { exists = true; break; }
        }
        if (!exists) passedRooms.add(room);
    }

    private static boolean hasComponent(DungeonRoom room, Vector2i target) {
        for (RoomComponent comp : room.roomComponents) {
            if (comp.vec2().equals(target)) return true;
        }
        return false;
    }

    private static DungeonRoom scanRoom(Minecraft client, Vector2i vec2) {
        int roomHeight = getTopLayerOfRoom(client, vec2);
        int core = getCore(client, vec2, roomHeight);
        RoomData data = CORE_TO_ROOM_MAP.get(core);
        if (data == null) return null;
        Set<RoomComponent> components = findRoomComponentsRecursively(
            client, vec2, data.cores(), roomHeight, new HashSet<>(), new HashSet<>());
        DungeonRoom room = new DungeonRoom(data, components);
        updateRotation(client, room, roomHeight);
        return room;
    }

    private static Set<RoomComponent> findRoomComponentsRecursively(
        Minecraft client, Vector2i vec2, List<Integer> cores, int roomHeight,
        Set<Vector2i> visited, Set<RoomComponent> tiles
    ) {
        if (visited.contains(vec2)) return tiles;
        visited.add(vec2);
        int core = getCore(client, vec2, roomHeight);
        if (!cores.contains(core)) return tiles;
        tiles.add(new RoomComponent(vec2.x, vec2.y, core));
        for (Direction facing : HORIZONTALS) {
            int dx = (facing.getAxis() == Direction.Axis.X ? facing.getStepX() : 0) << ROOM_SIZE_SHIFT;
            int dz = (facing.getAxis() == Direction.Axis.Z ? facing.getStepZ() : 0) << ROOM_SIZE_SHIFT;
            findRoomComponentsRecursively(
                client, new Vector2i(vec2.x + dx, vec2.y + dz), cores, roomHeight, visited, tiles);
        }
        return tiles;
    }

    private static void updateRotation(Minecraft client, DungeonRoom room, int roomHeight) {
        if ("Fairy".equals(room.data.name())) {
            if (!room.roomComponents.isEmpty()) {
                RoomComponent first = room.roomComponents.iterator().next();
                room.clayPos = new BlockPos(first.x() - 15, roomHeight, first.z() - 15);
                room.rotation = Rotations.SOUTH;
            }
            return;
        }
        ClientLevel level = client.level;
        if (level == null) { room.rotation = Rotations.NONE; return; }

        List<Rotations> rotations = Arrays.asList(Rotations.NORTH, Rotations.SOUTH, Rotations.WEST, Rotations.EAST);
        for (Rotations rot : rotations) {
            for (RoomComponent comp : room.roomComponents) {
                BlockPos checkPos = new BlockPos(comp.x() + rot.x, roomHeight, comp.z() + rot.z);
                if (isBlueTerracotta(level, checkPos)) {
                    boolean neighborsValid = true;
                    if (room.roomComponents.size() > 1) {
                        for (Direction facing : HORIZONTALS) {
                            BlockPos neighbor = checkPos.relative(facing);
                            if (!isBlueTerracottaOrAir(level, neighbor)) { neighborsValid = false; break; }
                        }
                    }
                    if (neighborsValid) {
                        room.clayPos = checkPos;
                        room.rotation = rot;
                        // Upstream Slime-5/Sewer-7 marker flip — keep as-is.
                        if (room.rotation != Rotations.NONE
                            && (room.data.name().equalsIgnoreCase("Slime-5")
                             || room.data.name().equalsIgnoreCase("Sewer-7"))) {
                            Rotations newRot = switch (room.rotation) {
                                case NORTH -> Rotations.SOUTH;
                                case SOUTH -> Rotations.NORTH;
                                case EAST  -> Rotations.WEST;
                                case WEST  -> Rotations.EAST;
                                default    -> room.rotation;
                            };
                            room.rotation = newRot;
                            room.clayPos = calculateCorner(room.roomComponents, newRot);
                        }
                        return;
                    }
                }
            }
        }
        room.rotation = Rotations.NONE;
    }

    private static BlockPos calculateCorner(Set<RoomComponent> components, Rotations rotation) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (RoomComponent comp : components) {
            if (comp.x() < minX) minX = comp.x();
            if (comp.x() > maxX) maxX = comp.x();
            if (comp.z() < minZ) minZ = comp.z();
            if (comp.z() > maxZ) maxZ = comp.z();
        }
        return switch (rotation) {
            case SOUTH -> new BlockPos(minX + rotation.x, 0, minZ + rotation.z);
            case WEST  -> new BlockPos(maxX + rotation.x, 0, minZ + rotation.z);
            case NORTH -> new BlockPos(maxX + rotation.x, 0, maxZ + rotation.z);
            case EAST  -> new BlockPos(minX + rotation.x, 0, maxZ + rotation.z);
            default    -> new BlockPos(minX, 0, minZ);
        };
    }

    private static boolean isBlueTerracotta(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() == Blocks.BLUE_TERRACOTTA;
    }

    private static boolean isBlueTerracottaOrAir(ClientLevel level, BlockPos pos) {
        Block b = level.getBlockState(pos).getBlock();
        return b == Blocks.AIR || b == Blocks.BLUE_TERRACOTTA;
    }

    public static Vector2i getRoomCentre(int posX, int posZ) {
        int roomX = (posX - START_COORDINATE + (1 << (ROOM_SIZE_SHIFT - 1))) >> ROOM_SIZE_SHIFT;
        int roomZ = (posZ - START_COORDINATE + (1 << (ROOM_SIZE_SHIFT - 1))) >> ROOM_SIZE_SHIFT;
        return new Vector2i((roomX << ROOM_SIZE_SHIFT) + START_COORDINATE,
                            (roomZ << ROOM_SIZE_SHIFT) + START_COORDINATE);
    }

    /** Block-column hash at the room center. The KEY innovation: turns a
     *  room's interior structure into a single int via String.hashCode(). */
    private static int getCore(Minecraft client, Vector2i vec2, Integer knownHeight) {
        ClientLevel level = client.level;
        if (level == null) return 0;
        int roomHeight = (knownHeight != null ? knownHeight : getTopLayerOfRoom(client, vec2));
        int clampedHeight = Math.max(11, Math.min(140, roomHeight));
        StringBuilder sb = new StringBuilder(150);
        for (int i = 0; i < 140 - clampedHeight; i++) sb.append('0');
        int bedrock = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = clampedHeight; y >= 12; y--) {
            pos.set(vec2.x, y, vec2.y);
            Block block = level.getBlockState(pos).getBlock();
            if (block == Blocks.AIR && bedrock >= 2 && y < 69) {
                int remaining = y - 11;
                for (int k = 0; k < remaining; k++) sb.append('0');
                break;
            }
            if (block == Blocks.BEDROCK) bedrock++;
            else {
                bedrock = 0;
                if (block == Blocks.OAK_PLANKS || block == Blocks.TRAPPED_CHEST || block == Blocks.CHEST) continue;
            }
            sb.append(block);
        }
        return sb.toString().hashCode();
    }

    private static int getTopLayerOfRoom(Minecraft client, Vector2i vec2) {
        ClientLevel level = client.level;
        if (level == null) return 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 160; y >= 12; y--) {
            pos.set(vec2.x, y, vec2.y);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                if (state.getBlock() == Blocks.GOLD_BLOCK) return y - 1;
                return y;
            }
        }
        return 0;
    }

    private static void reset() {
        lastRoomCentre = new Vector2i(0, 0);
        currentRoom = null;
        passedRooms.clear();
    }
}
