package it.unibo.alchemist.model.implementations.geometry.euclidean.twod

import it.unibo.alchemist.model.implementations.geometry.isDegenerate
import it.unibo.alchemist.model.implementations.geometry.vertices
import it.unibo.alchemist.model.implementations.geometry.intersection
import it.unibo.alchemist.model.implementations.geometry.zCross
import it.unibo.alchemist.model.implementations.geometry.toVector
import it.unibo.alchemist.model.implementations.geometry.contains
import it.unibo.alchemist.model.implementations.geometry.SegmentsIntersectionTypes.POINT
import it.unibo.alchemist.model.implementations.geometry.SegmentsIntersectionTypes.EMPTY
import it.unibo.alchemist.model.implementations.positions.Euclidean2DPosition
import it.unibo.alchemist.model.interfaces.geometry.GeometricShape
import it.unibo.alchemist.model.interfaces.geometry.euclidean.twod.Euclidean2DEdge
import it.unibo.alchemist.model.interfaces.geometry.euclidean.twod.Euclidean2DShape
import it.unibo.alchemist.model.interfaces.geometry.euclidean.twod.Euclidean2DTransformation
import it.unibo.alchemist.model.interfaces.geometry.euclidean.twod.ConvexPolygon
import it.unibo.alchemist.model.interfaces.geometry.euclidean.twod.MutableConvexPolygon

import java.awt.Shape
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.util.Optional

/**
 * Implementation of a [MutableConvexPolygon].
 *
 * Each modification operation on this object has a time complexity of
 * O(n), where n is the number of vertices/edges.
 */
