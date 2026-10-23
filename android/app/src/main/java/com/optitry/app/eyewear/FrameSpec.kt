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
    val rimWidth: Float = if (rim == RimStyle.ACETATE) 0.46f else 0.18f,
    val rimDepth: Float = if (rim == RimStyle.ACETATE) 0.55f else 0.18f,
    val bridgeWidth: Float = 1.8f,
    val wrap: Float = 0.022f,
    val templeThickness: Float = if (rim == RimStyle.ACETATE) 0.30f else 0.19f,
    val templeHeight: Float = if (rim == RimStyle.ACETATE) 0.80f else 0.19f
) {
    val isTranslucentFrame: Boolean get() = pattern == Pattern.CRYSTAL
}

/** 50 eyeglasses + 50 sunglasses: generic best-selling style archetypes (not brand-model copies) built from a few silhouettes and finishes. */
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
    private val BROWLINE = LensShape(5.4f, 4.0f, 3.0f, taper = 0.05f, topBoost = 0.8f)
    private val SPORT = LensShape(5.8f, 4.3f, 3.0f, taper = 0.04f)
    private val MODERN_OVAL = LensShape(5.1f, 3.8f, 2.1f)
    private val SLIM_RECT = LensShape(5.6f, 3.6f, 4.2f)
    private val SOFT_SQUARE = LensShape(5.1f, 4.4f, 3.4f)
    private val OVERSIZE_SQUARE = LensShape(5.7f, 4.8f, 3.6f, taper = 0.03f)
    private val BUTTERFLY = LensShape(5.7f, 4.6f, 2.5f, taper = 0.10f, lift = 0.45f)
    private val GEOMETRIC = LensShape(5.2f, 4.3f, 2.6f, taper = 0.06f)
    private val WAYFARER_SLIM = LensShape(5.3f, 3.9f, 3.0f, taper = 0.08f, lift = 0.04f)
    private val AVIATOR_LARGE = LensShape(5.8f, 5.0f, 2.3f, taper = -0.12f)
    private val PILOT = LensShape(5.5f, 4.7f, 2.8f, taper = -0.08f)

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
        eye(20, "Aviator Optical", AVIATOR, RimStyle.WIRE, argb(190, 156, 88), metal = true, bridge = 2.0f),
        // 21..50: styles that lead eyeglass sales in India today (rectangles, soft squares, ovals, browlines, geometric, cat-eye, butterfly)
        eye(21, "Everyday Black Rectangle", SLIM_RECT, RimStyle.ACETATE, argb(24, 24, 28)),
        eye(22, "Matte Black Square", SOFT_SQUARE, RimStyle.ACETATE, argb(38, 38, 42)),
        eye(23, "Gunmetal Slim", SLIM_RECT, RimStyle.WIRE, argb(92, 98, 108), metal = true),
        eye(24, "Transparent Grey", MODERN_OVAL, RimStyle.ACETATE, argb(170, 176, 184), pattern = Pattern.CRYSTAL),
        eye(25, "Clear Rectangle", SLIM_RECT, RimStyle.ACETATE, argb(205, 215, 220), pattern = Pattern.CRYSTAL),
        eye(26, "Honey Tortoise Oval", MODERN_OVAL, RimStyle.ACETATE, argb(140, 84, 36), argb(222, 168, 86), Pattern.TORTOISE),
        eye(27, "Brown Havana Square", SOFT_SQUARE, RimStyle.ACETATE, argb(96, 52, 24), argb(190, 130, 60), Pattern.TORTOISE),
        eye(28, "Black Gold Browline", BROWLINE, RimStyle.ACETATE, argb(26, 26, 30), argb(214, 180, 100), Pattern.GRADIENT),
        eye(29, "Steel Browline", BROWLINE, RimStyle.ACETATE, argb(60, 64, 72), argb(196, 202, 210), Pattern.GRADIENT),
        eye(30, "Blush Cat-Eye", CATEYE, RimStyle.ACETATE, argb(232, 176, 190)),
        eye(31, "Black Cat-Eye", CATEYE, RimStyle.ACETATE, argb(24, 24, 28)),
        eye(32, "Gold Butterfly", BUTTERFLY, RimStyle.WIRE, argb(216, 182, 98), metal = true),
        eye(33, "Wine Butterfly", BUTTERFLY, RimStyle.ACETATE, argb(110, 30, 60)),
        eye(34, "Geometric Gold", GEOMETRIC, RimStyle.WIRE, argb(214, 178, 94), metal = true),
        eye(35, "Geometric Black", GEOMETRIC, RimStyle.ACETATE, argb(26, 26, 30)),
        eye(36, "Geometric Silver", GEOMETRIC, RimStyle.WIRE, argb(196, 202, 210), metal = true),
        eye(37, "Oversized Black Square", OVERSIZE_SQUARE, RimStyle.ACETATE, argb(22, 22, 26)),
        eye(38, "Oversized Clear", OVERSIZE_SQUARE, RimStyle.ACETATE, argb(200, 214, 220), pattern = Pattern.CRYSTAL),
        eye(39, "Blue Light Black", SOFT_SQUARE, RimStyle.ACETATE, argb(30, 30, 34), tint = BLUE_LIGHT),
        eye(40, "Blue Light Tortoise", WAYFARER_SLIM, RimStyle.ACETATE, argb(120, 62, 26), argb(214, 150, 70), Pattern.TORTOISE, tint = BLUE_LIGHT),
        eye(41, "Thin Gold Round", ROUND, RimStyle.WIRE, argb(218, 182, 96), metal = true),
        eye(42, "Thin Black Round", ROUND, RimStyle.WIRE, argb(34, 34, 38), metal = true),
        eye(43, "Rose Gold Oval", MODERN_OVAL, RimStyle.WIRE, argb(214, 150, 138), metal = true),
        eye(44, "Gunmetal Pilot", PILOT, RimStyle.WIRE, argb(96, 102, 112), metal = true, bridge = 2.0f),
        eye(45, "Navy Slim Wayfarer", WAYFARER_SLIM, RimStyle.ACETATE, argb(30, 52, 104)),
        eye(46, "Olive Round", ROUND, RimStyle.ACETATE, argb(74, 86, 52)),
        eye(47, "Burgundy Cat-Eye", CATEYE, RimStyle.ACETATE, argb(110, 26, 50)),
        eye(48, "Sky Blue Crystal", SOFT_SQUARE, RimStyle.ACETATE, argb(160, 205, 235), pattern = Pattern.CRYSTAL),
        eye(49, "Matte Brown Rectangle", SLIM_RECT, RimStyle.ACETATE, argb(86, 58, 40)),
        eye(50, "Two-Tone Navy Rectangle", SLIM_RECT, RimStyle.ACETATE, argb(28, 48, 98), argb(140, 180, 222), Pattern.GRADIENT)
    )

    private val DARK = LensTint(argb(24, 28, 34), 0.78f)
    private val BROWN = LensTint(argb(70, 44, 24), 0.68f, gradient = true)
    private val GREEN_G15 = LensTint(argb(28, 58, 40), 0.74f)
    private val BLUE_MIRROR = LensTint(argb(40, 90, 170), 0.62f, mirror = 0.55f)
    private val SILVER_MIRROR = LensTint(argb(120, 130, 140), 0.55f, mirror = 0.7f)
    private val ROSE = LensTint(argb(176, 90, 112), 0.55f, gradient = true)
    private val NIGHT_YELLOW = LensTint(argb(226, 200, 60), 0.35f)
    private val GREY_GRADIENT = LensTint(argb(70, 76, 84), 0.62f, gradient = true)
    private val BLUE_FASHION = LensTint(argb(90, 140, 200), 0.50f, gradient = true)

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
        sun(20, "Retro Cat Eye", CATEYE, RimStyle.ACETATE, argb(26, 26, 30), tint = DARK),
        // 21..50: styles that lead sunglass sales in India today (aviator/pilot, wayfarer, round, browline, cat-eye, butterfly, oversized, sport)
        sun(21, "Black Polarized Aviator", AVIATOR_LARGE, RimStyle.WIRE, argb(24, 24, 28), metal = true, tint = DARK, bridge = 2.1f),
        sun(22, "Gold Green Pilot", PILOT, RimStyle.WIRE, argb(208, 174, 92), metal = true, tint = GREEN_G15, bridge = 2.0f),
        sun(23, "Gunmetal Blue Mirror", AVIATOR_LARGE, RimStyle.WIRE, argb(92, 98, 108), metal = true, tint = BLUE_MIRROR, bridge = 2.1f),
        sun(24, "Silver Mirror Pilot", PILOT, RimStyle.WIRE, argb(196, 202, 210), metal = true, tint = SILVER_MIRROR, bridge = 2.0f),
        sun(25, "Black Slim Wayfarer", WAYFARER_SLIM, RimStyle.ACETATE, argb(20, 20, 24), tint = DARK),
        sun(26, "Matte Black Wayfarer", WAYFARER, RimStyle.ACETATE, argb(36, 36, 40), tint = DARK),
        sun(27, "Havana Brown Wayfarer", WAYFARER, RimStyle.ACETATE, argb(104, 56, 24), argb(204, 146, 66), Pattern.TORTOISE, tint = BROWN),
        sun(28, "Blue Wayfarer", WAYFARER_SLIM, RimStyle.ACETATE, argb(34, 66, 120), tint = BLUE_MIRROR),
        sun(29, "Black Round Wire", ROUND, RimStyle.WIRE, argb(30, 30, 34), metal = true, tint = DARK),
        sun(30, "Gold Round Brown", ROUND, RimStyle.WIRE, argb(214, 178, 94), metal = true, tint = BROWN),
        sun(31, "Tortoise Round", ROUND, RimStyle.ACETATE, argb(112, 58, 24), argb(212, 148, 68), Pattern.TORTOISE, tint = BROWN),
        sun(32, "Black Gold Browline Shades", BROWLINE, RimStyle.ACETATE, argb(24, 24, 28), argb(210, 176, 98), Pattern.GRADIENT, tint = DARK),
        sun(33, "Steel Browline Green", BROWLINE, RimStyle.ACETATE, argb(62, 66, 74), argb(196, 202, 210), Pattern.GRADIENT, tint = GREEN_G15),
        sun(34, "Tortoise Cat-Eye", CATEYE, RimStyle.ACETATE, argb(120, 62, 26), argb(214, 150, 70), Pattern.TORTOISE, tint = BROWN),
        sun(35, "Pink Cat-Eye", CATEYE, RimStyle.ACETATE, argb(222, 150, 170), tint = ROSE),
        sun(36, "Black Butterfly", BUTTERFLY, RimStyle.ACETATE, argb(22, 22, 26), tint = DARK),
        sun(37, "Brown Butterfly", BUTTERFLY, RimStyle.ACETATE, argb(104, 56, 24), argb(204, 146, 66), Pattern.TORTOISE, tint = BROWN),
        sun(38, "Gold Butterfly Pink", BUTTERFLY, RimStyle.WIRE, argb(214, 178, 94), metal = true, tint = ROSE),
        sun(39, "Oversized Black Shades", OVERSIZE_SQUARE, RimStyle.ACETATE, argb(20, 20, 24), tint = DARK),
        sun(40, "Oversized Tortoise Square", OVERSIZE_SQUARE, RimStyle.ACETATE, argb(112, 58, 24), argb(212, 148, 68), Pattern.TORTOISE, tint = BROWN),
        sun(41, "Clear Frame Brown Lens", SOFT_SQUARE, RimStyle.ACETATE, argb(205, 215, 220), pattern = Pattern.CRYSTAL, tint = BROWN),
        sun(42, "Geometric Gold Green", GEOMETRIC, RimStyle.WIRE, argb(214, 178, 94), metal = true, tint = GREEN_G15),
        sun(43, "Geometric Silver Mirror", GEOMETRIC, RimStyle.WIRE, argb(196, 202, 210), metal = true, tint = SILVER_MIRROR),
        sun(44, "Wrap Black Mirror", SPORT, RimStyle.ACETATE, argb(20, 20, 24), tint = SILVER_MIRROR, wrap = 0.034f),
        sun(45, "Sport Blue", SPORT, RimStyle.ACETATE, argb(24, 60, 120), tint = BLUE_MIRROR, wrap = 0.034f),
        sun(46, "Sport Red", SPORT, RimStyle.ACETATE, argb(170, 30, 36), tint = DARK, wrap = 0.034f),
        sun(47, "Night Drive Yellow", SOFT_SQUARE, RimStyle.ACETATE, argb(24, 24, 28), tint = NIGHT_YELLOW),
        sun(48, "Gold Slim Rectangle", SLIM_RECT, RimStyle.WIRE, argb(214, 178, 94), metal = true, tint = GREEN_G15),
        sun(49, "Gradient Grey Square", SOFT_SQUARE, RimStyle.ACETATE, argb(60, 62, 70), tint = GREY_GRADIENT),
        sun(50, "Pastel Blue Oval", MODERN_OVAL, RimStyle.ACETATE, argb(150, 190, 220), tint = BLUE_FASHION)
    )

    val all: List<FrameSpec> = eyeglasses + sunglasses
    fun byCategory(c: Category) = if (c == Category.EYEGLASSES) eyeglasses else sunglasses
}
