package com.musicframe.app

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Song(val uri: String, val title: String, val artist: String, val album: String)

object Store {
    fun load(c: Context): List<Song> {
        val s = c.getSharedPreferences("songs", 0).getString("list", "[]") ?: "[]"
        val a = JSONArray(s)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Song(o.getString("uri"), o.getString("title"), o.getString("artist"), o.getString("album"))
        }
    }

    fun save(c: Context, l: List<Song>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("uri", it.uri).put("title", it.title).put("artist", it.artist).put("album", it.album))
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

@Composable
fun App() {
    val ctx = LocalContext.current
    var songs by remember { mutableStateOf(Store.load(ctx)) }
    var playingIndex by remember { mutableStateOf<Int?>(null) }
    var bgUri by remember { mutableStateOf(ctx.getSharedPreferences("settings", 0).getString("bg", null)) }
    var showSettings by remember { mutableStateOf(false) }

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

    Box(Modifier.fillMaxSize()) {
        HomeBackground(bgUri)
        if (songs.isEmpty()) {
            EmptyCard { picker.launch(arrayOf("audio/*")) }
        } else {
            Library(songs) { playingIndex = it }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(24.dp)
                    .size(64.dp)
                    .glass(32.dp)
                    .clickable { picker.launch(arrayOf("audio/*")) },
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = Color.White, fontSize = 32.sp)
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .size(44.dp)
                .background(Color.Black.copy(alpha = 0.2f), CircleShape)
                .glass(22.dp)
                .clickable { showSettings = true },
            contentAlignment = Alignment.Center
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
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(32.dp))
                    .glass(32.dp)
                    .padding(vertical = 8.dp)
            ) {
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
        playingIndex?.let { i ->
            PlayerScreen(songs, i, onClose = { playingIndex = null })
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
fun HomeBackground(uri: String?) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            try {
                val src = ImageDecoder.createSource(ctx.contentResolver, Uri.parse(uri))
                ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    val m = maxOf(info.size.width, info.size.height)
                    if (m > 2400) dec.setTargetSampleSize((m / 2400) + 1)
                }.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b,
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
        Column(
            Modifier
                .fillMaxWidth()
                .glass(40.dp)
                .clickable { onClick() }
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
    Column(Modifier.fillMaxWidth().glass(24.dp).clickable { onClick() }.padding(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.85f)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF5BA3D0), Color(0xFFF2B38F)))),
            contentAlignment = Alignment.Center
        ) {
            Text("♪", color = Color.White, fontSize = 40.sp)
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
