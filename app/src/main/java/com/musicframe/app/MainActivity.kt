package com.musicframe.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Song(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val cover: String = ""
)

object Store {
    fun load(c: Context): List<Song> {
        val s = c.getSharedPreferences("songs", 0).getString("list", "[]") ?: "[]"
        val a = JSONArray(s)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Song(
                o.getString("uri"),
                o.getString("title"),
                o.getString("artist"),
                o.getString("album"),
                o.optString("cover", "")
            )
        }
    }

    fun save(c: Context, l: List<Song>) {
        val a = JSONArray()
        l.forEach {
            a.put(
                JSONObject()
                    .put("uri", it.uri)
                    .put("title", it.title)
                    .put("artist", it.artist)
                    .put("album", it.album)
                    .put("cover", it.cover)
            )
        }
        c.getSharedPreferences("songs", 0).edit().putString("list", a.toString()).apply()
    }
}

fun readSong(c: Context, uri: Uri): Song {
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(c, uri)
        title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
    } catch (e: Exception) {
    } finally {
        r.release()
    }
    if (title.isNullOrBlank()) {
        c.contentResolver.query(uri, null, null, null, null)?.use { q ->
            if (q.moveToFirst()) {
                val i = q.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) title = q.getString(i)?.substringBeforeLast('.')
            }
        }
    }
    return Song(uri.toString(), title ?: "ไม่มีชื่อเพลง", artist ?: "", album ?: "")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

fun Modifier.glass(r: Dp): Modifier = this
    .clip(RoundedCornerShape(r))
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.12f))))
    .border(
        1.5.dp,
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.15f))),
        RoundedCornerShape(r)
    )

val LocalSoftBg = compositionLocalOf<ImageBitmap?> { null }
val LocalScreenSize = compositionLocalOf { IntSize.Zero }

data class BgImages(val full: ImageBitmap, val soft: ImageBitmap)

fun softBlur(src: Bitmap): Bitmap {
    var b = src
    while (b.width > 160 && b.height > 160) {
        b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
    }
    return b
}

@Composable
fun rememberBackground(uri: String?): State<BgImages?> {
    val ctx = LocalContext.current
    return produceState<BgImages?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            try {
                val src = ImageDecoder.createSource(ctx.contentResolver, Uri.parse(uri))
                val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val m = maxOf(info.size.width, info.size.height)
                    if (m > 2400) dec.setTargetSampleSize((m / 2400) + 1)
                }
                BgImages(bmp.asImageBitmap(), softBlur(bmp).asImageBitmap())
            } catch (e: Exception) {
                null
            }
        }
    }
}

