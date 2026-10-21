package com.nekobot.app.ui.screens.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LivePipelineMode
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import com.nekobot.app.ui.components.resolveAvatarUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class LiveConversationPhase {
    Connecting,
    Listening,
    Transcribing,
    Thinking,
    PreparingAudio,
    Speaking,
    Error
}

internal data class LiveRealtimeTurnCallbacks(
    val onConnected: () -> Unit,
    val onUserTranscript: (String, Boolean) -> Unit,
    val onAssistantTranscript: (String, Boolean) -> Unit,
    val onAudioDelta: (ByteArray) -> Unit,
    val onCompleted: () -> Unit,
    val onError: (String) -> Unit
)

internal fun isLiveConversationSession(sessionMode: String?): Boolean =
    sessionMode.isNullOrBlank() ||
        sessionMode.equals("character", ignoreCase = true) ||
        sessionMode.equals("agent", ignoreCase = true)

internal fun liveMessageFingerprint(message: Message): String {
    val stableId = message.id?.takeIf {
        it.isNotBlank() &&
            it != ChatViewModel.STREAMING_ID &&
            !it.startsWith(ChatViewModel.STREAM_FALLBACK_PREFIX)
    }
    return stableId ?: "${message.timestamp.orEmpty()}|${message.displayContent.trim()}"
}

internal fun findLiveAssistantReply(
    messages: List<Message>,
    baseline: Set<String>
): Message? = messages.asReversed().firstOrNull { message ->
    !message.isUser &&
        !message.isThinkingCard &&
        message.id != ChatViewModel.STREAMING_ID &&
        message.displayContent.isNotBlank() &&
        liveMessageFingerprint(message) !in baseline
}

@Composable
internal fun LiveConversationDialog(
    sessionId: String,
    sessionName: String,
    portraitUrl: String?,
    messages: List<Message>,
    sending: Boolean,
    streamingSubtitle: String,
    ttsStates: Map<String, MessageTtsUiState>,
    onSendMessage: (String) -> Unit,
    onPrepareTts: (Message) -> Unit,
    onStartRealtimeTurn: (ByteArray, LiveRealtimeTurnCallbacks) -> Unit,
    onStopRealtimeTurn: () -> Unit,
    onStopGeneration: () -> Unit,
    onValidatePipeline: suspend (LivePipelineMode) -> String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var interfaceVisible by remember(sessionId) { mutableStateOf(false) }
    var portraitLanded by remember(sessionId) { mutableStateOf(false) }
    var dismissing by remember(sessionId) { mutableStateOf(false) }
    var phase by remember(sessionId) { mutableStateOf(LiveConversationPhase.Connecting) }
    var subtitle by remember(sessionId) { mutableStateOf("") }
    var subtitleFromUser by remember(sessionId) { mutableStateOf(false) }
    var errorMessage by remember(sessionId) { mutableStateOf<String?>(null) }
    // 频谱用 State 承载：每帧在绘制阶段读取，避免音频回调触发整棵界面重组。
    val spectrumState = remember(sessionId) { mutableStateOf(FloatArray(LIVE_SPECTRUM_BARS) { 0.08f }) }
    var assistantBaseline by remember(sessionId) { mutableStateOf<Set<String>>(emptySet()) }
    var awaitingAssistant by remember(sessionId) { mutableStateOf(false) }
    var pendingTtsContent by remember(sessionId) { mutableStateOf<String?>(null) }
    var pendingTtsMessageId by remember(sessionId) { mutableStateOf<String?>(null) }
    var autoStopSignal by remember(sessionId) { mutableIntStateOf(0) }
    var playbackCompleteSignal by remember(sessionId) { mutableIntStateOf(0) }
    var pipeline by remember(sessionId) {
        mutableStateOf(ServiceContainer.prefs.livePipelineMode)
    }
    var realtimeAudioStarted by remember(sessionId) { mutableStateOf(false) }

    val soundEffects = remember(sessionId) { LiveConversationSoundEffects() }

    val audioController = remember(sessionId) {
        LiveAudioController(
            context = context,
            scope = scope,
            onSpectrum = { spectrumState.value = it },
            onAutoStop = { autoStopSignal++ },
            onPlaybackComplete = { playbackCompleteSignal++ },
            onError = {
                errorMessage = it
                phase = LiveConversationPhase.Error
            }
        )
    }

    fun beginListening(targetPipeline: LivePipelineMode = pipeline) {
        if (sending || phase == LiveConversationPhase.Transcribing) return
        errorMessage = null
        pendingTtsContent = null
        pendingTtsMessageId = null
        subtitle = ""
        subtitleFromUser = true
        realtimeAudioStarted = false
        val started = when (targetPipeline) {
            LivePipelineMode.CLASSIC -> audioController.startRecording()
            LivePipelineMode.REALTIME -> audioController.startRealtimeRecording()
        }
        if (started) {
            phase = LiveConversationPhase.Listening
        }
    }

    fun finishRecordingAndSend() {
        if (phase != LiveConversationPhase.Listening) return
        if (pipeline == LivePipelineMode.REALTIME) {
            val pcm16 = audioController.finishRealtimeRecording()
            if (pcm16 == null) {
                errorMessage = context.getString(R.string.live_recording_failed)
                phase = LiveConversationPhase.Error
                return
            }
            subtitle = ""
            subtitleFromUser = true
            phase = LiveConversationPhase.Thinking
            onStartRealtimeTurn(
                pcm16,
                LiveRealtimeTurnCallbacks(
                    onConnected = {
                        if (phase != LiveConversationPhase.Speaking) {
                            phase = LiveConversationPhase.Thinking
                        }
                    },
                    onUserTranscript = { text, _ ->
                        if (!realtimeAudioStarted && text.isNotBlank()) {
                            subtitle = text
                            subtitleFromUser = true
                        }
                    },
                    onAssistantTranscript = { text, _ ->
                        if (text.isNotBlank()) {
                            subtitle = text
                            subtitleFromUser = false
                            if (!realtimeAudioStarted) phase = LiveConversationPhase.Thinking
                        }
                    },
                    onAudioDelta = { bytes ->
                        if (!realtimeAudioStarted) {
                            realtimeAudioStarted = audioController.startRealtimePlayback()
                            if (realtimeAudioStarted) phase = LiveConversationPhase.Speaking
                        }
                        if (realtimeAudioStarted) audioController.writeRealtimeAudio(bytes)
                    },
                    onCompleted = {
                        if (realtimeAudioStarted) {
                            audioController.finishRealtimePlayback()
                        } else {
                            scope.launch {
                                delay(240)
                                beginListening(LivePipelineMode.REALTIME)
                            }
                        }
                    },
                    onError = { message ->
                        audioController.stopPlayback()
                        errorMessage = message
                        phase = LiveConversationPhase.Error
                    }
                )
            )
            return
        }
        val audioFile = audioController.finishRecording()
        if (audioFile == null) {
            errorMessage = context.getString(R.string.live_recording_failed)
            phase = LiveConversationPhase.Error
            return
        }
        phase = LiveConversationPhase.Transcribing
        scope.launch {
            try {
                val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) { audioFile.readBytes() }
                when (val result = ServiceContainer.unified.transcribeAudio(bytes, audioFile.name, null)) {
                    is Resource.Success -> {
                        val recognized = result.data?.text.orEmpty().trim()
                        if (recognized.isBlank()) {
                            errorMessage = context.getString(R.string.live_no_speech)
                            phase = LiveConversationPhase.Error
                        } else {
                            subtitle = recognized
                            subtitleFromUser = true
                            assistantBaseline = messages
                                .filter { !it.isUser && !it.isThinkingCard }
                                .mapTo(mutableSetOf(), ::liveMessageFingerprint)
                            awaitingAssistant = true
                            phase = LiveConversationPhase.Thinking
                            onSendMessage(recognized)
                        }
                    }
                    is Resource.Error -> {
                        errorMessage = result.message ?: context.getString(R.string.live_asr_failed)
                        phase = LiveConversationPhase.Error
                    }
                    is Resource.Loading -> Unit
                }
            } catch (error: Exception) {
                errorMessage = error.message ?: context.getString(R.string.live_asr_failed)
                phase = LiveConversationPhase.Error
            } finally {
                audioFile.delete()
            }
        }
    }

    DisposableEffect(audioController) {
        onDispose {
            onStopRealtimeTurn()
            audioController.release()
            soundEffects.release()
        }
    }

    LaunchedEffect(Unit) {
        soundEffects.playEntrance()
        portraitLanded = true
        delay(LIVE_PORTRAIT_TRANSITION_MS)
        interfaceVisible = true
        delay(LIVE_INTERFACE_TRANSITION_MS)
        if (!dismissing) beginListening()
    }

    LaunchedEffect(autoStopSignal) {
        if (autoStopSignal > 0 && phase == LiveConversationPhase.Listening) {
            finishRecordingAndSend()
        }
    }

    LaunchedEffect(playbackCompleteSignal) {
        if (playbackCompleteSignal > 0 && phase == LiveConversationPhase.Speaking) {
            delay(320)
            beginListening()
        }
    }

    LaunchedEffect(awaitingAssistant, sending, streamingSubtitle) {
        if (awaitingAssistant && sending) {
            phase = LiveConversationPhase.Thinking
            if (streamingSubtitle.isNotBlank()) {
                subtitle = liveSubtitleWindow(streamingSubtitle)
                subtitleFromUser = false
            }
        }
    }

    LaunchedEffect(messages, sending, awaitingAssistant) {
        if (awaitingAssistant && !sending) {
            val reply = findLiveAssistantReply(messages, assistantBaseline)
            if (reply != null) {
                awaitingAssistant = false
                subtitle = liveSubtitleWindow(reply.displayContent)
                subtitleFromUser = false
                pendingTtsContent = reply.displayContent.trim()
                pendingTtsMessageId = reply.id
                phase = LiveConversationPhase.PreparingAudio
                onPrepareTts(reply)
            }
        }
    }

    val pendingAudioMessage = remember(messages, pendingTtsContent) {
        val expected = pendingTtsContent
        if (expected.isNullOrBlank()) null else messages.asReversed().firstOrNull {
            !it.isUser && it.displayContent.trim() == expected && !it.audioUrl.isNullOrBlank()
        }
    }
    LaunchedEffect(pendingAudioMessage?.audioUrl) {
        val rawUrl = pendingAudioMessage?.audioUrl ?: return@LaunchedEffect
        val playableUrl = resolveAvatarUrl(rawUrl) ?: rawUrl
        pendingTtsMessageId = pendingAudioMessage.id
        phase = LiveConversationPhase.Speaking
        if (!audioController.play(playableUrl, ServiceContainer.prefs.token)) {
            phase = LiveConversationPhase.Error
        }
    }

    val pendingTtsError = remember(ttsStates, messages, pendingTtsMessageId, pendingTtsContent) {
        val direct = pendingTtsMessageId?.let(ttsStates::get)
        if (direct?.status == MessageTtsStatus.Error) return@remember direct.error
        val expected = pendingTtsContent
        messages.asReversed()
            .firstOrNull { !it.isUser && it.displayContent.trim() == expected }
            ?.id
            ?.let(ttsStates::get)
            ?.takeIf { it.status == MessageTtsStatus.Error }
            ?.error
    }
    LaunchedEffect(pendingTtsError) {
        if (!pendingTtsError.isNullOrBlank() && phase == LiveConversationPhase.PreparingAudio) {
            errorMessage = pendingTtsError
            phase = LiveConversationPhase.Error
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        LiveConversationContent(
            sessionName = sessionName,
            portraitUrl = portraitUrl,
            phase = phase,
            subtitle = subtitle,
            subtitleFromUser = subtitleFromUser,
            errorMessage = errorMessage,
            spectrumState = spectrumState,
            pipeline = pipeline,
            interfaceVisible = interfaceVisible,
            portraitLanded = portraitLanded,
            pipelineEnabled = interfaceVisible && (
                phase == LiveConversationPhase.Listening || phase == LiveConversationPhase.Error
            ),
            onPipelineChange = { selected ->
                if (selected != pipeline) {
                    scope.launch {
                        val validationError = onValidatePipeline(selected)
                        if (validationError != null) {
                            errorMessage = validationError
                            phase = LiveConversationPhase.Error
                            return@launch
                        }
                        onStopRealtimeTurn()
                        audioController.release()
                        pipeline = selected
                        ServiceContainer.prefs.livePipelineMode = selected
                        phase = LiveConversationPhase.Connecting
                        delay(180)
                        beginListening(selected)
                    }
                }
            },
            onPrimaryAction = {
                when (phase) {
                    LiveConversationPhase.Listening -> {
                        soundEffects.playFinishSpeaking()
                        finishRecordingAndSend()
                    }
                    LiveConversationPhase.Speaking -> {
                        onStopRealtimeTurn()
                        audioController.stopPlayback()
                        beginListening()
                    }
                    LiveConversationPhase.Thinking -> {
                        if (pipeline == LivePipelineMode.REALTIME) {
                            onStopRealtimeTurn()
                            audioController.stopPlayback()
                        } else {
                            onStopGeneration()
                        }
                        awaitingAssistant = false
                        errorMessage = context.getString(R.string.live_response_stopped)
                        phase = LiveConversationPhase.Error
                    }
                    LiveConversationPhase.Error -> beginListening()
                    else -> Unit
                }
            },
            onDismiss = {
                if (dismissing) return@LiveConversationContent
                dismissing = true
                soundEffects.playHangUp()
                onStopRealtimeTurn()
                audioController.release()
                scope.launch {
                    interfaceVisible = false
                    delay(LIVE_INTERFACE_TRANSITION_MS)
                    portraitLanded = false
                    delay(LIVE_PORTRAIT_TRANSITION_MS)
                    onDismiss()
                }
            }
        )
    }
}

