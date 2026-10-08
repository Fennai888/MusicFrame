package com.musicframe.app

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    val cover by produceState<ImageBitmap?>(null, s.uri) {
        value = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, Uri.parse(s.uri))
                r.embeddedPicture?.let {
                    val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeByteArray(it, 0, it.size, o)?.asImageBitmap()
                }
            } catch (e: Exception) {
                null
            } finally {
                r.release()
            }
        }
    }
    val c = cover

    Box(Modifier.fillMaxSize()) {
        if (c != null) {
            Image(
                bitmap = c,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                    .blur(60.dp, BlurredEdgeTreatment.Unbounded)
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
        } else {
            Backdrop()
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .offset { IntOffset(dragX.value.roundToInt(), 0) }
                .glass(44.dp)
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
                    .aspectRatio(0.9f)
                    .clip(RoundedCornerShape(34.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF5BA3D0), Color(0xFFF2B38F)))),
                contentAlignment = Alignment.Center
            ) {
                if (c != null) {
                    Image(
                        bitmap = c,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("♪", color = Color.White.copy(alpha = 0.6f), fontSize = 72.sp)
                }
                Text(flashIcon, color = Color.White, fontSize = 64.sp, modifier = Modifier.alpha(flash.value))
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(34.dp))
                    .background(Color.White.copy(alpha = 0.35f))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        s.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        s.artist, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier
                        .padding(horizontal = 10.dp)
                        .size(44.dp)
                        .border(
                            5.dp,
                            Brush.sweepGradient(
                                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("♪", color = Color.White, fontSize = 18.sp)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        s.album, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
