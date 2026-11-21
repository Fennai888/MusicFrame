package com.musicframe.app

import android.content.ComponentName
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/* ---------- ตัวเชื่อมกับ PlaybackService (แบบเดียวกับ PlayerScreen) ---------- */

@Composable
fun LandscapeScreen(songs: List<Song>, bg: BgImages?, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }

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
    if (p == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
    } else {
        LandscapeContent(p, songs, bg, onAdd)
    }
}

/** เก็บรูปปกที่ถอดแล้วไว้ในหน่วยความจำ — การ์ดที่ถูกสร้างใหม่จะได้รูปทันที ไม่กะพริบเป็นพื้นเปล่า */
private object ThumbCache {
    private val bitmaps = object : LruCache<String, ImageBitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val tints = HashMap<String, Color>()

    fun bitmap(key: String): ImageBitmap? = bitmaps.get(key)
    fun putBitmap(key: String, v: ImageBitmap) {
        bitmaps.put(key, v)
    }

    fun tint(key: String): Color? = tints[key]
    fun putTint(key: String, v: Color) {
        tints[key] = v
    }
}

private fun coverKey(s: Song, size: Int): String = "${s.uri}|${s.cover}|$size"

/** โหลดปกของเพลง โดยเริ่มจากแคชก่อน และไม่ล้างรูปทิ้งถ้าโหลดรอบใหม่ไม่ได้ */
@Composable
fun rememberCoverBitmap(s: Song, size: Int): ImageBitmap? {
    val ctx = LocalContext.current
    val key = coverKey(s, size)
    var bmp by remember(key) { mutableStateOf(ThumbCache.bitmap(key)) }
    LaunchedEffect(key) {
        if (bmp == null) {
            val b = withContext(Dispatchers.IO) { Covers.load(ctx, s, size)?.asImageBitmap() }
            if (b != null) {
                ThumbCache.putBitmap(key, b)
                bmp = b
            }
        }
    }
    return bmp
}

private fun playerLoaded(player: Player, songs: List<Song>): Boolean {
    if (player.mediaItemCount != songs.size) return false
    for (i in songs.indices) {
        if (player.getMediaItemAt(i).mediaId != songs[i].uri) return false
    }
    return true
}

