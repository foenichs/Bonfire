package com.foenichs.bonfire.service.map

import kotlin.math.abs

data class ClaimMapPoint(val x: Double, val z: Double)
data class ClaimPolygonData(val outer: List<ClaimMapPoint>, val holes: List<List<ClaimMapPoint>>)

object ClaimPolygonTracer {
    private const val EPSILON = 0.01

    private data class Segment(val x1: Double, val z1: Double, val x2: Double, val z2: Double)

    private enum class Direction {
        EAST,
        SOUTH,
        WEST,
        NORTH;

        fun priorityTo(next: Direction): Int {
            val turn = (next.ordinal - ordinal + 4) % 4
            return when (turn) {
                3 -> 0 // left
                0 -> 1 // straight
                1 -> 2 // right
                else -> 3 // back
            }
        }
    }

    fun traceChunks(chunkSet: Set<Pair<Int, Int>>): ClaimPolygonData? {
        if (chunkSet.isEmpty()) return null

        val segments = HashSet<Segment>()

        chunkSet.forEach { (cx, cz) ->
            val minX = cx * 16.0
            val maxX = minX + 16.0
            val minZ = cz * 16.0
            val maxZ = minZ + 16.0

            if (!chunkSet.contains(cx to cz - 1)) segments.add(Segment(minX, minZ, maxX, minZ))
            if (!chunkSet.contains(cx to cz + 1)) segments.add(Segment(maxX, maxZ, minX, maxZ))
            if (!chunkSet.contains(cx - 1 to cz)) segments.add(Segment(minX, maxZ, minX, minZ))
            if (!chunkSet.contains(cx + 1 to cz)) segments.add(Segment(maxX, minZ, maxX, maxZ))
        }

        if (segments.isEmpty()) return null

        val loops = mutableListOf<List<ClaimMapPoint>>()

        while (segments.isNotEmpty()) {
            val points = mutableListOf<ClaimMapPoint>()
            val startSegment = segments.first()
            var currentSegment = startSegment
            var closed = false

            points.add(ClaimMapPoint(currentSegment.x1, currentSegment.z1))
            segments.remove(currentSegment)

            while (true) {
                val nextPoint = ClaimMapPoint(currentSegment.x2, currentSegment.z2)
                if (samePoint(nextPoint, points.first())) {
                    closed = true
                    break
                }

                points.add(nextPoint)

                val next = findNextSegment(segments, currentSegment) ?: break
                currentSegment = next
                segments.remove(next)
            }

            if (closed && points.size >= 3) {
                loops.add(optimizePoints(points))
            }
        }

        if (loops.isEmpty()) return null

        val outer = loops.maxByOrNull { abs(signedArea(it)) } ?: return null
        val holes = loops.filter { it !== outer }
        return ClaimPolygonData(outer, holes)
    }

    private fun findNextSegment(segments: Set<Segment>, current: Segment): Segment? {
        val currentDirection = directionOf(current)

        return segments.asSequence()
            .filter {
                abs(it.x1 - current.x2) < EPSILON &&
                    abs(it.z1 - current.z2) < EPSILON
            }
            // Prefer left/straight/right turns so diagonally touching boundaries stay separate simple loops.
            .minByOrNull { currentDirection.priorityTo(directionOf(it)) }
    }

    private fun directionOf(segment: Segment): Direction = when {
        segment.x2 > segment.x1 -> Direction.EAST
        segment.z2 > segment.z1 -> Direction.SOUTH
        segment.x2 < segment.x1 -> Direction.WEST
        else -> Direction.NORTH
    }

    private fun samePoint(a: ClaimMapPoint, b: ClaimMapPoint): Boolean =
        abs(a.x - b.x) < EPSILON && abs(a.z - b.z) < EPSILON

    private fun signedArea(points: List<ClaimMapPoint>): Double {
        var area = 0.0
        for (i in points.indices) {
            val current = points[i]
            val next = points[(i + 1) % points.size]
            area += current.x * next.z - next.x * current.z
        }
        return area / 2.0
    }

    private fun optimizePoints(input: List<ClaimMapPoint>): List<ClaimMapPoint> {
        if (input.size < 3) return input

        return input.filterIndexed { index, current ->
            val previous = input[(index - 1 + input.size) % input.size]
            val next = input[(index + 1) % input.size]

            val vertical = abs(previous.x - current.x) < EPSILON && abs(current.x - next.x) < EPSILON
            val horizontal = abs(previous.z - current.z) < EPSILON && abs(current.z - next.z) < EPSILON
            !vertical && !horizontal
        }
    }
}
