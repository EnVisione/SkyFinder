package com.enviouse.skyfinder.client.render

import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * Small extension surface over Minecraft's Vec3 so the SkyHanni-derived path code reads
 * close to its original (LorenzVec) form without dragging in 200 lines of SkyHanni utils.
 */

operator fun Vec3.plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
operator fun Vec3.minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
operator fun Vec3.times(scalar: Double): Vec3 = Vec3(x * scalar, y * scalar, z * scalar)

fun Vec3.lengthExt(): Double = sqrt(x * x + y * y + z * z)

// Vec3 already provides normalize(), length(), distanceTo(); we don't shadow them.
// Kept here for clarity about what we're relying on.

fun Vec3.distance(other: Vec3): Double {
    val dx = x - other.x
    val dy = y - other.y
    val dz = z - other.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

fun Vec3.distanceSq(other: Vec3): Double {
    val dx = x - other.x
    val dy = y - other.y
    val dz = z - other.z
    return dx * dx + dy * dy + dz * dz
}

fun Vec3.addHalf(): Vec3 = Vec3(x + 0.5, y + 0.5, z + 0.5)

fun Vec3.negated(): Vec3 = Vec3(-x, -y, -z)

/** Project this point onto the line segment [a..b], returning the nearest point on that segment. */
fun Vec3.nearestPointOnLine(a: Vec3, b: Vec3): Vec3 {
    val ab = b - a
    val abLenSq = ab.x * ab.x + ab.y * ab.y + ab.z * ab.z
    if (abLenSq < 1.0e-9) return a
    val apX = x - a.x
    val apY = y - a.y
    val apZ = z - a.z
    val t = ((apX * ab.x + apY * ab.y + apZ * ab.z) / abLenSq).coerceIn(0.0, 1.0)
    return a + ab * t
}
