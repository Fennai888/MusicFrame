package com.musicframe.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(songs: List<Song>, startIndex: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItems(songs.map { MediaItem.fromUri(Uri.parse(it.uri)) })
            seekTo(startIndex, 0L)
            prepare()
            playWhenReady = true
        }
    }
    var index by remember { mutableIntStateOf(startIndex) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                index = player.currentMediaItemIndex
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            player.release()
        }
    }
    BackHandler(onBack = onClose)

    val flash = remember { Animatable(0f) }
    var flashIcon by remember { mutableStateOf("▶") }
    val dragX = remember { Animatable(0f) }
    val s = songs.getOrNull(index) ?: return

    Box(Modifier.fillMaxSize()) {
        Backdrop()
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(20.dp)
                .offset { IntOffset(dragX.value.roundToInt(), 0) }
                .glass(56.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        if (player.playWhenReady) player.pause() else player.play()
                        flashIcon = if (player.playWhenReady) "▶" else "❚❚"
                        scope.launch {
                            flash.snapTo(1f)
                            flash.animateTo(0f, tween(700))
                        }
                    })
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val d = dragX.value
                            scope.launch {
                                val cur = player.currentMediaItemIndex
                                if (d < -200f && cur < player.mediaItemCount - 1) player.seekTo(cur + 1, 0L)
                                else if (d > 200f && cur > 0) player.seekTo(cur - 1, 0L)
                                dragX.animateTo(0f, tween(250))
                            }
                        },
                        onDragCancel = { scope.launch { dragX.animateTo(0f, tween(250)) } }
                    ) { change, amount ->
                        change.consume()
                        scope.launch { dragX.snapTo(dragX.value + amount) }
                    }
                }
                .padding(10.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(44.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF5BA3D0), Color(0xFFF2B38F)))),
                contentAlignment = Alignment.Center
            ) {
                Text("♪", color = Color.White.copy(alpha = 0.6f), fontSize = 72.sp)
                Text(flashIcon, color = Color.White, fontSize = 64.sp, modifier = Modifier.alpha(flash.value))
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(40.dp))
                    .background(Color.White.copy(alpha = 0.35f))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        s.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        s.artist, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .size(52.dp)
                        .border(
                            6.dp,
                            Brush.sweepGradient(
                                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("♪", color = Color.White, fontSize = 20.sp)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        s.album, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
