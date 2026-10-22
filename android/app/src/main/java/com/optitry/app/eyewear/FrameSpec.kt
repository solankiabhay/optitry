package com.optitry.app.eyewear

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

enum class Category { EYEGLASSES, SUNGLASSES }

/** ACETATE = thick moulded rim; WIRE = thin metal tube rim with nose-pad arms. */
enum class RimStyle { ACETATE, WIRE }

/** Surface pattern evaluated in the fragment shader (ids must match shader constants). */
enum class Pattern(val id: Float) { SOLID(0f), TORTOISE(1f), GRADIENT(2f), CRYSTAL(3f) }

/**
 * Lens outline in cm, defined for the right-hand lens with its outer side at +x.
 * [squareness] is the super-ellipse exponent (2 = ellipse, 4+ = rounded rectangle).
 * [taper] > 0 widens the top, < 0 widens the bottom. [lift] raises the outer corner (cat-eye).
 * [topBoost] thickens the rim along the top (browline).
 * [noseCut] (fraction of the inner half-width, 0..0.4) relieves the lower inner corner so the rim clears the nose wing, like real frames.
 * It scales x toward the lens centre, so the contour stays star-shaped.
 */
data class LensShape(
    val width: Float,
    val height: Float,
    val squareness: Float,
    val taper: Float = 0f,
    val lift: Float = 0f,
    val topBoost: Float = 0f,
    val noseCut: Float = 0.22f
) {
    /** Counter-clockwise contour of [segments] points around the lens centre, outer side at +x. */
    fun contour(segments: Int): List<FloatArray> {
        val a = width / 2f
        val b = height / 2f
        val e = 2.0 / squareness
        return List(segments) { i ->
            val t = 2.0 * Math.PI * i / segments
            val c = cos(t); val s = sin(t)
            var x = (a * sign(c) * abs(c).pow(e)).toFloat()
            var y = (b * sign(s) * abs(s).pow(e)).toFloat()
            x *= 1f + taper * (y / b)
            if (lift != 0f && x > 0f) y += lift * (x / a).pow(2)
            if (noseCut != 0f && x < 0f && y < 0f) x *= 1f - noseCut * (-y / b).coerceIn(0f, 1f).pow(1.2f)
            floatArrayOf(x, y)
        }
    }
}

data class LensTint(
    val color: Int,            // 0xAARRGGBB, alpha ignored (use [opacity])
    val opacity: Float,        // 0.05 clear ... 0.85 dark
    val mirror: Float = 0f,    // extra reflectivity for mirrored lenses
    val gradient: Boolean = false // darker at the top, clearer at the bottom
)

data class FrameSpec(
    val id: String,
    val name: String,
    val category: Category,
    val shape: LensShape,
    val rim: RimStyle,
    val frameColor: Int,
    val accentColor: Int,
    val pattern: Pattern = Pattern.SOLID,
    val metallic: Boolean = false,
    val tint: LensTint,
    val rimWidth: Float = if (rim == RimStyle.ACETATE) 0.38f else 0.11f,
    val rimDepth: Float = if (rim == RimStyle.ACETATE) 0.55f else 0.11f,
    val bridgeWidth: Float = 1.8f,
    val wrap: Float = 0.022f,
    val templeThickness: Float = if (rim == RimStyle.ACETATE) 0.24f else 0.13f,
    val templeHeight: Float = if (rim == RimStyle.ACETATE) 0.50f else 0.13f
) {
    val isTranslucentFrame: Boolean get() = pattern == Pattern.CRYSTAL
}

/** 20 eyeglasses + 20 sunglasses built from a handful of silhouettes and finishes. */
object FrameCatalog {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val ROUND = LensShape(4.7f, 4.7f, 2.0f)
    private val PANTO = LensShape(4.9f, 4.2f, 2.35f, taper = 0.07f)
    private val OVAL = LensShape(5.2f, 3.9f, 2.0f)
    private val WAYFARER = LensShape(5.3f, 4.2f, 3.1f, taper = 0.09f, lift = 0.06f)
    private val RECT = LensShape(5.5f, 3.6f, 4.2f)
    private val SQUARE = LensShape(5.0f, 4.5f, 4.0f)
    private val CATEYE = LensShape(5.3f, 4.1f, 2.7f, taper = 0.10f, lift = 0.50f)
    private val AVIATOR = LensShape(5.6f, 5.1f, 2.2f, taper = -0.13f)
    private val BROWLINE = LensShape(5.4f, 4.0f, 3.0f, taper = 0.05f, topBoost = 1.1f)
    private val SPORT = LensShape(5.8f, 4.3f, 3.0f, taper = 0.04f)

