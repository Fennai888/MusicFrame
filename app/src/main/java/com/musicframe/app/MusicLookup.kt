package com.musicframe.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** ผลลัพธ์หนึ่งรายการจาก MusicBrainz */
data class MbHit(
    val title: String,
    val artist: String,
    val album: String,
    val year: String,
    val releaseIds: List<String>,
    val groupId: String
)

data class MbSearchResult(val hits: List<MbHit>, val failed: Boolean)

/**
 * ค้นหาข้อมูลเพลงจาก MusicBrainz และปกจาก Cover Art Archive (ทั้งคู่เป็นบริการเปิด ใช้ได้ฟรี)
 * MusicBrainz ขอให้แอปบอกชื่อ + ช่องทางติดต่อใน User-Agent ถ้าจะให้ดี ใส่อีเมลหรือลิงก์ repo ของคุณใน UA ด้านล่าง
 * และห้ามยิงเกินราว 1 คำขอต่อวินาที (โค้ดนี้เว้นช่วงให้แล้ว)
 */
object MusicLookup {
    private const val UA = "MusicFrame/0.1 (personal Android app)"

    private val bracket = Regex("""\([^)]*\)|\[[^\]]*\]|\{[^}]*\}""")
    private val noise = Regex(
        """(?i)\b(super slowed|ultra slowed|slowed|reverb|sped up|speed up|bass boosted|nightcore|8d audio|official audio|official video|official music video|lyrics|lyric video|audio|visualizer)\b"""
    )
    private val topic = Regex("""(?i)\s*-\s*topic\b""")
    private val vevo = Regex("""(?i)vevo\b""")
    private val spaces = Regex("""\s+""")
    private val special = Regex("""[+\-&|!(){}\[\]^"~*?:\\/]""")

    fun cleanTitle(raw: String): String {
        var t = bracket.replace(raw, " ")
        t = noise.replace(t, " ")
        t = topic.replace(t, " ")
        t = spaces.replace(t, " ").trim()
        return t.trim('-', ' ', '|')
    }

    fun cleanArtist(raw: String): String {
        var t = topic.replace(raw, " ")
        t = vevo.replace(t, " ")
        t = bracket.replace(t, " ")
        t = spaces.replace(t, " ").trim()
        return t.trim('-', ' ', '|')
    }

    private fun quoted(s: String) = s.replace("\"", " ").replace("\\", " ").trim()

    private fun structured(title: String, artist: String): String =
        if (artist.isBlank()) {
            "recording:\"${quoted(title)}\""
        } else {
            "recording:\"${quoted(title)}\" AND artist:\"${quoted(artist)}\""
        }

    private fun free(text: String): String = spaces.replace(special.replace(text, " "), " ").trim()

    private fun httpGet(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, image/*")
            }
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(text: String): List<MbHit> {
        val out = ArrayList<MbHit>()
        val arr = try {
            JSONObject(text).optJSONArray("recordings")
        } catch (e: Exception) {
            null
        } ?: return out
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val title = r.optString("title")
            val credit = r.optJSONArray("artist-credit")
            val artist = buildString {
                if (credit != null) {
                    for (j in 0 until credit.length()) {
                        val c = credit.optJSONObject(j) ?: continue
                        append(c.optString("name"))
                        append(c.optString("joinphrase"))
                    }
                }
            }.trim()
            val rels = r.optJSONArray("releases")
            val ids = ArrayList<String>()
            var album = ""
            var year = ""
            var group = ""
            if (rels != null) {
                // เอารายการที่เป็น Official ขึ้นก่อน
                val list = (0 until rels.length()).mapNotNull { rels.optJSONObject(it) }
                    .sortedBy { if (it.optString("status") == "Official") 0 else 1 }
                for (rel in list.take(5)) {
                    val id = rel.optString("id")
                    if (id.isNotBlank()) ids.add(id)
                }
                val first = list.firstOrNull()
                if (first != null) {
                    album = first.optString("title")
                    year = first.optString("date").take(4)
                    group = first.optJSONObject("release-group")?.optString("id") ?: ""
                }
            }
            if (title.isNotBlank()) out.add(MbHit(title, artist, album, year, ids, group))
        }
        return out.distinctBy { it.title + "|" + it.artist + "|" + it.album }.take(10)
    }

    private fun runQuery(query: String): List<MbHit>? {
        val url = "https://musicbrainz.org/ws/2/recording?query=" +
            URLEncoder.encode(query, "UTF-8") + "&fmt=json&limit=12"
        val bytes = httpGet(url) ?: return null
        return parse(String(bytes, Charsets.UTF_8))
    }

    suspend fun search(rawTitle: String, rawArtist: String): MbSearchResult = withContext(Dispatchers.IO) {
        var title = cleanTitle(rawTitle)
        var artist = cleanArtist(rawArtist)
        val attempts = ArrayList<String>()
        // รูปแบบ "ศิลปิน - ชื่อเพลง" ที่เจอบ่อย
        val dash = title.indexOf(" - ")
        if (dash > 0) {
            val left = title.substring(0, dash).trim()
            val right = title.substring(dash + 3).trim()
            if (left.isNotBlank() && right.isNotBlank()) attempts.add(structured(right, left))
            title = left.ifBlank { title }
        }
        if (title.isNotBlank()) attempts.add(structured(title, artist))
        val f = free("$title $artist")
        if (f.isNotBlank()) attempts.add(f)

        var failed = false
        var hits: List<MbHit> = emptyList()
        for ((n, q) in attempts.distinct().withIndex()) {
            if (n > 0) delay(1100)
            val r = runQuery(q)
            if (r == null) {
                failed = true
                continue
            }
            if (r.isNotEmpty()) {
                hits = r
                failed = false
                break
            }
        }
        MbSearchResult(hits, failed && hits.isEmpty())
    }

    /** โหลดรูปปกหน้า (front) ของรายการนี้ size = 250 / 500 / 1200 ; ไม่มีรูปจะคืน null */
    fun fetchCoverBytes(hit: MbHit, size: Int): ByteArray? {
        for (id in hit.releaseIds) {
            httpGet("https://coverartarchive.org/release/$id/front-$size")?.let { return it }
        }
        if (hit.groupId.isNotBlank()) {
            httpGet("https://coverartarchive.org/release-group/${hit.groupId}/front-$size")?.let { return it }
        }
        return null
    }

    /** โหลดปกมาเก็บในเครื่อง คืนค่า file:// uri สำหรับใส่ในข้อมูลเพลง หรือ null ถ้าไม่มีปก */
    fun saveCover(ctx: Context, hit: MbHit): String? {
        var bytes = fetchCoverBytes(hit, 500) ?: return null
        // ลองเอาไฟล์ใหญ่กว่า ถ้าไม่มีก็ใช้ 500
        val big = fetchCoverBytes(hit, 1200)
        if (big != null) bytes = big
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bo)
        if (bo.outWidth <= 0 || bo.outHeight <= 0) return null
        return try {
            val dir = File(ctx.filesDir, "covers")
            dir.mkdirs()
            val key = hit.releaseIds.firstOrNull() ?: hit.groupId.ifBlank { hit.title.hashCode().toString() }
            val f = File(dir, "mb_$key.jpg")
            f.writeBytes(bytes)
            Uri.fromFile(f).toString()
        } catch (e: Exception) {
            null
        }
    }
}