/* ==================== Live 实时对话界面 ==================== */

/**
 * 实时对话界面。
 *
 * 视觉层次（由底向上）：氛围光晕 → 呼吸光环与环形频谱 → 头像 → 状态 → 字幕 → 操作。
 * 装饰动画统一在 draw / graphicsLayer 阶段读取动画值，避免逐帧重组整棵界面。
 */
@Composable
private fun LiveConversationContent(
    sessionName: String,
    portraitUrl: String?,
    phase: LiveConversationPhase,
    subtitle: String,
    subtitleFromUser: Boolean,
    errorMessage: String?,
    spectrumState: State<FloatArray>,
    pipeline: LivePipelineMode,
    interfaceVisible: Boolean,
    portraitLanded: Boolean,
    pipelineEnabled: Boolean,
    onPipelineChange: (LivePipelineMode) -> Unit,
    onPrimaryAction: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val background = scheme.background
    val primary = scheme.primary
    val motionEnabled = appearanceMotionAllowed()

    // 呼吸（缓慢起伏）与漂移（冷光游走）分离，避免机械式的同频动画。
    val breath: State<Float>
    val drift: State<Float>
    val spin: State<Float>
    if (motionEnabled) {
        val transition = rememberInfiniteTransition(label = "live_breath")
        breath = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(LIVE_BREATH_PERIOD_MS.toInt(), easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "live_breath_value"
        )
        drift = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(LIVE_DRIFT_PERIOD_MS.toInt(), easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "live_drift_value"
        )
        spin = transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(LIVE_SPIN_PERIOD_MS.toInt(), easing = LinearEasing)
            ),
            label = "live_spin_value"
        )
    } else {
        breath = remember { mutableFloatStateOf(0.4f) }
        drift = remember { mutableFloatStateOf(0.2f) }
        spin = remember { mutableFloatStateOf(0f) }
    }

    val flow = remember(spectrumState) { LiveSpectrumFlow(LIVE_SPECTRUM_BARS) }
    LaunchedEffect(spectrumState, flow) {
        while (isActive) {
            withFrameNanos { }
            flow.update(spectrumState.value)
        }
    }

    val interfaceAlpha by animateFloatAsState(
        targetValue = if (interfaceVisible) 1f else 0f,
        animationSpec = tween(LIVE_INTERFACE_TRANSITION_MS.toInt(), easing = FastOutSlowInEasing),
        label = "live_interface_alpha"
    )
    val interfaceScale by animateFloatAsState(
        targetValue = if (interfaceVisible) 1f else 0.94f,
        animationSpec = tween(LIVE_INTERFACE_TRANSITION_MS.toInt(), easing = FastOutSlowInEasing),
        label = "live_interface_scale"
    )
    val portraitAppear = animateFloatAsState(
        targetValue = if (portraitLanded) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        label = "live_portrait_appear"
    )
    val interfaceModifier = Modifier.graphicsLayer {
        alpha = interfaceAlpha
        scaleX = interfaceScale
        scaleY = interfaceScale
    }
    val intensity = when (phase) {
        LiveConversationPhase.Listening -> 0.9f
        LiveConversationPhase.Speaking -> 1f
        LiveConversationPhase.Thinking -> 0.72f
        LiveConversationPhase.Transcribing -> 0.6f
        LiveConversationPhase.PreparingAudio -> 0.55f
        LiveConversationPhase.Connecting -> 0.5f
        LiveConversationPhase.Error -> 0.34f
    }
    val status = when (phase) {
        LiveConversationPhase.Connecting -> stringResource(R.string.live_connecting)
        LiveConversationPhase.Listening -> stringResource(R.string.live_listening)
        LiveConversationPhase.Transcribing -> stringResource(R.string.live_transcribing)
        LiveConversationPhase.Thinking -> stringResource(R.string.live_thinking)
        LiveConversationPhase.PreparingAudio -> stringResource(R.string.live_preparing_audio)
        LiveConversationPhase.Speaking -> stringResource(R.string.live_speaking)
        LiveConversationPhase.Error -> stringResource(R.string.live_tap_to_continue)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        lerp(background, primary, 0.16f),
                        background,
                        lerp(background, scheme.surface, 0.9f)
                    )
                )
            )
    ) {
        LiveAmbientBackdrop(
            flow = flow,
            breath = breath,
            drift = drift,
            intensity = intensity,
            modifier = Modifier.fillMaxSize()
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 10.dp)
        ) {
            // 尺寸跟随可用空间：高度富余时给头像更多呼吸空间，空间紧张时自动收紧。
            val availableWidth = maxWidth.takeIf { it.value.isFinite() && it.value > 0f } ?: 360.dp
            val availableHeight = maxHeight.takeIf { it.value.isFinite() && it.value > 0f } ?: 640.dp
            val subtitleHeight = (availableHeight * 0.25f).coerceIn(96.dp, 172.dp)
            val portraitBudget = (availableHeight - subtitleHeight - LIVE_FIXED_CONTENT_HEIGHT)
                .coerceAtLeast(150.dp)
            val portraitSize = minOf(availableWidth * 0.8f, portraitBudget).coerceIn(150.dp, 300.dp)
            // 横屏等极矮窗口改为可滚动排布，保证挂断按钮始终可达。
            val compact = availableHeight < 540.dp
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (compact) {
                            Modifier.verticalScroll(scrollState)
                        } else {
                            Modifier
                        }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LiveHeader(
                    sessionName = sessionName,
                    pipeline = pipeline,
                    pipelineEnabled = pipelineEnabled,
                    flow = flow,
                    breath = breath,
                    onPipelineChange = onPipelineChange,
                    modifier = interfaceModifier
                )
                if (compact) {
                    Spacer(Modifier.height(28.dp))
                } else {
                    Spacer(Modifier.weight(0.5f))
                }
                LivePortraitAura(
                    portraitUrl = portraitUrl,
                    flow = flow,
                    breath = breath,
                    drift = drift,
                    spin = spin,
                    appear = portraitAppear,
                    intensity = intensity,
                    boxSize = portraitSize
                )
                Spacer(Modifier.height(8.dp))
                LiveStatusRow(
                    status = status,
                    phase = phase,
                    breath = breath,
                    spin = spin,
                    modifier = interfaceModifier
                )
                if (compact) {
                    Spacer(Modifier.height(24.dp))
                } else {
                    Spacer(Modifier.weight(0.32f))
                }
                LiveSubtitlePanel(
                    sessionName = sessionName,
                    subtitle = subtitle,
                    subtitleFromUser = subtitleFromUser,
                    errorMessage = errorMessage,
                    phase = phase,
                    flow = flow,
                    breath = breath,
                    panelHeight = subtitleHeight,
                    modifier = interfaceModifier
                )
                Spacer(Modifier.height(16.dp))
                LiveControls(
                    phase = phase,
                    interfaceVisible = interfaceVisible,
                    breath = breath,
                    onPrimaryAction = onPrimaryAction,
                    onDismiss = onDismiss,
                    modifier = interfaceModifier
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** 顶部标题与流程选择。 */
@Composable
private fun LiveHeader(
    sessionName: String,
    pipeline: LivePipelineMode,
    pipelineEnabled: Boolean,
    flow: LiveSpectrumFlow,
    breath: State<Float>,
    onPipelineChange: (LivePipelineMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    var pipelineMenuExpanded by remember { mutableStateOf(false) }
    val isRealtime = pipeline == LivePipelineMode.REALTIME
    val chipTint by animateColorAsState(
        targetValue = if (isRealtime) {
            lerp(scheme.surface, scheme.primary, 0.18f)
        } else {
            lerp(scheme.surface, scheme.background, 0.25f)
        },
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "live_chip_tint"
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 会呼吸的在线指示点
        Box(
            modifier = Modifier
                .size(14.dp)
                .drawBehind {
                    val wave = 0.5f + 0.5f * sin(breath.value * TWO_PI)
                    drawCircle(
                        color = scheme.primary.copy(alpha = (0.10f + 0.12f * wave) * (0.7f + 0.3f * flow.energy)),
                        radius = size.minDimension * 0.5f
                    )
                    drawCircle(
                        color = scheme.primary.copy(alpha = 0.5f + 0.35f * wave),
                        radius = size.minDimension * 0.15f
                    )
                }
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.live_title),
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 1.6.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = sessionName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(chipTint.copy(alpha = 0.55f))
                    .clickable(enabled = pipelineEnabled) { pipelineMenuExpanded = true }
                    .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(
                            color = if (isRealtime) scheme.primary else scheme.secondary,
                            shape = CircleShape
                        )
                )
                Spacer(Modifier.width(7.dp))
                AnimatedContent(
                    targetState = isRealtime,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                    label = "live_pipeline_label"
                ) { realtime ->
                    Text(
                        text = if (realtime) {
                            stringResource(R.string.live_pipeline_realtime_short)
                        } else {
                            stringResource(R.string.live_pipeline_classic_short)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (pipelineEnabled) {
                            scheme.onSurface
                        } else {
                            scheme.onSurfaceVariant.copy(alpha = 0.55f)
                        },
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.live_pipeline_label),
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.9f),
                    modifier = Modifier.size(16.dp)
                )
            }
            DropdownMenu(
                expanded = pipelineMenuExpanded,
                onDismissRequest = { pipelineMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.live_pipeline_classic)) },
                    onClick = {
                        pipelineMenuExpanded = false
                        onPipelineChange(LivePipelineMode.CLASSIC)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.live_pipeline_realtime)) },
                    onClick = {
                        pipelineMenuExpanded = false
                        onPipelineChange(LivePipelineMode.REALTIME)
                    }
                )
            }
        }
    }
}

