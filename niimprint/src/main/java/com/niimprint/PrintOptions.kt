package com.niimprint

/**
 * User-facing print settings used before a bitmap is sent to the printer.
 *
 * The source bitmap is first rotated with [rotation], then scaled proportionally to fit inside
 * [targetWidth] x [targetHeight], then placed according to [horizontalAlignment] and
 * [verticalAlignment] on a white canvas.
 */
data class PrintOptions(
    /** Printer density value sent to the device. Higher values usually produce darker output. */
    val density: Int = 5,
    /** Width of the prepared print canvas in pixels. */
    val targetWidth: Int = 320,
    /** Height of the prepared print canvas in pixels. */
    val targetHeight: Int = 320,
    /** Horizontal placement of the scaled bitmap inside the prepared canvas. */
    val horizontalAlignment: HorizontalAlignment = HorizontalAlignment.CENTER,
    /** Vertical placement of the scaled bitmap inside the prepared canvas. */
    val verticalAlignment: VerticalAlignment = VerticalAlignment.CENTER,
    /** Rotation applied before scaling and alignment. */
    val rotation: ImageRotation = ImageRotation.ROTATE_90
) {
    init {
        require(density >= 0) { "Density must be greater than or equal to 0" }
        require(targetWidth > 0) { "Target width must be greater than 0" }
        require(targetHeight > 0) { "Target height must be greater than 0" }
    }
}

/** Horizontal placement of the image inside the target print canvas. */
enum class HorizontalAlignment {
    /** Place the image at the left edge of the canvas. */
    LEFT,
    /** Center the image horizontally. */
    CENTER,
    /** Place the image at the right edge of the canvas. */
    RIGHT
}

/** Vertical placement of the image inside the target print canvas. */
enum class VerticalAlignment {
    /** Place the image at the top edge of the canvas. */
    TOP,
    /** Center the image vertically. */
    CENTER,
    /** Place the image at the bottom edge of the canvas. */
    BOTTOM
}

/** Rotation applied to the source bitmap before scaling it to the print canvas. */
enum class ImageRotation(val degrees: Float) {
    /** Do not rotate the source bitmap. */
    NONE(0f),
    /** Rotate the source bitmap clockwise by 90 degrees. */
    ROTATE_90(90f),
    /** Rotate the source bitmap by 180 degrees. */
    ROTATE_180(180f),
    /** Rotate the source bitmap clockwise by 270 degrees. */
    ROTATE_270(270f)
}