    private val CLEAR = LensTint(argb(190, 215, 225), 0.07f)
    private val BLUE_LIGHT = LensTint(argb(150, 190, 255), 0.13f)

    private fun eye(i: Int, name: String, shape: LensShape, rim: RimStyle, c: Int, accent: Int = c,
                    pattern: Pattern = Pattern.SOLID, metal: Boolean = false, tint: LensTint = CLEAR,
                    bridge: Float = 1.8f) =
        FrameSpec("glass-%02d".format(i), name, Category.EYEGLASSES, shape, rim, c, accent, pattern, metal, tint, bridgeWidth = bridge)

    private fun sun(i: Int, name: String, shape: LensShape, rim: RimStyle, c: Int, accent: Int = c,
                    pattern: Pattern = Pattern.SOLID, metal: Boolean = false, tint: LensTint,
                    bridge: Float = 1.8f, wrap: Float = 0.022f) =
        FrameSpec("sun-%02d".format(i), name, Category.SUNGLASSES, shape, rim, c, accent, pattern, metal, tint, bridgeWidth = bridge, wrap = wrap)

    val eyeglasses: List<FrameSpec> = listOf(
        eye(1, "Classic Black", WAYFARER, RimStyle.ACETATE, argb(28, 28, 32)),
        eye(2, "Crystal Clear", ROUND, RimStyle.ACETATE, argb(190, 225, 232), pattern = Pattern.CRYSTAL),
        eye(3, "Tortoise", WAYFARER, RimStyle.ACETATE, argb(120, 62, 26), argb(214, 150, 70), Pattern.TORTOISE),
        eye(4, "Silver Metal", ROUND, RimStyle.WIRE, argb(196, 202, 210), metal = true),
        eye(5, "Rose Gold", PANTO, RimStyle.WIRE, argb(214, 150, 138), metal = true),
        eye(6, "Navy Blue", RECT, RimStyle.ACETATE, argb(32, 60, 112)),
        eye(7, "Forest Green", OVAL, RimStyle.ACETATE, argb(32, 92, 66)),
        eye(8, "Burgundy", SQUARE, RimStyle.ACETATE, argb(104, 28, 52)),
        eye(9, "Matte Grey", RECT, RimStyle.ACETATE, argb(96, 100, 110)),
        eye(10, "Champagne", OVAL, RimStyle.WIRE, argb(204, 176, 120), metal = true),
        eye(11, "Retro Round", ROUND, RimStyle.ACETATE, argb(58, 56, 62), tint = BLUE_LIGHT),
        eye(12, "Bold Square", SQUARE, RimStyle.ACETATE, argb(18, 18, 22)),
        eye(13, "Slim Gold", RECT, RimStyle.WIRE, argb(218, 182, 96), metal = true),
        eye(14, "Transparent Blue", PANTO, RimStyle.ACETATE, argb(110, 170, 214), pattern = Pattern.CRYSTAL),
        eye(15, "Olive Havana", WAYFARER, RimStyle.ACETATE, argb(92, 98, 48), argb(168, 150, 70), Pattern.TORTOISE),
        eye(16, "Plum Cat-Eye", CATEYE, RimStyle.ACETATE, argb(98, 52, 108)),
        eye(17, "Browline Classic", BROWLINE, RimStyle.ACETATE, argb(30, 30, 34), argb(196, 168, 110), Pattern.GRADIENT),
        eye(18, "White Frame", SQUARE, RimStyle.ACETATE, argb(232, 232, 226)),
        eye(19, "Teal Round", ROUND, RimStyle.WIRE, argb(30, 128, 134), metal = true, tint = BLUE_LIGHT),
        eye(20, "Aviator Optical", AVIATOR, RimStyle.WIRE, argb(190, 156, 88), metal = true, bridge = 2.0f)
    )

