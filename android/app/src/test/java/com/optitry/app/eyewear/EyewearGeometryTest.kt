package com.optitry.app.eyewear

import com.optitry.app.core.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class EyewearGeometryTest {
    private val faceMesh: OccluderMesh by lazy {
        // Unit tests run with the module directory as working dir; fall back for IDE runs from the repo root.
        val f = listOf("src/main/assets/face_mesh.txt", "android/app/src/main/assets/face_mesh.txt").map(::File).first { it.exists() }
        FaceMeshParser.parse(f.readText())
    }

    private fun meshesFor(spec: FrameSpec) = EyewearGeometry.build(spec)

    @Test fun catalogHasFiftyPlusFiftyUniqueStyles() {
        assertEquals(50, FrameCatalog.eyeglasses.size)
        assertEquals(50, FrameCatalog.sunglasses.size)
        assertEquals(100, FrameCatalog.all.map { it.id }.toSet().size)
        assertEquals(100, FrameCatalog.all.map { it.name }.toSet().size)
        assertTrue(FrameCatalog.eyeglasses.all { it.category == Category.EYEGLASSES })
        assertTrue(FrameCatalog.sunglasses.all { it.category == Category.SUNGLASSES })
    }

    @Test fun faceMeshAssetParses() {
        assertEquals(468, faceMesh.vertexCount - 0)
        assertEquals(898 * 3, faceMesh.indices.size)
        assertTrue(faceMesh.indices.all { it in 0 until 468 })
    }

    @Test fun occluderIncludesHeadAndFace() {
        val o = Occluders.build(faceMesh)
        assertTrue(o.vertexCount > 468)
        assertTrue(o.indices.all { it.toInt() in 0 until o.vertexCount })
    }

    @Test fun everyStyleBuildsValidMeshes() {
        for (spec in FrameCatalog.all) {
            val g = meshesFor(spec)
            for ((name, m) in listOf("frame" to g.frame, "lenses" to g.lenses)) {
                assertTrue("${spec.id} $name vertices", m.vertexCount in 100..MeshData.MAX_VERTICES)
                assertTrue("${spec.id} $name finite", m.vertices.all { it.isFinite() })
                assertTrue("${spec.id} $name indices in range", m.indices.all { it.toInt() and 0xFFFF < m.vertexCount })
                assertEquals("${spec.id} $name triangle multiple", 0, m.indices.size % 3)
                var bad = 0
                for (i in 0 until m.vertexCount) if (abs(m.normal(i).length() - 1f) > 1e-3f) bad++
                assertEquals("${spec.id} $name non-unit normals", 0, bad)
            }
        }
    }

    @Test fun triangleWindingAgreesWithNormals() {
        for (spec in FrameCatalog.all) {
            val g = meshesFor(spec)
            for ((name, m) in listOf("frame" to g.frame, "lenses" to g.lenses)) {
                var good = 0; var total = 0
                for (t in 0 until m.triangleCount) {
                    val a = m.indices[t * 3].toInt() and 0xFFFF
                    val b = m.indices[t * 3 + 1].toInt() and 0xFFFF
                    val c = m.indices[t * 3 + 2].toInt() and 0xFFFF
                    val n = (m.position(b) - m.position(a)).cross(m.position(c) - m.position(a))
                    if (n.length() < 1e-9f) continue // degenerate (pole of a sphere)
                    total++
                    val avg = m.normal(a) + m.normal(b) + m.normal(c)
                    if (n.dot(avg) > 0f) good++
                }
                assertTrue("${spec.id} $name winding ${good}/$total", good >= total * 0.99)
            }
        }
    }

    @Test fun frameWidthAndPlacementAreRealistic() {
        for (spec in FrameCatalog.all) {
            val g = meshesFor(spec)
            var minX = 1e9f; var maxX = -1e9f; var minY = 1e9f; var maxY = -1e9f
            for (i in 0 until g.frame.vertexCount) {
                val p = g.frame.position(i)
                // front frame only (ignore the temple arms running back behind the ears)
                if (p.z > 3.5f) { minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x); minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y) }
            }
            val width = maxX - minX
            assertTrue("${spec.id} front width $width cm", width in 12.5f..15.6f)
            assertTrue("${spec.id} symmetric", abs(maxX + minX) < 0.05f)
            assertTrue("${spec.id} hinge ${g.hingeX}", g.hingeX in 6.3f..7.6f)
            assertTrue("${spec.id} vertical position ${minY}..$maxY", minY > -0.4f && maxY < 5.4f) // below the brow (y~5.1), above the cheek
        }
    }

    @Test fun templesStayOutsideTheHeadAndFace() {
        val face = faceMesh
        for (spec in FrameCatalog.all) {
            val g = meshesFor(spec)
            for (i in 0 until g.frame.vertexCount) {
                if (g.frame.vertices[i * MeshData.STRIDE_FLOATS + 6] != EyewearGeometry.PART_TEMPLE) continue
                val p = g.frame.position(i)
                assertTrue("${spec.id} temple vertex inside head ellipsoid at $p", !Occluders.insideHead(p.x, p.y, p.z))
                if (p.z < 4f && p.z > -2.3f) {
                    // distance to the nearest canonical face vertex must exceed the arm's own half-width plus a small gap
                    var best = 1e9f
                    for (v in 0 until face.vertexCount) {
                        val dx = p.x - face.positions[v * 3]; val dy = p.y - face.positions[v * 3 + 1]; val dz = p.z - face.positions[v * 3 + 2]
                        best = minOf(best, sqrt(dx * dx + dy * dy + dz * dz))
                    }
                    assertTrue("${spec.id} temple $p only $best cm from face", best > 0.12f)
                }
            }
        }
    }

    /** Highest z of the (shrunk) face occluder covering canonical (x, y), or NaN. Exact barycentric ray-cast over the face triangles. */
    private fun occluderSurfaceZ(o: OccluderMesh, x: Float, y: Float): Float {
        var best = Float.NaN
        for (t in 0 until 898) {
            val i0 = o.indices[t * 3].toInt() * 3; val i1 = o.indices[t * 3 + 1].toInt() * 3; val i2 = o.indices[t * 3 + 2].toInt() * 3
            val x0 = o.positions[i0]; val y0 = o.positions[i0 + 1]
            val x1 = o.positions[i1]; val y1 = o.positions[i1 + 1]
            val x2 = o.positions[i2]; val y2 = o.positions[i2 + 1]
            val det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            if (abs(det) < 1e-9f) continue
            val l0 = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / det
            val l1 = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / det
            val l2 = 1f - l0 - l1
            if (l0 < 0f || l1 < 0f || l2 < 0f) continue
            val z = l0 * o.positions[i0 + 2] + l1 * o.positions[i1 + 2] + l2 * o.positions[i2 + 2]
            if (best.isNaN() || z > best) best = z
        }
        return best
    }

    @Test fun rimBridgeAndArmsAreNotHiddenByTheOccluder() {
        val occ = Occluders.build(faceMesh)
        for (spec in FrameCatalog.all) {
            val g = meshesFor(spec)
            for (i in 0 until g.frame.vertexCount) {
                val part = g.frame.vertices[i * MeshData.STRIDE_FLOATS + 6]
                if (part != EyewearGeometry.PART_BODY) continue
                val p = g.frame.position(i)
                if (abs(p.x) > 6.0f || p.z < 4.4f) continue
                val faceZ = occluderSurfaceZ(occ, p.x, p.y)
                if (!faceZ.isNaN()) assertTrue("${spec.id} front part at $p is buried more than 1 mm in the occluder (surface z=$faceZ)", p.z > faceZ - 0.10f)
            }
        }
    }

    @Test fun nosePadsRestNearTheNoseSurface() {
        val occ = Occluders.build(faceMesh)
        for (spec in FrameCatalog.all) {
            val g = meshesFor(spec)
            for (i in 0 until g.frame.vertexCount) {
                if (g.frame.vertices[i * MeshData.STRIDE_FLOATS + 6] != EyewearGeometry.PART_PAD) continue
                val p = g.frame.position(i)
                val faceZ = occluderSurfaceZ(occ, p.x, p.y)
                if (faceZ.isNaN()) continue
                assertTrue("${spec.id} pad vertex $p floats ${p.z - faceZ} cm in front of the nose", p.z - faceZ < 0.60f)
            }
        }
    }

    @Test fun lensesLieInsideTheRimAndAreCurved() {
        for (spec in FrameCatalog.all) {
            val g = meshesFor(spec)
            var minZ = 1e9f; var maxZ = -1e9f
            for (i in 0 until g.lenses.vertexCount) { val z = g.lenses.position(i).z; minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z) }
            assertTrue("${spec.id} lens z-range ${maxZ - minZ}", maxZ - minZ > 0.3f)
            // every lens vertex is within the frame's horizontal and vertical envelope
            for (i in 0 until g.lenses.vertexCount) {
                val p = g.lenses.position(i)
                assertTrue("${spec.id} lens x ${p.x}", abs(p.x) < g.hingeX)
                assertTrue("${spec.id} lens y ${p.y}", p.y in -0.3f..5.3f)
            }
        }
    }

    @Test fun lensShapeContoursAreCounterClockwise() {
        for (spec in FrameCatalog.all) {
            val c = spec.shape.contour(72)
            var area = 0f
            for (i in c.indices) { val a = c[i]; val b = c[(i + 1) % c.size]; area += a[0] * b[1] - b[0] * a[1] }
            assertTrue("${spec.id} contour area sign $area", area > 0f)
        }
    }

    @Test fun mirroredLensesAreSymmetric() {
        val g = meshesFor(FrameCatalog.eyeglasses[0])
        val xs = (0 until g.lenses.vertexCount).map { g.lenses.position(it).x }
        assertEquals(0f, xs.sum() / xs.size, 0.02f)
    }
}
