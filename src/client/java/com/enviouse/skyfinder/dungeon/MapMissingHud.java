/*
 * SkyFinder — MapMissingHud
 *
 * Big intrusive title prompt when we genuinely cannot find the dungeon
 * Magical Map. Triggered when the player is in Catacombs but the inventory
 * scan keeps failing AND we have no cached MapId — typically because they
 * joined with a full inventory + a weapon equipped, so Hypixel had no slot
 * to drop the map item into.
 *
 * IMPORTANT: This is a DISPLAY-ONLY prompt. We never swap the player's
 * hotbar slot ourselves — that would count as a macro and could get the
 * player banned on Hypixel. We just tell them, intrusively, what to do.
 *
 * Why title + sound (not toast or chat):
 *   - The mod literally does NOT WORK without the map cache. This is a
 *     hard-blocking condition; the user needs to know NOW. Toasts sit
 *     quietly in the corner; chat gets buried.
 *   - SkyHanni does the same for "no API key" / "no SkyHanni profile"
 *     prompts — big title + sound — because users routinely miss
 *     low-key signals.
 *   - The sound is CLIENT-SIDE only via player.playSound, so it doesn't
 *     leak to other players in the dungeon.
 *
 * Guards to avoid spam:
 *   - Only triggered while in Catacombs.
 *   - Only fires after we've been in this state for ≥ HOLD_TICKS, to
 *     avoid flashing during the brief race window on dungeon entry.
 *   - Re-display once every REPEAT_TICKS so a player who ignores it
 *     gets reminded but isn't pinned by a constant prompt.
 *   - Auto-clears the title the moment a map is detected, so the prompt
 *     vanishes within a tick of the player switching slots.
 */
package com.enviouse.skyfinder.dungeon;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

public final class MapMissingHud {

    private MapMissingHud() {}

    private static final long HOLD_TICKS    = 20L * 3;   // 3s grace before first alert
    private static final long REPEAT_TICKS  = 20L * 10;  // re-show every 10s while unresolved
    private static final long TITLE_HOLD    = 80;        // 4s visible per show

    private static long missingSinceTick = -1;
    private static long lastShownTick    = -1;
    private static boolean titleActive   = false;
    private static long tickCounter      = 0;

    public static void tick() {
        tickCounter++;
        Minecraft mc = Minecraft.getInstance();
        Gui gui = mc.gui;
        if (gui == null || mc.player == null) return;

        boolean missing = HypixelLocationDetector.inCatacombs()
            && DungeonMapReader.currentMapId() < 0
            && !DungeonMapReader.mapExists();

        if (!missing) {
            // Auto-clear our title the instant the situation resolves so the
            // player doesn't see "switch hotbar slot" lingering after they did.
            if (titleActive) {
                gui.clearTitles();
                titleActive = false;
            }
            missingSinceTick = -1;
            return;
        }

        // SUPPRESS during the dungeon-start countdown + first frames inside
        // the entrance. The `roomChangeCounter` from HypixelLocationDetector
        // bumps once on every server-side room boundary cross:
        //   counter == 1 → player is still in the entrance room (first
        //     coord stamp Hypixel sends after countdown).
        //   counter >= 2 → player has crossed into the first real room.
        // Gating on >= 2 lets the countdown finish AND gives the player a
        // grace second after countdown to switch slots themselves before we
        // get loud. If they DO switch in time, `missing` flips false and
        // the title never fires.
        if (HypixelLocationDetector.roomChangeCounter() < 2) {
            missingSinceTick = -1;
            return;
        }

        if (missingSinceTick < 0) missingSinceTick = tickCounter;
        if (tickCounter - missingSinceTick < HOLD_TICKS) return;
        if (lastShownTick >= 0 && tickCounter - lastShownTick < REPEAT_TICKS) return;

        // Intrusive title slot — large, centered, can't be missed.
        gui.setTitle(
            Component.literal("SkyFinder needs the map")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
        );
        gui.setSubtitle(
            Component.literal("Switch hotbar slot once to reveal the Magical Map")
                .withStyle(ChatFormatting.YELLOW)
        );
        // fadeIn, hold, fadeOut (ticks).
        gui.setTimes(2, (int) TITLE_HOLD, 10);

        // Client-side attention ping. Pitch 1.5 = high "ding" — matches the
        // SkyBlock UI vibe without sounding alarming.
        mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.5f);

        lastShownTick = tickCounter;
        titleActive = true;
    }

    /** Reset on world disconnect so the timer doesn't carry across servers. */
    public static void reset() {
        missingSinceTick = -1;
        lastShownTick = -1;
        titleActive = false;
    }
}
