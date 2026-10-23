package com.example.wellnessgame.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.wellnessgame.ui.theme.Wellness
import com.example.wellnessgame.ui.theme.WellnessMetrics

/**
 * 셀 애니 외곽선: 오프셋 그림자 → 채움 → 잉크 외곽선 순서로 그린다(iOS `celOutline`).
 * 그림자는 레이아웃 크기를 바꾸지 않도록 바깥으로 그리므로 호출부에서 그림자만큼 여백을 둔다.
 */
fun Modifier.celOutline(
    fill: Color,
    ink: Color,
    shadow: Color,
    radius: Dp = WellnessMetrics.cardRadius,
    shadowOffset: Dp = WellnessMetrics.cardShadow,
    line: Dp = WellnessMetrics.line,
    shadowX: Boolean = true,
): Modifier = drawBehind {
    val r = CornerRadius(radius.toPx())
    val offset = shadowOffset.toPx()
    drawRoundRect(shadow, topLeft = Offset(if (shadowX) offset else 0f, offset), size = size, cornerRadius = r)
    drawRoundRect(fill, cornerRadius = r)
    val stroke = line.toPx()
    drawRoundRect(
        ink,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx() - stroke / 2),
        style = Stroke(stroke),
    )
}

/** 카드: surface + 3 외곽선 + 4 오프셋 그림자. */
@Composable
fun CelCard(
    modifier: Modifier = Modifier,
    fill: Color = Wellness.colors.surface,
    radius: Dp = WellnessMetrics.cardRadius,
    content: @Composable () -> Unit,
) {
    val colors = Wellness.colors
    Box(
        modifier
            .fillMaxWidth()
            .padding(end = WellnessMetrics.cardShadow, bottom = WellnessMetrics.cardShadow)
            .celOutline(fill, colors.ink, colors.popShadow, radius = radius)
            .padding(16.dp),
    ) { content() }
}

/** 제목 + 본문(선택) + 추가 내용(버튼·칩 등)을 담은 카드. */
@Composable
fun MessageCard(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = Wellness.colors
    CelCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = titleStyle, color = colors.textPrimary)
            body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary) }
            content()
        }
    }
}

/** 버튼: 3 외곽선 + 아래로 떨어지는 그림자, 누르면 그림자 쪽으로 내려앉는다. */
@Composable
fun CelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Wellness.colors.yellow,
    textColor: Color = Wellness.colors.onPop,
    enabled: Boolean = true,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = Wellness.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val drop = 5.dp
    Box(modifier.fillMaxWidth().padding(bottom = drop)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .offset { IntOffset(0, if (pressed) (drop - 1.dp).roundToPx() else 0) }
                .celOutline(
                    // 반투명 채움은 아래 그림자가 비쳐 탁해지므로 배경과 미리 섞어 불투명하게 칠한다.
                    fill = if (enabled) fill else fill.copy(alpha = 0.45f).compositeOver(colors.background),
                    ink = colors.ink,
                    shadow = colors.popShadow,
                    radius = WellnessMetrics.buttonRadius,
                    shadowOffset = if (pressed) 1.dp else drop,
                    shadowX = false,
                )
                .clip(RoundedCornerShape(WellnessMetrics.buttonRadius))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke(this)
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) textColor else textColor.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 캡슐 칩. 채색 면 위 글자는 항상 잉크(onPop). */
@Composable
fun PillBadge(text: String, modifier: Modifier = Modifier, fill: Color = Wellness.colors.yellow) {
    val colors = Wellness.colors
    Text(
        text,
        modifier = modifier
            .clip(CircleShape)
            .background(fill)
            .border(WellnessMetrics.thinLine, colors.ink, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = colors.onPop,
    )
}

/** 섹션 제목: 기울어진 브랜드 그린 막대 + 굵은 제목. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    val colors = Wellness.colors
    Row(modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 8.dp, height = 22.dp)
                .rotate(12f)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.primary),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
        )
    }
}

/** 경고 박스: danger-bg + 외곽선, danger-text 문구. */
@Composable
fun DangerNotice(text: String, modifier: Modifier = Modifier) {
    val colors = Wellness.colors
    Text(
        text,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WellnessMetrics.chipRadius))
            .background(colors.dangerBackground)
            .border(WellnessMetrics.thinLine, colors.ink, RoundedCornerShape(WellnessMetrics.chipRadius))
            .padding(12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.dangerText,
    )
}

/** 진행 막대(XP·퀘스트). 접근성 값은 [description]으로만 읽힌다. */
@Composable
fun ProgressBar(
    progress: Float,
    description: String,
    modifier: Modifier = Modifier,
    fill: Color = Wellness.colors.xp,
    height: Dp = 24.dp,
) {
    val colors = Wellness.colors
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(WellnessMetrics.chipRadius))
            .background(colors.track)
            .border(WellnessMetrics.thinLine, colors.ink, RoundedCornerShape(WellnessMetrics.chipRadius))
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
                stateDescription = description
            },
    ) {
        if (clamped > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(clamped)
                    .height(height)
                    .clip(RoundedCornerShape(WellnessMetrics.chipRadius))
                    .background(fill)
                    .drawBehind {
                        // 사선 줄무늬 + 채움 끝 잉크 세로선
                        val stripe = 10.dp.toPx()
                        var x = -size.height
                        while (x < size.width) {
                            drawLine(
                                Color.White.copy(alpha = 0.28f),
                                Offset(x, size.height),
                                Offset(x + size.height, 0f),
                                strokeWidth = stripe / 2,
                            )
                            x += stripe * 1.6f
                        }
                        drawLine(colors.ink, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 2.5.dp.toPx())
                    },
            )
        }
    }
}

/** 퀘스트 카드: 46 크기 틴트 아이콘 상자, 제목·부제, 오른쪽 칩, 아래 진행 막대(선택). */
@Composable
fun QuestCard(
    glyph: String,
    tint: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    progressDescription: String = "",
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Wellness.colors
    CelCard(modifier, radius = WellnessMetrics.cardRadius) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(WellnessMetrics.chipRadius))
                        .background(tint)
                        .border(WellnessMetrics.thinLine, colors.ink, RoundedCornerShape(WellnessMetrics.chipRadius))
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(glyph, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
                trailing?.let {
                    Spacer(Modifier.width(8.dp))
                    it()
                }
            }
            if (progress != null) {
                ProgressBar(progress, progressDescription, height = 14.dp, fill = tint)
            }
        }
    }
}

/** 스탯 타일: 위쪽 색 띠 + 큰 숫자 + 'STR 근력' 라벨. */
@Composable
fun StatTile(abbr: String, label: String, value: Int, band: Color, modifier: Modifier = Modifier) {
    val colors = Wellness.colors
    Column(
        modifier
            .padding(end = 3.dp, bottom = 3.dp)
            .celOutline(colors.surface, colors.ink, colors.popShadow, radius = 18.dp, shadowOffset = 3.dp)
            .clip(RoundedCornerShape(18.dp))
            .semantics(mergeDescendants = true) { contentDescription = "$label $value" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(10.dp).background(band))
        Text(
            value.toString(),
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.titleLarge,
            color = colors.textPrimary,
        )
        Text(abbr, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black), color = colors.textPrimary)
        Text(
            label,
            modifier = Modifier.padding(bottom = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
    }
}
