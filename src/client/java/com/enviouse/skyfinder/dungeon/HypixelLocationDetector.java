/*
 * SkyFinder — HypixelLocationDetector
 *
 * Detects whether the player is currently in Hypixel SkyBlock and, more
 * specifically, in The Catacombs. Polls the scoreboard each tick (cheap).
 *
 * Algorithm (lifted from DRM's Utils.checkForSkyblock/checkForCatacombs,
 * which were originally derived from Danker's Skyblock Mod, GPL-3.0):
 *
 *   inSkyblock  = the scoreboard's display-slot objective NAME contains "SKYBLOCK"
 *   inCatacombs = inSkyblock AND any sidebar line contains "The Catacombs"
 *
 * Floor extraction is SkyFinder-original: parses the matched sidebar line for a
 * trailing `(F<N>)` or `(M<N>)` to expose the current floor (1-7) and whether
 * it's the Master Mode variant.
 *
 * State is cached and only mutates between ticks; safe to read from the render
 * thread.
 */
package com.enviouse.skyfinder.dungeon;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HypixelLocationDetector {

    private HypixelLocationDetector() {}

    // Matches "(F1)" through "(F7)" or "(M1)" through "(M7)" anywhere in the
    // sidebar text. Hypixel uses parens around the floor token, with F or M
    // prefix for normal/master.
    private static final Pattern FLOOR_PATTERN = Pattern.compile("\\(([FM])(\\d+)\\)");

    // The top sidebar line on Hypixel SkyBlock looks like:
    //   "05/27/26 m24BA 234,-524"
    // The trailing "X,Z" pair is the server-side coordinate stamp that
    // updates ONLY when the player crosses into a different dungeon room.
    // For L-shape / 2x2 / multi-tile rooms it stays CONSTANT across every
    // tile in the room (server treats the whole shape as one room). That
    // makes it a perfect cheap room-change event signal — far cheaper than
    // decoding the 128×128 map every tick.
    private static final Pattern ROOM_COORD_PATTERN = Pattern.compile("(-?\\d+,-?\\d+)\\s*$");

    private static volatile boolean inSkyblock  = false;
    private static volatile boolean inCatacombs = false;
    private static volatile int     floor       = 0;       // 0 = unknown / not in catacombs
    private static volatile boolean masterMode  = false;
    private static volatile String  rawLocationLine = "";  // for diagnostics
    private static volatile String  roomCoordToken  = "";  // "234,-524" — empty if not extracted
    private static volatile long    roomChangeCounter = 0; // bumps every time roomCoordToken changes

    /** Re-evaluate from the live scoreboard. Cheap: a handful of string scans. */
    public static void tick(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null) {
            reset();
            return;
        }
        // Single-player has no Hypixel scoreboard. Don't bother.
        if (mc.hasSingleplayerServer()) {
            reset();
            return;
        }
        Scoreboard sb = mc.level.getScoreboard();
        if (sb == null) {
            reset();
            return;
        }

        // SKYBLOCK check: the display-slot objective's display name.
        Objective sidebarObj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        boolean nowInSb = false;
        if (sidebarObj != null) {
            String objTitle = stripFormatting(sidebarObj.getDisplayName().getString());
            if (objTitle.toUpperCase().contains("SKYBLOCK")) {
                nowInSb = true;
            }
        }

        boolean nowInCata = false;
        int    nowFloor   = 0;
        boolean nowMaster = false;
        String  nowLine   = "";
        String  nowCoord  = "";
        if (nowInSb && sidebarObj != null) {
            List<String> rawLines = sidebarLines(sb, sidebarObj);
            // Extract the room-coord stamp from line[0] (the top line on
            // Hypixel SkyBlock — date + server id + coords). This works in
            // ANY SkyBlock area, not just Catacombs; we just don't act on
            // changes outside Catacombs.
            if (!rawLines.isEmpty()) {
                String topClean = stripFormatting(rawLines.get(0));
                Matcher cm = ROOM_COORD_PATTERN.matcher(topClean);
                if (cm.find()) nowCoord = cm.group(1);
            }
            for (String line : rawLines) {
                String clean = stripFormatting(line);
                if (clean.contains("The Catacombs")) {
                    nowInCata = true;
                    nowLine = clean;
                    Matcher m = FLOOR_PATTERN.matcher(clean);
                    if (m.find()) {
                        nowMaster = m.group(1).equals("M");
                        try { nowFloor = Integer.parseInt(m.group(2)); } catch (NumberFormatException ignored) {}
                    }
                    break;
                }
            }
        }

        inSkyblock  = nowInSb;
        inCatacombs = nowInCata;
        floor       = nowFloor;
        masterMode  = nowMaster;
        rawLocationLine = nowLine;
        // Bump the room-change counter ONLY when the coord token actually
        // changes AND we're in Catacombs. Outside Catacombs the coord may
        // mean "hub location" which we don't care about. Empty → non-empty
        // also counts (initial entry into Catacombs).
        if (nowInCata && !nowCoord.isEmpty() && !nowCoord.equals(roomCoordToken)) {
            roomCoordToken = nowCoord;
            roomChangeCounter++;
        } else if (!nowInCata) {
            // Leaving Catacombs: clear so a fresh entry counts as a change.
            roomCoordToken = "";
        }
    }

    /** Returns the sidebar lines from TOP to BOTTOM as the player sees them. */
    private static List<String> sidebarLines(Scoreboard sb, Objective obj) {
        // Scoreboard.listPlayerScores(obj) returns score entries. Sort by score
        // DESC — sidebar renders highest score at the top.
        Collection<PlayerScoreEntry> entries = sb.listPlayerScores(obj);
        List<PlayerScoreEntry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> Integer.compare(b.value(), a.value()));
        List<String> out = new ArrayList<>(sorted.size());
        for (PlayerScoreEntry e : sorted) {
            out.add(renderLine(sb, e));
        }
        return out;
    }

    /**
     * Resolve an entry's *visible* line text. Hypixel's scoreboard uses one of
     * two encodings depending on the version of the player's profile:
     *
     *   (1) Modern: `PlayerScoreEntry.display()` returns a non-null Component
     *       containing the visible line text. Use that directly.
     *   (2) Legacy: Hypixel assigns each line to a scoreboard TEAM and stuffs
     *       the visible text into the team's prefix + suffix. The entry's
     *       `owner()` is a cryptic internal key (e.g. "§0§a"), NOT the text.
     *       Look up the team for that owner and concatenate
     *       prefix + owner + suffix.
     *
     * Previously this just returned owner() — which silently worked in the hub
     * (no legacy formatting) but broke in The Catacombs (full team-based
     * prefix/suffix encoding). Symptom: status reported "In Catacombs: no"
     * while the chat clearly said the player entered Catacombs.
     */
    private static String renderLine(Scoreboard sb, PlayerScoreEntry entry) {
        // Path 1 — modern display Component
        Component display = entry.display();
        if (display != null) {
            String s = display.getString();
            if (s != null && !s.isEmpty()) return s;
        }
        // Path 2 — legacy team prefix+suffix decoration
        String owner = entry.owner();
        PlayerTeam team = sb.getPlayersTeam(owner);
        if (team != null) {
            String prefix = team.getPlayerPrefix() != null ? team.getPlayerPrefix().getString() : "";
            String suffix = team.getPlayerSuffix() != null ? team.getPlayerSuffix().getString() : "";
            return prefix + owner + suffix;
        }
        return owner;
    }

    /**
     * Strip every `§X` formatting code, regardless of whether X is a known
     * vanilla code (0-9, a-f, k-r). Hypixel injects CUSTOM codes like `§u`,
     * `§v`, `§w`, `§x`, `§y`, `§q`, `§s` etc. inline with the visible text
     * (apparently for client-side color overrides on their patched client).
     * `ChatFormatting.stripFormatting()` only knows about vanilla codes, so it
     * leaves the Hypixel ones in place, which then corrupts substring matches
     * like `.contains("The Catacombs")` — actual sidebar text we saw was
     * "The Catac§uombs (F1)", so the substring never matched.
     */
    public static String stripFormatting(String s) {
        if (s == null || s.isEmpty()) return "";
        // Strip § followed by ANY single character (including newline guard).
        return s.replaceAll("(?i)\u00a7.", "");
    }

    private static void reset() {
        inSkyblock = false;
        inCatacombs = false;
        floor = 0;
        masterMode = false;
        rawLocationLine = "";
        roomCoordToken = "";
        // Do NOT reset roomChangeCounter — it's a monotonic event counter
        // consumers use to detect changes; clobbering it would suppress the
        // very next change event.
    }

    // ----- accessors

    public static boolean inSkyblock()  { return inSkyblock; }
    public static boolean inCatacombs() { return inCatacombs; }
    public static int     floor()       { return floor; }
    public static boolean masterMode()  { return masterMode; }
    public static String  rawLocationLine() { return rawLocationLine; }
    /** "234,-524" or empty. The trailing X,Z stamp from the top sidebar line. */
    public static String  roomCoordToken()  { return roomCoordToken; }
    /** Monotonic counter that increments every time `roomCoordToken` changes (= Hypixel says we crossed into a new room). */
    public static long    roomChangeCounter() { return roomChangeCounter; }

    /** Dump the entire current sidebar (top-to-bottom, with formatting codes) for debugging. */
    public static List<String> debugSidebarLines() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return List.of();
        Scoreboard sb = mc.level.getScoreboard();
        if (sb == null) return List.of();
        Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) return List.of();
        return sidebarLines(sb, obj);
    }

    /** Dump the sidebar objective title for debugging. */
    public static String debugObjectiveTitle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return "<no level>";
        Scoreboard sb = mc.level.getScoreboard();
        if (sb == null) return "<no scoreboard>";
        Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) return "<no sidebar objective>";
        return obj.getDisplayName().getString();
    }

    public static String  floorLabel() {
        if (!inCatacombs || floor == 0) return "-";
        return (masterMode ? "M" : "F") + floor;
    }
}