    private val DARK = LensTint(argb(24, 28, 34), 0.78f)
    private val BROWN = LensTint(argb(70, 44, 24), 0.68f, gradient = true)
    private val GREEN_G15 = LensTint(argb(28, 58, 40), 0.74f)
    private val BLUE_MIRROR = LensTint(argb(40, 90, 170), 0.62f, mirror = 0.55f)
    private val SILVER_MIRROR = LensTint(argb(120, 130, 140), 0.55f, mirror = 0.7f)
    private val ROSE = LensTint(argb(176, 90, 112), 0.55f, gradient = true)

    val sunglasses: List<FrameSpec> = listOf(
        sun(1, "Black Classic", WAYFARER, RimStyle.ACETATE, argb(20, 20, 24), tint = DARK),
        sun(2, "Aviator Gold", AVIATOR, RimStyle.WIRE, argb(204, 170, 88), metal = true, tint = GREEN_G15, bridge = 2.1f),
        sun(3, "Tortoise Sun", WAYFARER, RimStyle.ACETATE, argb(112, 58, 24), argb(212, 148, 68), Pattern.TORTOISE, tint = BROWN),
        sun(4, "Silver Mirror", AVIATOR, RimStyle.WIRE, argb(190, 196, 206), metal = true, tint = SILVER_MIRROR, bridge = 2.1f),
        sun(5, "Rose Tint", ROUND, RimStyle.WIRE, argb(214, 150, 138), metal = true, tint = ROSE),
        sun(6, "Ocean Blue", SQUARE, RimStyle.ACETATE, argb(30, 84, 160), tint = BLUE_MIRROR),
        sun(7, "Emerald", OVAL, RimStyle.ACETATE, argb(30, 104, 74), tint = GREEN_G15),
        sun(8, "Burgundy Sun", SQUARE, RimStyle.ACETATE, argb(96, 26, 46), tint = BROWN),
        sun(9, "Smoke Grey", RECT, RimStyle.ACETATE, argb(84, 90, 100), tint = DARK),
        sun(10, "Champagne Sun", PANTO, RimStyle.WIRE, argb(196, 164, 104), metal = true, tint = BROWN),
        sun(11, "Round Retro", ROUND, RimStyle.ACETATE, argb(36, 36, 42), tint = DARK),
        sun(12, "Sport Wrap", SPORT, RimStyle.ACETATE, argb(18, 18, 22), tint = BLUE_MIRROR, wrap = 0.034f),
        sun(13, "Gold Wire", ROUND, RimStyle.WIRE, argb(214, 178, 94), metal = true, tint = GREEN_G15),
        sun(14, "Ice Blue", WAYFARER, RimStyle.ACETATE, argb(130, 190, 226), pattern = Pattern.CRYSTAL, tint = SILVER_MIRROR),
        sun(15, "Olive Pilot", AVIATOR, RimStyle.WIRE, argb(96, 104, 52), metal = true, tint = GREEN_G15, bridge = 2.1f),
        sun(16, "Plum Fade", CATEYE, RimStyle.ACETATE, argb(92, 48, 100), tint = ROSE),
        sun(17, "Copper Lens", SQUARE, RimStyle.WIRE, argb(176, 104, 66), metal = true, tint = BROWN),
        sun(18, "White Sport", SPORT, RimStyle.ACETATE, argb(228, 228, 222), tint = DARK, wrap = 0.034f),
        sun(19, "Teal Mirror", ROUND, RimStyle.WIRE, argb(28, 124, 130), metal = true, tint = BLUE_MIRROR),
        sun(20, "Retro Cat Eye", CATEYE, RimStyle.ACETATE, argb(26, 26, 30), tint = DARK)
    )

    val all: List<FrameSpec> = eyeglasses + sunglasses
    fun byCategory(c: Category) = if (c == Category.EYEGLASSES) eyeglasses else sunglasses
}
