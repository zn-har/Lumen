package com.lumen.editor.model

import androidx.compose.runtime.Immutable

/*
 * Non-destructive edit description. Every value is in "slider units" (usually -100..100),
 * the renderer converts to shader units. All classes are immutable so undo snapshots share
 * structure and cost almost nothing.
 */

@Immutable
data class LightAdj(
    val exposure: Float = 0f,     // EV, -5..5
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
)

@Immutable
data class CurvePoint(val x: Float, val y: Float)

@Immutable
data class ToneCurve(
    val master: List<CurvePoint> = IDENTITY,
    val red: List<CurvePoint> = IDENTITY,
    val green: List<CurvePoint> = IDENTITY,
    val blue: List<CurvePoint> = IDENTITY,
) {
    val isIdentity get() = master == IDENTITY && red == IDENTITY && green == IDENTITY && blue == IDENTITY

    fun channel(c: CurveChannel) = when (c) {
        CurveChannel.MASTER -> master; CurveChannel.RED -> red
        CurveChannel.GREEN -> green; CurveChannel.BLUE -> blue
    }

    fun with(c: CurveChannel, pts: List<CurvePoint>) = when (c) {
        CurveChannel.MASTER -> copy(master = pts); CurveChannel.RED -> copy(red = pts)
        CurveChannel.GREEN -> copy(green = pts); CurveChannel.BLUE -> copy(blue = pts)
    }

    companion object {
        val IDENTITY = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    }
}

enum class CurveChannel { MASTER, RED, GREEN, BLUE }

@Immutable
data class HslBand(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f)

/** Red, Orange, Yellow, Green, Aqua, Blue, Purple, Magenta. */
val HSL_CENTERS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 270f, 300f)

@Immutable
data class ColorAdj(
    val temp: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val hsl: List<HslBand> = List(8) { HslBand() },
    val bw: Boolean = false,
    val wbPreset: WbPreset = WbPreset.AS_SHOT,
)

/** White balance dropdown entries; selecting one sets temp/tint (JPEG-relative values). */
enum class WbPreset(val label: String, val temp: Float, val tint: Float) {
    AS_SHOT("As shot", 0f, 0f), AUTO("Auto", 0f, 0f), DAYLIGHT("Daylight", 4f, 2f), CLOUDY("Cloudy", 14f, 4f),
    SHADE("Shade", 24f, 6f), TUNGSTEN("Tungsten", -38f, 8f), FLUORESCENT("Fluorescent", -20f, 22f),
    FLASH("Flash", 8f, 2f), CUSTOM("Custom", 0f, 0f),
}

@Immutable
data class GradeWheel(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f)

@Immutable
data class ColorGrading(
    val shadows: GradeWheel = GradeWheel(),
    val midtones: GradeWheel = GradeWheel(),
    val highlights: GradeWheel = GradeWheel(),
    val global: GradeWheel = GradeWheel(),
    val blending: Float = 50f,
    val balance: Float = 0f,
)

@Immutable
data class EffectsAdj(
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val vignette: Float = 0f,
    val vigMidpoint: Float = 50f,
    val vigFeather: Float = 50f,
    val vigRoundness: Float = 0f,
    val vigHighlights: Float = 0f,
    val grain: Float = 0f,
    val grainSize: Float = 25f,
    val grainRoughness: Float = 50f,
)

@Immutable
data class DetailAdj(
    val sharpen: Float = 0f,      // 0..150
    val sharpRadius: Float = 1f,  // 0.5..3
    val sharpDetail: Float = 25f,
    val sharpMasking: Float = 0f,
    val noiseLum: Float = 0f,
    val noiseColor: Float = 0f,
)

@Immutable
data class OpticsAdj(
    val distortion: Float = 0f,
    val lensVignette: Float = 0f,
    val defringe: Float = 0f,
    val removeCA: Boolean = false,
    val lensCorrections: Boolean = false,
)

@Immutable
data class CropState(
    val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f,
    val angle: Float = 0f,   // degrees, -45..45
    val aspect: Float = 0f,  // 0 = free / original
    val quarterTurns: Int = 0, // clockwise 90° steps applied to the output, 0..3
    val flipH: Boolean = false,
    val flipV: Boolean = false,
) {
    val isIdentity get() = left == 0f && top == 0f && right == 1f && bottom == 1f && angle == 0f &&
        quarterTurns == 0 && !flipH && !flipV
}

// ---------------------------------------------------------------- masks

enum class MaskMode { ADD, SUBTRACT, INTERSECT }

enum class AiKind(val label: String) {
    SUBJECT("Subject"), SKY("Sky"), BACKGROUND("Background"),
    PEOPLE("People"), FACE_SKIN("Facial Skin"), BODY_SKIN("Body Skin"),
    HAIR("Hair"), CLOTHES("Clothes"),
}