@Composable
fun LandscapeContent(player: Player, songs: List<Song>, bg: BgImages?, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember {
        mutableIntStateOf(
            if (playerLoaded(player, songs)) player.currentMediaItemIndex.coerceIn(0, songs.size - 1) else 0
        )
    }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    var loaded by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var seekFrac by remember { mutableFloatStateOf(0f) }

    fun isLoaded(): Boolean {
        if (player.mediaItemCount != songs.size) return false
        for (i in songs.indices) {
            if (player.getMediaItemAt(i).mediaId != songs[i].uri) return false
        }
        return true
    }

    fun goTo(i: Int) {
        if (i !in songs.indices) return
        index = i
        scope.launch {
            if (!isLoaded()) {
                withContext(Dispatchers.IO) { ensureArt(ctx, songs[i]) }
                player.setMediaItems(songs.map { mediaItemOf(ctx, it) }, i, 0L)
                player.prepare()
                player.play()
                launch(Dispatchers.IO) { songs.forEach { ensureArt(ctx, it) } }
            } else {
                player.seekTo(i, 0L)
                player.play()
            }
        }
    }

    fun toggle() {
        if (!isLoaded()) {
            goTo(index)
        } else if (player.playWhenReady) {
            player.pause()
        } else {
            player.play()
        }
    }

    LaunchedEffect(player) {
        loaded = isLoaded()
        playing = player.playWhenReady
        if (loaded) index = player.currentMediaItemIndex.coerceIn(0, songs.size - 1)
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (isLoaded()) index = player.currentMediaItemIndex.coerceIn(0, songs.size - 1)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playing = playWhenReady
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            loaded = isLoaded()
            if (loaded) {
                posMs = player.currentPosition
                durMs = player.duration.coerceAtLeast(0L)
            } else {
                posMs = 0L
                durMs = 0L
            }
            delay(250)
        }
    }

    val cur = songs.getOrNull(index)
    val cover by produceState<ImageBitmap?>(
        cur?.let { ThumbCache.bitmap(coverKey(it, 800)) }, cur?.uri, cur?.cover
    ) {
        if (cur != null) {
            val k = coverKey(cur, 800)
            val b = ThumbCache.bitmap(k) ?: withContext(Dispatchers.IO) {
                Covers.load(ctx, cur, 800)?.asImageBitmap()
            }?.also { ThumbCache.putBitmap(k, it) }
            if (b != null) value = b
        }
    }
    val c = cover
    val bgSoft by produceState<ImageBitmap?>(null, c) {
        if (c != null) {
            value = withContext(Dispatchers.Default) {
                softBlurBitmap(c.asAndroidBitmap(), 96, 3).asImageBitmap()
            }
        }
    }
    val coverTint by produceState<Color?>(null, c) {
        value = if (c == null) null else withContext(Dispatchers.Default) { dominantTint(c) }
    }
    val tintState = animateColorAsState(
        targetValue = coverTint ?: Color.White,
        animationSpec = tween(900),
        label = "lsTint"
    )

    val backdrop = rememberLayerBackdrop {
        drawRect(Color.Black)
        drawContent()
    }


    CompositionLocalProvider(
        LocalSoftBg provides null,
        LocalGlassBackdrop provides backdrop
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                if (bg != null) {
                    HomeBackground(bg)
                } else if (c != null) {
                    val soft = bgSoft
                    if (soft != null) {
                        Image(
                            bitmap = soft,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.High,
                            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.4f) }),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)))
                } else {
                    Backdrop()
                }
            }
            Row(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QueueList(
                    modifier = Modifier.weight(0.30f).fillMaxHeight(),
                    songs = songs,
                    index = index,
                    tint = tintState,
                    onPick = { goTo(it) },
                    onAdd = onAdd
                )
                Column(
                    Modifier.weight(0.70f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CoverCarousel(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        songs = songs,
                        index = index,
                        onChange = { goTo(it) },
                        onToggle = { toggle() }
                    )
                    PageDots(songs.size, index)
                    Spacer(Modifier.height(6.dp))
                    LandscapeControls(
                        song = cur,
                        tint = tintState,
                        playing = playing && loaded,
                        shownPos = { if (seeking && durMs > 0) (seekFrac * durMs).toLong() else posMs },
                        durMs = { durMs },
                        frac = {
                            if (durMs > 0) {
                                (if (seeking) seekFrac else posMs.toFloat() / durMs).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        onPrev = { if (index > 0) goTo(index - 1) },
                        onToggle = { toggle() },
                        onNext = { if (index < songs.size - 1) goTo(index + 1) },
                        onSeekPreview = {
                            seeking = true
                            seekFrac = it
                        },
                        onSeekCommit = {
                            if (loaded && durMs > 0) player.seekTo((it * durMs).toLong())
                            seeking = false
                        }
                    )
                }
            }
        }
    }
}

/* ---------- กระจก liquid glass แบบนิ่ง (ใช้กับของที่ถูกเอียง 3D) ---------- */

fun Modifier.glassTint(r: Dp, tint: Color): Modifier = this
    .clip(RoundedCornerShape(r))
    .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.28f))))
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f))))
    .border(
        1.dp,
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.06f), tint.copy(alpha = 0.55f))),
        RoundedCornerShape(r)
    )

/** เหมือน glassTint แต่อ่านสีตอนวาดเท่านั้น สีเปลี่ยนทีละเฟรมก็ไม่ต้อง recompose */
fun Modifier.glassTintLazy(r: Dp, tint: () -> Color): Modifier = this
    .clip(RoundedCornerShape(r))
    .drawBehind {
        val t = tint()
        drawRect(Brush.verticalGradient(listOf(t.copy(alpha = 0.55f), t.copy(alpha = 0.28f))))
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f))))
        val sw = 1.dp.toPx()
        drawRoundRect(
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.06f), t.copy(alpha = 0.55f))),
            topLeft = Offset(sw / 2f, sw / 2f),
            size = Size(size.width - sw, size.height - sw),
            cornerRadius = CornerRadius(r.toPx()),
            style = Stroke(sw)
        )
    }

private val textShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f), 6f)

/* ---------- ซ้าย: Continue Playing (ไม่มีกระจกครอบ เอียง 3D) ---------- */

