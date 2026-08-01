package com.of.colorpicker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController

data class DyeParams(
    val pictureId: Int,
    val params: DoubleArray
)

data class SearchResult(
    val targetHex: String,
    val matchedHex: String,
    val similarity: Double,
    val uvy: Double,
    val slot: Int,
    val colors: List<Color>
)

/**
 * Compact color picker overlay for global WindowManager overlay.
 *
 * Layout (matching Windows version, scaled down):
 * ┌──────────────────────────────────────┐
 * │  drag bar                      [−]   │
 * ├─────┬────────────────────────────────┤
 * │     │  info log                      │
 * │ bar │                                │
 * │ 1↕0 │────────────────────────────────│
 * │     │  [target color strip]          │
 * │     │  [■] [■] [■] [■] [■]  squares │
 * │     │  ▲ (marker below best square)  │
 * └─────┴────────────────────────────────┘
 */
@Composable
fun ColorPickerOverlay(
    targetHex: String,
    onTargetClick: () -> Unit,
    result: SearchResult?,
    logLines: List<String>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Overlay dimensions (compact)
    val expandedWidthDp = 280.dp
    val expandedHeightDp = 220.dp
    val collapsedWidthDp = 48.dp
    val collapsedHeightDp = 22.dp

    val barWidthDp = 36.dp
    val dragHeightDp = 22.dp
    val stripHeightDp = 24.dp
    val squareHeightDp = 80.dp
    val markerHeightDp = 14.dp

    val barPadTopDp = 4.dp
    val barPadBottomDp = 14.dp

    var collapsed by remember { mutableStateOf(false) }

    // When collapsing/expanding, adjust WindowManager position to anchor right edge
    val overlayService = remember { context as? OverlayService }
    val savedExpandedX = remember { mutableIntStateOf(0) }

    LaunchedEffect(collapsed) {
        val svc = overlayService ?: return@LaunchedEffect
        val bounds = svc.getLayoutBounds() ?: return@LaunchedEffect
        val (curX, curY, curWidth, curHeight) = bounds
        val densityValue = density.density

        if (collapsed) {
            savedExpandedX.intValue = curX
            val collapsedWidthPx = (collapsedWidthDp.value * densityValue).toInt()
            val collapsedHeightPx = (collapsedHeightDp.value * densityValue).toInt()
            val newX = curX + curWidth - collapsedWidthPx
            svc.updateOverlayLayout(newX, collapsedWidthPx, collapsedHeightPx)
        } else {
            val expandedWidthPx = (expandedWidthDp.value * densityValue).toInt()
            val expandedHeightPx = (expandedHeightDp.value * densityValue).toInt()
            val currentRightEdge = curX + curWidth
            val newX = currentRightEdge - expandedWidthPx
            svc.updateOverlayLayout(newX, expandedWidthPx, expandedHeightPx)
        }
    }

    val uvy = result?.uvy ?: 0.5

    if (collapsed) {
        Row(
            modifier = Modifier
                .width(collapsedWidthDp)
                .height(collapsedHeightDp)
                .background(Color(0, 0, 0, 160), RoundedCornerShape(6.dp)),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "+",
                color = Color(170, 170, 170),
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { collapsed = false }
                    .padding(horizontal = 6.dp)
            )
        }
    } else {
        Column(
            modifier = Modifier
                .width(expandedWidthDp)
                .height(expandedHeightDp)
        ) {
            // Drag bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dragHeightDp)
                    .background(Color(0, 0, 0, 160), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "−",
                    color = Color(170, 170, 170),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { collapsed = true }
                        .padding(horizontal = 6.dp)
                )
            }

            // Content area
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0, 0, 0, 160), RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
            ) {
                // Left: uvy bar
                UvyBar(
                    uvy = uvy,
                    barWidth = barWidthDp,
                    padTop = barPadTopDp,
                    padBottom = barPadBottomDp,
                    modifier = Modifier
                        .width(barWidthDp)
                        .fillMaxHeight()
                )

                // Right: info + color strip + squares + marker
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp)
                ) {
                    InfoLog(
                        lines = logLines,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )

                    ColorStrip(
                        color = parseHexColor(targetHex),
                        onClick = onTargetClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(stripHeightDp)
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(squareHeightDp + markerHeightDp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(squareHeightDp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            for (i in 0 until 5) {
                                val color = result?.colors?.getOrNull(i) ?: Color(60, 60, 60)
                                ColorSquare(
                                    color = color,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(markerHeightDp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            for (i in 0 until 5) {
                                val isMarked = result != null && result.slot == i + 1
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    if (isMarked) {
                                        MarkerTriangle(
                                            modifier = Modifier
                                                .width(14.dp)
                                                .height(markerHeightDp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UvyBar(
    uvy: Double,
    barWidth: androidx.compose.ui.unit.Dp,
    padTop: androidx.compose.ui.unit.Dp,
    padBottom: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val totalHeight = maxHeight
        val usableHeight = totalHeight - padTop - padBottom
        val markerFromTop = padTop + usableHeight * (1f - uvy.toFloat())

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(Color(42, 42, 48), Offset.Zero, Size(w, h))
            drawRect(Color(65, 65, 70), Offset(w - 1, 0f), Size(1f, h))
            drawLine(
                Color(220, 220, 220, 200),
                Offset(3f, markerFromTop.toPx()),
                Offset(w - 3, markerFromTop.toPx()),
                strokeWidth = 2f
            )
        }

        Text(
            text = "%.3f".format(uvy),
            color = Color(220, 220, 220),
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .offset(y = markerFromTop - 10.dp)
                .wrapContentWidth(Alignment.CenterHorizontally)
        )
    }
}

@Composable
private fun InfoLog(
    lines: List<String>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        val visibleLines = lines.takeLast(3)
        for (line in visibleLines) {
            Text(
                text = line,
                color = Color(187, 187, 187),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                lineHeight = 13.sp
            )
        }
        if (lines.isEmpty()) {
            Text(
                text = "select a target color",
                color = Color(187, 187, 187),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
private fun ColorStrip(
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(color, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
    )
}

@Composable
private fun ColorSquare(
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(color)
    )
}

@Composable
private fun MarkerTriangle(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val triW = w * 0.8f
        val cx = w / 2
        val path = Path().apply {
            moveTo(cx, 0f)
            lineTo(cx - triW / 2, h)
            lineTo(cx + triW / 2, h)
            close()
        }
        drawPath(path, Color(255, 200, 50), style = Fill)
    }
}

private fun parseHexColor(hex: String): Color {
    val h = hex.removePrefix("#")
    if (h.length != 6) return Color.White
    val r = h.substring(0, 2).toInt(16)
    val g = h.substring(2, 4).toInt(16)
    val b = h.substring(4, 6).toInt(16)
    return Color(r, g, b)
}

@Composable
fun SimpleColorPickerDialog(
    currentColor: String,
    onColorSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val controller = rememberColorPickerController()
    var selectedColor by remember { mutableStateOf(parseHexColor(currentColor)) }
    var hexInput by remember { mutableStateOf(currentColor.removePrefix("#")) }
    val density = LocalDensity.current

    // When dialog opens, make overlay focusable and expand it
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val svc = context as? OverlayService
        svc?.setFocusable(true)
        val bounds = svc?.getLayoutBounds()
        val d = density.density
        val dialogWidth = (280 * d).toInt()
        val dialogHeight = (290 * d).toInt()
        val curRight = if (bounds != null) bounds[0] + bounds[2] else Int.MAX_VALUE
        val newX = curRight - dialogWidth
        svc?.updateOverlayLayout(newX, dialogWidth, dialogHeight)
        onDispose {
            svc?.setFocusable(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0, 0, 0, 140))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(260.dp)
                .background(Color(25, 25, 35), RoundedCornerShape(8.dp))
                .padding(12.dp)
                .clickable(enabled = false) {}
        ) {
            // HSV color picker (square)
            HsvColorPicker(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                controller = controller,
                initialColor = parseHexColor(currentColor),
                onColorChanged = { colorEnvelope ->
                    selectedColor = colorEnvelope.color
                    // hexCode is ARGB (8 chars), take last 6 for RGB
                    hexInput = colorEnvelope.hexCode.takeLast(6)
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Horizontal brightness slider below the color picker
            BrightnessSlider(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(4.dp)),
                controller = controller,
                borderRadius = 4.dp,
                borderSize = 1.dp,
                borderColor = Color(60, 60, 70),
                initialColor = parseHexColor(currentColor)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom row: square preview + hex input + buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Square color preview
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(selectedColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("#", color = Color(170, 170, 170), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                BasicTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        val filtered = input.filter { c -> c.isLetterOrDigit() }.take(6)
                        hexInput = filtered
                        if (filtered.length == 6) {
                            val color = parseHexColor("#$filtered")
                            selectedColor = color
                            controller.selectByColor(color, fromUser = false)
                        }
                    },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    singleLine = true,
                    modifier = Modifier.width(64.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "Cancel",
                    color = Color(150, 150, 150),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Text(
                    "OK",
                    color = Color(100, 180, 255),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { onColorSelected("#$hexInput") }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
