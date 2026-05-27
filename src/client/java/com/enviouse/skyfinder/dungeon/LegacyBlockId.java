/*
 * SkyFinder — LegacyBlockId
 *
 * Maps modern 1.21.11 Block instances to DRM's 1.8.9-era `blockId*100 + meta`
 * identifiers. The .skeleton files store coordinates keyed by these legacy
 * identifiers; without this mapping the matcher would always say "no match".
 *
 * Identifier numbers come straight from DRM's RoomDetectionUtils.whitelistedBlocks
 * — these are the ONLY blocks DRM scans (the high-information dungeon
 * construction blocks: stone, polished granite/diorite/andesite, dirt, cobble,
 * mossy stone bricks, terracotta variants, etc.). Anything outside this
 * whitelist returns -1 and is skipped by the matcher.
 *
 * Translation source: Mojang's 1.8.9 → 1.13+ flattening table for these
 * specific blocks (single-block IDs are unambiguous; the only metadata
 * variants we care about are the stone-variant family at id 100 and the
 * stained-clay/wool families at id 159/35).
 */
package com.enviouse.skyfinder.dungeon;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;

public final class LegacyBlockId {

    private LegacyBlockId() {}

    private static final Map<Block, Integer> TABLE = new HashMap<>();

    static {
        // id*100 + meta. Values lifted verbatim from DRM's
        // RoomDetectionUtils.whitelistedBlocks.

        // id 1 (stone variants): meta 0=stone, 3=diorite, 4=polished_diorite, 5=andesite, 6=polished_andesite
        TABLE.put(Blocks.STONE,              100);
        TABLE.put(Blocks.DIORITE,            103);
        TABLE.put(Blocks.POLISHED_DIORITE,   104);
        TABLE.put(Blocks.ANDESITE,           105);
        TABLE.put(Blocks.POLISHED_ANDESITE,  106);

        // id 2 = grass, id 3 = dirt family
        TABLE.put(Blocks.GRASS_BLOCK,        200);
        TABLE.put(Blocks.DIRT,               300);
        TABLE.put(Blocks.COARSE_DIRT,        301);

        // id 4 = cobble, id 7 = bedrock, id 18 = oak leaves
        TABLE.put(Blocks.COBBLESTONE,        400);
        TABLE.put(Blocks.BEDROCK,            700);
        TABLE.put(Blocks.OAK_LEAVES,         1800);

        // id 35 = wool family. DRM whitelists meta 7 = gray.
        TABLE.put(Blocks.GRAY_WOOL,          3507);

        // id 43 = double_stone_slab. Modern: SMOOTH_STONE block represents
        // what DRM called "double stone slab" (the polished smooth-stone
        // variant). Hypixel-built rooms still use this.
        TABLE.put(Blocks.SMOOTH_STONE,       4300);

        // id 48 = mossy cobblestone, id 82 = clay block
        TABLE.put(Blocks.MOSSY_COBBLESTONE,  4800);
        TABLE.put(Blocks.CLAY,               8200);

        // id 98 = stone bricks family: meta 0=normal, 1=mossy, 3=chiseled
        TABLE.put(Blocks.STONE_BRICKS,         9800);
        TABLE.put(Blocks.MOSSY_STONE_BRICKS,   9801);
        TABLE.put(Blocks.CHISELED_STONE_BRICKS,9803);
        // Note: cracked stone bricks (meta 2 = 9802) NOT in DRM whitelist.

        // id 159 = stained clay (terracotta) family. DRM whitelists meta
        // 7=gray, 9=cyan, 15=black.
        TABLE.put(Blocks.GRAY_TERRACOTTA,    15907);
        TABLE.put(Blocks.CYAN_TERRACOTTA,    15909);
        TABLE.put(Blocks.BLACK_TERRACOTTA,   15915);
    }

    /** Returns DRM's legacy identifier (blockId*100+meta), or -1 if not on the whitelist. */
    public static int idFor(Block block) {
        if (block == null) return -1;
        Integer i = TABLE.get(block);
        return i == null ? -1 : i;
    }

    public static boolean isWhitelisted(Block block) {
        return TABLE.containsKey(block);
    }

    public static int whitelistSize() { return TABLE.size(); }
}
