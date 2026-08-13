package com.coreline.ai.voice.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun SoundThread(
    amplitude: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val latestAmplitude by rememberUpdatedState(amplitude)
    val samples = remember { mutableStateListOf<Float>().apply { repeat(48) { add(0f) } } }
    LaunchedEffect(active) {
        while (active) {
            samples.removeAt(0)
            samples.add(latestAmplitude)
            delay(120)
        }
        samples.indices.forEach { samples[it] = 0f }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics {
                contentDescription = if (active) "실시간 음성 레벨" else "음성 레벨 대기"
            },
    ) {
        if (samples.size < 2) return@Canvas
        val center = size.height / 2f
        val step = size.width / (samples.size - 1)
        val path = Path().apply { moveTo(0f, center) }
        samples.forEachIndexed { index, sample ->
            val wave = if (index % 2 == 0) -1 else 1
            val y = center + wave * sample.coerceIn(0f, 1f) * size.height * 0.42f
            path.lineTo(index * step, y)
        }
        drawPath(
            path = path,
            color = if (active) colors.primary else colors.outline,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
fun RecordControl(
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val description = if (active) "녹음 정지" else "녹음 시작"
    Box(
        modifier = modifier
            .size(184.dp)
            .clip(CircleShape)
            .background(if (active) colors.primary else colors.surfaceVariant)
            .border(
                1.dp,
                if (active) colors.primary else colors.outline,
                CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(142.dp)
                .border(1.dp, colors.onSurface.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (active) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = null,
                tint = if (active) colors.onPrimary else colors.primary,
                modifier = Modifier.size(46.dp),
            )
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    good: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val background = if (good) colors.secondary else colors.surfaceVariant
    val foreground = if (good) colors.onSecondary else colors.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (good) colors.onSecondary else colors.primary),
        )
        Text(text, style = MaterialTheme.typography.labelLarge, color = foreground)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

@Composable
fun ImageState(
    @DrawableRes image: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(image),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(18.dp)),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.sizeIn(minHeight = 48.dp)) { action() }
        }
    }
}

fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
