package no.nrk.player.ui.player

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

/**
 * Full-screen video player screen.
 *
 * Features:
 *  - Tap-to-show/hide controls with auto-hide after 3 s
 *  - Double-tap left/right to seek ±10 s (YouTube-style ripple)
 *  - Animated subtitle overlay with optional translation line
 *  - "Skip credits" prompt that slides up near end of programme
 *  - "60-second digest" mode toggle
 *  - Adaptive bottom gradient so subtitles remain readable on any content
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun PlayerScreen(
    programmeId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var autoHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    fun showControlsTemporarily() {
        controlsVisible = true
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(3_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(Unit) { showControlsTemporarily() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap         = { showControlsTemporarily() },
                    onDoubleTap   = { offset ->
                        if (offset.x < size.width / 2) viewModel.skipBack()
                        else viewModel.skipForward()
                        showControlsTemporarily()
                    }
                )
            }
    ) {
        // ── Video surface ───────────────────────────────────────────
        val context = LocalContext.current
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player        = viewModel.player
                    useController = false               // we draw our own controls
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Bottom gradient for subtitle readability ─────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                    )
                )
        )

        // ── Subtitle overlay ────────────────────────────────────────
        SubtitleOverlay(
            subtitle           = uiState.currentSubtitle,
            translatedSubtitle = if (uiState.translationEnabled) uiState.translatedSubtitle else null,
            modifier           = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (controlsVisible) 88.dp else 32.dp)
        )

        // ── Skip credits prompt ─────────────────────────────────────
        AnimatedVisibility(
            visible = uiState.showSkipCredits && !uiState.isDigestMode,
            enter   = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit    = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 100.dp)
        ) {
            OutlinedButton(
                onClick = viewModel::skipCredits,
                colors  = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border  = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f))
            ) {
                Text("Skip credits →")
            }
        }

        // ── Digest mode badge ───────────────────────────────────────
        AnimatedVisibility(
            visible  = uiState.isDigestMode,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            DigestModeBadge(onExit = viewModel::exitDigestMode)
        }

        // ── Player controls ─────────────────────────────────────────
        AnimatedVisibility(
            visible = controlsVisible,
            enter   = fadeIn(),
            exit    = fadeOut()
        ) {
            PlayerControls(
                uiState  = uiState,
                viewModel = viewModel,
                onBack   = onBack,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── Loading / error ─────────────────────────────────────────
        if (uiState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = Color.White
            )
        }

        uiState.error?.let { error ->
            ErrorSnackbar(
                message   = error,
                onDismiss = viewModel::dismissError,
                modifier  = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Controls overlay
// ---------------------------------------------------------------------------

@UnstableApi
@Composable
private fun PlayerControls(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPlaying = viewModel.player.isPlaying

    Box(modifier = modifier) {

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))

            // Translation toggle
            IconButton(onClick = {
                if (uiState.translationEnabled) viewModel.disableTranslation()
                else viewModel.enableTranslation("en")   // default → English; expose picker in real app
            }) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = "Toggle translation",
                    tint = if (uiState.translationEnabled) MaterialTheme.colorScheme.primary else Color.White
                )
            }

            // Digest mode toggle
            if (uiState.digest != null && !uiState.isDigestMode) {
                TextButton(onClick = viewModel::startDigestMode) {
                    Text(
                        text  = "60s Digest",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // Centre play/pause
        Row(
            modifier            = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment   = Alignment.CenterVertically
        ) {
            IconButton(onClick = viewModel::skipBack) {
                Icon(Icons.Default.Replay10, contentDescription = "Back 10s",
                    tint = Color.White, modifier = Modifier.size(36.dp))
            }
            IconButton(
                onClick  = viewModel::togglePlayPause,
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.White.copy(alpha = 0.15f), CircleShape)
            ) {
                Icon(
                    imageVector     = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint            = Color.White,
                    modifier        = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = viewModel::skipForward) {
                Icon(Icons.Default.Forward10, contentDescription = "Forward 10s",
                    tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }

        // Buffering spinner
        if (uiState.playerState is NrkPlayerController.PlayerState.Buffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = Color.White,
                strokeWidth = 2.dp
            )
        }

        // Bottom seek bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val position = viewModel.player.currentPosition
            val duration = viewModel.player.duration.coerceAtLeast(1L)
            val progress = (position.toFloat() / duration).coerceIn(0f, 1f)

            Slider(
                value         = progress,
                onValueChange = { viewModel.seekTo((it * duration).toLong()) },
                colors        = SliderDefaults.colors(
                    thumbColor       = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )

            Row(Modifier.fillMaxWidth()) {
                Text(formatMs(position), color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(formatMs(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Subtitle overlay
// ---------------------------------------------------------------------------

@Composable
private fun SubtitleOverlay(
    subtitle: String?,
    translatedSubtitle: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier             = modifier.padding(horizontal = 24.dp),
        horizontalAlignment  = Alignment.CenterHorizontally
    ) {
        subtitle?.let { text ->
            SubtitleText(text = text, isTranslation = false)
        }
        translatedSubtitle?.let { text ->
            Spacer(Modifier.height(4.dp))
            SubtitleText(text = text, isTranslation = true)
        }
    }
}

@Composable
private fun SubtitleText(text: String, isTranslation: Boolean) {
    Text(
        text      = text,
        color     = if (isTranslation) Color(0xFFFFD700) else Color.White,
        fontSize  = if (isTranslation) 15.sp else 17.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        lineHeight = 22.sp,
        modifier  = Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

// ---------------------------------------------------------------------------
// Digest mode badge
// ---------------------------------------------------------------------------

@Composable
private fun DigestModeBadge(onExit: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("60s Digest", color = Color.White, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onExit, modifier = Modifier.size(18.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Exit digest", tint = Color.White)
        }
    }
}

// ---------------------------------------------------------------------------
// Error snackbar
// ---------------------------------------------------------------------------

@Composable
private fun ErrorSnackbar(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Snackbar(
        modifier = modifier.padding(16.dp),
        action   = { TextButton(onClick = onDismiss) { Text("Dismiss") } }
    ) {
        Text(message)
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else              "%d:%02d".format(m, s)
}
