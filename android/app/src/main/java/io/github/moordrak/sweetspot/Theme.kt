package io.github.moordrak.sweetspot

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image

// Boje web sučelja SweetSpot (tema Svijetla i Tamna)
val Brand = Color(0xFF2A78D6)
val BrandDark = Color(0xFF3987E5)
val Accent = Color(0xFFEB6834)
val Warn = Color(0xFFC2571A)

private val LightColors = lightColorScheme(
    primary = Brand, onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE9FA), onPrimaryContainer = Color(0xFF0B2E57),
    secondary = Accent, onSecondary = Color.White,
    background = Color(0xFFF4F3EF), onBackground = Color(0xFF0B0B0B),
    surface = Color(0xFFFCFCFB), onSurface = Color(0xFF0B0B0B),
    surfaceVariant = Color(0xFFEDECE6), onSurfaceVariant = Color(0xFF52514E),
    surfaceContainer = Color(0xFFFCFCFB), surfaceContainerLow = Color(0xFFFCFCFB),
    surfaceContainerHigh = Color(0xFFF7F6F2), surfaceContainerHighest = Color(0xFFEFEEE9),
    outline = Color(0xFFC9C8C1), outlineVariant = Color(0xFFE1E0D9),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = BrandDark, onPrimary = Color.White,
    primaryContainer = Color(0xFF173A63), onPrimaryContainer = Color(0xFFDCE9FA),
    secondary = Color(0xFFD95926), onSecondary = Color.White,
    background = Color(0xFF111110), onBackground = Color(0xFFF0EFEC),
    surface = Color(0xFF1A1A19), onSurface = Color(0xFFF0EFEC),
    surfaceVariant = Color(0xFF262624), onSurfaceVariant = Color(0xFFC3C2B7),
    surfaceContainer = Color(0xFF1A1A19), surfaceContainerLow = Color(0xFF1A1A19),
    surfaceContainerHigh = Color(0xFF21211F), surfaceContainerHighest = Color(0xFF2A2A28),
    outline = Color(0xFF4A4A46), outlineVariant = Color(0xFF2C2C2A),
    error = Color(0xFFFF8A80),
)

@Composable
fun SweetSpotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

@Composable
fun Logo(size: Int = 40) {
    Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(size.dp))
}

/** Zaglavlje zaslona: logo, naslov i podnaslov. */
@Composable
fun Header(title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Logo(40)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Kartica u stilu web sučelja: tanki rub, bez sjene. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun Label(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
