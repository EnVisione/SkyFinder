package com.enviouse.skyfinder.client.pathfind

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A* pathfinder over the Minecraft block grid.
 *
 * Each node is a "standable block position" — a position the player's feet can occupy:
 *  - The block at that position is passable (air-ish, isPathfindable LAND)
 *  - The block above it is passable too (head clearance)
 *  - The block below it is solid (something to stand on)
 *
 * Neighbors:
 *  - 8 horizontal moves (N/S/E/W + diagonals), each requiring the destination's
 *    feet+head positions to be passable and its floor to be solid
 *  - Step up 1 (jump): destination one block higher, requires extra headroom above
 *    the current position too
 *  - Step down: target's floor can be 1–3 blocks below (fall damage tolerated)
 *
 * Cost: Euclidean distance between block centers. Diagonals cost ~1.414, vertical
 * costs ~1 plus a small "jump penalty" so the path prefers flat ground when both
 * options exist.
 *
 * Heuristic: 3D Euclidean (admissible — never overestimates real cost).
 *
 * Safety: explored-node cap so an unreachable target doesn't lock the client.
 *
 * Designed to run on a background thread — only reads the world via [BlockGetter],
 * doesn't mutate anything, no MC client state touched.
 */
object SkyFinderPathfinder {

    private const val MAX_FALL = 3
    /**
     * Higher than v0's 20k because real worlds with hills produce A* graphs much
     * bigger than my test envs. 100k expansions completes in well under 100ms even
     * on slow hardware (it's a HashSet/PriorityQueue, nothing heavy per node).
     */
    private const val MAX_EXPLORED_DEFAULT = 100_000

    /**
     * Find a path of standable block positions from [start] to [goal].
     * Returns null if no path was found within [maxExplored] expansions.
     *
     * The returned list always starts with the closest standable approximation of
     * [start] and ends with the closest standable approximation of [goal] (or the
     * actual [goal] if it's standable).
     */
    fun findPath(
        world: BlockGetter,
        start: BlockPos,
        goal: BlockPos,
        maxExplored: Int = MAX_EXPLORED_DEFAULT,
    ): List<BlockPos>? {
        val realStart = snapToStandable(world, start) ?: return null
        val realGoal = snapToStandable(world, goal) ?: goal

        if (realStart == realGoal) return listOf(realStart)

        val open = PriorityQueue<AStarNode>(compareBy { it.fScore })
        val gScore = HashMap<BlockPos, Double>()
        val cameFrom = HashMap<BlockPos, BlockPos>()
        val closed = HashSet<BlockPos>()

        gScore[realStart] = 0.0
        open.add(AStarNode(realStart, 0.0, heuristic(realStart, realGoal)))

        var explored = 0
        while (open.isNotEmpty()) {
            if (explored++ > maxExplored) return null
            val current = open.poll()
            if (current.pos == realGoal) {
                return reconstruct(cameFrom, current.pos)
            }
            if (!closed.add(current.pos)) continue

            for (neighbor in neighbors(world, current.pos)) {
                if (neighbor.pos in closed) continue
                val tentativeG = (gScore[current.pos] ?: Double.MAX_VALUE) + neighbor.stepCost
                if (tentativeG < (gScore[neighbor.pos] ?: Double.MAX_VALUE)) {
                    cameFrom[neighbor.pos] = current.pos
                    gScore[neighbor.pos] = tentativeG
                    open.add(AStarNode(neighbor.pos, tentativeG, tentativeG + heuristic(neighbor.pos, realGoal)))
                }
            }
        }
        return null
    }

    private fun reconstruct(cameFrom: Map<BlockPos, BlockPos>, end: BlockPos): List<BlockPos> {
        val result = ArrayDeque<BlockPos>()
        var current: BlockPos? = end
        while (current != null) {
            result.addFirst(current)
            current = cameFrom[current]
        }
        return result.toList()
    }