open class MutableConvexPolygonImpl(
    private val vertices: MutableList<Euclidean2DPosition>
) : MutableConvexPolygon {

    init {
        // at least 3 non degenerate edges to make a polygon
        require(vertices.indices.filter { !getEdge(it).isDegenerate() }.size >= 3 && isConvex()) {
            "Given vertices do not represent a convex polygon"
        }
    }

    companion object {
        /**
         * Creates a MutableConvexPolygon from a java.awt.Shape.
         * If the Polygon could not be created (e.g. because of the
         * non-convexity of the given shape), an empty optional is
         * returned.
         * Each curved segment of the shape will be considered as
         * a straight line.
         */
        fun fromShape(s: Shape): Optional<MutableConvexPolygon> {
            return try {
                Optional.of(MutableConvexPolygonImpl(s.vertices().toMutableList()))
            } catch (e: IllegalArgumentException) {
                Optional.empty()
            }
        }
    }

    /*
     * An AwtEuclidean2DShape is immutable, thus composition is used
     * over inheritance.
     */
    private var shape: AwtEuclidean2DShape? = null

    override val diameter: Double = getShape().diameter

    override val centroid: Euclidean2DPosition = getShape().centroid

    override fun vertices(): List<Euclidean2DPosition> = vertices

    override fun addVertex(x: Double, y: Double) = addVertex(vertices.size, x, y)

    override fun addVertex(index: Int, x: Double, y: Double): Boolean {
        vertices.add(index, Euclidean2DPosition(x, y))
        /*
         * Only the modified/new edges are passed, which varies depending
         * on the operation performed (addition/removal of a vertex/edge).
         */
        if (isConvex(circularPrev(index), index)) {
            shape = null
            return true
        }
        vertices.removeAt(index)
        return false
    }

    override fun removeVertex(index: Int): Boolean {
        if (vertices.size == 3) {
            return false
        }
        val oldV = vertices[index]
        vertices.removeAt(index)
        if (isConvex(circularPrev(index))) {
            shape = null
            return true
        }
        vertices.add(index, oldV)
        return false
    }

    override fun moveVertex(index: Int, newX: Double, newY: Double): Boolean {
        val oldV = vertices[index]
        vertices[index] = Euclidean2DPosition(newX, newY)
        if (isConvex(circularPrev(index), index)) {
            shape = null
            return true
        }
        vertices[index] = oldV
        return false
    }

    override fun getEdge(index: Int) = Pair(vertices[index], vertices[circularNext(index)])

    override fun moveEdge(index: Int, newEdge: Euclidean2DEdge): Boolean {
        val oldEdge = getEdge(index)
        vertices[index] = newEdge.first
        vertices[circularNext(index)] = newEdge.second
        if (isConvex(circularPrev(index), index, circularNext(index))) {
            shape = null
            return true
        }
        moveEdge(index, oldEdge)
        return false
    }

    override fun intersects(other: Euclidean2DShape) = getShape().intersects(other)

    /*
     * Delegates to java.awt.Area.
     */
    override fun intersects(other: Shape): Boolean {
        val a = Area(asAwtShape())
        a.intersect(Area(other))
        return !a.isEmpty
    }

    override fun contains(vector: Euclidean2DPosition) = getShape().contains(vector)

    override fun containsOrLiesOnBoundary(vector: Euclidean2DPosition) =
        contains(vector) || vertices.indices.map { getEdge(it) }.any { it.contains(vector) }

    /*
     * Delegates to java.awt.Area.
     */
    override fun union(polygons: Collection<ConvexPolygon>): Boolean {
        if (polygons.any { !intersects(it.asAwtShape()) }) {
            return false // polygons are not overlapping
        }
        val union = Area(asAwtShape())
        polygons.forEach { union.add(Area(it.asAwtShape())) }
        val resultingPolygon = fromShape(union)
        if (resultingPolygon.isPresent) {
            mutateTo(resultingPolygon.get())
            return true
        }
        return false
    }

    override fun transformed(transformation: Euclidean2DTransformation.() -> Unit) =
        getShape().transformed(transformation) as GeometricShape<Euclidean2DPosition, Euclidean2DTransformation>

    final override fun asAwtShape() = getShape().asAwtShape()

    /**
     */
    override fun equals(other: Any?) =
        other != null && (this === other || (other is MutableConvexPolygonImpl && vertices == other.vertices))

    /**
     */
    override fun hashCode() = vertices.hashCode()

    /**
     * Mutates this polygon to a copy of the specified one.
     */
    protected open fun mutateTo(p: MutableConvexPolygon) {
        p.vertices().forEachIndexed { i, newV ->
            if (i < vertices.size) {
                vertices[i] = newV
            } else {
                vertices.add(i, newV)
            }
        }
        while (vertices.size > p.vertices().size) {
            vertices.removeAt(vertices.size - 1)
        }
        shape = null // invalid cached shape
    }

    /**
     */
    protected fun circularPrev(index: Int) = (index - 1 + vertices.size) % vertices.size

    /**
     */
    protected fun circularNext(index: Int) = (index + 1) % vertices.size

    /*
     * In order to be convex, a polygon must first be simple (not self-
     * intersecting). Ascertained that the polygon is simple, a rather
     * easy convexity test is the following: we check that every angle
     * of the polygon is either > or < 180. That is the definition of
     * convexity of a polygon's boundary in this context.
     */
    private fun isConvex() = !isSelfIntersecting() && isBoundaryConvex()

    /*
     * Checks if the polygon is convex, assuming that every edge apart from
     * the specified ones does not cause self-intersection.
     */
    private fun isConvex(vararg modifiedEdges: Int) =
        isBoundaryConvex() && modifiedEdges.none { causeSelfIntersection(it) }

    /*
     * Checks if the polygon's boundary is convex. See [isConvex].
     */
    private fun isBoundaryConvex(): Boolean {
        var e1 = getEdge(vertices.size - 1)
        var e2: Euclidean2DEdge
        var sense: Boolean? = null
        vertices.indices.forEach { i ->
            e2 = getEdge(i)
            val z = zCross(e1.toVector(), e2.toVector())
            /*
             * Cross product is 0 in the following cases:
             * - one (or both) of the two edges is degenerate, so it's perfectly
             * fine to skip it as it doesn't affect convexity.
             * - the two edges are linearly dependent, i.e. either they have
             * the same direction or opposite ones. In the former case it's
             * fine to ignore the edge since it can't violate convexity,
             * whereas the latter case means edges are overlapping (since they
             * have opposite directions and are consecutive), which will be
             * detected by a self-intersection test.
             */
            if (z != 0.0) {
                if (sense == null) {
                    sense = z > 0.0
                } else if (sense != z > 0.0) {
                    return false
                }
                e1 = e2
            }
        }
        return true
    }

    /*
     * Checks whether the polygon is self-intersecting. In this context,
     * a polygon is considered non self-intersecting if the following holds
     * for every edge e:
     * - e must share ONLY its endpoints with its neighboring edges,
     * no other point shall be in common with those edges.
     * - e should not have any point in common with any other edge.
     *
     * This method has a time complexity of O(n^2). Consider using a hash
     * data structure with spatial-related buckets in the future.
     */
    private fun isSelfIntersecting() = vertices.indices.any { causeSelfIntersection(it) }

    /*
     * Checks whether an edge of the polygon cause the latter to be self-
     * intersecting. See [isSelfIntersecting].
     */
    private fun causeSelfIntersection(index: Int) =
        vertices.indices.any {
            if (it == index) {
                false
            } else if (it == circularPrev(index) || it == circularNext(index)) {
                intersection(getEdge(index), getEdge(it)).type != POINT
            } else {
                intersection(getEdge(index), getEdge(it)).type != EMPTY
            }
        }

    /*
     * If the cache is not valid, recomputes it.
     */
    private fun getShape(): AwtEuclidean2DShape {
        if (shape == null) {
            // a Path2D is used to represent a Polygon in double precision
            val s = Path2D.Double()
            vertices.forEachIndexed { i, p ->
                if (i == 0) {
                    s.moveTo(p.x, p.y)
                } else {
                    s.lineTo(p.x, p.y)
                }
            }
            s.closePath()
            shape = AwtEuclidean2DShape(s)
        }
        return shape as AwtEuclidean2DShape
    }
}
