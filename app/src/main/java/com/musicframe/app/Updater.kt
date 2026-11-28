package com.musicframe.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** ข้อมูล Release ล่าสุดบน GitHub */
data class LatestRelease(
    val build: Int,
    val title: String,
    val apkUrl: String,
    val sizeBytes: Long
)

/**
 * ตรวจและติดตั้งอัปเดตจาก GitHub Releases ของ repo นี้
 * Release ถูกตั้งชื่อ tag เป็น "build-เลขรอบ" ซึ่งตรงกับ versionCode ของแอป จึงเทียบเลขกันได้ตรง ๆ
 */
object Updater {
    private const val REPO = "Fennai888/MusicFrame"
    private const val UA = "MusicFrame-updater"

    /** เลขรอบ (versionCode) ของแอปที่ติดตั้งอยู่ */
    fun installedBuild(ctx: Context): Int =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()

    /** ถาม GitHub ว่า Release ล่าสุดคืออะไร คืนค่า null ถ้าตรวจไม่ได้ (ไม่มีเน็ต / ไม่มี Release / ไม่มีไฟล์ APK) */
    suspend fun fetchLatest(): LatestRelease? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", UA)
            }
            if (conn.responseCode != 200) return@withContext null
            val json = JSONObject(conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
            val tag = json.optString("tag_name")
            val build = Regex("""build-(\d+)""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) {
                    return@withContext LatestRelease(
                        build = build,
                        title = json.optString("name").ifBlank { tag },
                        apkUrl = a.optString("browser_download_url"),
                        sizeBytes = a.optLong("size")
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** โหลดไฟล์ APK ไปเก็บในแคชของแอป คืนค่าไฟล์ หรือ null ถ้าโหลดไม่สำเร็จ onProgress ส่งค่า 0..1 */
    suspend fun download(ctx: Context, rel: LatestRelease, onProgress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "updates")
            dir.mkdirs()
            val out = File(dir, "MusicFrame-update.apk")
            val tmp = File(dir, "MusicFrame-update.part")
            var conn: HttpURLConnection? = null
            try {
                tmp.delete()
                conn = (URL(rel.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                }
                if (conn.responseCode != 200) return@withContext null
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: rel.sizeBytes
                var done = 0L
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (total > 0 && done < total) {
                    tmp.delete()
                    return@withContext null
                }
                out.delete()
                if (!tmp.renameTo(out)) return@withContext null
                out
            } catch (e: Exception) {
                tmp.delete()
                null
            } finally {
                conn?.disconnect()
            }
        }

    /** แอปนี้ได้รับอนุญาตให้ติดตั้งแอปจากแหล่งที่ไม่รู้จักแล้วหรือยัง */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** เปิดหน้าตั้งค่า "ติดตั้งแอปที่ไม่รู้จัก" ของแอปนี้ ให้ผู้ใช้กดอนุญาตเอง */
    fun openInstallPermissionSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    /** เปิดหน้าติดตั้งของระบบสำหรับไฟล์ APK ที่โหลดมา */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
