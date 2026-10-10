package com.musicframe.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
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

fun artFile(ctx: Context, s: Song): File {
    val dir = File(ctx.cacheDir, "art")
    dir.mkdirs()
    return File(dir, (s.uri + "|" + s.cover).hashCode().toUInt().toString(16) + ".jpg")
}

fun ensureArt(ctx: Context, s: Song) {
    val f = artFile(ctx, s)
    if (f.exists()) return
    val b = Covers.load(ctx, s, 512) ?: return
    try {
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    } catch (e: Exception) {
        f.delete()
    }
}

fun mediaItemOf(ctx: Context, s: Song): MediaItem = MediaItem.Builder()
    .setUri(Uri.parse(s.uri))
    .setMediaId(s.uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(s.title)
            .setArtist(s.artist)
            .setAlbumTitle(s.album)
            .setArtworkUri(Uri.fromFile(artFile(ctx, s)))
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
fun VideoFill(vp: ExoPlayer, aspect: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val boxAr = maxWidth / maxHeight
        val w: Dp
        val h: Dp
        if (aspect > boxAr) {
            h = maxHeight
            w = maxHeight * aspect
        } else {
            w = maxWidth
            h = maxWidth / aspect
        }
        AndroidView(
            factory = { TextureView(it) },
            update = { tv -> vp.setVideoTextureView(tv) },
            onRelease = { tv ->
                try {
                    vp.clearVideoTextureView(tv)
                } catch (e: Exception) {
                }
            },
            modifier = Modifier.requiredSize(w, h)
        )
    }
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
    var showList by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var seekFrac by remember { mutableFloatStateOf(0f) }
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    var vplayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var vAspect by remember { mutableFloatStateOf(16f / 9f) }
    var resumed by remember { mutableStateOf(true) }

    LaunchedEffect(player) {
        val ids = songs.map { it.uri }
        val cur = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (cur != ids) {
            withContext(Dispatchers.IO) {
                songs.getOrNull(startIndex)?.let { ensureArt(ctx, it) }
            }
            player.setMediaItems(songs.map { mediaItemOf(ctx, it) }, startIndex, 0L)
            player.prepare()
            player.play()
        } else {
            if (player.currentMediaItemIndex != startIndex) {
                player.seekTo(startIndex, 0L)
            }
            player.play()
        }
        index = player.currentMediaItemIndex
        launch(Dispatchers.IO) {
            songs.forEach { ensureArt(ctx, it) }
        }
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
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var bx = Float.NaN
        var by = 0f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = e.values[0]
                val y = e.values[1]
                if (bx.isNaN()) {
                    bx = x
                    by = y
                }
                val nx = ((x - bx) / 4f).coerceIn(-1f, 1f)
                val ny = ((y - by) / 4f).coerceIn(-1f, 1f)
                tiltX += (nx - tiltX) * 0.12f
                tiltY += (ny - tiltY) * 0.12f
                bx += (x - bx) * 0.01f
                by += (y - by) * 0.01f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (acc != null) sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                resumed = false
            } else if (e == Lifecycle.Event.ON_START) {
                resumed = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    BackHandler(enabled = !editing && !showCoverSheet && !showList, onBack = onClose)
    BackHandler(enabled = editing) { editing = false }
    BackHandler(enabled = showCoverSheet) { showCoverSheet = false }
    BackHandler(enabled = showList) { showList = false }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            songs.getOrNull(index)?.let {
                val sp = parseCover(it.cover)
                onUpdate(index, it.copy(cover = buildCover(sp.copy(image = u.toString()))))
            }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            songs.getOrNull(index)?.let {
                val sp = parseCover(it.cover)
                onUpdate(index, it.copy(cover = buildCover(sp.copy(video = u.toString()))))
            }
        }
    }

    val flash = remember { Animatable(0f) }
    var flashIcon by remember { mutableStateOf("▶") }
    val dragX = remember { Animatable(0f) }
    val s = songs.getOrNull(index) ?: return
    val spec = parseCover(s.cover)

    DisposableEffect(spec.video) {
        if (spec.video.isBlank()) {
            vplayer = null
            onDispose { }
        } else {
            val ex = ExoPlayer.Builder(ctx).build()
            ex.setMediaItem(MediaItem.fromUri(Uri.parse(spec.video)))
            ex.repeatMode = Player.REPEAT_MODE_ONE
            ex.volume = 0f
            ex.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        vAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                }
            })
            ex.prepare()
            ex.playWhenReady = player.playWhenReady
            vplayer = ex
            onDispose {
                vplayer = null
                ex.release()
            }
        }
    }
    LaunchedEffect(vplayer, spec.sound) {
        vplayer?.let { v ->
            v.trackSelectionParameters = v.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !spec.sound)
                .build()
            v.volume = if (spec.sound) 1f else 0f
        }
    }
    LaunchedEffect(vplayer, resumed) {
        val v = vplayer ?: return@LaunchedEffect
        while (true) {
            val want = resumed && player.playWhenReady && player.playbackState != Player.STATE_ENDED
            if (v.playWhenReady != want) v.playWhenReady = want
            delay(200)
        }
    }

    var tTitle by remember(editing) { mutableStateOf(s.title) }
    var tArtist by remember(editing) { mutableStateOf(s.artist) }
    var tAlbum by remember(editing) { mutableStateOf(s.album) }

    val cover by produceState<ImageBitmap?>(null, s.uri, s.cover) {
        value = withContext(Dispatchers.IO) { Covers.load(ctx, s, 1600)?.asImageBitmap() }
    }
    val c = cover

    val glowFrac by animateFloatAsState(
        targetValue = if (durMs > 0) {
            (if (seeking) seekFrac else posMs.toFloat() / durMs).coerceIn(0f, 1f)
        } else {
            0f
        },
        animationSpec = tween(250, easing = LinearEasing),
        label = "glow"
    )
    val shownPos = if (seeking && durMs > 0) (seekFrac * durMs).toLong() else posMs

    fun showFlash(label: String) {
        flashIcon = label
        scope.launch {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(700))
        }
    }

    fun toggle() {
        if (player.playWhenReady) player.pause() else player.play()
        showFlash(if (player.playWhenReady) "▶" else "❚❚")
    }

    fun seekBy(ms: Long, label: String) {
        val d = player.duration
        var t = player.currentPosition + ms
        if (t < 0) t = 0
        if (d > 0 && t > d - 500) t = (d - 500).coerceAtLeast(0)
        player.seekTo(t)
        showFlash(label)
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
                        .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f }
                        .blur(24.dp, BlurredEdgeTreatment.Unbounded)
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
                        .clip(RoundedCornerShape(44.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.12f))
                            )
                        )
                        .drawWithContent {
                            drawContent()
                            val shine = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                                center = Offset(
                                    size.width * (0.5f + tiltX * 0.8f),
                                    size.height * (0.4f + tiltY * 0.5f)
                                ),
                                radius = size.width * 0.8f
                            )
                            drawRect(shine)
                            val inset = 2.dp.toPx()
                            val rad = 44.dp.toPx() - inset
                            val path = Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        inset,
                                        inset,
                                        size.width - inset,
                                        size.height - inset,
                                        CornerRadius(rad, rad)
                                    )
                                )
                            }
                            drawPath(path, Color.White.copy(alpha = 0.10f), style = Stroke(width = 1.5.dp.toPx()))
                            if (glowFrac > 0f) {
                                val pm = PathMeasure()
                                pm.setPath(path, false)
                                val seg = Path()
                                pm.getSegment(0f, pm.length * glowFrac, seg, true)
                                drawPath(
                                    seg,
                                    Color.White.copy(alpha = 0.95f),
                                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }
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
                                    onDoubleTap = { off ->
                                        if (!editing) {
                                            val w = size.width
                                            if (off.x < w * 0.35f) seekBy(-10_000L, "−10s")
                                            else if (off.x > w * 0.65f) seekBy(10_000L, "+10s")
                                        }
                                    },
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
                        val vp = vplayer
                        if (vp != null) {
                            VideoFill(vp, vAspect, Modifier.fillMaxSize())
                        }
                        Text(flashIcon, color = Color.White, fontSize = 56.sp, modifier = Modifier.alpha(flash.value))
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
                                .pointerInput(Unit) {
                                    var mode = 0
                                    var ax = 0f
                                    var ay = 0f
                                    detectDragGestures(
                                        onDragStart = {
                                            mode = 0
                                            ax = 0f
                                            ay = 0f
                                        },
                                        onDragEnd = {
                                            if (mode == 1) {
                                                if (durMs > 0) player.seekTo((seekFrac * durMs).toLong())
                                            } else if (mode == 2 && ay < -80f) {
                                                showList = true
                                            }
                                            seeking = false
                                        },
                                        onDragCancel = { seeking = false }
                                    ) { change, d ->
                                        change.consume()
                                        ax += d.x
                                        ay += d.y
                                        if (mode == 0 && (abs(ax) > 12f || abs(ay) > 12f)) {
                                            mode = if (abs(ax) >= abs(ay)) 1 else 2
                                            if (mode == 1) {
                                                seeking = true
                                                seekFrac = if (durMs > 0) posMs.toFloat() / durMs else 0f
                                            }
                                        }
                                        if (mode == 1) {
                                            seekFrac = (seekFrac + d.x / size.width).coerceIn(0f, 1f)
                                        }
                                    }
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
                                    fmtTime(shownPos), color = Color.White, fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp, fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    if (durMs > 0) "-" + fmtTime(durMs - shownPos) else "--:--",
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
                        if (spec.image.isNotBlank()) {
                            SettingRow("ใช้ปกในไฟล์เพลง") {
                                showCoverSheet = false
                                onUpdate(index, s.copy(cover = buildCover(spec.copy(image = ""))))
                            }
                        }
                        SettingRow(if (spec.video.isBlank()) "ใส่วิดีโอ" else "เปลี่ยนวิดีโอ") {
                            showCoverSheet = false
                            videoPicker.launch(arrayOf("video/*"))
                        }
                        if (spec.video.isNotBlank()) {
                            SettingRow(
                                "เสียงวิดีโอ: " + (if (spec.sound) "เปิด" else "ปิด") + " (แตะเพื่อสลับ)"
                            ) {
                                showCoverSheet = false
                                onUpdate(index, s.copy(cover = buildCover(spec.copy(sound = !spec.sound))))
                            }
                            SettingRow("เอาวิดีโอออก", Color(0xFFFF6B6B)) {
                                showCoverSheet = false
                                onUpdate(index, s.copy(cover = buildCover(spec.copy(video = "", sound = false))))
                            }
                        }
                    }
                }
            }
            if (showList) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .pointerInput(Unit) { detectTapGestures { showList = false } }
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
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .padding(vertical = 8.dp)
                    ) {
                        itemsIndexed(songs) { i, sg ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        player.seekTo(i, 0L)
                                        player.play()
                                        showList = false
                                    }
                                    .padding(horizontal = 24.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    (if (i == index) "▶  " else "") + sg.title,
                                    color = Color.White,
                                    fontWeight = if (i == index) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (sg.artist.isNotBlank()) {
                                    Text(
                                        sg.artist,
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
