package io.github.seongkyulim.wellnessgame.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.seongkyulim.wellnessgame.domain.GrowthStage

/**
 * 화분 마스코트 '새싹이'(Sprouty). iOS `CharacterAvatarView`·웹 `CharacterAvatar.tsx`와 같은 120×120 좌표와
 * 같은 SVG 경로 문자열을 Canvas로 그린다. 웹 그림을 고치면 이 파일도 함께 고친다.
 */
@Composable
fun SproutyAvatar(stage: GrowthStage, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val semantics = if (contentDescription == null) {
        Modifier.clearAndSetSemantics { }
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(modifier.then(semantics)) {
        val unit = minOf(size.width, size.height) / 120f
        scale(unit, unit, pivot = Offset.Zero) {
            drawSprouty(stage)
        }
    }
}

private object C {
    val ink = Color(0xFF1F2340)
    val potBody = Color(0xFFF4A06C)
    val potShade = Color(0xFFDC7A4C)
    val potRim = Color(0xFFF9BC8A)
    val potShine = Color(0xFFFFE2C6)
    val blush = Color(0xFFFF8FB1)
    val mouth = Color(0xFFE8577F)
    val stem = Color(0xFF5BB04F)
    val leaf = Color(0xFF8ED65A)
    val leafTip = Color(0xFFB4EA7A)
    val canopy = Color(0xFF7DCB55)
    val canopyBig = Color(0xFF6CC04A)
    val canopyShade = Color(0xFF5AA845)
    val canopyBigShade = Color(0xFF4E9E3E)
    val canopyShine = Color(0xFFBDEE90)
    val trunk = Color(0xFFA8744A)
    val seed = Color(0xFFC8935E)
    val petal = Color(0xFFFFB3CF)
    val petalCore = Color(0xFFFF6FA5)
    val eyeShine = Color(0xFFFFFFFF)
}

private enum class Face { SLEEP, OPEN, HAPPY }

private fun face(stage: GrowthStage) = when (stage) {
    GrowthStage.SEED -> Face.SLEEP
    GrowthStage.SPROUT, GrowthStage.SAPLING, GrowthStage.TREE -> Face.OPEN
    GrowthStage.YOUNG, GrowthStage.BLOSSOM -> Face.HAPPY
}

private val pathCache = HashMap<String, Path>()

private fun svg(data: String): Path = pathCache.getOrPut(data) { PathParser().parsePathString(data).toPath() }

private fun DrawScope.paint(path: Path, fill: Color?, line: Float? = 3f) {
    fill?.let { drawPath(path, it) }
    line?.let { drawPath(path, C.ink, style = Stroke(width = it, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
}

private fun DrawScope.ellipse(cx: Float, cy: Float, rx: Float, ry: Float, fill: Color?, line: Float? = 3f) {
    val topLeft = Offset(cx - rx, cy - ry)
    val size = Size(rx * 2, ry * 2)
    fill?.let { drawOval(it, topLeft, size) }
    line?.let { drawOval(C.ink, topLeft, size, style = Stroke(width = it)) }
}

private fun DrawScope.drawSprouty(stage: GrowthStage) {
    drawOval(C.ink.copy(alpha = 0.18f), Offset(30f, 108f), Size(60f, 8f))
    // 식물을 먼저 그려 줄기 아래쪽이 화분 테두리에 가려지게 한다
    drawPlant(stage)
    drawPot()
    drawFace(face(stage))
}

private fun DrawScope.drawPot() {
    paint(svg("M38 76 H82 L77 104 Q76 109 71 109 H49 Q44 109 43 104 Z"), C.potBody)
    paint(svg("M66 77.5 H80 L75.5 103 Q74.5 107 70 107 H63 Q68 94 66 77.5 Z"), C.potShade, line = null)
    paint(svg("M38 68 H82 Q87 68 87 73 V75 Q87 80 82 80 H38 Q33 80 33 75 V73 Q33 68 38 68 Z"), C.potRim)
    paint(svg("M39.5 71 H56.5 Q58 71 58 72.5 Q58 74 56.5 74 H39.5 Q38 74 38 72.5 Q38 71 39.5 71 Z"), C.potShine, line = null)
    ellipse(44f, 97f, 4.2f, 2.3f, C.blush, line = null)
    ellipse(76f, 97f, 4.2f, 2.3f, C.blush, line = null)
}

private fun DrawScope.drawFace(face: Face) {
    when (face) {
        Face.SLEEP -> {
            paint(svg("M46.5 90 Q51 93 55.5 90"), null, 2.4f)
            paint(svg("M64.5 90 Q69 93 73.5 90"), null, 2.4f)
            paint(svg("M58 98 Q60 99.5 62 98"), null, 2f)
        }
        Face.HAPPY -> {
            paint(svg("M46.5 91 Q51 85 55.5 91"), null, 2.6f)
            paint(svg("M64.5 91 Q69 85 73.5 91"), null, 2.6f)
            paint(svg("M55.5 95.5 Q60 103 64.5 95.5 Z"), C.mouth, 2.2f)
        }
        Face.OPEN -> {
            ellipse(51f, 90f, 4.2f, 5.6f, C.ink, line = null)
            ellipse(69f, 90f, 4.2f, 5.6f, C.ink, line = null)
            ellipse(52.4f, 87.8f, 1.7f, 1.7f, C.eyeShine, line = null)
            ellipse(70.4f, 87.8f, 1.7f, 1.7f, C.eyeShine, line = null)
            ellipse(49.8f, 92.4f, 0.8f, 0.8f, C.eyeShine, line = null)
            ellipse(67.8f, 92.4f, 0.8f, 0.8f, C.eyeShine, line = null)
            paint(svg("M57 97 Q60 100.5 63 97"), null, 2.2f)
        }
    }
}

private val flowers = listOf(36f to 30f, 62f to 12f, 86f to 30f, 48f to 44f, 76f to 44f, 60f to 30f)

private fun DrawScope.drawPlant(stage: GrowthStage) {
    when (stage) {
        GrowthStage.SEED -> {
            ellipse(60f, 64f, 11f, 9f, C.seed)
            paint(svg("M55 60 Q60 56 65 60"), null, 2f)
        }
        GrowthStage.SPROUT -> {
            paint(svg("M60 48 Q63 48 63 51 V69 Q63 72 60 72 Q57 72 57 69 V51 Q57 48 60 48 Z"), C.stem)
            paint(svg("M58 58 Q44 58 40 46 Q54 42 58 58 Z"), C.leaf)
            paint(svg("M62 54 Q74 52 80 40 Q66 38 62 54 Z"), C.leaf)
        }
        GrowthStage.SAPLING -> {
            paint(svg("M60 30 Q63 30 63 33 V69 Q63 72 60 72 Q57 72 57 69 V33 Q57 30 60 30 Z"), C.stem)
            paint(svg("M58 60 Q44 60 38 48 Q52 44 58 60 Z"), C.leaf)
            paint(svg("M62 50 Q76 50 84 38 Q68 34 62 50 Z"), C.leaf)
            paint(svg("M58 40 Q48 38 44 28 Q55 26 58 40 Z"), C.leaf)
            paint(svg("M60 31 Q54 22 60 12 Q66 22 60 31 Z"), C.leafTip)
        }
        GrowthStage.YOUNG -> {
            paint(svg("M55 72 L57 46 H63 L65 72 Z"), C.trunk)
            paint(svg("M34 46 Q26 30 42 24 Q46 10 62 12 Q78 8 82 24 Q96 30 86 46 Q80 56 60 52 Q40 56 34 46 Z"), C.canopy)
            paint(svg("M61 50.5 Q79 54 85 45 Q91 37 87.5 30 Q79 43 61 45.5 Z"), C.canopyShade, line = null)
            ellipse(47f, 24f, 7f, 3.6f, C.canopyShine, line = null)
        }
        GrowthStage.TREE -> drawBigCanopy()
        GrowthStage.BLOSSOM -> {
            drawBigCanopy()
            flowers.forEach { (cx, cy) ->
                ellipse(cx, cy, 6f, 6f, C.petal)
                ellipse(cx, cy, 2.2f, 2.2f, C.petalCore, line = null)
            }
        }
    }
}

private fun DrawScope.drawBigCanopy() {
    paint(svg("M52 72 L55 40 H65 L68 72 Z"), C.trunk)
    paint(svg("M26 44 Q16 26 34 18 Q40 2 60 4 Q80 2 86 18 Q104 26 94 44 Q86 58 60 54 Q34 58 26 44 Z"), C.canopyBig)
    paint(svg("M61 52.5 Q85 56 93 44 Q100 34 96 25 Q86 43 61 47 Z"), C.canopyBigShade, line = null)
    ellipse(42f, 18f, 8f, 4f, C.canopyShine, line = null)
}