/** One brush stroke. points = x0,y0,pressure0,x1,y1,pressure1... in normalized image coords. */
@Immutable
class BrushStroke(
    val points: FloatArray,
    val size: Float,      // radius as fraction of image width
    val feather: Float,   // 0..1
    val flow: Float,      // 0..1
    val erase: Boolean,
)

@Immutable
sealed class MaskComponent {
    abstract val id: Long
    abstract val mode: MaskMode
    abstract val invert: Boolean
    abstract fun with(mode: MaskMode = this.mode, invert: Boolean = this.invert): MaskComponent

    data class Brush(
        override val id: Long, override val mode: MaskMode = MaskMode.ADD, override val invert: Boolean = false,
        val strokes: List<BrushStroke> = emptyList(),
    ) : MaskComponent() {
        override fun with(mode: MaskMode, invert: Boolean) = copy(mode = mode, invert = invert)
    }

    /** Full effect at (x0,y0), fading to none at (x1,y1). */
    data class Linear(
        override val id: Long, override val mode: MaskMode = MaskMode.ADD, override val invert: Boolean = false,
        val x0: Float = 0.5f, val y0: Float = 0.25f, val x1: Float = 0.5f, val y1: Float = 0.6f,
    ) : MaskComponent() {
        override fun with(mode: MaskMode, invert: Boolean) = copy(mode = mode, invert = invert)
    }

    data class Radial(
        override val id: Long, override val mode: MaskMode = MaskMode.ADD, override val invert: Boolean = false,
        val cx: Float = 0.5f, val cy: Float = 0.5f, val rx: Float = 0.25f, val ry: Float = 0.25f,
        val angle: Float = 0f, val feather: Float = 50f,
    ) : MaskComponent() {
        override fun with(mode: MaskMode, invert: Boolean) = copy(mode = mode, invert = invert)
    }

    /** samples = r,g,b triples (display sRGB 0..1). */
    data class ColorRange(
        override val id: Long, override val mode: MaskMode = MaskMode.ADD, override val invert: Boolean = false,
        val samples: List<FloatArray> = emptyList(), val refine: Float = 50f,
    ) : MaskComponent() {
        override fun with(mode: MaskMode, invert: Boolean) = copy(mode = mode, invert = invert)
    }

    data class LuminanceRange(
        override val id: Long, override val mode: MaskMode = MaskMode.ADD, override val invert: Boolean = false,
        val lo: Float = 0.5f, val hi: Float = 1f, val featherLo: Float = 0.15f, val featherHi: Float = 0f,
    ) : MaskComponent() {
        override fun with(mode: MaskMode, invert: Boolean) = copy(mode = mode, invert = invert)
    }

    data class Ai(
        override val id: Long, override val mode: MaskMode = MaskMode.ADD, override val invert: Boolean = false,
        val kind: AiKind,
    ) : MaskComponent() {
        override fun with(mode: MaskMode, invert: Boolean) = copy(mode = mode, invert = invert)
    }
}

/** Local adjustment values (all -100..100 except exposure in EV, amount 0..200). */
@Immutable
data class LocalAdj(
    val exposure: Float = 0f, val contrast: Float = 0f,
    val highlights: Float = 0f, val shadows: Float = 0f,
    val whites: Float = 0f, val blacks: Float = 0f,
    val temp: Float = 0f, val tint: Float = 0f,
    val saturation: Float = 0f, val texture: Float = 0f,
    val clarity: Float = 0f, val dehaze: Float = 0f,
    val colorHue: Float = 0f, val colorSat: Float = 0f,
    val sharpness: Float = 0f, val noise: Float = 0f,
    val amount: Float = 100f,
    // local colour grading (blending / balance are shared with the global grading)
    val gradeShadows: GradeWheel = GradeWheel(),
    val gradeMidtones: GradeWheel = GradeWheel(),
    val gradeHighlights: GradeWheel = GradeWheel(),
    val gradeGlobal: GradeWheel = GradeWheel(),
) {
    val hasGrading get() = gradeShadows != GradeWheel() || gradeMidtones != GradeWheel() ||
        gradeHighlights != GradeWheel() || gradeGlobal != GradeWheel()
}

@Immutable
data class Mask(
    val id: Long,
    val name: String,
    val components: List<MaskComponent>,
    val adj: LocalAdj = LocalAdj(),
    val inverted: Boolean = false,
    val visible: Boolean = true,
)

@Immutable
data class EditState(
    val light: LightAdj = LightAdj(),
    val curve: ToneCurve = ToneCurve(),
    val color: ColorAdj = ColorAdj(),
    val grading: ColorGrading = ColorGrading(),
    val effects: EffectsAdj = EffectsAdj(),
    val detail: DetailAdj = DetailAdj(),
    val optics: OpticsAdj = OpticsAdj(),
    val crop: CropState = CropState(),
    val masks: List<Mask> = emptyList(),
)

private var idCounter = System.nanoTime()
fun newId(): Long = ++idCounter
