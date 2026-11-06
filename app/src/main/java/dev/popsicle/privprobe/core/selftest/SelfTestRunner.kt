package dev.popsicle.privprobe.core.selftest

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import dev.popsicle.privprobe.core.app.UidResolver
import dev.popsicle.privprobe.core.data.PrivacyRepository
import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 自检：主动触发一次敏感行为，验证「行为发生 → 系统接口可见 → 事件入库」整条链路。
 *
 * 步骤：DNS 查询 → 打开相机 ~1s → 录音 ~1.5s → 请求定位 → 注册加速度计 ~1s → 写/读剪贴板。
 * 所有步骤独立容错；每步产生 source=SELF_TEST 的事件（同时运行中的探针也会抓到系统层事件，
 * 可交叉验证探针是否正常）。自检前通过 root 自动授予运行时权限，避免 UI 未申请权限导致整条链路失败。
 */
class SelfTestRunner(
    private val context: Context,
    private val repository: PrivacyRepository,
) {

    data class Step(val name: String, val ok: Boolean, val detail: String)

    data class Report(val steps: List<Step>) {
        val success: Boolean get() = steps.all { it.ok }
        fun summary(): String = steps.joinToString("；") { "${it.name}=${if (it.ok) "OK" else "失败"}(${it.detail})" }
    }

    suspend fun run(): Report = withContext(Dispatchers.IO) {
        val steps = ArrayList<Step>()
        // 不通过 root 强授任何权限：UI 必须先取得用户对相机/麦克风/定位的运行时同意。
        val permissions = permissionsStep()
        steps.add(permissions)
        steps.add(runCatchingStep("dns") { dnsStep() })
        steps.add(runCatchingStep("camera") { cameraStep() })
        steps.add(runCatchingStep("microphone") { microphoneStep() })
        steps.add(runCatchingStep("location") { locationStep() })
        steps.add(runCatchingStep("sensor") { sensorStep() })
        steps.add(runCatchingStep("clipboard") { clipboardStep() })
        val report = Report(steps)
        Log.i(TAG, "self-test: ${report.summary()}")
        report
    }

    // ----------------------------------------------------------------- steps

    private fun permissionsStep(): Step {
        val camera = hasRuntimePermission(Manifest.permission.CAMERA)
        val mic = hasRuntimePermission(Manifest.permission.RECORD_AUDIO)
        val location = hasRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasRuntimePermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val missing = buildList {
            if (!camera) add("相机")
            if (!mic) add("录音")
            if (!location) add("定位")
        }
        return if (missing.isEmpty()) {
            Step("permissions", true, "相机/录音/定位权限已由用户授予")
        } else {
            Step("permissions", false, "缺少权限：${missing.joinToString("、")}（自检已跳过对应步骤）")
        }
    }

    private suspend fun runCatchingStep(name: String, block: suspend () -> Step): Step {
        return try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "self-test step $name failed", t)
            Step(name, false, t.message ?: t.javaClass.simpleName)
        }
    }

    private suspend fun dnsStep(): Step {
        val domain = "example.com"
        emit(
            category = PrivacyCategory.NETWORK,
            subType = "selftest_dns",
            target = domain,
            detail = "自检发起 DNS 查询：$domain",
            confidence = 100,
        )
        val addrs = runCatching { InetAddress.getAllByName(domain) }.getOrElse { emptyArray() }
        val detail = if (addrs.isNotEmpty()) {
            "解析成功：${addrs.first().hostAddress}（${addrs.size} 个地址）"
        } else {
            "解析失败（无网络？）"
        }
        return Step("dns", addrs.isNotEmpty(), detail)
    }

    private fun hasRuntimePermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun cameraStep(): Step {
        if (!hasRuntimePermission(Manifest.permission.CAMERA)) {
            return Step("camera", false, "未授予 CAMERA 权限（请在系统设置中允许）")
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = runCatching { manager.cameraIdList.firstOrNull() }.getOrNull()
            ?: return Step("camera", false, "无可用摄像头")
        val opened = CountDownLatch(1)
        var device: CameraDevice? = null
        val handler = Handler(Looper.getMainLooper())
        var error: String? = null
        try {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        opened.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        opened.countDown()
                        error = "摄像头被断开"
                    }

                    override fun onError(camera: CameraDevice, code: Int) {
                        camera.close()
                        opened.countDown()
                        error = "摄像头错误 code=$code"
                    }
                },
                handler,
            )
            val ok = opened.await(4, TimeUnit.SECONDS)
            if (!ok || device == null) return Step("camera", false, error ?: "打开超时")
            emit(
                category = PrivacyCategory.CAMERA,
                subType = "selftest_camera",
                target = "camera $cameraId",
                detail = "自检打开摄像头 $cameraId 约 1.2 秒",
                duration = 1_200L,
                confidence = 100,
            )
            delay(1_200L)
            return Step("camera", true, "摄像头 $cameraId 打开成功")
        } finally {
            runCatching { device?.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun microphoneStep(): Step {
        if (!hasRuntimePermission(Manifest.permission.RECORD_AUDIO)) {
            return Step("microphone", false, "未授予 RECORD_AUDIO 权限")
        }
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return Step("microphone", false, "设备不支持 16k 单声道录音")
        val bufferSize = maxOf(minBuffer, sampleRate)
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }.getOrElse { return Step("microphone", false, "AudioRecord 创建失败：${it.message}") }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Step("microphone", false, "AudioRecord 未初始化")
        }
        var read = 0L
        try {
            record.startRecording()
            emit(
                category = PrivacyCategory.MICROPHONE,
                subType = "selftest_record",
                target = "mic:MIC",
                detail = "自检录音 1.5 秒（16kHz 单声道）",
                duration = 1_500L,
                confidence = 100,
            )
            val buf = ShortArray(sampleRate / 2)
            val deadline = System.currentTimeMillis() + 1_500L
            while (System.currentTimeMillis() < deadline) {
                read += record.read(buf, 0, buf.size).coerceAtLeast(0)
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        return Step("microphone", read > 0, "读取 ${read} 个采样点")
    }

    @SuppressLint("MissingPermission")
    private suspend fun locationStep(): Step {
        if (!hasRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasRuntimePermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return Step("location", false, "未授予定位权限")
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
        for (provider in providers) {
            val available = runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
            if (!available) continue
            val latch = CountDownLatch(1)
            var location: Location? = null
            try {
                lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                    location = loc
                    latch.countDown()
                }
                latch.await(6, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                Log.w(TAG, "location provider $provider failed", t)
            }
            emit(
                category = PrivacyCategory.LOCATION,
                subType = "selftest_location",
                target = "location:$provider",
                detail = "自检通过 $provider 请求一次定位" +
                    (location?.let { "，结果 ${it.latitude},${it.longitude}" } ?: "，未在超时内返回结果"),
                confidence = 95,
            )
            return Step("location", true, "provider=$provider")
        }
        // 没有可用 provider 也发一条，证明自检链路本身通
        emit(
            category = PrivacyCategory.LOCATION,
            subType = "selftest_location_unavailable",
            target = "location:none",
            detail = "设备当前无可用定位 provider（定位开关关闭）",
            confidence = 90,
        )
        return Step("location", false, "无可用定位 provider")
    }

    private suspend fun sensorStep(): Step {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return Step("sensor", false, "无加速度计")
        val latch = CountDownLatch(1)
        val got = AtomicBoolean(false)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                got.set(true)
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        try {
            val registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            if (!registered) return Step("sensor", false, "注册加速度计失败")
            emit(
                category = PrivacyCategory.SENSOR,
                subType = "selftest_sensor",
                target = sensor.name,
                detail = "自检注册加速度计约 1 秒",
                duration = 1_000L,
                confidence = 100,
            )
            latch.await(1_200L, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { sm.unregisterListener(listener) }
        }
        return Step("sensor", true, "加速度计 ${sensor.name} 注册成功，收到事件=${got.get()}")
    }

    private suspend fun clipboardStep(): Step {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val payload = "privprobe-selftest-${System.currentTimeMillis()}"
        cm.setPrimaryClip(ClipData.newPlainText("privprobe-selftest", payload))
        emit(
            category = PrivacyCategory.CLIPBOARD,
            subType = "selftest_clipboard_write",
            target = "clipboard:write",
            detail = "自检写入剪贴板（${payload.length} 字符）",
            direction = "write",
            confidence = 100,
        )
        val read = runCatching { cm.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
        emit(
            category = PrivacyCategory.CLIPBOARD,
            subType = "selftest_clipboard_read",
            target = "clipboard:read",
            detail = "自检读取剪贴板" + (read?.let { "：${it.take(32)}" } ?: "（读取失败）"),
            direction = "read",
            confidence = 100,
        )
        return Step("clipboard", read != null, "剪贴板读写" + if (read != null) "成功" else "失败")
    }

    // ---------------------------------------------------------------- helper

    private suspend fun emit(
        category: PrivacyCategory,
        subType: String,
        target: String?,
        detail: String,
        confidence: Int,
        duration: Long? = null,
        direction: String? = null,
    ) {
        val uid = Process.myUid()
        val pkg = context.packageName
        val event = PrivacyEvent(
            ts = System.currentTimeMillis(),
            category = category,
            subType = subType,
            uid = uid,
            pid = Process.myPid(),
            packageName = pkg,
            appLabel = UidResolver.labelFor(pkg, uid),
            target = target,
            detail = detail,
            source = EventSource.SELF_TEST,
            confidence = confidence,
            durationMs = duration,
            direction = direction,
        )
        runCatching { repository.insertEvents(listOf(event)) }
            .onFailure { Log.w(TAG, "emit selftest event failed", it) }
    }

    companion object {
        private const val TAG = "SelfTestRunner"
    }
}