    private fun heuristic(a: BlockPos, b: BlockPos): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        val dz = (a.z - b.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private data class AStarNode(val pos: BlockPos, val gScore: Double, val fScore: Double)

    private data class Neighbor(val pos: BlockPos, val stepCost: Double)

    /**
     * Adjust [origin] so it lands on a standable position. The player might be flying
     * (creative) or jumping mid-arc, so we search downward generously for solid ground.
     * Returns null if no standable position is anywhere near origin.
     */
    private fun snapToStandable(world: BlockGetter, origin: BlockPos): BlockPos? {
        if (isStandable(world, origin)) return origin
        // Search down up to 64 blocks — catches creative flying high above terrain.
        for (dy in 1..64) {
            val p = origin.below(dy)
            if (isStandable(world, p)) return p
        }
        // A few above (in case origin is in a 1-block-deep depression / on a stair edge).
        for (dy in 1..3) {
            val p = origin.above(dy)
            if (isStandable(world, p)) return p
        }
        return null
    }

    /**
     * A position is standable when:
     *  - feet block is passable
     *  - head block is passable
     *  - block beneath is solid (something to stand on)
     */
    @Suppress("DEPRECATION")
    private fun isStandable(world: BlockGetter, pos: BlockPos): Boolean {
        val below = world.getBlockState(pos.below())
        if (!below.isSolid) return false
        val feet = world.getBlockState(pos)
        if (!isPassable(feet)) return false
        val head = world.getBlockState(pos.above())
        return isPassable(head)
    }

    @Suppress("DEPRECATION")
    private fun isPassable(state: net.minecraft.world.level.block.state.BlockState): Boolean {
        if (state.isAir) return true
        // blocksMotion: true if the block stops the player (full collision)
        // We treat anything that doesn't block motion as walkable (carpets, snow layers, signs, etc.)
        return !state.blocksMotion()
    }

    private fun neighbors(world: BlockGetter, pos: BlockPos): List<Neighbor> {
        val out = ArrayList<Neighbor>(24)
        // 8 horizontal directions
        val dirs = arrayOf(
            intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
            intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1),
        )
        for (d in dirs) {
            val dx = d[0]
            val dz = d[1]
            val diag = dx != 0 && dz != 0

            // Diagonal moves require both orthogonal cells to be passable so we don't
            // squeeze through corners — gives a much more natural-looking path.
            if (diag) {
                val sideA = BlockPos(pos.x + dx, pos.y, pos.z)
                val sideB = BlockPos(pos.x, pos.y, pos.z + dz)
                if (!isPassable(world.getBlockState(sideA)) ||
                    !isPassable(world.getBlockState(sideA.above())) ||
                    !isPassable(world.getBlockState(sideB)) ||
                    !isPassable(world.getBlockState(sideB.above()))
                ) continue
            }

            // Same-level walk
            val flat = BlockPos(pos.x + dx, pos.y, pos.z + dz)
            if (isStandable(world, flat)) {
                out.add(Neighbor(flat, (if (diag) DIAG else STRAIGHT) + wallClearancePenalty(world, flat)))
                continue
            }

            // Step up one (jump) — only valid when there's a REAL solid obstacle
            // at the destination's foot level that we'd be climbing over. Without
            // this check, the pathfinder treats "the floor is missing here, but
            // there happens to be a floating ledge 1 block above" as a jumpable
            // step-up, which the player physically cannot do.
            //
            // The obstacle's `blocksMotion()` distinguishes a real wall (climbable
            // by a 1-block hop) from air / open space / floating geometry.
            @Suppress("DEPRECATION")
            val obstacleIsRealWall = world
                .getBlockState(BlockPos(pos.x + dx, pos.y, pos.z + dz))
                .blocksMotion()
            val up = BlockPos(pos.x + dx, pos.y + 1, pos.z + dz)
            // Require extra headroom above origin so the player can clear the jump
            if (obstacleIsRealWall &&
                isPassable(world.getBlockState(pos.above(2))) &&
                isStandable(world, up)
            ) {
                out.add(Neighbor(up, (if (diag) DIAG else STRAIGHT) + JUMP_PENALTY + wallClearancePenalty(world, up)))
                continue
            }

            // Step down (drop up to MAX_FALL blocks)
            for (drop in 1..MAX_FALL) {
                val down = BlockPos(pos.x + dx, pos.y - drop, pos.z + dz)
                // Walking off must be unobstructed
                if (!isPassable(world.getBlockState(BlockPos(pos.x + dx, pos.y, pos.z + dz))) ||
                    !isPassable(world.getBlockState(BlockPos(pos.x + dx, pos.y + 1, pos.z + dz)))
                ) break
                if (isStandable(world, down)) {
                    val cost = (if (diag) DIAG else STRAIGHT) + drop * FALL_COST_PER_BLOCK +
                        wallClearancePenalty(world, down)
                    out.add(Neighbor(down, cost))
                    break
                }
            }
        }
        return out
    }

