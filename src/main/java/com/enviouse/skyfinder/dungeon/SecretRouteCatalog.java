/*
 * SkyFinder — SecretRouteCatalog
 *
 * Loads Zyra/yourboykyle's `routes.json` and `pearlroutes.json` from
 * yourboykyle/SecretRoutes (GPL-3.0, https://github.com/yourboykyle/SecretRoutes).
 *
 * Each top-level key is a room name (matching the DRM `.skeleton` file names),
 * mapping to an array of "routes" — alternative paths through the room.
 * Each route has:
 *
 *   locations[]         — list of [x,y,z] checkpoints in room-LOCAL coords
 *   etherwarps[]        — list of [x,y,z] AOTV etherwarp anchor points
 *   mines[]             — list of [x,y,z] block positions to mine (superboom/stonk)
 *   interacts[]         — list of [x,y,z] right-clickable trigger blocks
 *   tnts[]              — list of [x,y,z] TNT placement points
 *   enderpearls[]       — list of [x,y,z] enderpearl throw landing points
 *   enderpearlangles[]  — optional list of [pitch,yaw] for the throws
 *   secret              — { type: "<category>", location: [x,y,z] } final secret
 *
 * Two top-level meta keys are skipped: "#origin", "#copyright", "Version".
 *
 * Data is bundled at `assets/skyfinder/data/{routes,pearlroutes}.json` as a
 * fallback. On first launch, attempts a network refresh from
 * `raw.githubusercontent.com/yourboykyle/SecretRoutes/main/{routes,pearlroutes}.json`
 * with a 5-second timeout; if it succeeds, the result is cached to
 * `<runDir>/config/skyfinder/{routes,pearlroutes}.json` and used in preference
 * to the bundled copy on every subsequent launch. Failures are silent — we
 * fall back to the bundled copy and log a debug-level message.
 *
 * Coordinate system: route coordinates are ROOM-LOCAL canonical coords. World
 * coords come from `DungeonMapDecoder.roomLocalToWorld(local, cornerWorldX,
 * cornerWorldZ, rotation)` at render time, NOT cached here.
 */
