package com.bido.budgetsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Same family as the ToDo app (gradient banner, soft tiles, rounded cards), but in money colours:
// emerald into deep petrol instead of ToDo's indigo into teal, so the two never get mixed up.
private val Emerald = Color(0xFF1B7F5C)
private val Petrol = Color(0xFF0D4F6B)

val HeaderGradient = Brush.linearGradient(listOf(Emerald, Petrol))

private val LightColors = lightColorScheme(
    primary = Emerald,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBDC),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = Petrol,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4ECE1),
    onSecondaryContainer = Color(0xFF0B3A2B),
    tertiary = Color(0xFFB26A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE7C2),
    onTertiaryContainer = Color(0xFF3D2600),
    background = Color(0xFFF4F7F5),
    surface = Color.White,
    surfaceVariant = Color(0xFFE2EEE7),
    onSurfaceVariant = Color(0xFF56675F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F6F3),
    surfaceContainer = Color(0xFFEBF2EE),
    surfaceContainerHigh = Color(0xFFE5EDE9),
    surfaceContainerHighest = Color(0xFFDFE8E3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FD3A4),
    onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF0F5A40),
    onPrimaryContainer = Color(0xFFB6F0D2),
    secondary = Color(0xFF7FC4E0),
    onSecondary = Color(0xFF003548),
    secondaryContainer = Color(0xFF2A4A3D),
    onSecondaryContainer = Color(0xFFCDEBDC),
    tertiary = Color(0xFFF5BF6B),
    onTertiary = Color(0xFF422C00),
    tertiaryContainer = Color(0xFF5C4100),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFF0E1412),
    surface = Color(0xFF17201C),
    surfaceVariant = Color(0xFF24302A),
    onSurfaceVariant = Color(0xFFA2B4AB),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF151D19),
    surfaceContainer = Color(0xFF19221E),
    surfaceContainerHigh = Color(0xFF212B26),
    surfaceContainerHighest = Color(0xFF2B3631),
)

@Composable
fun BudgetTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

private val CategoryPalette = listOf(
    Color(0xFF00897B), Color(0xFFF4511E), Color(0xFF3949AB), Color(0xFFFFA000),
    Color(0xFF8E24AA), Color(0xFF039BE5), Color(0xFFD81B60), Color(0xFF6D8B3A),
)

/** Each category keeps one colour, picked from its name, so it reads the same on every screen. Income is always green. */
@Composable
fun categoryColor(category: String): Color =
    if (category.equals(com.bido.budgetsync.MainViewModel.INCOME, ignoreCase = true)) incomeColor()
    else CategoryPalette[Math.floorMod(category.trim().lowercase().hashCode(), CategoryPalette.size)]

/** Status colours that stay readable on the green banner. */
fun onBannerStatus(status: String): Color = when (status) {
    "OVER", "UNPLANNED" -> Color(0xFFFFB4A8)
    "WATCH" -> Color(0xFFFFD58A)
    else -> Color.White
}

/**
 * The gradient banner at the top of every screen. With [onBack] it shows a back arrow beside the title;
 * [actions] go on the right as round translucent buttons; [content] is whatever the screen shows inside it.
 */
@Composable
fun GradientHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(HeaderGradient, RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .statusBarsPadding()
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 12.dp, top = 12.dp, bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            }
            Column(Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp)) {
                Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
        Column(Modifier.padding(start = if (onBack != null) 12.dp else 0.dp, end = 4.dp), content = content)
    }
}

@Composable
fun HeaderAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.background(Color.White.copy(alpha = 0.16f), CircleShape)) {
        Icon(icon, contentDescription = description, tint = Color.White)
    }
}

/** A translucent tile on the banner: a figure over a label. */
@Composable
fun HeaderTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.White) {
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun HeaderTiles(vararg tiles: Triple<String, String, Color>) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEach { (label, value, color) -> HeaderTile(label, value, Modifier.weight(1f), color) }
    }
}

/** The white progress bar used on the banner. */
@Composable
fun HeaderProgress(fraction: Float, color: Color = Color.White) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(8.dp).clip(CircleShape),
        color = color,
        trackColor = Color.White.copy(alpha = 0.25f),
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** The rounded progress bar used inside cards. */
@Composable
fun CardProgress(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        color = color,
        trackColor = color.copy(alpha = 0.16f),
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 8.dp),
    )
}

/** The plain white card every list item sits on. */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

/** A tinted card for things that want attention: messages to review, an update, the month-end decision. */
@Composable
fun NoticeCard(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.13f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), content = content)
        }
    }
}

/** A small coloured pill, e.g. a category's status. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A category name with its colour dot, like the tags in the ToDo app. */
@Composable
fun CategoryTag(name: String, modifier: Modifier = Modifier) {
    val color = categoryColor(name)
    Row(
        modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
