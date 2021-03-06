package openrndr.utils

inline class Radians(
        val value: Double
) {
    fun toDegrees() = Degrees(value * Math.PI / 180.0)
}

fun <T: Number> T.radians() = Radians(this.toDouble())