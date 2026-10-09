package com.musicframe.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

fun trimBorders(b: Bitmap): Bitmap {
    val w = b.width
    val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    fun blank(p: Int): Boolean {
        if ((p ushr 24) < 16) return true
        return ((p shr 16) and 255) < 8 && ((p shr 8) and 255) < 8 && (p and 255) < 8
    }
    fun rowBlank(y: Int): Boolean {
        var n = 0
        for (x in 0 until w) if (!blank(px[y * w + x])) n++
        return n <= w / 100
    }
    fun colBlank(x: Int, t: Int, bt: Int): Boolean {
        var n = 0
        for (y in t..bt) if (!blank(px[y * w + x])) n++
        return n <= (bt - t + 1) / 100
    }
    var top = 0
    var bottom = h - 1
    var left = 0
    var right = w - 1
    while (top < bottom && rowBlank(top)) top++
    while (bottom > top && rowBlank(bottom)) bottom--
    while (left < right && colBlank(left, top, bottom)) left++
    while (right > left && colBlank(right, top, bottom)) right--
    val nw = right - left + 1
    val nh = bottom - top + 1
    if (nw == w && nh == h) return b
    if (nw.toLong() * nh < w.toLong() * h * 35 / 100) return b
    return Bitmap.createBitmap(b, left, top, nw, nh)
}

fun prepareCover(src: Bitmap): Bitmap {
    val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val cv = Canvas(flat)
    cv.drawColor(android.graphics.Color.BLACK)
    cv.drawBitmap(src, 0f, 0f, null)
    return trimBorders(flat)
}

fun fmtTime(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return "${t / 60}:${(t % 60).toString().padStart(2, '0')}"
}

fun mediaItemOf(s: Song): MediaItem = MediaItem.Builder()
    .setUri(Uri.parse(s.uri))
    .setMediaId(s.uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(s.title)
            .setArtist(s.artist)
            .setAlbumTitle(s.album)
            .build()
    )
    .build()

@Composable
fun GlassField(value: String, onChange: (String) -> Unit, hint: String, size: TextUnit, bold: Boolean) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(
            color = Color.White,
            fontSize = size,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        ),
        cursorBrush = SolidColor(Color.White),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (value.isEmpty()) {
                    Text(hint, color = Color.White.copy(alpha = 0.5f), fontSize = size)
                }
                inner()
            }
        }
    )
}