@Composable
fun QueueList(
    modifier: Modifier,
    songs: List<Song>,
    index: Int,
    tint: State<Color>,
    onPick: (Int) -> Unit,
    onAdd: () -> Unit
) {
    val density = LocalDensity.current
    val dens = density.density
    val rowH = 46.dp
    val gap = 2.dp
    val topPad = 4.dp
    val pitchPx = with(density) { (rowH + gap).toPx() }
    val topPx = with(density) { topPad.toPx() }

    // แผ่นกระจกชิ้นเดียว เลื่อนไปหาเพลงที่เลือก (ไม่ได้วาร์ปทีละแถว)
    val pillY = remember { Animatable(index * pitchPx) }
    LaunchedEffect(index) {
        pillY.animateTo(index * pitchPx, spring(dampingRatio = 0.82f, stiffness = 320f))
    }
    val ls = rememberLazyListState()
    LaunchedEffect(index) {
        ls.animateScrollToItem((index - 1).coerceAtLeast(0))
    }

    Column(
        modifier.graphicsLayer {
            rotationY = LIST_TILT
            cameraDistance = 11f * dens
            transformOrigin = TransformOrigin(0f, 0.5f)
        }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Continue Playing",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(shadow = textShadow),
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier.size(30.dp).clip(CircleShape).clickable { onAdd() },
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = Color.White, fontSize = 20.sp, style = TextStyle(shadow = textShadow))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            Box(
                Modifier
                    .offset {
                        val scroll = ls.firstVisibleItemIndex * pitchPx + ls.firstVisibleItemScrollOffset
                        IntOffset(0, (topPx + pillY.value - scroll).roundToInt())
                    }
                    .fillMaxWidth()
                    .height(rowH)
                    .glassTintLazy(16.dp) { tint.value }
            )
            LazyColumn(
                state = ls,
                contentPadding = PaddingValues(vertical = topPad),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                itemsIndexed(songs, key = { _, s -> s.uri }) { i, s ->
                    val isCur = i == index
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(rowH)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onPick(i) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QueueThumb(s)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.title,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                                style = TextStyle(shadow = textShadow),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (s.artist.isNotBlank()) {
                                Text(
                                    s.artist,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 10.sp,
                                    style = TextStyle(shadow = textShadow),
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

/** รูปปกเล็กหน้าชื่อเพลงในรายการซ้าย */
@Composable
fun QueueThumb(s: Song) {
    val t = rememberCoverBitmap(s, 160)
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF5BA3D0), Color(0xFFF2B38F)))),
        contentAlignment = Alignment.Center
    ) {
        if (t != null) {
            Image(
                bitmap = t,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("♪", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
        }
    }
}

/** องศาเอียงของรายการซ้าย — ถ้าเอียงผิดด้านให้เปลี่ยนเครื่องหมาย */
private const val LIST_TILT = 26f

/** ระยะห่างของการ์ดวงนอก (เทียบกับวงใน) ยิ่งน้อยยิ่งชิด */
private const val OUTER_STEP = 0.7f

/** องศาเอียงของการ์ดข้าง ๆ ต่อ 1 ช่อง — ถ้าเอียงผิดด้านให้เปลี่ยนเครื่องหมาย */
private const val CARD_TILT = -28f

/* ---------- กลาง: การ์ดปกซ้อนกัน เอียง 3D ---------- */

@Composable
fun CoverCarousel(
    modifier: Modifier,
    songs: List<Song>,
    index: Int,
    onChange: (Int) -> Unit,
    onToggle: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dens = density.density
    // ตำแหน่งต่อเนื่องของวงล้อการ์ด (หน่วย = จำนวนเพลง) เปลี่ยนเพลงที่ไหนก็เลื่อนมาเอง
    val pos = remember { Animatable(index.toFloat()) }
    val curIndex by rememberUpdatedState(index)
    val count by rememberUpdatedState(songs.size)
    val onChangeNow by rememberUpdatedState(onChange)
    val onToggleNow by rememberUpdatedState(onToggle)
    val cur = songs.getOrNull(index)

    LaunchedEffect(index) {
        pos.animateTo(index.toFloat(), spring(dampingRatio = 0.85f, stiffness = 260f))
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val cardSide = minOf(maxHeight - 46.dp, maxWidth * 0.24f).coerceAtLeast(60.dp)
        val cardPx = with(density) { cardSide.toPx() }
        val stepPx = cardPx * 0.78f
        val stepNow by rememberUpdatedState(stepPx)
        val cardNow by rememberUpdatedState(cardPx)

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { off ->
                        val mid = size.width / 2f
                        val half = cardNow / 2f
                        if (off.x < mid - half) {
                            if (curIndex > 0) onChangeNow(curIndex - 1)
                        } else if (off.x > mid + half) {
                            if (curIndex < count - 1) onChangeNow(curIndex + 1)
                        } else {
                            onToggleNow()
                        }
                    }
                }
                .pointerInput(Unit) {
                    fun settle() {
                        val p = pos.value
                        val c = curIndex
                        val target = when {
                            p > c + 0.25f -> c + 1
                            p < c - 0.25f -> c - 1
                            else -> c
                        }.coerceIn(0, (count - 1).coerceAtLeast(0))
                        if (target != c) {
                            onChangeNow(target)
                        } else {
                            scope.launch {
                                pos.animateTo(c.toFloat(), spring(dampingRatio = 0.85f, stiffness = 260f))
                            }
                        }
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { scope.launch { pos.stop() } },
                        onDragEnd = { settle() },
                        onDragCancel = { settle() }
                    ) { change, amount ->
                        change.consume()
                        scope.launch {
                            pos.snapTo(
                                (pos.value - amount / stepNow)
                                    .coerceIn(-0.4f, (count - 1).coerceAtLeast(0) + 0.4f)
                            )
                        }
                    }
                }
        ) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.fillMaxWidth().height(cardSide)) {
                    for (rel in listOf(-4, 4, -3, 3, -2, 2, -1, 1, 0)) {
                        val i = index + rel
                        val s = songs.getOrNull(i) ?: continue
                        key(s.uri) {
                            Box(
                                Modifier
                                    .align(Alignment.Center)
                                    .graphicsLayer {
                                        val o = i - pos.value
                                        val a = abs(o)
                                        val sc = (1f - 0.17f * minOf(a, 2f)).coerceAtLeast(0.6f)
                                        scaleX = sc
                                        scaleY = sc
                                        // ใบนอก ๆ ขยับเข้ามาชิดกว่า เพราะถูกเอียงและย่อจนดูแคบลง
                                        val dir = if (o < 0f) -1f else 1f
                                        translationX = if (a <= 1f) o * stepNow
                                        else dir * (stepNow + (a - 1f) * stepNow * OUTER_STEP)
                                        rotationY = (o * CARD_TILT).coerceIn(-45f, 45f)
                                        cameraDistance = 16f * dens
                                        alpha = if (a <= 2f) 1f - 0.12f * a else ((3f - a) * 0.76f).coerceAtLeast(0f)
                                    }
                            ) {
                                CoverCard(s, cardSide)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Crossfade(targetState = cur, animationSpec = tween(250), label = "carouselTitle") { s ->
                    if (s != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                s.title,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                style = TextStyle(shadow = textShadow),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = cardSide * 1.8f)
                            )
                            Text(
                                s.artist,
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                                style = TextStyle(shadow = textShadow),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = cardSide * 1.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CoverCard(s: Song, side: Dp) {
    val t = rememberCoverBitmap(s, 480)
    val tintKey = coverKey(s, 480)
    val tint by produceState(ThumbCache.tint(tintKey) ?: Color.White, t) {
        if (t != null) {
            val c = ThumbCache.tint(tintKey)
                ?: withContext(Dispatchers.Default) { dominantTint(t) }?.also { ThumbCache.putTint(tintKey, it) }
            if (c != null) value = c
        }
    }
    val r = side * 0.14f
    Box(
        Modifier
            .size(side)
            .glassTint(r, tint)
            .padding(5.dp)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(r - 5.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF5BA3D0), Color(0xFFF2B38F)))),
            contentAlignment = Alignment.Center
        ) {
            if (t != null) {
                Image(
                    bitmap = t,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("♪", color = Color.White.copy(alpha = 0.7f), fontSize = 32.sp)
            }
        }
    }
}

/* ---------- จุดบอกหน้า ---------- */

@Composable
fun PageDots(count: Int, index: Int) {
    if (count <= 1) return
    val n = minOf(count, 9)
    val act = if (count <= 9) index else index * (n - 1) / (count - 1)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until n) {
            Box(
                Modifier
                    .size(if (i == act) 6.dp else 4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == act) 0.95f else 0.45f))
            )
        }
    }
}