/** 全屏氛围光：极慢漂移的两团柔光 + 边缘收束，让界面与背景连成一体。 */
@Composable
private fun LiveAmbientBackdrop(
    flow: LiveSpectrumFlow,
    breath: State<Float>,
    drift: State<Float>,
    intensity: Float,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier = modifier) {
        val energy = flow.energy
        val wave = breath.value
        val angle = drift.value * TWO_PI
        val focus = Offset(
            x = size.width * (0.5f + 0.05f * sin(angle)),
            y = size.height * (0.34f + 0.03f * cos(angle))
        )
        val glowRadius = size.minDimension * (0.78f + 0.06f * wave + 0.1f * energy)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    scheme.primary.copy(alpha = (0.13f + 0.07f * energy) * intensity),
                    scheme.primary.copy(alpha = 0.03f * intensity),
                    Color.Transparent
                ),
                center = focus,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = focus
        )
        val coolFocus = Offset(
            x = size.width * (0.18f + 0.05f * cos(angle)),
            y = size.height * (0.76f + 0.04f * sin(angle))
        )
        val coolRadius = size.minDimension * 0.62f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    scheme.tertiary.copy(alpha = 0.055f * intensity),
                    Color.Transparent
                ),
                center = coolFocus,
                radius = coolRadius
            ),
            radius = coolRadius,
            center = coolFocus
        )
        // 边缘收束：光晕在屏幕边界自然消失，不会出现硬边。
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, scheme.background.copy(alpha = 0.5f)),
                center = center,
                radius = size.maxDimension * 0.72f
            )
        )
    }
}

