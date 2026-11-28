package com.musicframe.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** แผ่นกระจกตรวจสอบอัปเดต วางไว้ล่างจอ (ใส่ align / padding จากข้างนอก) */
@Composable
fun UpdateSheet(modifier: Modifier, onClose: () -> Unit, backdrop: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { Updater.installedBuild(ctx) }

    // checking / latest / available / downloading / ready / failed / downloadFailed
    var phase by remember { mutableStateOf("checking") }
    var rel by remember { mutableStateOf<LatestRelease?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var apk by remember { mutableStateOf<File?>(null) }
    var waitingPermission by remember { mutableStateOf(false) }

    fun check() {
        phase = "checking"
        scope.launch {
            val r = Updater.fetchLatest()
            if (r == null) {
                phase = "failed"
            } else {
                rel = r
                phase = if (r.build > installed) "available" else "latest"
            }
        }
    }

    fun startDownload() {
        val r = rel ?: return
        phase = "downloading"
        progress = 0f
        scope.launch {
            val f = Updater.download(ctx, r) { progress = it }
            if (f == null) {
                phase = "downloadFailed"
                return@launch
            }
            apk = f
            phase = "ready"
            if (Updater.canInstall(ctx)) {
                Updater.install(ctx, f)
            } else {
                waitingPermission = true
                Updater.openInstallPermissionSettings(ctx)
            }
        }
    }

    LaunchedEffect(Unit) { check() }

    // กลับมาจากหน้าตั้งค่า "ติดตั้งแอปที่ไม่รู้จัก" แล้วอนุญาตแล้ว -> ติดตั้งต่อเลย
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && waitingPermission && Updater.canInstall(ctx)) {
                waitingPermission = false
                apk?.let { Updater.install(ctx, it) }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Glass(modifier = modifier, radius = 32.dp, dim = 0.30f, backdrop = backdrop) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(
                "ตรวจสอบอัปเดต",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
            )
            Text(
                "เวอร์ชันที่ติดตั้ง: Build $installed",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            val r = rel
            val status = when (phase) {
                "checking" -> "กำลังตรวจสอบ..."
                "latest" -> "คุณใช้เวอร์ชันล่าสุดแล้ว"
                "available" -> "มีเวอร์ชันใหม่: Build ${r?.build}" + sizeText(r)
                "downloading" -> "กำลังดาวน์โหลด ${(progress * 100).roundToInt()}%"
                "ready" ->
                    if (waitingPermission) {
                        "ต้องอนุญาตให้ MusicFrame ติดตั้งแอปก่อน กดอนุญาตแล้วกลับมาที่แอปนี้ ระบบจะติดตั้งต่อให้"
                    } else {
                        "ดาวน์โหลดเสร็จแล้ว"
                    }
                "downloadFailed" -> "ดาวน์โหลดไม่สำเร็จ ลองใหม่อีกครั้ง"
                else -> "ตรวจสอบไม่ได้ ตรวจสอบอินเทอร์เน็ตแล้วลองใหม่"
            }
            Text(
                status,
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
            when (phase) {
                "available" -> UpdateRow("ดาวน์โหลดและติดตั้ง") { startDownload() }
                "ready" -> UpdateRow("ติดตั้ง") {
                    val f = apk
                    if (f != null) {
                        if (Updater.canInstall(ctx)) {
                            Updater.install(ctx, f)
                        } else {
                            waitingPermission = true
                            Updater.openInstallPermissionSettings(ctx)
                        }
                    }
                }
                "failed" -> UpdateRow("ลองใหม่") { check() }
                "downloadFailed" -> UpdateRow("ลองใหม่") { startDownload() }
                else -> {}
            }
            UpdateRow("ปิด") { onClose() }
        }
    }
}

private fun sizeText(r: LatestRelease?): String {
    val b = r?.sizeBytes ?: return ""
    if (b <= 0) return ""
    return " (" + (b / (1024 * 1024)) + " MB)"
}

@Composable
private fun UpdateRow(text: String, onClick: () -> Unit) {
    SettingRow(text, Color.White, onClick)
}