private val thumbCache = LruCache<String, ImageBitmap>(60)

@Composable
private fun HitThumb(hit: MbHit, size: Dp) {
    val key = hit.releaseIds.firstOrNull() ?: hit.groupId
    val img by produceState<ImageBitmap?>(if (key.isBlank()) null else thumbCache.get(key), key) {
        if (value == null && key.isNotBlank()) {
            val bmp = withContext(Dispatchers.IO) {
                MusicLookup.fetchCoverBytes(hit, 250)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            if (bmp != null) {
                val ib = bmp.asImageBitmap()
                thumbCache.put(key, ib)
                value = ib
            }
        }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        val b = img
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("♪", color = Color.White.copy(alpha = 0.6f), fontSize = 18.sp)
        }
    }
}

/** แผ่นกระจกค้นหาข้อมูลเพลง วางไว้ล่างจอ (ใส่ align / padding จากข้างนอก) */
@Composable
fun MusicSearchSheet(
    modifier: Modifier,
    initialTitle: String,
    initialArtist: String,
    applyTitle: Boolean,
    onToggleApplyTitle: () -> Unit,
    onPick: (MbHit, String?) -> Unit,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var qTitle by remember { mutableStateOf(initialTitle) }
    var qArtist by remember { mutableStateOf(initialArtist) }
    var loading by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<MbHit>>(emptyList()) }

    fun runSearch() {
        if (loading || picking) return
        loading = true
        failed = false
        scope.launch {
            val r = MusicLookup.search(qTitle, qArtist)
            hits = r.hits
            failed = r.failed
            searched = true
            loading = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { runSearch() }

    Glass(modifier = modifier, radius = 32.dp, dim = 0.35f) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("ค้นหาข้อมูลเพลง", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            GlassField(qTitle, { qTitle = it }, "ชื่อเพลง", 14.sp, true)
            Spacer(Modifier.height(8.dp))
            GlassField(qArtist, { qArtist = it }, "ชื่อศิลปิน", 13.sp, false)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "อัปเดตชื่อเพลงด้วย: " + (if (applyTitle) "เปิด" else "ปิด") + " (แตะเพื่อสลับ)",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onToggleApplyTitle() }
                        .padding(vertical = 8.dp)
                )
                Text(
                    "ค้นหา",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { runSearch() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
                Text(
                    "ปิด",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable { onClose() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
            val status = when {
                picking -> "กำลังโหลดปก..."
                loading -> "กำลังค้นหา..."
                failed -> "ค้นหาไม่ได้ ตรวจสอบอินเทอร์เน็ตแล้วลองใหม่"
                searched && hits.isEmpty() -> "ไม่พบผลลัพธ์ ลองแก้ชื่อให้สั้นลง หรือใส่แค่ชื่อเพลง"
                else -> ""
            }
            if (status.isNotEmpty()) {
                Text(status, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                itemsIndexed(hits) { _, hit ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                if (picking) return@clickable
                                picking = true
                                scope.launch {
                                    val uri = withContext(Dispatchers.IO) { MusicLookup.saveCover(ctx, hit) }
                                    onPick(hit, uri)
                                    picking = false
                                }
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        HitThumb(hit, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                hit.title,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                listOf(hit.artist, hit.album, hit.year).filter { it.isNotBlank() }.joinToString(" · "),
                                color = Color.White.copy(alpha = 0.65f),
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
