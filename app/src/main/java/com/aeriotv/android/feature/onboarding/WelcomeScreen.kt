package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.R
import com.aeriotv.android.feature.onboarding.components.SourceTypeCard

/**
 * Cold-start welcome surface. Mirrors iOS App Store screenshot IMG_1076: AerioTV
 * brand block + supported source types + Sync / Detect-Home-WiFi info cards +
 * "Connect a Server" CTA + "Skip for now" link.
 *
 * Layout adapts to viewport: a short-but-wide screen (Android TV at 960dp x
 * 540dp, tablets in landscape, foldables unfolded) switches to a two-column
 * presentation so the CTA stays above the fold and the user can reach it with
 * a D-pad without scrolling. Portrait phones get the original vertical stack.
 */
@Composable
fun WelcomeScreen(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    /**
     * Optional Sign-in-with-Google handler. When provided, a pill appears
     * between Connect-a-Server and Skip on the welcome screen so the user
     * can authenticate Drive Sync up front instead of being routed back to
     * Settings later. Setting this null hides the row entirely (e.g. on
     * builds that ship without a Google Cloud OAuth client ID baked in).
     */
    onSignInWithGoogle: (() -> Unit)? = null,
    googleSignInInProgress: Boolean = false,
) {
    val config = LocalConfiguration.current
    val isTv = com.aeriotv.android.feature.livetv.rememberLiveTvFormFactor().isTv
    // Two-column when the viewport is meaningfully wider than tall (tablet
    // landscape, foldable unfolded). TV is excluded: it uses the centered
    // single column to mirror the tvOS welcome layout.
    val twoColumn = !isTv && config.screenWidthDp >= 720 && config.screenHeightDp < 720

    if (twoColumn) WelcomeTwoColumn(onConnectServer, onSkip, onSignInWithGoogle, googleSignInInProgress)
    else WelcomeSingleColumn(onConnectServer, onSkip, onSignInWithGoogle, googleSignInInProgress)
}

@Composable
private fun WelcomeSingleColumn(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)?,
    googleSignInInProgress: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WelcomeAmbientOrbs(modifier = Modifier.fillMaxSize())
        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize(),
        ) {
            // Fit the whole page without scrolling, at any screen size. A short
            // viewport (every Android TV: a 1080p panel is only ~540dp tall, and
            // landscape phones) can't fit the roomy stacked layout -- its
            // intrinsic content height alone exceeds the screen. Below the
            // threshold we switch to a COMPACT layout that spends the ample
            // WIDTH instead: source types in one row, the two info cards side by
            // side, a smaller logo, tighter spacing. The roomy stacked layout
            // stays for tall screens (portrait phones). verticalScroll remains
            // only as a last-ditch fallback so an unforeseen size never clips.
            val compact = maxHeight < 640.dp
            val colMaxWidth = if (compact) 900.dp else 560.dp
            val vPad = if (compact) 12.dp else 24.dp
            val gap = if (compact) 12.dp else 18.dp
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .widthIn(max = colMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = vPad),
                horizontalAlignment = Alignment.CenterHorizontally,
                // Center the content so, when it fits (the normal case now), it
                // sits balanced in the viewport instead of pinned to the top.
                verticalArrangement = Arrangement.Center,
            ) {
                BrandBlock(compact = compact)
                Spacer(Modifier.height(gap))
                SupportedTypesGroup(alignStart = false, compact = compact)
                Spacer(Modifier.height(gap))
                InfoCardsGroup(compact = compact)
                Spacer(Modifier.height(gap))
                ActionButtons(
                    onConnectServer = onConnectServer,
                    onSkip = onSkip,
                    onSignInWithGoogle = onSignInWithGoogle,
                    googleSignInInProgress = googleSignInInProgress,
                    compact = compact,
                )
            }
        }
    }
}

@Composable
private fun WelcomeTwoColumn(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)?,
    googleSignInInProgress: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WelcomeAmbientOrbs(modifier = Modifier.fillMaxSize())
        WelcomeTwoColumnRow(onConnectServer, onSkip, onSignInWithGoogle, googleSignInInProgress)
    }
}

@Composable
private fun WelcomeTwoColumnRow(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)?,
    googleSignInInProgress: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        // Left column: brand + supported source types. Centered vertically.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            BrandBlock(alignStart = true)
            Spacer(Modifier.height(20.dp))
            SupportedTypesGroup(alignStart = true)
        }
        // Right column: info cards + CTAs. The Connect button sits in the
        // viewport center on TV so the focus ring lands on it at cold start,
        // which is what a remote-driven flow wants.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .widthIn(max = 540.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            InfoCardsGroup()
            Spacer(Modifier.height(20.dp))
            ActionButtons(
                onConnectServer = onConnectServer,
                onSkip = onSkip,
                onSignInWithGoogle = onSignInWithGoogle,
                googleSignInInProgress = googleSignInInProgress,
            )
        }
    }
}