@Composable
fun PlayerScreen(songs: List<Song>, startIndex: Int, onUpdate: (Int, Song) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(Unit) {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            try {
                controller = future.get()
            } catch (e: Exception) {
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }

    val p = controller
    BackHandler(enabled = p == null, onBack = onClose)
    if (p == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
    } else {
        PlayerContent(p, songs, startIndex, onUpdate, onClose)
    }
}

@Composable
fun PlayerContent(
    player: Player,
    songs: List<Song>,
    startIndex: Int,
    onUpdate: (Int, Song) -> Unit,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    var index by remember { mutableIntStateOf(startIndex) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    var editing by remember { mutableStateOf(false) }
    var showCoverSheet by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        val ids = songs.map { it.uri }
        val cur = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (cur != ids) {
            player.setMediaItems(songs.map { mediaItemOf(it) }, startIndex, 0L)
            player.prepare()
            player.play()
        } else {
            if (player.currentMediaItemIndex != startIndex) {
                player.seekTo(startIndex, 0L)
            }
            player.play()
        }
        index = player.currentMediaItemIndex
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                index = player.currentMediaItemIndex
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            posMs = player.currentPosition
            durMs = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }
    BackHandler(enabled = !editing && !showCoverSheet, onBack = onClose)
    BackHandler(enabled = editing) { editing = false }
    BackHandler(enabled = showCoverSheet) { showCoverSheet = false }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            songs.getOrNull(index)?.let { onUpdate(index, it.copy(cover = u.toString())) }
        }
    }

    val flash = remember { Animatable(0f) }
    var flashIcon by remember { mutableStateOf("▶") }
    val dragX = remember { Animatable(0f) }
    val s = songs.getOrNull(index) ?: return

    var tTitle by remember(editing) { mutableStateOf(s.title) }
    var tArtist by remember(editing) { mutableStateOf(s.artist) }
    var tAlbum by remember(editing) { mutableStateOf(s.album) }

    val cover by produceState<ImageBitmap?>(null, s.uri, s.cover) {
        value = withContext(Dispatchers.IO) { Covers.load(ctx, s, 1600)?.asImageBitmap() }
    }
    val c = cover

    fun toggle() {
        if (player.playWhenReady) player.pause() else player.play()
        flashIcon = if (player.playWhenReady) "▶" else "❚❚"
        scope.launch {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(700))
        }
    }

    CompositionLocalProvider(LocalSoftBg provides null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (c != null) {
                Image(
                    bitmap = c,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.4f) }),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                        .blur(60.dp, BlurredEdgeTreatment.Unbounded)
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.10f)))
            } else {
                Backdrop()
            }
            Box(Modifier.fillMaxSize().imePadding()) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp)
                        .offset { IntOffset(dragX.value.roundToInt(), 0) }
                        .glass(44.dp)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val d = dragX.value
                                    scope.launch {
                                        if (!editing) {
                                            val cur = player.currentMediaItemIndex
                                            if (d < -200f && cur < player.mediaItemCount - 1) player.seekTo(cur + 1, 0L)
                                            else if (d > 200f && cur > 0) player.seekTo(cur - 1, 0L)
                                        }
                                        dragX.animateTo(0f, tween(250))
                                    }
                                },
                                onDragCancel = { scope.launch { dragX.animateTo(0f, tween(250)) } }
                            ) { change, amount ->
                                if (!editing) {
                                    change.consume()
                                    scope.launch { dragX.snapTo(dragX.value + amount) }
                                }
                            }
                        }
                        .padding(10.dp)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(if (editing) 1.8f else 0.9f)
                            .clip(RoundedCornerShape(34.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF5BA3D0), Color(0xFFF2B38F))))
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { if (!editing) toggle() },
                                    onLongPress = {
                                        if (!editing) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showCoverSheet = true
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (c != null) {
                            Image(
                                bitmap = c,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                filterQuality = FilterQuality.High,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("♪", color = Color.White.copy(alpha = 0.6f), fontSize = 72.sp)
                        }
                        Text(flashIcon, color = Color.White, fontSize = 64.sp, modifier = Modifier.alpha(flash.value))
                    }
                    Spacer(Modifier.height(10.dp))
                    if (editing) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(34.dp))
                                .background(Color.White.copy(alpha = 0.35f))
                                .padding(16.dp)
                        ) {
                            GlassField(tTitle, { tTitle = it }, "ชื่อเพลง", 15.sp, true)
                            Spacer(Modifier.height(8.dp))
                            GlassField(tArtist, { tArtist = it }, "ชื่อศิลปิน", 14.sp, false)
                            Spacer(Modifier.height(8.dp))
                            GlassField(tAlbum, { tAlbum = it }, "ข้อความฝั่งขวา (เช่น อัลบั้ม)", 14.sp, false)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Text(
                                    "ยกเลิก",
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 15.sp,
                                    modifier = Modifier
                                        .clickable {
                                            focus.clearFocus()
                                            editing = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                Text(
                                    "บันทึก",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable {
                                            onUpdate(
                                                index,
                                                s.copy(
                                                    title = tTitle.trim().ifBlank { s.title },
                                                    artist = tArtist.trim(),
                                                    album = tAlbum.trim()
                                                )
                                            )
                                            focus.clearFocus()
                                            editing = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(34.dp))
                                .background(Color.White.copy(alpha = 0.35f))
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { toggle() },
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            editing = true
                                        }
                                    )
                                }
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
                            Column(
                                Modifier.padding(horizontal = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    fmtTime(posMs), color = Color.White, fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp, fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    if (durMs > 0) "-" + fmtTime(durMs - posMs) else "--:--",
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                                )
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
            if (showCoverSheet) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .pointerInput(Unit) { detectTapGestures { showCoverSheet = false } }
                )
                Glass(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp)
                        .fillMaxWidth(),
                    radius = 32.dp,
                    dim = 0.35f
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        SettingRow("เปลี่ยนรูปปก") {
                            showCoverSheet = false
                            coverPicker.launch(arrayOf("image/*"))
                        }
                        if (s.cover.isNotBlank()) {
                            SettingRow("ใช้ปกในไฟล์เพลง") {
                                showCoverSheet = false
                                onUpdate(index, s.copy(cover = ""))
                            }
                        }
                    }
                }
            }
        }
    }
}