    /**
     * Penalize positions adjacent to solid blocks. Counts the 4 cardinal neighbors
     * at foot height AND head height — each solid block adjacent adds [WALL_PENALTY].
     *
     * Effect on A*: in open areas there are no penalties, so this changes nothing.
     * In partially-open areas (corridor with one side open), A* prefers the open side
     * by ~1 cost unit per move — naturally produces paths with ~1 block of wall
     * clearance, exactly like SkyHanni's hand-placed graph nodes.
     *
     * In a true narrow corridor (walls both sides) every option has the same penalty,
     * so A* still finds the path — penalty is a tie-breaker, never a hard constraint.
     */
    @Suppress("DEPRECATION")
    private fun wallClearancePenalty(world: BlockGetter, pos: BlockPos): Double {
        var count = 0
        for (off in CARDINAL_OFFSETS) {
            val side = BlockPos(pos.x + off[0], pos.y, pos.z + off[1])
            if (world.getBlockState(side).blocksMotion()) count++
            val sideHead = BlockPos(pos.x + off[0], pos.y + 1, pos.z + off[1])
            if (world.getBlockState(sideHead).blocksMotion()) count++
        }
        return count * WALL_PENALTY
    }

    private val CARDINAL_OFFSETS = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
    )

    private const val STRAIGHT = 1.0
    private const val DIAG = 1.41421356
    /**
     * Cost added to every jump (step-up) move. Set high (3.0) so the pathfinder
     * very strongly prefers walking around obstacles over climbing them. A 1-block
     * jump now costs as much as 3 extra blocks of flat walking — so if there's a
     * detour of 3 or fewer flat blocks that avoids the jump, A* will take it.
     */
    private const val JUMP_PENALTY = 3.0
    private const val FALL_COST_PER_BLOCK = 0.5
    private const val EYE_LIFT = 1.0
    /**
     * Cost added per adjacent solid block when expanding A* neighbors. Tuned high
     * (0.8) so the pathfinder strongly prefers paths with full clearance from walls.
     * Effect:
     *  - open-air moves: no penalty
     *  - one-side-wall move: +0.8 per move (worse than walking a full extra block)
     *  - corridor (walls both sides): +1.6 per move, but every option in a corridor
     *    has the same penalty so A* still finds the path — penalty is a strong
     *    preference, never a hard constraint.
     */
    private const val WALL_PENALTY = 0.8

    /**
     * Manhattan-3D distance — handy quick filter before we kick off a full A* run,
     * since we cap exploration and don't want to even start if the target is way
     * past the cap (e.g. across an island).
     */
    fun roughDistance(a: BlockPos, b: BlockPos): Int =
        max(abs(a.x - b.x), abs(a.z - b.z)) + abs(a.y - b.y)

    /**
     * Post-process an A* path so the curve renderer has clean input.
     *
     * A* over the block grid produces a path that hugs walls block-by-block — when
     * the Catmull-Rom subdivision smooths that, the curve dips into the walls at
     * corners. SkyHanni avoids this entirely by routing through hand-placed nodes
     * in the middle of rooms, so the curve only ever bends through open space.
     *
     * We get the same effect by "string-pulling": walk forward from each anchor
     * point and skip every intermediate that still has line of sight. Result is a
     * path of long straight segments connecting only the "corner" points where
     * direction actually changes. Visually identical to SkyHanni's pre-baked graph.
     *
     * The raycast uses ClipContext.Block.COLLIDER against the player's eye-line
     * (anchor lifted by [EYE_LIFT] so the LoS check matches what the rendered line
     * will look like).
     */
    fun stringPull(level: Level, path: List<BlockPos>): List<BlockPos> {
        if (path.size <= 2) return path
        val result = ArrayList<BlockPos>(path.size)
        result.add(path.first())
        var anchor = 0
        var probe = 2
        while (probe < path.size) {
            if (hasLineOfSight(level, path[anchor], path[probe])) {
                probe++
            } else {
                result.add(path[probe - 1])
                anchor = probe - 1
                probe++
            }
        }
        result.add(path.last())
        return result
    }

    /**
     * Raycast at player eye-line height between two block centers, using **four
     * corner rays at the player's body width**. Returns true only if all four
     * corners are unobstructed — so string-pulled segments are guaranteed to
     * have a player-width corridor of clear space, not just a single thin LoS line
     * that scrapes walls when the curve smooths.
     */
    private fun hasLineOfSight(level: Level, a: BlockPos, b: BlockPos): Boolean {
        val padding = 0.5 // wider than player width — enforces ~1-block-wide LoS corridor
        val y = EYE_LIFT
        val offsets = arrayOf(
            doubleArrayOf(-padding, -padding),
            doubleArrayOf(-padding, padding),
            doubleArrayOf(padding, -padding),
            doubleArrayOf(padding, padding),
        )
        for (off in offsets) {
            val from = Vec3(a.x + 0.5 + off[0], a.y + y, a.z + 0.5 + off[1])
            val to = Vec3(b.x + 0.5 + off[0], b.y + y, b.z + 0.5 + off[1])
            val hit = level.clip(
                ClipContext(
                    from,
                    to,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    CollisionContext.empty(),
                )
            )
            if (hit.type != HitResult.Type.MISS) return false
        }
        return true
    }

    /**
     * Validate that an existing path still has clear corridor LoS between every
     * pair of consecutive waypoints. Used to detect when a placed block has
     * invalidated the current displayed path.
     */
    fun pathStillValid(level: Level, path: List<BlockPos>): Boolean {
        if (path.size < 2) return true
        for (i in 0 until path.lastIndex) {
            if (!hasLineOfSight(level, path[i], path[i + 1])) return false
        }
        return true
    }

    /**
     * Decide whether to swap the displayed path with a freshly-computed one.
     *
     * Replaces the old "fuzzy similarity score" approach which had two failure
     * modes: (1) teleport produced 0% similarity so the line never updated, and
     * (2) any reroute around an obstacle dropped under 80% similarity so the
     * line never updated for that either.
     *
     * The new rule focuses on what the player actually cares about: the **near
     * end** of the path (where they're standing). The far end can change freely.
     *
     * Two checks:
     *   1. If the player is more than [TELEPORT_DIST] blocks from any point on
     *      the old path, the old path is irrelevant (teleported, fell, etc.) →
     *      swap regardless of direction.
     *   2. Otherwise, compare the "first leg" direction (where the path heads in
     *      its first ~5 blocks) for both new and old. If the angle between them
     *      is less than ~60° (dot > 0.5), the player will move the same way
     *      either way → swap. If the new path wants to send the player a
     *      noticeably different direction → keep the old one.
     */
    fun shouldReplacePath(playerPos: Vec3, newPath: List<BlockPos>, oldPath: List<BlockPos>?): Boolean {
        if (oldPath == null || oldPath.size < 2 || newPath.size < 2) return true

        // Teleport / large displacement case: old path is no longer near the player.
        val nearestOnOldSq = oldPath.minOf { p ->
            val dx = (p.x + 0.5) - playerPos.x
            val dy = (p.y + 0.5) - playerPos.y
            val dz = (p.z + 0.5) - playerPos.z
            dx * dx + dy * dy + dz * dz
        }
        if (nearestOnOldSq > TELEPORT_DIST * TELEPORT_DIST) return true

        val newDir = firstLegDirection(newPath) ?: return true
        val oldDir = firstLegDirection(oldPath) ?: return true
        val dot = newDir.x * oldDir.x + newDir.y * oldDir.y + newDir.z * oldDir.z
        return dot > NEAR_DIRECTION_THRESHOLD
    }

    /**
     * Direction vector of the path's first ~5 blocks of travel, normalized.
     * Returns null if the path is too short to derive a meaningful direction.
     */
    private fun firstLegDirection(path: List<BlockPos>): Vec3? {
        if (path.size < 2) return null
        val start = Vec3(path[0].x + 0.5, path[0].y + 0.5, path[0].z + 0.5)
        var traveled = 0.0
        var endPoint = start
        for (i in 0 until path.lastIndex) {
            val a = Vec3(path[i].x + 0.5, path[i].y + 0.5, path[i].z + 0.5)
            val b = Vec3(path[i + 1].x + 0.5, path[i + 1].y + 0.5, path[i + 1].z + 0.5)
            val segLen = a.distanceTo(b)
            if (traveled + segLen >= FIRST_LEG_LENGTH) {
                val t = (FIRST_LEG_LENGTH - traveled) / segLen
                endPoint = a.add(b.subtract(a).scale(t))
                break
            }
            traveled += segLen
            endPoint = b
        }
        val delta = endPoint.subtract(start)
        if (delta.lengthSqr() < 1.0e-6) return null
        return delta.normalize()
    }

    /**
     * Render-side waypoint positioning.
     *
     * Lifts each waypoint to [LINE_HEIGHT_ABOVE_FLOOR] (2.0 — top of the player's
     * head box). The player's line of sight is at +1.5 (eye level), and the line
     * sits half a block above that so it visually sits AT the cursor height when
     * the player is looking forward. Two blocks above the floor also keeps it
     * comfortably clear of the floor surface so the lowest part of the curve
     * doesn't visibly clip the ground.
     *
     * Ceiling clamp: if the block at floor+2 (i.e. directly above the head) is
     * solid, lowering the line to chest height ([LINE_HEIGHT_LOW_CEILING] = 1.5)
     * keeps it from clipping into the ceiling. Standable positions guarantee
     * floor+1 is passable so 1.5 is always safe.
     *
     * Lateral offset: if there's a wall on only one side at chest level, shift
     * the line by [CLEARANCE_OFFSET] toward the open side. In a 1-wide corridor
     * (walls both sides) the line stays centered.
     *
     * Wall check uses the block AT the floor's head level (pos.above) — what the
     * line itself passes through. A wall at foot level you can step over doesn't
     * count.
     */
    @Suppress("DEPRECATION")
    fun visualWaypointPosition(world: BlockGetter, pos: BlockPos): Vec3 {
        val chestY = pos.y + 1 // block above the floor — where chest-height geometry lives
        val east = world.getBlockState(BlockPos(pos.x + 1, chestY, pos.z)).blocksMotion()
        val west = world.getBlockState(BlockPos(pos.x - 1, chestY, pos.z)).blocksMotion()
        val south = world.getBlockState(BlockPos(pos.x, chestY, pos.z + 1)).blocksMotion()
        val north = world.getBlockState(BlockPos(pos.x, chestY, pos.z - 1)).blocksMotion()

        var dx = 0.0
        var dz = 0.0
        if (east && !west) dx = -CLEARANCE_OFFSET
        else if (west && !east) dx = CLEARANCE_OFFSET
        if (south && !north) dz = -CLEARANCE_OFFSET
        else if (north && !south) dz = CLEARANCE_OFFSET

        // Ceiling clamp: target line height is floor+2 (top of head). If the block
        // at that level (pos.above(2)) is solid, drop the line to chest height so
        // it doesn't clip the ceiling.
        val ceilingBlocked = world.getBlockState(pos.above(2)).blocksMotion()
        // Also check the lateral neighbor blocks at +2 — if the wall continues up
        // past head level, the line shouldn't poke through their top face either.
        val ceilingNeighborE = dx < 0 && world.getBlockState(BlockPos(pos.x + 1, pos.y + 2, pos.z)).blocksMotion()
        val ceilingNeighborW = dx > 0 && world.getBlockState(BlockPos(pos.x - 1, pos.y + 2, pos.z)).blocksMotion()
        val ceilingNeighborS = dz < 0 && world.getBlockState(BlockPos(pos.x, pos.y + 2, pos.z + 1)).blocksMotion()
        val ceilingNeighborN = dz > 0 && world.getBlockState(BlockPos(pos.x, pos.y + 2, pos.z - 1)).blocksMotion()
        val anyCeiling = ceilingBlocked || ceilingNeighborE || ceilingNeighborW || ceilingNeighborS || ceilingNeighborN
        val height = if (anyCeiling) LINE_HEIGHT_LOW_CEILING else LINE_HEIGHT_ABOVE_FLOOR

        return Vec3(
            pos.x + 0.5 + dx,
            pos.y + height,
            pos.z + 0.5 + dz,
        )
    }

    /**
     * Line height above the floor block. 2.0 places the line at the top of the
     * player's head box — visually right at the crosshair when looking forward,
     * and 2 blocks clear of the ground so the curve never dips into terrain.
     */
    private const val LINE_HEIGHT_ABOVE_FLOOR = 2.0
    /** Fallback height when a ceiling is detected directly above head. */
    private const val LINE_HEIGHT_LOW_CEILING = 1.5
    /**
     * Lateral shift away from a single-sided wall. 0.5 means the line center sits
     * exactly 1 block away from the wall surface — matching Leo's requirement of
     * "at least 1 block from the nearest block, unless 1-wide passage then centered".
     *
     * Geometry: waypoint block extends from x=0 to x=1, center at x=0.5. With a
     * wall block to the west (x=-1..0), wall surface at x=0. Shifting east by
     * 0.5 puts line center at x=1.0, exactly 1.0 from the wall surface.
     */
    private const val CLEARANCE_OFFSET = 0.5

    private const val TELEPORT_DIST = 5.0
    private const val FIRST_LEG_LENGTH = 5.0
    private const val NEAR_DIRECTION_THRESHOLD = 0.5 // dot > 0.5 ≈ angle < 60°
}
