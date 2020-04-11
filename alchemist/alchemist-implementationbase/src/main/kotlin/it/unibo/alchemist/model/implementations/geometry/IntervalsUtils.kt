/*
 * Copyright (C) 2010-2020, Danilo Pianini and contributors
 * listed in the main project's alchemist/build.gradle.kts file.
 *
 * This file is part of Alchemist, and is distributed under the terms of the
 * GNU General Public License, with a linking exception,
 * as described in the file LICENSE in the Alchemist distribution's top directory.
 */

package it.unibo.alchemist.model.implementations.geometry

import it.unibo.alchemist.model.implementations.positions.Euclidean2DPosition
import it.unibo.alchemist.model.interfaces.geometry.euclidean.twod.Segment2D
import org.danilopianini.lang.MathUtils.fuzzyEquals
import kotlin.math.max
import kotlin.math.min

/**
 * Checks if a double value lies between two values (included) provided in any order.
 */
fun Double.liesBetween(v1: Double, v2: Double) = this >= min(v1, v2) && this <= max(v1, v2)

/**
 * Checks if a double value lies between two values (included) provided in any order with
 * some tolerance.
 */
fun Double.fuzzyLiesBetween(v1: Double, v2: Double) =
    liesBetween(v1, v2) || fuzzyEquals(this, v1) || fuzzyEquals(this, v2)

/**
 * Defines an interval with double precision.
 */
class DoubleInterval(
    endPoint1: Double,
    endPoint2: Double
) {
    /**
     */
    val first: Double = min(endPoint1, endPoint2)
    /**
     */
    val second: Double = max(endPoint1, endPoint2)

    /**
     * Checks whether the interval is contained in another given interval.
     */
    fun isContained(other: DoubleInterval) = other.first <= first && other.second >= second

    /**
     * Finds the intersection between two intervals. If the intervals do not
     * intersect, null is returned.
     */
    fun intersection(other: DoubleInterval): DoubleInterval? {
        if (other.second < first || second < other.first) {
            return null
        }
        return DoubleInterval(max(first, other.first), min(second, other.second))
    }

    /**
     * Checks whether the interval intersects with the one given.
     */
    fun intersects(other: DoubleInterval) = intersection(other) != null

    /**
     * Finds the intersection between two intervals. If the intervals do not
     * intersect or share a single endpoint, null is returned.
     */
    fun intersectionEndpointsExcluded(other: DoubleInterval): DoubleInterval? {
        if (other.second <= first || second <= other.first) {
            return null
        }
        return DoubleInterval(max(first, other.first), min(second, other.second))
    }

    /**
     * Checks whether the interval intersects with the one given, endpoints are exluded.
     */
    fun intersectsEndpointsExcluded(other: DoubleInterval) =
        intersectionEndpointsExcluded(other) != null

    /**
     * Subtracts a given interval from the current one.
     */
    fun subtract(other: DoubleInterval): MutableList<DoubleInterval> =
        when {
            isContained(other) -> mutableListOf()
            !intersects(other) -> mutableListOf(this)
            other.first <= first -> mutableListOf(DoubleInterval(other.second, second))
            other.second >= second -> mutableListOf(DoubleInterval(first, other.first))
            else -> mutableListOf(DoubleInterval(first, other.first), DoubleInterval(other.second, second))
        }

    /**
     * Subtracts all the given intervals from the current one.
     */
    fun subtractAll(intervals: Collection<DoubleInterval>): Collection<DoubleInterval> {
        if (intervals.isEmpty()) {
            return mutableListOf(this)
        }
        return subtract(intervals.first()).flatMap { it.subtractAll(intervals.drop(1)) }
    }

    companion object {
        /**
         * Maps a segment to an interval, the parameter [xAxisDirection]
         * indicates which pair of coords (x or y) should be extracted.
         */
        fun Segment2D<*>.toInterval(xAxisDirection: Boolean = xAxisAligned) =
            if (xAxisDirection) DoubleInterval(first.x, second.x) else DoubleInterval(first.y, second.y)

        /**
         * Given a collection of points, finds the two extreme points in a
         * given direction (i.e. min and max in such direction). Only axis
         * aligned directions are supported now.
         */
        fun Collection<Euclidean2DPosition>.findExtremePoints(xAxisDirection: Boolean): DoubleInterval {
            val selector: (Euclidean2DPosition) -> Double = { if (xAxisDirection) it.x else it.y }
            val min = minBy(selector)?.run(selector)
            val max = maxBy(selector)?.run(selector)
            require(min != null && max != null) { "no point could be found" }
            return DoubleInterval(min, max)
        }
    }
}