@Composable
fun Glass(
    modifier: Modifier = Modifier,
    radius: Dp,
    contentAlignment: Alignment = Alignment.TopStart,
    dim: Float = 0f,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val soft = LocalSoftBg.current
    val screen = LocalScreenSize.current
    val density = LocalDensity.current
    var pos by remember { mutableStateOf(IntOffset.Zero) }
    val shape = RoundedCornerShape(radius)
    val hasBlur = soft != null && screen.width > 0
    val dark = dim > 0f
    val a1 = if (dark) 0.08f else if (hasBlur) 0.22f else 0.30f
    val a2 = if (dark) 0.03f else if (hasBlur) 0.08f else 0.12f
    Box(
        modifier
            .onGloballyPositioned { pos = it.positionInRoot().round() }
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = contentAlignment
    ) {
        if (soft != null && screen.width > 0) {
            Box(Modifier.matchParentSize()) {
                Image(
                    bitmap = soft,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    colorFilter = ColorFilter.tint(Color.Black.copy(alpha = 0.12f), BlendMode.SrcOver),
                    modifier = Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .offset { IntOffset(-pos.x, -pos.y) }
                        .requiredSize(
                            with(density) { screen.width.toDp() },
                            with(density) { screen.height.toDp() }
                        )
                )
            }
        }
        if (dim > 0f) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = dim)))
        }
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = a1), Color.White.copy(alpha = a2))))
        )
        content()
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var songs by remember { mutableStateOf(Store.load(ctx)) }
    var playingIndex by remember { mutableStateOf<Int?>(null) }
    var bgUri by remember { mutableStateOf(ctx.getSharedPreferences("settings", 0).getString("bg", null)) }
    var showSettings by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    val bg by rememberBackground(bgUri)

    fun saveBg(v: String?) {
        ctx.getSharedPreferences("settings", 0).edit().putString("bg", v).apply()
        bgUri = v
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val added = uris.map { u ->
            try {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            readSong(ctx, u)
        }.filter { n -> songs.none { it.uri == n.uri } }
        if (added.isNotEmpty()) {
            songs = songs + added
            Store.save(ctx, songs)
        }
    }
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            saveBg(u.toString())
        }
    }
    BackHandler(enabled = showSettings) { showSettings = false }

    CompositionLocalProvider(
        LocalSoftBg provides bg?.soft,
        LocalScreenSize provides screen
    ) {
        Box(Modifier.fillMaxSize().onSizeChanged { screen = it }) {
            HomeBackground(bg)
            if (songs.isEmpty()) {
                EmptyCard { picker.launch(arrayOf("audio/*")) }
            } else {
                Library(songs) { playingIndex = it }
                Glass(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(24.dp)
                        .size(64.dp),
                    radius = 32.dp,
                    contentAlignment = Alignment.Center,
                    onClick = { picker.launch(arrayOf("audio/*")) }
                ) {
                    Text("+", color = Color.White, fontSize = 32.sp)
                }
            }
            Glass(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(44.dp),
                radius = 22.dp,
                contentAlignment = Alignment.Center,
                dim = 0.2f,
                onClick = { showSettings = true }
            ) {
                Text("⚙", color = Color.White, fontSize = 20.sp)
            }
            if (showSettings) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .pointerInput(Unit) { detectTapGestures { showSettings = false } }
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
                        SettingRow("เลือกรูปพื้นหลัง") {
                            showSettings = false
                            bgPicker.launch(arrayOf("image/*"))
                        }
                        if (bgUri != null) {
                            SettingRow("ใช้พื้นหลังเดิม") {
                                showSettings = false
                                saveBg(null)
                            }
                        }
                    }
                }
            }
            playingIndex?.let { i ->
                PlayerScreen(
                    songs,
                    i,
                    onUpdate = { idx, sg ->
                        songs = songs.toMutableList().also { it[idx] = sg }
                        Store.save(ctx, songs)
                    },
                    onClose = { playingIndex = null }
                )
            }
        }
    }
}

@Composable
fun SettingRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 16.dp)
    )
}

@Composable
fun HomeBackground(images: BgImages?) {
    if (images != null) {
        Image(
            bitmap = images.full,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)))
    } else {
        Backdrop()
    }
}

@Composable
fun Backdrop() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0F3B63), Color(0xFF2A7FB8), Color(0xFFE8A27C))))
    )
    Box(Modifier.fillMaxSize().blur(80.dp)) {
        Box(
            Modifier
                .offset(x = (-60).dp, y = 120.dp)
                .size(260.dp)
                .background(Color.White.copy(alpha = 0.45f), CircleShape)
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 50.dp, y = (-140).dp)
                .size(300.dp)
                .background(Color(0xFFFFC9A3).copy(alpha = 0.6f), CircleShape)
        )
    }
}

@Composable
fun EmptyCard(onClick: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Glass(Modifier.fillMaxWidth(), radius = 40.dp, onClick = onClick) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("♪", color = Color.White, fontSize = 48.sp)
                Spacer(Modifier.height(12.dp))
                Text("เพิ่มเพลงแรก", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "เลือกไฟล์ .mp3 จากเครื่อง",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun Library(songs: List<Song>, onPlay: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "เพลงของฉัน",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(songs) { i, s -> MiniCard(s) { onPlay(i) } }
        }
    }
}

@Composable
fun MiniCard(s: Song, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, s.uri, s.cover) {
        value = withContext(Dispatchers.IO) { Covers.load(ctx, s, 600)?.asImageBitmap() }
    }
    val t = thumb
    Glass(Modifier.fillMaxWidth(), radius = 24.dp, onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.85f)
                    .clip(RoundedCornerShape(18.dp))
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
                    Text("♪", color = Color.White, fontSize = 40.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.25f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    s.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (s.artist.isBlank()) "ไม่ทราบศิลปิน" else s.artist,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
