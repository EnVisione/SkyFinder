/*
 * SkyFinder — DungeonRoomCatalog
 *
 * Loads dungeon room metadata and secret coordinates from the bundled DRM data
 * (Quantizr/DungeonRoomsMod, GPL-3.0). Both JSON files live under
 *   /assets/skyfinder/data/{dungeonrooms,secretlocations}.json
 *
 * Data files redistributed under GPL-3.0. Original copyright 2021 Quantizr.
 * Adapted for SkyFinder (LGPL-3.0-or-later) — the LOADER code here is original.
 *
 * Coordinate system: all (x,y,z) values in secretlocations.json are ROOM-LOCAL
 * canonical coords (the room as stored in its *.skeleton). World coords come
 * from RoomDetectionUtils.localToWorld(secret, rotation, cornerWorld) at lookup
 * time — NOT cached here.
 */
package com.enviouse.skyfinder.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class DungeonRoomCatalog {

    private static final String META_PATH    = "/assets/skyfinder/data/dungeonrooms.json";
    private static final String SECRETS_PATH = "/assets/skyfinder/data/secretlocations.json";

    private static volatile DungeonRoomCatalog INSTANCE;

    private final Map<String, RoomMeta> rooms;            // name -> meta
    private final Map<String, List<Secret>> secretsByRoom; // name -> immutable list

    private DungeonRoomCatalog(Map<String, RoomMeta> rooms, Map<String, List<Secret>> secrets) {
        this.rooms = Collections.unmodifiableMap(rooms);
        this.secretsByRoom = Collections.unmodifiableMap(secrets);
    }

    /** Lazy singleton — first call parses ~120KB of JSON, ~5ms typical. */
    public static DungeonRoomCatalog get() {
        DungeonRoomCatalog local = INSTANCE;
        if (local != null) return local;
        synchronized (DungeonRoomCatalog.class) {
            if (INSTANCE == null) INSTANCE = load();
            return INSTANCE;
        }
    }

    public Map<String, RoomMeta> rooms() { return rooms; }

    public List<Secret> secretsFor(String roomName) {
        return secretsByRoom.getOrDefault(roomName, Collections.emptyList());
    }

    public Optional<RoomMeta> roomMeta(String roomName) {
        return Optional.ofNullable(rooms.get(roomName));
    }

    public int totalSecrets() {
        int t = 0;
        for (List<Secret> s : secretsByRoom.values()) t += s.size();
        return t;
    }

    public int roomCountWithSecrets() { return secretsByRoom.size(); }
    public int roomCountTotal()       { return rooms.size(); }

    // ---------------------------------------------------------------- loader

    private static DungeonRoomCatalog load() {
        Map<String, RoomMeta> metas = parseRooms(readResource(META_PATH));
        Map<String, List<Secret>> secrets = parseSecrets(readResource(SECRETS_PATH));
        return new DungeonRoomCatalog(metas, secrets);
    }

    private static String readResource(String path) {
        try (InputStream in = DungeonRoomCatalog.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing bundled resource " + path);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder(1024);
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                return sb.toString();
            }
        } catch (IOException e) {
            throw new RuntimeException("SkyFinder failed to load " + path, e);
        }
    }

    private static Map<String, RoomMeta> parseRooms(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, RoomMeta> out = new LinkedHashMap<>();
        Gson gson = new Gson();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            String name = e.getKey();
            if (isMetaKey(name)) continue;             // skip "copyright", "license"
            if (!e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            RoomMeta m = new RoomMeta(
                name,
                optString(o, "category", "Unknown"),
                optInt(o, "secrets", 0),
                optBool(o, "fairysoul", false),
                optString(o, "dsg", "")
            );
            out.put(name, m);
        }
        return out;
    }

    private static Map<String, List<Secret>> parseSecrets(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, List<Secret>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            String name = e.getKey();
            if (isMetaKey(name)) continue;
            if (!e.getValue().isJsonArray()) continue;
            List<Secret> list = new ArrayList<>();
            for (JsonElement el : e.getValue().getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                list.add(new Secret(
                    optString(o, "secretName", ""),
                    normalizeCategory(optString(o, "category", "")),
                    optInt(o, "x", 0),
                    optInt(o, "y", 0),
                    optInt(o, "z", 0)
                ));
            }
            out.put(name, Collections.unmodifiableList(list));
        }
        return out;
    }

    private static boolean isMetaKey(String k) {
        return "copyright".equalsIgnoreCase(k) || "license".equalsIgnoreCase(k);
    }

    /** DRM mixes "Item"/"item", "Lever"/"lever". Normalize to lower-case enum-style. */
    private static SecretCategory normalizeCategory(String raw) {
        if (raw == null || raw.isEmpty()) return SecretCategory.UNKNOWN;
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "chest":      return SecretCategory.CHEST;
            case "item":       return SecretCategory.ITEM;
            case "bat":        return SecretCategory.BAT;
            case "lever":      return SecretCategory.LEVER;
            case "entrance":   return SecretCategory.ENTRANCE;
            case "puzzle":     return SecretCategory.PUZZLE;
            case "stonk":      return SecretCategory.STONK;
            case "superboom":  return SecretCategory.SUPERBOOM;
            case "wither":     return SecretCategory.WITHER;
            case "fairysoul":  return SecretCategory.FAIRYSOUL;
            default:           return SecretCategory.UNKNOWN;
        }
    }

    private static String optString(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }
    private static int optInt(JsonObject o, String key, int fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback;
    }
    private static boolean optBool(JsonObject o, String key, boolean fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : fallback;
    }

    // -------------------------------------------------------------- data types

    public enum SecretCategory {
        CHEST, ITEM, BAT, LEVER, ENTRANCE, PUZZLE, STONK, SUPERBOOM, WITHER, FAIRYSOUL, UNKNOWN
    }

    /** Room metadata from dungeonrooms.json. Secrets count here is the AUTHORITATIVE
     *  count — some rooms exist in dungeonrooms.json with no entry in
     *  secretlocations.json (15 rooms in current DRM data) because they have no
     *  per-secret coords but still report a count. */
    public record RoomMeta(
        String name,
        String category,        // "General", "Puzzle", "Trap", "Miniboss", "Fairy", ...
        int secrets,            // declared secret count
        boolean fairysoul,
        String dsg              // DRM "downloaded secret guide" url string ("null" if none)
    ) {}

    /** Single secret entry. (x,y,z) are room-LOCAL canonical coords. */
    public record Secret(
        String secretName,
        SecretCategory category,
        int x, int y, int z
    ) {}
}