@Composable
private fun BrandBlock(alignStart: Boolean = false, compact: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        BrandLogo(compact = compact)
        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        Text(
            text = "AerioTV",
            style = if (compact) MaterialTheme.typography.titleLarge
            else MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        Text(
            text = "Your IPTV & Media Hub",
            style = if (compact) MaterialTheme.typography.bodyMedium
            else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        // The platform line is the least essential brand text; drop it in the
        // compact (short-viewport) layout to reclaim vertical space.
        if (!compact) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Android TV · Phone · Tablet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SupportedTypesGroup(alignStart: Boolean = false, compact: Boolean = false) {
    if (compact) {
        // Short viewport: the three supported types ride a single row so they
        // cost ~one line of height instead of three.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SupportedTypeInline(icon = Icons.Filled.Key, label = "Dispatcharr Direct Connect")
            SupportedTypeInline(icon = Icons.Filled.Tv, label = "Xtream Codes")
            SupportedTypeInline(icon = Icons.Filled.Description, label = "M3U + EPG")
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        SupportedTypeRow(icon = Icons.Filled.Key, label = "Dispatcharr Direct Connect", alignStart = alignStart)
        SupportedTypeRow(icon = Icons.Filled.Tv, label = "Xtream Codes", alignStart = alignStart)
        SupportedTypeRow(icon = Icons.Filled.Description, label = "M3U + EPG", alignStart = alignStart)
    }
}

@Composable
private fun InfoCardsGroup(compact: Boolean = false) {
    if (compact) {
        // Short viewport: the two info cards sit side by side (each takes half
        // the wide TV/landscape column) so they cost one card-height, not two.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SourceTypeCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.CloudOff,
                title = "Sync via Google Account",
                subtitle = "After setup, sign in to Drive in Settings > Sync to mirror playlists, watch progress, reminders, and preferences across devices.",
            )
            SourceTypeCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Wifi,
                title = "Automatic LAN Switching",
                subtitle = "After setup, add a LAN URL to your playlist in Settings and AerioTV uses it automatically whenever your server is reachable locally.",
            )
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        SourceTypeCard(
            icon = Icons.Filled.CloudOff,
            title = "Sync via Google Account",
            subtitle = "After setup, sign in to Drive in Settings > Sync to mirror playlists, watch progress, reminders, and preferences across devices.",
        )
        Spacer(Modifier.height(10.dp))
        SourceTypeCard(
            icon = Icons.Outlined.Wifi,
            title = "Automatic LAN Switching",
            subtitle = "After setup, add a LAN URL to your playlist in Settings and AerioTV uses it automatically whenever your server is reachable locally.",
        )
    }
}

/** One supported-source type as a compact inline icon+label, for the
 *  short-viewport single-row layout. */
@Composable
private fun SupportedTypeInline(icon: ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GradientIcon(
            imageVector = icon,
            brush = accentBrush(),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun ActionButtons(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)? = null,
    googleSignInInProgress: Boolean = false,
    compact: Boolean = false,
) {
    val gradient = accentBrush()
    val shape = RoundedCornerShape(28.dp)
    Column(
        // Cap width in compact so the buttons don't stretch across the wide
        // TV/landscape column; full-width on the narrow stacked layout.
        modifier = (if (compact) Modifier.widthIn(max = 520.dp) else Modifier)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // iOS WelcomeView line 174: `.background(LinearGradient.accentGradient)`.
        // Material3 Button can't host a Brush container, so the CTA is a
        // gradient-backed Box with clickable + same Material ripple behavior.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                // Clip to the rounded shape BEFORE clickable so the D-pad focus
                // highlight follows the corners instead of a squared rectangle.
                .clip(shape)
                .background(brush = gradient, shape = shape)
                .clickable(onClick = onConnectServer),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Connect a Server",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (onSignInWithGoogle != null) {
            Spacer(Modifier.height(12.dp))
            // Optional Drive Sign-in path. Lives between Connect (primary) and
            // Skip (secondary) so it reads as a "I already have devices set
            // up, pull my data" alternative to fresh-server onboarding. The
            // visual is the Google-compliant dark variant (#131314 BG +
            // four-color G mark) so it stays on-brand against AerioTV navy.
            WelcomeGoogleSignInButton(
                enabled = !googleSignInInProgress,
                onClick = onSignInWithGoogle,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text(
                text = "Skip for now",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Onboarding-flavour of the Google Sign-In button. Mirrors the dark variant
 * in [com.aeriotv.android.feature.settings.SyncSettingsScreen.SignInWithGoogleButton]
 * — same #131314 background, #8E918F border, four-color G mark drawn from
 * res/drawable/ic_google_g.xml. Kept local so the welcome screen doesn't
 * depend on Settings internals.
 */
@Composable
private fun WelcomeGoogleSignInButton(enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) Color(0xFF131314) else Color(0xFF131314).copy(alpha = 0.55f)
    val stroke = Color(0xFF8E918F).copy(alpha = if (enabled) 1f else 0.55f)
    val fg = if (enabled) Color(0xFFE3E3E3) else Color(0xFFE3E3E3).copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, stroke, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(
                id = com.aeriotv.android.R.drawable.ic_google_g,
            ),
            contentDescription = null,
            // Tint.Unspecified preserves the four-color brand mark — Google
            // brand guidelines forbid tinting the G to a single colour.
            tint = Color.Unspecified,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = "Sign in with Google",
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
    }
    // Stroke goes after content so the rounded outline reads cleanly on top
    // of the BG fill regardless of which composable above happens to clip
    // its descendants.
}

@Composable
private fun BrandLogo(compact: Boolean = false) {
    // Mirrors iOS WelcomeView's `.shadow(color: aerioCyan @ 0.45, radius: 20, y: 8)`
    // under the logo. Compose's shadow modifier only renders elevation drop-shadows,
    // not colored glows, so we draw a soft radial gradient halo behind the logo
    // bitmap instead — same visual effect, API-agnostic.
    val accent = MaterialTheme.colorScheme.primary
    val haloSize = if (compact) 64.dp else 96.dp
    val imageSize = if (compact) 44.dp else 64.dp
    Box(
        modifier = Modifier.size(haloSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(haloSize)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to accent.copy(alpha = 0.55f),
                        0.35f to accent.copy(alpha = 0.28f),
                        0.7f to accent.copy(alpha = 0.06f),
                        1.0f to Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height / 2f + 8.dp.toPx()),
                    radius = size.minDimension / 2f,
                ),
                center = Offset(size.width / 2f, size.height / 2f + 8.dp.toPx()),
                radius = size.minDimension / 2f,
            )
        }
        Image(
            painter = painterResource(id = R.drawable.aerio_logo),
            contentDescription = null,
            modifier = Modifier.size(imageSize),
        )
    }
}

/**
 * Two soft, blurred accent orbs in opposite corners — mirrors iOS WelcomeView
 * lines 22-35 where SwiftUI uses `Circle().blur(radius: 80)` on top of the
 * navy background. Compose's Modifier.blur is API 31+, so the same look here
 * comes from radial gradients with carefully tuned color stops.
 */
@Composable
private fun WelcomeAmbientOrbs(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Canvas(modifier = modifier) {
        // Top-left orb (cyan)
        val tlCenter = Offset(-50.dp.toPx(), -30.dp.toPx())
        val tlRadius = 360.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to primary.copy(alpha = 0.18f),
                    0.4f to primary.copy(alpha = 0.10f),
                    0.75f to primary.copy(alpha = 0.03f),
                    1.0f to Color.Transparent,
                ),
                center = tlCenter,
                radius = tlRadius,
            ),
            center = tlCenter,
            radius = tlRadius,
        )
        // Bottom-right orb (teal)
        val brCenter = Offset(size.width + 50.dp.toPx(), size.height - 80.dp.toPx())
        val brRadius = 320.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to secondary.copy(alpha = 0.16f),
                    0.4f to secondary.copy(alpha = 0.08f),
                    0.75f to secondary.copy(alpha = 0.02f),
                    1.0f to Color.Transparent,
                ),
                center = brCenter,
                radius = brRadius,
            ),
            center = brCenter,
            radius = brRadius,
        )
    }
}

/** Linear cyan→teal gradient matching iOS LinearGradient.accentGradient
 * (top-leading to bottom-trailing). */
@Composable
private fun accentBrush(): Brush = Brush.linearGradient(
    colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
    ),
)

/** Icon filled with a Brush instead of a solid tint — mirrors iOS FeaturePill's
 * `.foregroundStyle(LinearGradient.accentGradient)` on the SF Symbol. */
@Composable
private fun GradientIcon(
    imageVector: ImageVector,
    brush: Brush,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.SrcIn)
                },
        )
    }
}

@Composable
private fun SupportedTypeRow(icon: ImageVector, label: String, alignStart: Boolean = false) {
    val gradient = accentBrush()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            10.dp,
            if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
        ),
    ) {
        GradientIcon(
            imageVector = icon,
            brush = gradient,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
