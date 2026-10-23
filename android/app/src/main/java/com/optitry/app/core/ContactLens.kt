package com.optitry.app.core

/** A cosmetic contact lens colour. [color] is the outer iris colour, [color2] the lighter inner collar (both ARGB; alpha ignored). */
data class ContactLens(val id: String, val name: String, val color: Int, val color2: Int, val defaultOpacity: Float = 0.72f)

object ContactLenses {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    /** Shades that sell best in India first: brown family, then grey, green, blue, then fashion colours. */
    val all: List<ContactLens> = listOf(
        ContactLens("natural-brown", "Natural Brown", argb(92, 56, 30), argb(140, 92, 50), 0.60f),
        ContactLens("honey", "Honey", argb(190, 128, 52), argb(222, 170, 92)),
        ContactLens("hazel", "Hazel", argb(116, 98, 44), argb(170, 138, 66)),
        ContactLens("amber", "Amber", argb(178, 102, 28), argb(224, 150, 60)),
        ContactLens("ice-grey", "Ice Grey", argb(130, 140, 150), argb(190, 198, 206)),
        ContactLens("steel-grey", "Steel Grey", argb(84, 92, 102), argb(144, 152, 162)),
        ContactLens("emerald", "Emerald Green", argb(36, 120, 74), argb(98, 170, 110)),
        ContactLens("sea-green", "Sea Green", argb(52, 140, 128), argb(120, 188, 170)),
        ContactLens("ocean-blue", "Ocean Blue", argb(40, 98, 170), argb(110, 160, 214)),
        ContactLens("sky-blue", "Sky Blue", argb(92, 160, 220), argb(168, 208, 240)),
        ContactLens("amethyst", "Amethyst", argb(110, 70, 150), argb(168, 130, 200)),
        ContactLens("rose-brown", "Rose Brown", argb(130, 70, 62), argb(188, 120, 104))
    )
}