/** 头像 + 呼吸光环 + 环形频谱。 */
@Composable
private fun LivePortraitAura(
    portraitUrl: String?,
    flow: LiveSpectrumFlow,
    breath: State<Float>,
    drift: State<Float>,
    spin: State<Float>,
    appear: State<Float>,
    intensity: Float,
    boxSize: Dp,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val resolved = remember(portraitUrl) { resolveAvatarUrl(portraitUrl) }
    val portraitDiameter = boxSize * 0.615f

    Box(
        modifier = modifier.size(boxSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawLiveAura(
                bars = flow.bars,
                energy = flow.energy,
                breath = breath.value,
                drift = drift.value,
                spin = spin.value,
                appear = appear.value,
                intensity = intensity,
                primary = scheme.primary,
                secondary = scheme.secondary,
                tertiary = scheme.tertiary
            )
        }
        Box(
            modifier = Modifier
                .size(portraitDiameter)
                .graphicsLayer {
                    val landed = appear.value
                    val breathe = 1f + 0.018f * breath.value + 0.05f * flow.energy
                    val scale = (0.84f + 0.16f * landed) * breathe
                    scaleX = scale
                    scaleY = scale
                    alpha = landed.coerceIn(0f, 1f)
                    translationY = (1f - landed) * -26.dp.toPx()
                    // 圆角裁剪与图层同一层完成：离场淡出时不会露出方形图层边界。
                    clip = true
                    shape = CircleShape
                }
                .background(
                    Brush.verticalGradient(
                        listOf(
                            lerp(scheme.surfaceVariant, scheme.primary, 0.12f),
                            scheme.surfaceVariant
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (resolved.isNullOrBlank()) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(portraitDiameter * 0.52f),
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.62f)
                )
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(resolved)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 边缘暗角 + 缓慢旋转的柔光描边：头像自然融入背景，而非贴上去的圆形。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { rotationZ = spin.value * 0.5f }
                    .drawBehind {
                        val radius = size.minDimension / 2f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.5f to Color.Transparent,
                                    0.84f to scheme.background.copy(alpha = 0.16f),
                                    1f to scheme.background.copy(alpha = 0.44f)
                                ),
                                center = center,
                                radius = radius
                            ),
                            radius = radius,
                            center = center
                        )
                        drawCircle(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    scheme.primary.copy(alpha = 0.36f),
                                    Color.Transparent,
                                    scheme.secondary.copy(alpha = 0.24f),
                                    Color.Transparent,
                                    scheme.primary.copy(alpha = 0.36f)
                                )
                            ),
                            radius = radius - 0.6.dp.toPx(),
                            center = center,
                            style = Stroke(width = 1.1.dp.toPx())
                        )
                    }
            )
        }
    }
}