package com.enviouse.skyfinder.dungeon;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SecretRouteCatalog {

    private static final String BUNDLED_ROUTES_PATH       = "/assets/skyfinder/data/routes.json";
    private static final String BUNDLED_PEARLROUTES_PATH  = "/assets/skyfinder/data/pearlroutes.json";
    // Live update endpoints. SkyFinder hosts its OWN forked copy of the
    // SecretRoutes data under /AutoUpdater/ so we can: (a) iterate on routes
    // without waiting for upstream merges, (b) audit any change before it
    // ships to players, (c) keep working even if upstream goes offline. The
    // forked files preserve upstream provenance via `#origin`/`#copyright`
    // meta keys inside the JSON itself. See AutoUpdater/README.md.
    private static final String REMOTE_ROUTES_URL         = "https://raw.githubusercontent.com/EnVisione/SkyFinder/main/AutoUpdater/routes.json";
    private static final String REMOTE_PEARLROUTES_URL    = "https://raw.githubusercontent.com/EnVisione/SkyFinder/main/AutoUpdater/pearlroutes.json";
    private static final Duration NETWORK_TIMEOUT         = Duration.ofSeconds(5);

    private static volatile SecretRouteCatalog INSTANCE;

    private final Map<String, List<SecretRoute>> routes;       // roomName -> routes
    private final Map<String, List<SecretRoute>> pearlRoutes;  // roomName -> pearl routes
    private final String routesSource;     // "remote", "cached", "bundled" — for diagnostics
    private final String pearlSource;
    private final long   loadedAtMs;

    private SecretRouteCatalog(
        Map<String, List<SecretRoute>> routes,
        Map<String, List<SecretRoute>> pearlRoutes,
        String routesSource,
        String pearlSource
    ) {
        this.routes = Collections.unmodifiableMap(routes);
        this.pearlRoutes = Collections.unmodifiableMap(pearlRoutes);
        this.routesSource = routesSource;
        this.pearlSource = pearlSource;
        this.loadedAtMs = System.currentTimeMillis();
    }

    public static SecretRouteCatalog get() {
        SecretRouteCatalog local = INSTANCE;
        if (local != null) return local;
        synchronized (SecretRouteCatalog.class) {
            if (INSTANCE == null) INSTANCE = load();
            return INSTANCE;
        }
    }

    /** Force a reload (re-pulls remote if reachable). Used by `/skyfinder routes update`. */
    public static synchronized SecretRouteCatalog reload() {
        INSTANCE = load();
        return INSTANCE;
    }

    public List<SecretRoute> routesFor(String roomName) {
        return routes.getOrDefault(roomName, Collections.emptyList());
    }

    public List<SecretRoute> pearlRoutesFor(String roomName) {
        return pearlRoutes.getOrDefault(roomName, Collections.emptyList());
    }

    public int totalRoutes() {
        int t = 0;
        for (List<SecretRoute> r : routes.values()) t += r.size();
        return t;
    }

    public int totalPearlRoutes() {
        int t = 0;
        for (List<SecretRoute> r : pearlRoutes.values()) t += r.size();
        return t;
    }

    public int roomsWithRoutes()      { return routes.size(); }
    public int roomsWithPearlRoutes() { return pearlRoutes.size(); }
    public String routesSource()      { return routesSource; }
    public String pearlSource()       { return pearlSource; }
    public long loadedAtMs()          { return loadedAtMs; }

    /** Histogram of secret types across all loaded routes. */
    public Map<SecretType, Integer> secretTypeHistogram() {
        Map<SecretType, Integer> hist = new HashMap<>();
        for (List<SecretRoute> rl : routes.values()) {
            for (SecretRoute r : rl) {
                hist.merge(r.secretType(), 1, Integer::sum);
            }
        }
        return hist;
    }

    // ============================================================
    // Loader
    // ============================================================

    private static SecretRouteCatalog load() {
        SourcedJson routesJson = fetchWithFallback(REMOTE_ROUTES_URL, BUNDLED_ROUTES_PATH, "routes.json");
        SourcedJson pearlJson  = fetchWithFallback(REMOTE_PEARLROUTES_URL, BUNDLED_PEARLROUTES_PATH, "pearlroutes.json");
        return new SecretRouteCatalog(
            parseRoutesJson(routesJson.body()),
            parseRoutesJson(pearlJson.body()),
            routesJson.source(),
            pearlJson.source()
        );
    }

    private record SourcedJson(String body, String source) {}

    private static SourcedJson fetchWithFallback(String url, String bundledPath, String filename) {
        // 1. Try network → cache to disk → use.
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(NETWORK_TIMEOUT).build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(NETWORK_TIMEOUT)
                .header("User-Agent", "SkyFinder/1.0")
                .GET()
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200 && !resp.body().isEmpty()) {
                writeCache(filename, resp.body());
                return new SourcedJson(resp.body(), "remote");
            }
        } catch (Exception e) {
            // Network failed — fall through to disk cache, then bundled.
        }
        // 2. Try disk cache from previous successful pull.
        try {
            Path cache = cachePath(filename);
            if (Files.exists(cache)) {
                String body = Files.readString(cache, StandardCharsets.UTF_8);
                if (!body.isEmpty()) return new SourcedJson(body, "cached");
            }
        } catch (IOException e) {
            // Cache unreadable — fall through.
        }
        // 3. Fall back to bundled.
        return new SourcedJson(readBundled(bundledPath), "bundled");
    }

    private static String readBundled(String path) {
        try (InputStream in = SecretRouteCatalog.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing bundled resource " + path);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder(1024);
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                return sb.toString();
            }
        } catch (IOException e) {
            throw new RuntimeException("SkyFinder failed to load bundled " + path, e);
        }
    }

    private static Path cachePath(String filename) {
        return FabricLoader.getInstance().getConfigDir().resolve("skyfinder").resolve(filename);
    }

    private static void writeCache(String filename, String body) {
        try {
            Path p = cachePath(filename);
            Files.createDirectories(p.getParent());
            Files.writeString(p, body, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Cache write is best-effort; not having it doesn't break anything.
        }
    }

    /**
     * Parse the routes.json shape. Each room has an array of routes; each
     * route has the waypoint lists + a final `secret` object. Meta keys
     * (#origin, #copyright, Version) are skipped.
     */
    private static Map<String, List<SecretRoute>> parseRoutesJson(String json) {
        Map<String, List<SecretRoute>> out = new HashMap<>();
        if (json == null || json.isEmpty()) return out;
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            String key = e.getKey();
            if (isMetaKey(key)) continue;
            if (!e.getValue().isJsonArray()) continue;
            List<SecretRoute> roomRoutes = new ArrayList<>();
            for (JsonElement el : e.getValue().getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                SecretRoute parsed = parseRoute(el.getAsJsonObject());
                if (parsed != null) roomRoutes.add(parsed);
            }
            if (!roomRoutes.isEmpty()) {
                out.put(key, Collections.unmodifiableList(roomRoutes));
            }
        }
        return out;
    }

    private static SecretRoute parseRoute(JsonObject o) {
        List<int[]> locations    = parseVec3Array(o, "locations");
        List<int[]> etherwarps   = parseVec3Array(o, "etherwarps");
        List<int[]> mines        = parseVec3Array(o, "mines");
        List<int[]> interacts    = parseVec3Array(o, "interacts");
        List<int[]> tnts         = parseVec3Array(o, "tnts");
        List<int[]> enderpearls  = parseVec3Array(o, "enderpearls");
        List<double[]> angles    = parseVec2DoubleArray(o, "enderpearlangles");

        SecretType type = SecretType.UNKNOWN;
        int[] secretLoc = null;
        if (o.has("secret") && o.get("secret").isJsonObject()) {
            JsonObject s = o.getAsJsonObject("secret");
            if (s.has("type") && !s.get("type").isJsonNull()) {
                type = SecretType.fromString(s.get("type").getAsString());
            }
            if (s.has("location") && s.get("location").isJsonArray()) {
                secretLoc = parseVec3(s.getAsJsonArray("location"));
            }
        }
        return new SecretRoute(type, secretLoc, locations, etherwarps, mines, interacts, tnts, enderpearls, angles);
    }

    private static List<int[]> parseVec3Array(JsonObject o, String key) {
        List<int[]> out = new ArrayList<>();
        if (!o.has(key) || !o.get(key).isJsonArray()) return out;
        for (JsonElement el : o.getAsJsonArray(key)) {
            if (!el.isJsonArray()) continue;
            int[] vec = parseVec3(el.getAsJsonArray());
            if (vec != null) out.add(vec);
        }
        return out;
    }

    private static int[] parseVec3(JsonArray arr) {
        if (arr.size() < 3) return null;
        try {
            return new int[]{ arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt() };
        } catch (Exception e) { return null; }
    }

    private static List<double[]> parseVec2DoubleArray(JsonObject o, String key) {
        List<double[]> out = new ArrayList<>();
        if (!o.has(key) || !o.get(key).isJsonArray()) return out;
        for (JsonElement el : o.getAsJsonArray(key)) {
            if (!el.isJsonArray()) continue;
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() < 2) continue;
            try {
                out.add(new double[]{ arr.get(0).getAsDouble(), arr.get(1).getAsDouble() });
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean isMetaKey(String k) {
        return k.startsWith("#")
            || "version".equalsIgnoreCase(k)
            || "copyright".equalsIgnoreCase(k)
            || "license".equalsIgnoreCase(k);
    }

    // ============================================================
    // Data types
    // ============================================================

    /**
     * Canonical secret category. Maps the loose strings used in routes.json
     * ("chest", "interact", "exitroute", ...) to a stable enum.
     */
    public enum SecretType {
        CHEST, ITEM, BAT, LEVER, WITHER, INTERACT, SUPERBOOM, STONK,
        ENTRANCE, EXIT, EXITROUTE, FAIRYSOUL, UNKNOWN;

        public static SecretType fromString(String s) {
            if (s == null) return UNKNOWN;
            switch (s.toLowerCase(Locale.ROOT).trim()) {
                case "chest":      return CHEST;
                case "item":       return ITEM;
                case "bat":        return BAT;
                case "lever":      return LEVER;
                case "wither":     return WITHER;
                case "interact":   return INTERACT;
                case "superboom":  return SUPERBOOM;
                case "stonk":      return STONK;
                case "entrance":   return ENTRANCE;
                case "exit":       return EXIT;
                case "exitroute":  return EXITROUTE;
                case "fairysoul":  return FAIRYSOUL;
                default:           return UNKNOWN;
            }
        }
    }

    /**
     * One alternative route through a room toward a single secret. All XYZ
     * arrays are room-LOCAL coords; transform to world with
     * DungeonMapDecoder.roomLocalToWorld at render time.
     */
    public record SecretRoute(
        SecretType secretType,
        int[] secretLocation,        // [x,y,z] or null if not present
        List<int[]> locations,       // path checkpoints
        List<int[]> etherwarps,
        List<int[]> mines,
        List<int[]> interacts,
        List<int[]> tnts,
        List<int[]> enderpearls,
        List<double[]> enderpearlAngles
    ) {
        public int totalWaypoints() {
            return locations.size() + etherwarps.size() + mines.size()
                 + interacts.size() + tnts.size() + enderpearls.size();
        }
    }
}