/* ---------- ล่าง: แถบควบคุมกระจก ---------- */

@Composable
fun LandscapeControls(
    song: Song?,
    tint: State<Color>,
    playing: Boolean,
    shownPos: () -> Long,
    durMs: () -> Long,
    frac: () -> Float,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSeekPreview: (Float) -> Unit,
    onSeekCommit: (Float) -> Unit
) {
    val pos = shownPos()
    val dur = durMs()
    Glass(Modifier.fillMaxWidth(), radius = 28.dp, dim = 0.10f, tint = tint.value) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportIcon(0, 16.dp, onPrev)
            TransportIcon(if (playing) 2 else 1, 20.dp, onToggle)
            TransportIcon(3, 16.dp, onNext)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song?.title ?: "",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    song?.artist ?: "",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1.6f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fmtTime(pos),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                SeekBar(frac(), onSeekPreview, onSeekCommit, Modifier.weight(1f))
                Text(
                    if (dur > 0) "-" + fmtTime(dur - pos) else "--:--",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/** kind: 0 = ก่อนหน้า, 1 = เล่น, 2 = หยุด, 3 = ถัดไป */
@Composable
fun TransportIcon(kind: Int, side: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(side + 16.dp).clip(CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(side)) {
            val w = size.width
            val h = size.height
            when (kind) {
                1 -> {
                    val p = Path().apply {
                        moveTo(w * 0.22f, h * 0.08f)
                        lineTo(w * 0.92f, h * 0.5f)
                        lineTo(w * 0.22f, h * 0.92f)
                        close()
                    }
                    drawPath(p, Color.White)
                }
                2 -> {
                    val r = CornerRadius(w * 0.06f)
                    drawRoundRect(Color.White, Offset(w * 0.18f, h * 0.1f), Size(w * 0.24f, h * 0.8f), r)
                    drawRoundRect(Color.White, Offset(w * 0.58f, h * 0.1f), Size(w * 0.24f, h * 0.8f), r)
                }
                3 -> {
                    val p = Path().apply {
                        moveTo(w * 0.08f, h * 0.15f)
                        lineTo(w * 0.68f, h * 0.5f)
                        lineTo(w * 0.08f, h * 0.85f)
                        close()
                    }
                    drawPath(p, Color.White)
                    drawRoundRect(
                        Color.White, Offset(w * 0.76f, h * 0.15f), Size(w * 0.14f, h * 0.7f), CornerRadius(w * 0.05f)
                    )
                }
                else -> {
                    val p = Path().apply {
                        moveTo(w * 0.92f, h * 0.15f)
                        lineTo(w * 0.32f, h * 0.5f)
                        lineTo(w * 0.92f, h * 0.85f)
                        close()
                    }
                    drawPath(p, Color.White)
                    drawRoundRect(
                        Color.White, Offset(w * 0.10f, h * 0.15f), Size(w * 0.14f, h * 0.7f), CornerRadius(w * 0.05f)
                    )
                }
            }
        }
    }
}

@Composable
fun SeekBar(
    frac: Float,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var w by remember { mutableFloatStateOf(1f) }
    var local by remember { mutableFloatStateOf(0f) }
    val previewNow by rememberUpdatedState(onPreview)
    val commitNow by rememberUpdatedState(onCommit)
    Box(
        modifier
            .height(32.dp)
            .padding(horizontal = 8.dp)
            .onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val f = (off.x / w).coerceIn(0f, 1f)
                    previewNow(f)
                    commitNow(f)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { off ->
                        local = (off.x / w).coerceIn(0f, 1f)
                        previewNow(local)
                    },
                    onDragEnd = { commitNow(local) },
                    onDragCancel = { commitNow(local) }
                ) { change, amount ->
                    change.consume()
                    local = (local + amount / w).coerceIn(0f, 1f)
                    previewNow(local)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val th = 4.dp.toPx()
            val f = frac.coerceIn(0f, 1f)
            drawRoundRect(
                Color.White.copy(alpha = 0.3f),
                Offset(0f, cy - th / 2f),
                Size(size.width, th),
                CornerRadius(th / 2f)
            )
            drawRoundRect(
                Color.White,
                Offset(0f, cy - th / 2f),
                Size(size.width * f, th),
                CornerRadius(th / 2f)
            )
            drawCircle(Color.White, 6.dp.toPx(), Offset(size.width * f, cy))
        }
    }
}