/** 绘制呼吸光晕、同心环、环形频谱与游走高光。 */
private fun DrawScope.drawLiveAura(
    bars: FloatArray,
    energy: Float,
    breath: Float,
    drift: Float,
    spin: Float,
    appear: Float,
    intensity: Float,
    primary: Color,
    secondary: Color,
    tertiary: Color
) {
    val alive = appear.coerceIn(0f, 1f)
    if (alive <= 0.002f) return
    val base = size.minDimension / 2f
    val centerOffset = center
    val glowAlpha = (0.15f + 0.10f * energy) * intensity * alive

    // 主呼吸光晕
    val haloRadius = base * (0.78f + 0.06f * breath + 0.1f * energy)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                primary.copy(alpha = glowAlpha),
                primary.copy(alpha = glowAlpha * 0.4f),
                Color.Transparent
            ),
            center = centerOffset,
            radius = haloRadius
        ),
        radius = haloRadius,
        center = centerOffset
    )

    // 游走的冷色补光，让呼吸有方向感
    val driftAngle = drift * TWO_PI
    val driftCenter = centerOffset + Offset(
        x = cos(driftAngle) * base * 0.18f,
        y = sin(driftAngle) * base * 0.14f
    )
    val coolRadius = base * (0.46f + 0.10f * energy + 0.04f * breath)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                secondary.copy(alpha = 0.10f * intensity * alive),
                Color.Transparent
            ),
            center = driftCenter,
            radius = coolRadius
        ),
        radius = coolRadius,
        center = driftCenter
    )

    // 同心呼吸环
    repeat(2) { index ->
        val wave = 0.5f + 0.5f * sin((breath + index * 0.26f) * TWO_PI)
        val radius = base * (0.70f + 0.10f * index) + base * 0.02f * wave * intensity
        drawCircle(
            color = primary.copy(alpha = (0.05f + 0.07f * wave) * intensity * alive),
            radius = radius,
            center = centerOffset,
            style = Stroke(width = 1.dp.toPx())
        )
    }

    // 环形频谱：贴着头像边缘生长。
    // 静默时收束成一条均匀细环（整圈只有一个极低频起伏），避免安静时出现锯齿状毛刺。
    val barCount = bars.size
    if (barCount > 0) {
        val inner = base * 0.615f
        val maxLength = base * 0.285f
        val barWidth = 2.4.dp.toPx()
        val stepAngle = 360f / barCount
        val gateRaw = ((energy - LIVE_SPECTRUM_GATE_FLOOR) / LIVE_SPECTRUM_GATE_RANGE)
            .coerceIn(0f, 1f)
        val gate = gateRaw * gateRaw * (3f - 2f * gateRaw)
        rotate(degrees = spin, pivot = centerOffset) {
            for (index in 0 until barCount) {
                val sample = bars[index].coerceIn(0f, 1f)
                val previous = bars[(index - 1 + barCount) % barCount].coerceIn(0f, 1f)
                val next = bars[(index + 1) % barCount].coerceIn(0f, 1f)
                val spoken = (previous + sample * 2f + next) / 4f
                // 静默基准：低频缓慢起伏，环上不会出现高频高低跳变
                val calm = 0.5f + 0.5f * sin(index * 0.09f + spin * 0.02f)
                val rest = 0.030f + 0.010f * calm
                val level = rest + (spoken - rest) * gate
                val length = maxLength * level * (0.55f + 0.45f * alive)
                if (length <= 0.3f) continue
                val angle = index * stepAngle * (PI.toFloat() / 180f)
                val cosAngle = cos(angle)
                val sinAngle = sin(angle)
                drawLine(
                    color = lerp(primary, tertiary, (index.toFloat() / barCount) * 0.7f).copy(
                        alpha = (0.16f + 0.5f * level) * alive
                    ),
                    start = Offset(
                        x = centerOffset.x + cosAngle * inner,
                        y = centerOffset.y + sinAngle * inner
                    ),
                    end = Offset(
                        x = centerOffset.x + cosAngle * (inner + length),
                        y = centerOffset.y + sinAngle * (inner + length)
                    ),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }

    // 缓慢游走的高光弧
    val orbitRadius = base * 0.90f
    rotate(degrees = -spin * 0.7f, pivot = centerOffset) {
        drawArc(
            color = secondary.copy(alpha = 0.18f * intensity * alive),
            startAngle = -116f,
            sweepAngle = 46f,
            useCenter = false,
            topLeft = Offset(centerOffset.x - orbitRadius, centerOffset.y - orbitRadius),
            size = Size(orbitRadius * 2f, orbitRadius * 2f),
            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/** 状态行：呼吸圆点 / 加载弧 + 状态文案。 */
@Composable
private fun LiveStatusRow(
    status: String,
    phase: LiveConversationPhase,
    breath: State<Float>,
    spin: State<Float>,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val isError = phase == LiveConversationPhase.Error
    val accent = if (isError) scheme.error else scheme.primary
    val busy = phase == LiveConversationPhase.Connecting ||
        phase == LiveConversationPhase.Transcribing ||
        phase == LiveConversationPhase.PreparingAudio

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(18.dp)) {
            if (busy) {
                rotate(degrees = spin.value * 2.4f) {
                    drawArc(
                        color = accent.copy(alpha = 0.85f),
                        startAngle = 0f,
                        sweepAngle = 260f,
                        useCenter = false,
                        topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                        size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            } else {
                val wave = breath.value
                drawCircle(
                    color = accent.copy(alpha = 0.14f + 0.16f * wave),
                    radius = size.minDimension * 0.5f
                )
                drawCircle(
                    color = accent.copy(alpha = 0.85f),
                    radius = size.minDimension * (0.17f + 0.03f * wave)
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically { it / 3 }) togetherWith
                    (fadeOut(tween(150)) + slideOutVertically { -it / 3 })
            },
            label = "live_status"
        ) { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = if (isError) scheme.error else scheme.onSurface.copy(alpha = 0.94f)
            )
        }
    }
}

/** 字幕面板：上下边缘羽化，与背景自然衔接。 */
@Composable
private fun LiveSubtitlePanel(
    sessionName: String,
    subtitle: String,
    subtitleFromUser: Boolean,
    errorMessage: String?,
    phase: LiveConversationPhase,
    flow: LiveSpectrumFlow,
    breath: State<Float>,
    panelHeight: Dp,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    val tertiary = scheme.tertiary
    val panelTint = lerp(scheme.background, scheme.surface, 0.92f)
    val scrollState = rememberScrollState()
    val canScrollUp = scrollState.canScrollBackward
    val canScrollDown = scrollState.canScrollForward

    LaunchedEffect(subtitleFromUser) { scrollState.scrollTo(0) }
    LaunchedEffect(subtitle) {
        if (!subtitleFromUser) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(panelHeight)
            .clip(RoundedCornerShape(28.dp))
            .drawBehind {
                val energy = flow.energy
                val alphaBase = (0.40f + 0.10f * energy).coerceAtMost(0.62f)
                val corner = CornerRadius(28.dp.toPx())
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to panelTint.copy(alpha = 0f),
                        0.16f to panelTint.copy(alpha = alphaBase * 0.9f),
                        0.55f to panelTint.copy(alpha = alphaBase),
                        0.88f to panelTint.copy(alpha = alphaBase * 0.7f),
                        1f to panelTint.copy(alpha = 0f)
                    ),
                    cornerRadius = corner
                )
                // 顶部一缕随呼吸变亮的高光
                val sheen = 0.08f + 0.07f * breath.value + 0.06f * energy
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, primary.copy(alpha = sheen), Color.Transparent)
                    ),
                    topLeft = Offset(size.width * 0.1f, 0f),
                    size = Size(size.width * 0.8f, 1.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx())
                )
            }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            color = if (subtitleFromUser) {
                                tertiary.copy(alpha = 0.9f)
                            } else {
                                primary.copy(alpha = 0.95f)
                            },
                            shape = CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                AnimatedContent(
                    targetState = subtitleFromUser,
                    transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) },
                    modifier = Modifier.widthIn(max = 180.dp),
                    label = "live_speaker"
                ) { fromUser ->
                    Text(
                        text = if (fromUser) stringResource(R.string.live_you) else sessionName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (fromUser) {
                            tertiary.copy(alpha = 0.9f)
                        } else {
                            primary.copy(alpha = 0.9f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.weight(1f))
                if (phase == LiveConversationPhase.Speaking || phase == LiveConversationPhase.Thinking) {
                    LiveMiniWave(
                        flow = flow,
                        breath = breath,
                        color = primary.copy(alpha = 0.72f),
                        modifier = Modifier.size(width = 26.dp, height = 14.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .fadingScrollEdges(
                        fadingTop = canScrollUp,
                        fadingBottom = canScrollDown
                    )
            ) {
                Text(
                    text = errorMessage ?: subtitle.ifBlank {
                        if (phase == LiveConversationPhase.Listening) {
                            stringResource(R.string.live_listening_hint)
                        } else {
                            stringResource(R.string.live_waiting_subtitle)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp,
                    letterSpacing = 0.2.sp,
                    color = if (errorMessage != null) scheme.error else scheme.onSurface.copy(alpha = 0.94f)
                )
            }
        }
    }
}

/** 说话时的微型律动条。 */
@Composable
private fun LiveMiniWave(
    flow: LiveSpectrumFlow,
    breath: State<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val bars = flow.bars
        if (bars.isEmpty()) return@Canvas
        val count = 4
        val step = size.width / count
        val barWidth = (step * 0.44f).coerceAtLeast(1.5.dp.toPx())
        val stride = (bars.size / 8).coerceAtLeast(1)
        for (index in 0 until count) {
            val value = bars.getOrElse(index * stride) { 0f }.coerceIn(0f, 1f)
            val wave = 0.5f + 0.5f * sin((breath.value + index * 0.16f) * TWO_PI)
            val heightRatio = (0.2f + 0.8f * (0.45f * wave + 0.55f * value)).coerceIn(0.12f, 1f)
            val barHeight = size.height * heightRatio
            drawRoundRect(
                color = color.copy(alpha = 0.45f + 0.45f * value),
                topLeft = Offset(step * index + (step - barWidth) / 2f, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

/** 底部操作：麦克风 / 挂断。 */
@Composable
private fun LiveControls(
    phase: LiveConversationPhase,
    interfaceVisible: Boolean,
    breath: State<Float>,
    onPrimaryAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val busy = phase == LiveConversationPhase.Connecting ||
        phase == LiveConversationPhase.Transcribing ||
        phase == LiveConversationPhase.PreparingAudio
    val active = phase == LiveConversationPhase.Listening || phase == LiveConversationPhase.Speaking
    val primaryBackground = Brush.verticalGradient(
        listOf(
            lerp(scheme.surface, scheme.primary, 0.22f).copy(alpha = 0.92f),
            lerp(scheme.surface, scheme.background, 0.35f).copy(alpha = 0.94f)
        )
    )
    val hangUpBackground = Brush.verticalGradient(
        listOf(Color(0xFFF4707F), Color(0xFFE0455B))
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiveActionButton(
            size = 66.dp,
            enabled = interfaceVisible && !busy,
            emphasized = active,
            glow = scheme.primary,
            breath = breath,
            background = primaryBackground,
            onClick = onPrimaryAction
        ) {
            Icon(
                imageVector = if (
                    phase == LiveConversationPhase.Listening ||
                    phase == LiveConversationPhase.Thinking
                ) {
                    Icons.Filled.Stop
                } else {
                    Icons.Filled.Mic
                },
                contentDescription = stringResource(R.string.live_primary_action),
                tint = scheme.onSurface,
                modifier = Modifier.size(26.dp)
            )
        }
        LiveActionButton(
            size = 66.dp,
            enabled = interfaceVisible,
            emphasized = false,
            glow = Color(0xFFE84A5F),
            breath = breath,
            background = hangUpBackground,
            onClick = onDismiss
        ) {
            Icon(
                Icons.Filled.CallEnd,
                contentDescription = stringResource(R.string.live_hang_up),
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** 玻璃质操作按钮：按下轻微回缩，强调态带呼吸光晕。 */
@Composable
private fun LiveActionButton(
    size: Dp,
    enabled: Boolean,
    emphasized: Boolean,
    glow: Color,
    breath: State<Float>,
    background: Brush,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "live_button_press"
    )
    Box(
        modifier = Modifier.size(size * 1.34f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (!emphasized) return@Canvas
            val wave = breath.value
            // 光晕落在按钮外缘（半径约 0.40×外层尺寸），形成一圈随呼吸涨落的柔光。
            val haloRadius = this.size.minDimension * (0.40f + 0.03f * wave)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow.copy(alpha = 0.20f + 0.10f * wave), Color.Transparent),
                    radius = haloRadius
                ),
                radius = haloRadius
            )
            drawCircle(
                color = glow.copy(alpha = 0.08f + 0.12f * wave),
                radius = this.size.minDimension * (0.42f + 0.015f * wave),
                style = Stroke(width = 1.dp.toPx())
            )
        }
        IconButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                    alpha = if (enabled) 1f else 0.42f
                }
                .clip(CircleShape)
                .background(background)
                .drawBehind {
                    drawCircle(
                        color = glow.copy(alpha = 0.18f),
                        radius = this.size.minDimension / 2f - 0.5.dp.toPx(),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
        ) {
            content()
        }
    }
}

/** 字幕滚动到边界时羽化，避免文字被硬生生切断。 */
private fun Modifier.fadingScrollEdges(
    fadingTop: Boolean,
    fadingBottom: Boolean
): Modifier {
    if (!fadingTop && !fadingBottom) return this
    val fadeFraction = 0.16f
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val stops = buildList {
                add(0f to Color.Black.copy(alpha = if (fadingTop) 0f else 1f))
                if (fadingTop) add(fadeFraction to Color.Black)
                if (fadingBottom) add(1f - fadeFraction to Color.Black)
                add(1f to Color.Black.copy(alpha = if (fadingBottom) 0f else 1f))
            }.toTypedArray()
            drawRect(
                brush = Brush.verticalGradient(colorStops = stops),
                blendMode = BlendMode.DstIn
            )
        }
}

/** 频谱平滑器：把音频回调的瞬时值缓动成连续波形，静默时停止触发重绘。 */
private class LiveSpectrumFlow(private val barCount: Int) {
    private val scratch = FloatArray(barCount)

    var bars by mutableStateOf(FloatArray(barCount))
        private set
    var energy by mutableFloatStateOf(0.08f)
        private set

    fun update(target: FloatArray) {
        var peak = 0f
        var changed = false
        for (index in 0 until barCount) {
            val value = target.getOrElse(index) { 0f }.coerceIn(0f, 1f)
            val next = scratch[index] + (value - scratch[index]) * LIVE_SPECTRUM_SMOOTHING
            if (abs(next - scratch[index]) > 0.0004f) changed = true
            scratch[index] = next
            if (value > peak) peak = value
        }
        val nextEnergy = energy + (peak - energy) * if (peak > energy) 0.34f else 0.12f
        if (abs(nextEnergy - energy) > 0.0004f) changed = true
        if (!changed) return
        energy = nextEnergy
        bars = scratch.copyOf()
    }
}

private class LiveConversationSoundEffects {
    private val lock = Any()
    private var activeTrack: AudioTrack? = null
    private var released = false

    fun playEntrance() {
        play(
            durationMs = 390,
            notes = listOf(
                LiveSoundNote(523.25, 0, 220, 0.18),
                LiveSoundNote(659.25, 88, 225, 0.16),
                LiveSoundNote(783.99, 172, 210, 0.14)
            )
        )
    }

    fun playFinishSpeaking() {
        play(
            durationMs = 235,
            notes = listOf(
                LiveSoundNote(587.33, 0, 155, 0.19),
                LiveSoundNote(739.99, 82, 140, 0.17)
            )
        )
    }

    fun playHangUp() {
        play(
            durationMs = 255,
            notes = listOf(
                LiveSoundNote(493.88, 0, 155, 0.17),
                LiveSoundNote(392.00, 82, 155, 0.15)
            )
        )
    }

    fun release() {
        synchronized(lock) {
            released = true
            activeTrack?.let(::stopAndRelease)
            activeTrack = null
        }
    }

    private fun play(durationMs: Int, notes: List<LiveSoundNote>) {
        val samples = createSamples(durationMs, notes)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(LIVE_SOUND_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return

        synchronized(lock) {
            if (released) {
                stopAndRelease(track)
                return
            }
            activeTrack?.let(::stopAndRelease)
            activeTrack = track
        }

        Thread({
            try {
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                track.play()
                Thread.sleep(durationMs.toLong() + LIVE_SOUND_RELEASE_PADDING_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // 新音效触发时可能会提前释放旧音轨。
            } finally {
                synchronized(lock) {
                    if (activeTrack === track) {
                        stopAndRelease(track)
                        activeTrack = null
                    }
                }
            }
        }, "live-sound-effect").apply {
            isDaemon = true
            start()
        }
    }

    private fun createSamples(durationMs: Int, notes: List<LiveSoundNote>): ShortArray {
        val sampleCount = durationMs * LIVE_SOUND_SAMPLE_RATE / 1_000
        return ShortArray(sampleCount) { index ->
            val timeSeconds = index.toDouble() / LIVE_SOUND_SAMPLE_RATE
            var mix = 0.0
            notes.forEach { note ->
                val elapsedSeconds = timeSeconds - note.startMs / 1_000.0
                val noteDurationSeconds = note.durationMs / 1_000.0
                if (elapsedSeconds in 0.0..noteDurationSeconds) {
                    val attack = (elapsedSeconds / LIVE_SOUND_ATTACK_SECONDS).coerceAtMost(1.0)
                    val release = ((noteDurationSeconds - elapsedSeconds) / LIVE_SOUND_RELEASE_SECONDS)
                        .coerceIn(0.0, 1.0)
                    val phase = 2.0 * Math.PI * note.frequencyHz * elapsedSeconds
                    val tone = sin(phase) * 0.86 + sin(phase * 2.0) * 0.14
                    mix += tone * note.gain * attack * release
                }
            }
            (mix.coerceIn(-0.72, 0.72) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun stopAndRelease(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private data class LiveSoundNote(
        val frequencyHz: Double,
        val startMs: Int,
        val durationMs: Int,
        val gain: Double
    )
}

internal fun liveSubtitleWindow(text: String, limit: Int = Int.MAX_VALUE): String =
    text.trim().let { normalized ->
        if (normalized.length <= limit) normalized else normalized.takeLast(limit)
    }

private class LiveAudioController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSpectrum: (FloatArray) -> Unit,
    private val onAutoStop: () -> Unit,
    private val onPlaybackComplete: () -> Unit,
    private val onError: (String) -> Unit
) {
    private var recorder: MediaRecorder? = null
    private var realtimeRecorder: AudioRecord? = null
    private var realtimeAgc: AutomaticGainControl? = null
    private var realtimeNs: NoiseSuppressor? = null
    private var realtimeAec: AcousticEchoCanceler? = null
    private var realtimeRecordingBuffer: ByteArrayOutputStream? = null
    private val realtimeRecordingLock = Any()
    private var recordingFile: File? = null
    private var amplitudeJob: Job? = null
    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var fallbackSpectrumJob: Job? = null
    private var realtimePlayer: AudioTrack? = null
    private var realtimePlaybackChannel: Channel<ByteArray>? = null
    private var realtimePlaybackJob: Job? = null

    fun startRecording(): Boolean {
        stopPlayback()
        cancelRecording()
        return try {
            val file = File(context.cacheDir, "live_${System.currentTimeMillis()}.m4a")
            val nextRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recordingFile = file
            recorder = nextRecorder
            nextRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            startAmplitudeCapture(nextRecorder)
            true
        } catch (error: Exception) {
            cancelRecording()
            onError(error.message ?: context.getString(R.string.live_recording_failed))
            false
        }
    }

    fun startRealtimeRecording(): Boolean {
        stopPlayback()
        cancelRecording()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onError(context.getString(R.string.chat_voice_permission_required))
            return false
        }
        return try {
            // 录音使用 16000Hz（Qwen Realtime 官方默认输入采样率，更稳定）
            val minBuffer = AudioRecord.getMinBufferSize(
                REALTIME_AUDIO_INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4_096)
            val active = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                REALTIME_AUDIO_INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
            check(active.state == AudioRecord.STATE_INITIALIZED) { "无法初始化 Realtime 录音器" }
            // 应用音频增强效果：自动增益、噪声抑制、回声消除
            val audioSessionId = active.audioSessionId
            if (AutomaticGainControl.isAvailable()) {
                realtimeAgc = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                realtimeNs = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                realtimeAec = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            }
            val output = ByteArrayOutputStream()
            realtimeRecorder = active
            synchronized(realtimeRecordingLock) { realtimeRecordingBuffer = output }
            active.startRecording()
            amplitudeJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(minBuffer)
                val startedAt = SystemClock.elapsedRealtime()
                var lastVoiceAt = startedAt
                var voiceDetected = false
                var tick = 0
                while (isActive && realtimeRecorder === active) {
                    val count = active.read(buffer, 0, buffer.size)
                    if (count <= 0) continue
                    synchronized(realtimeRecordingLock) {
                        if (realtimeRecordingBuffer === output) output.write(buffer, 0, count)
                    }
                    val level = pcm16Level(buffer, count)
                    scope.launch { onSpectrum(spectrumFromLevel(level, tick++)) }
                    val now = SystemClock.elapsedRealtime()
                    if (level >= LIVE_VOICE_THRESHOLD) {
                        voiceDetected = true
                        lastVoiceAt = now
                    }
                    val elapsed = now - startedAt
                    if (
                        elapsed >= LIVE_MAX_RECORDING_MS ||
                        (voiceDetected && elapsed >= LIVE_MIN_RECORDING_MS && now - lastVoiceAt >= LIVE_SILENCE_MS)
                    ) {
                        scope.launch { onAutoStop() }
                        break
                    }
                }
            }
            true
        } catch (error: Exception) {
            cancelRecording()
            onError(error.message ?: context.getString(R.string.live_recording_failed))
            false
        }
    }

    fun finishRecording(): File? {
        amplitudeJob?.cancel()
        amplitudeJob = null
        val currentRecorder = recorder
        recorder = null
        val file = recordingFile
        recordingFile = null
        val stopped = runCatching { currentRecorder?.stop() }.isSuccess
        runCatching { currentRecorder?.release() }
        onSpectrum(FloatArray(LIVE_SPECTRUM_BARS) { 0.08f })
        if (!stopped || file == null || !file.exists() || file.length() < 512L) {
            file?.delete()
            return null
        }
        return file
    }

    fun finishRealtimeRecording(): ByteArray? {
        val active = realtimeRecorder
        realtimeRecorder = null
        runCatching { active?.stop() }
        amplitudeJob?.cancel()
        amplitudeJob = null
        runCatching { active?.release() }
        // 释放音频增强效果
        runCatching { realtimeAgc?.release() }
        runCatching { realtimeNs?.release() }
        runCatching { realtimeAec?.release() }
        realtimeAgc = null
        realtimeNs = null
        realtimeAec = null
        val bytes = synchronized(realtimeRecordingLock) {
            realtimeRecordingBuffer?.toByteArray().also { realtimeRecordingBuffer = null }
        }
        onSpectrum(FloatArray(LIVE_SPECTRUM_BARS) { 0.08f })
        // 最小音频长度约 0.25 秒（16000Hz × 0.25s × 2 bytes = 8000 bytes）
        return bytes?.takeIf { it.size >= REALTIME_AUDIO_INPUT_SAMPLE_RATE / 2 }
    }

    fun cancelRecording() {
        amplitudeJob?.cancel()
        amplitudeJob = null
        recorder?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        recorder = null
        realtimeRecorder?.let { active ->
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        realtimeRecorder = null
        // 释放音频增强效果
        runCatching { realtimeAgc?.release() }
        runCatching { realtimeNs?.release() }
        runCatching { realtimeAec?.release() }
        realtimeAgc = null
        realtimeNs = null
        realtimeAec = null
        synchronized(realtimeRecordingLock) { realtimeRecordingBuffer = null }
        recordingFile?.delete()
        recordingFile = null
        onSpectrum(FloatArray(LIVE_SPECTRUM_BARS) { 0.08f })
    }

    fun play(url: String, authToken: String?): Boolean {
        stopPlayback()
        return try {
            val nextPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
            }
            player = nextPlayer
            val headers = authToken
                ?.takeIf { it.isNotBlank() && url.startsWith("http", ignoreCase = true) }
                ?.let { mapOf("Authorization" to "Bearer $it") }
                .orEmpty()
            if (headers.isEmpty()) {
                nextPlayer.setDataSource(url)
            } else {
                nextPlayer.setDataSource(context, Uri.parse(url), headers)
            }
            nextPlayer.setOnPreparedListener { prepared ->
                runCatching {
                    prepared.start()
                    attachVisualizer(prepared)
                    startFallbackPlaybackSpectrum(prepared)
                }.onFailure { onError(it.message ?: context.getString(R.string.live_playback_failed)) }
            }
            nextPlayer.setOnCompletionListener {
                stopPlayback(releasePlayer = true)
                onPlaybackComplete()
            }
            nextPlayer.setOnErrorListener { _, _, _ ->
                stopPlayback(releasePlayer = true)
                onError(context.getString(R.string.live_playback_failed))
                true
            }
            nextPlayer.prepareAsync()
            true
        } catch (error: Exception) {
            stopPlayback(releasePlayer = true)
            onError(error.message ?: context.getString(R.string.live_playback_failed))
            false
        }
    }

    fun startRealtimePlayback(): Boolean {
        stopPlayback()
        return try {
            // 播放使用 24000Hz（Qwen Realtime 官方默认输出采样率）
            val minBuffer = AudioTrack.getMinBufferSize(
                REALTIME_AUDIO_OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8_192)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(REALTIME_AUDIO_OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "无法初始化 Realtime 播放器" }
            val channel = Channel<ByteArray>(Channel.UNLIMITED)
            realtimePlayer = track
            realtimePlaybackChannel = channel
            track.play()
            realtimePlaybackJob = scope.launch(Dispatchers.IO) {
                var totalBytes = 0L
                try {
                    for (chunk in channel) {
                        var offset = 0
                        while (offset < chunk.size && isActive && realtimePlayer === track) {
                            val written = track.write(chunk, offset, chunk.size - offset)
                            if (written <= 0) break
                            offset += written
                            totalBytes += written
                        }
                        scope.launch { onSpectrum(spectrumFromPcm16(chunk)) }
                    }
                    val totalFrames = totalBytes / 2L
                    val timeoutAt = SystemClock.elapsedRealtime() +
                        (totalFrames * 1_000L / REALTIME_AUDIO_OUTPUT_SAMPLE_RATE) + 1_500L
                    while (
                        isActive && realtimePlayer === track &&
                        track.playbackHeadPosition.toLong() < totalFrames &&
                        SystemClock.elapsedRealtime() < timeoutAt
                    ) {
                        delay(24)
                    }
                    if (realtimePlayer === track) {
                        realtimePlayer = null
                        realtimePlaybackChannel = null
                        runCatching { track.stop() }
                        runCatching { track.release() }
                        scope.launch {
                            onSpectrum(FloatArray(LIVE_SPECTRUM_BARS) { 0.08f })
                            onPlaybackComplete()
                        }
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // 主动停止播放。
                }
            }
            true
        } catch (error: Exception) {
            stopPlayback()
            onError(error.message ?: context.getString(R.string.live_playback_failed))
            false
        }
    }

    fun writeRealtimeAudio(pcm16: ByteArray) {
        if (pcm16.isNotEmpty()) realtimePlaybackChannel?.trySend(pcm16.copyOf())
    }

    fun finishRealtimePlayback() {
        realtimePlaybackChannel?.close()
    }

    fun stopPlayback(releasePlayer: Boolean = true) {
        fallbackSpectrumJob?.cancel()
        fallbackSpectrumJob = null
        visualizer?.let { runCatching { it.release() } }
        visualizer = null
        realtimePlaybackChannel?.close()
        realtimePlaybackChannel = null
        realtimePlaybackJob?.cancel()
        realtimePlaybackJob = null
        realtimePlayer?.let { active ->
            runCatching { active.pause() }
            runCatching { active.flush() }
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        realtimePlayer = null
        if (releasePlayer) {
            player?.let { active ->
                runCatching { active.stop() }
                runCatching { active.release() }
            }
            player = null
        }
        onSpectrum(FloatArray(LIVE_SPECTRUM_BARS) { 0.08f })
    }

    fun release() {
        cancelRecording()
        stopPlayback()
    }

    private fun startAmplitudeCapture(activeRecorder: MediaRecorder) {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var lastVoiceAt = startedAt
            var voiceDetected = false
            var smoothLevel = 0f
            var tick = 0
            while (isActive && recorder === activeRecorder) {
                delay(80)
                val raw = runCatching { activeRecorder.maxAmplitude }.getOrDefault(0)
                val normalized = sqrt((raw / 32_767f).coerceIn(0f, 1f))
                smoothLevel = smoothLevel * 0.62f + normalized * 0.38f
                onSpectrum(spectrumFromLevel(smoothLevel, tick++))
                val now = SystemClock.elapsedRealtime()
                if (normalized >= LIVE_VOICE_THRESHOLD) {
                    voiceDetected = true
                    lastVoiceAt = now
                }
                val elapsed = now - startedAt
                if (
                    elapsed >= LIVE_MAX_RECORDING_MS ||
                    (voiceDetected && elapsed >= LIVE_MIN_RECORDING_MS && now - lastVoiceAt >= LIVE_SILENCE_MS)
                ) {
                    onAutoStop()
                    break
                }
            }
        }
    }

    private fun attachVisualizer(activePlayer: MediaPlayer) {
        visualizer?.let { runCatching { it.release() } }
        visualizer = runCatching {
            Visualizer(activePlayer.audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            if (waveform != null && waveform.isNotEmpty()) {
                                scope.launch { onSpectrum(spectrumFromWaveform(waveform)) }
                            }
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) = Unit
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    true,
                    false
                )
                enabled = true
            }
        }.getOrNull()
    }

    private fun startFallbackPlaybackSpectrum(activePlayer: MediaPlayer) {
        fallbackSpectrumJob?.cancel()
        fallbackSpectrumJob = scope.launch {
            var tick = 0
            while (isActive && player === activePlayer && runCatching { activePlayer.isPlaying }.getOrDefault(false)) {
                delay(90)
                if (visualizer == null) {
                    val position = runCatching { activePlayer.currentPosition }.getOrDefault(tick * 90)
                    val level = 0.38f + abs(sin(position / 170f)) * 0.46f
                    onSpectrum(spectrumFromLevel(level, tick))
                }
                tick++
            }
        }
    }
}

private fun spectrumFromLevel(level: Float, tick: Int): FloatArray =
    FloatArray(LIVE_SPECTRUM_BARS) { index ->
        val variation = 0.34f + abs(sin(index * 0.73f + tick * 0.21f)) * 0.66f
        (0.06f + level.coerceIn(0f, 1f) * variation).coerceIn(0.06f, 1f)
    }

private fun spectrumFromWaveform(waveform: ByteArray): FloatArray {
    val groupSize = (waveform.size / LIVE_SPECTRUM_BARS).coerceAtLeast(1)
    return FloatArray(LIVE_SPECTRUM_BARS) { index ->
        val start = index * groupSize
        val end = (start + groupSize).coerceAtMost(waveform.size)
        if (start >= end) return@FloatArray 0.06f
        var peak = 0f
        for (sampleIndex in start until end) {
            val centered = abs((waveform[sampleIndex].toInt() and 0xFF) - 128) / 128f
            if (centered > peak) peak = centered
        }
        (0.06f + sqrt(peak.coerceIn(0f, 1f)) * 0.94f).coerceIn(0.06f, 1f)
    }
}

private fun pcm16Level(bytes: ByteArray, count: Int = bytes.size): Float {
    var peak = 0
    var index = 0
    val safeCount = count.coerceAtMost(bytes.size)
    while (index + 1 < safeCount) {
        val sample = (bytes[index].toInt() and 0xFF) or (bytes[index + 1].toInt() shl 8)
        peak = maxOf(peak, abs(sample.toShort().toInt()))
        index += 2
    }
    return sqrt((peak / 32_767f).coerceIn(0f, 1f))
}

private fun spectrumFromPcm16(bytes: ByteArray): FloatArray {
    val sampleCount = bytes.size / 2
    val groupSize = (sampleCount / LIVE_SPECTRUM_BARS).coerceAtLeast(1)
    return FloatArray(LIVE_SPECTRUM_BARS) { band ->
        val startSample = band * groupSize
        val endSample = (startSample + groupSize).coerceAtMost(sampleCount)
        var peak = 0
        for (sampleIndex in startSample until endSample) {
            val byteIndex = sampleIndex * 2
            val sample = (bytes[byteIndex].toInt() and 0xFF) or (bytes[byteIndex + 1].toInt() shl 8)
            peak = maxOf(peak, abs(sample.toShort().toInt()))
        }
        (0.06f + sqrt((peak / 32_767f).coerceIn(0f, 1f)) * 0.94f).coerceIn(0.06f, 1f)
    }
}

/** 头部、状态行、间距与操作区的固定高度估算，用于把剩余空间分配给头像。 */
private val LIVE_FIXED_CONTENT_HEIGHT = 210.dp

/** 环形频谱的采样条数（同时是环形排布的角度份数）。 */
private const val LIVE_SPECTRUM_BARS = 64
private const val LIVE_SPECTRUM_SMOOTHING = 0.24f

/** 静默门限：能量低于 floor 时环形频谱收回为均匀细环，高于 floor+range 时完整跟随音频。 */
private const val LIVE_SPECTRUM_GATE_FLOOR = 0.10f
private const val LIVE_SPECTRUM_GATE_RANGE = 0.18f
/** 呼吸周期：约 11 次/分钟，接近平静状态下的真实呼吸节奏。 */
private const val LIVE_BREATH_PERIOD_MS = 5_600L
private const val LIVE_DRIFT_PERIOD_MS = 17_000L
private const val LIVE_SPIN_PERIOD_MS = 46_000L
/** 2π：呼吸相位计算用（const val 不允许调用 toFloat，这里直接写字面量）。 */
private const val TWO_PI = 6.2831855f
private const val LIVE_PORTRAIT_TRANSITION_MS = 580L
private const val LIVE_INTERFACE_TRANSITION_MS = 240L
private const val LIVE_SOUND_SAMPLE_RATE = 44_100
private const val LIVE_SOUND_RELEASE_PADDING_MS = 50L
private const val LIVE_SOUND_ATTACK_SECONDS = 0.016
private const val LIVE_SOUND_RELEASE_SECONDS = 0.072
private const val LIVE_VOICE_THRESHOLD = 0.075f
private const val LIVE_MIN_RECORDING_MS = 900L
private const val LIVE_SILENCE_MS = 1_250L
private const val LIVE_MAX_RECORDING_MS = 60_000L
/** Realtime 录音采样率（Qwen 官方默认输入 16000Hz，更稳定）。 */
private const val REALTIME_AUDIO_INPUT_SAMPLE_RATE = 16_000
/** Realtime 播放采样率（Qwen 官方默认输出 24000Hz）。 */
private const val REALTIME_AUDIO_OUTPUT_SAMPLE_RATE = 24_000
