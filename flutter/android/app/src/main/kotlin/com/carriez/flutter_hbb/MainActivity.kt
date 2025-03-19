package com.carriez.flutter_hbb

/**
 * Handle events from flutter
 * Request system permissions for screen capturing
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import ffi.FFI

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.DisplayMetrics
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import com.hjq.permissions.XXPermissions
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.concurrent.thread
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.content.IntentFilter

class MainActivity : FlutterActivity() {
    companion object {
        var flutterMethodChannel: MethodChannel? = null
        private var _rdClipboardManager: RdClipboardManager? = null
        val rdClipboardManager: RdClipboardManager?
            get() = _rdClipboardManager;
        
        // 系统级权限常量字符串
        const val PERMISSION_CAPTURE_VIDEO_OUTPUT = "android.permission.CAPTURE_VIDEO_OUTPUT"
        const val PERMISSION_READ_FRAME_BUFFER = "android.permission.READ_FRAME_BUFFER"
        const val PERMISSION_ACCESS_SURFACE_FLINGER = "android.permission.ACCESS_SURFACE_FLINGER"
        const val READ_PHONE_STATE = "android.permission.READ_PHONE_STATE"
    }

    private val channelTag = "mChannel"
    private val logTag = "mMainActivity"
    private var mainService: MainService? = null
    private var handler: Handler? = null

    private var isAudioStart = false
    private val audioRecordHandle = AudioRecordHandle(this, { false }, { isAudioStart })

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        if (MainService.isReady) {
            Intent(activity, MainService::class.java).also {
                bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
        flutterMethodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            channelTag
        )
        initFlutterChannel(flutterMethodChannel!!)
        thread { setCodecInfo() }
    }

    override fun onResume() {
        super.onResume()
        val inputPer = InputService.isOpen
        activity.runOnUiThread {
            flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to inputPer.toString())
            )
        }
    }

    // 检查系统级权限
    private fun checkSystemPermissions(): Boolean {
        // 优先检查 ACCESS_SURFACE_FLINGER 权限
        val accessSurfaceFlingerPermission = checkCallingOrSelfPermission(PERMISSION_ACCESS_SURFACE_FLINGER)
        val hasSurfaceFlingerPermission = accessSurfaceFlingerPermission == PackageManager.PERMISSION_GRANTED
        
        // 然后检查其他权限
        val captureVideoPermission = checkCallingOrSelfPermission(PERMISSION_CAPTURE_VIDEO_OUTPUT)
        val readFrameBufferPermission = checkCallingOrSelfPermission(PERMISSION_READ_FRAME_BUFFER)
        val hasOtherPermissions = captureVideoPermission == PackageManager.PERMISSION_GRANTED && 
                                readFrameBufferPermission == PackageManager.PERMISSION_GRANTED
        
        // 返回结果，只要有一种方式能用就可以
        return hasSurfaceFlingerPermission || hasOtherPermissions
    }
    
    // 显示缺少权限的弹窗提醒
    private fun showPermissionRequiredDialog() {
        try {
            val dialogBuilder = android.app.AlertDialog.Builder(this)
                .setTitle("权限不足")
                .setMessage("你的设备无权限，请联系你的服务商后台给予授权。")
                .setCancelable(false)
                .setPositiveButton("确定") { dialog, _ -> 
                    dialog.dismiss()
                }
            
            // 在UI线程显示弹窗
            runOnUiThread {
                val dialog = dialogBuilder.create()
                dialog.show()
            }
            
            // 同时通知Flutter层权限问题
            Handler(Looper.getMainLooper()).post {
                flutterMethodChannel?.invokeMethod(
                    "on_permission_error",
                    mapOf("message" to "系统权限不足，无法启动服务")
                )
            }
        } catch (e: Exception) {
            Log.e(logTag, "显示权限提醒弹窗失败: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (_rdClipboardManager == null) {
            _rdClipboardManager = RdClipboardManager(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            FFI.setClipboardManager(_rdClipboardManager!!)
        }
        
        handler = Handler(Looper.getMainLooper())
        
        // 应用启动时检查系统级权限状态并通知Flutter端
        if (checkSystemPermissions()) {
            Log.d(logTag, "系统级权限已预授权")
            
            // 检查是否有ACCESS_SURFACE_FLINGER权限
            val accessSurfaceFlingerPermission = checkCallingOrSelfPermission(PERMISSION_ACCESS_SURFACE_FLINGER)
            val hasSurfaceFlingerPermission = accessSurfaceFlingerPermission == PackageManager.PERMISSION_GRANTED
            
            if (hasSurfaceFlingerPermission) {
                // 使用自定义Toast显示"已就绪"提示
                try {
                    showReadyToast() // 替换为直接实现
                } catch (e: Exception) {
                    // 如果自定义Toast失败，回退到标准Toast
                    val toast = android.widget.Toast.makeText(
                        this,
                        "已就绪",
                        android.widget.Toast.LENGTH_SHORT
                    )
                    toast.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 0, 100)
                    toast.show()
                }
            }
            
            flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to "true")
            )
        } else {
            Log.d(logTag, "系统级权限未授权")
            // 显示缺少权限的提醒弹窗
            showPermissionRequiredDialog()
            
            // 通知Flutter层权限不足
            flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to "false")
            )
        }
    }

    // 添加简单的showReadyToast方法代替ToastUtils
    private fun showReadyToast() {
        val toast = android.widget.Toast.makeText(
            this,
            "已就绪",
            android.widget.Toast.LENGTH_SHORT
        )
        toast.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 0, 100)
        toast.show()
    }

    override fun onDestroy() {
        Log.e(logTag, "onDestroy")
        mainService?.let {
            unbindService(serviceConnection)
        }
        super.onDestroy()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(logTag, "onServiceConnected")
            val binder = service as MainService.LocalBinder
            mainService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(logTag, "onServiceDisconnected")
            mainService = null
        }
    }

    // 修改initFlutterChannel方法中处理MediaProjection的部分
    private fun initFlutterChannel(messenger: MethodChannel) {
        // 从信使通道接收方法调用
        messenger.setMethodCallHandler { call, result ->
            when (call.method) {
                // 系统级权限相关
                "check_permission" -> {
                    val type = call.argument<String>("type")
                    if (type != null) {
                        when (type) {
                            "video" -> {
                                // 检查系统级权限状态
                                val hasPermission = checkSystemPermissions()
                                if (!hasPermission) {
                                    // 如果没有权限，显示提醒弹窗
                                    // 注意：这里仅检查权限，不直接显示弹窗
                                    // showPermissionRequiredDialog()
                                }
                                result.success(hasPermission)
                            }
                            else -> {
                                result.error(type, "not support", null)
                            }
                        }
                    }
                }
                
                "start_service" -> {
                    try {
                        // 获取可选的ignorePermission参数，默认为false
                        val ignorePermission = call.argument<Boolean>("ignorePermission") ?: false
                        
                        // 先检查权限，没有权限的处理逻辑
                        if (!checkSystemPermissions() && !ignorePermission) {
                            showPermissionRequiredDialog()
                            result.success(false)
                            return@setMethodCallHandler
                        }
                        
                        // 有权限或强制启动模式，直接启动服务
                        val intent = Intent(context, MainService::class.java)
                        intent.action = ACT_INIT_SERVICE
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("start_service", e.toString(), e.message)
                    }
                }
                
                "stop_service" -> {
                    val intent = Intent(context, MainService::class.java)
                    stopService(intent)
                    result.success(true)
                }
                "start_capture" -> {
                    mainService?.let {
                        result.success(it.startCapture())
                    } ?: let {
                        result.success(false)
                    }
                }
                "check_video_permission" -> {
                    mainService?.let {
                        result.success(it.checkMediaPermission())
                    } ?: let {
                        result.success(false)
                    }
                }
                "check_service" -> {
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                    )
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "media", "value" to MainService.isReady.toString())
                    )
                    result.success(true)
                }
                "start_input" -> {
                    if (InputService.ctx == null) {
                        if (checkInjectEventsPermission(this)) {
                            try {
                                InputService(this)
                                Companion.flutterMethodChannel?.invokeMethod(
                                    "on_state_changed",
                                    mapOf("name" to "input", "value" to InputService.isOpen.toString())
                                )
                                result.success(true)
                            } catch (e: Exception) {
                                Log.e(logTag, "Error initializing InputService: ${e.message}")
                                result.success(false)
                            }
                        } else {
                            Log.d(logTag, "Requesting INJECT_EVENTS permission")
                            Log.e(logTag, "尝试申请INJECT_EVENTS权限")
                            requestInjectEventsPermission(this) { granted ->
                                if (granted) {
                                    try {
                                        InputService(this)
                                        Log.d(logTag, "INJECT_EVENTS权限获取成功，已初始化InputService")
                                    } catch (e: Exception) {
                                        Log.e(logTag, "Error initializing InputService after permission: ${e.message}")
                                    }
                                } else {
                                    Log.d(logTag, "INJECT_EVENTS permission denied")
                                    Log.e(logTag, "INJECT_EVENTS权限被拒绝")
                                }
                                activity.runOnUiThread {
                                    Companion.flutterMethodChannel?.invokeMethod(
                                        "on_state_changed",
                                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                                    )
                                }
                            }
                            result.success(false)
                        }
                    } else {
                        Companion.flutterMethodChannel?.invokeMethod(
                            "on_state_changed",
                            mapOf("name" to "input", "value" to InputService.isOpen.toString())
                        )
                        result.success(true)
                    }
                }
                "start_input_without_dialog" -> {
                    Log.d(logTag, "尝试在定制系统环境下无需弹窗获取INJECT_EVENTS权限")
                    if (InputService.ctx == null) {
                        try {
                            // 定制系统中，直接初始化InputService，应该无需显示权限请求
                            // 在定制系统中，INJECT_EVENTS权限应该已经预授权
                            InputService(this)
                            Log.d(logTag, "定制系统中成功初始化InputService，无需显示权限弹窗")
                            
                            Companion.flutterMethodChannel?.invokeMethod(
                                "on_state_changed",
                                mapOf("name" to "input", "value" to "true")
                            )
                            result.success(true)
                        } catch (e: Exception) {
                            Log.e(logTag, "定制系统中初始化InputService失败: ${e.message}")
                            // 如果失败，回退到普通方式
                            if (!checkInjectEventsPermission(this)) {
                                requestInjectEventsPermission(this) { granted ->
                                    if (granted) {
                                        try {
                                            InputService(this)
                                            // 成功初始化后更新状态
                                            activity.runOnUiThread {
                                                Companion.flutterMethodChannel?.invokeMethod(
                                                    "on_state_changed",
                                                    mapOf("name" to "input", "value" to "true")
                                                )
                                            }
                                        } catch (e: Exception) {
                                            Log.e(logTag, "Error initializing InputService after permission: ${e.message}")
                                        }
                                    }
                                    activity.runOnUiThread {
                                        Companion.flutterMethodChannel?.invokeMethod(
                                            "on_state_changed",
                                            mapOf(
                                                "name" to "input",
                                                "value" to InputService.isOpen.toString()
                                            )
                                        )
                                    }
                                }
                            }
                            result.success(false)
                        }
                    } else {
                        result.success(true)
                    }
                }
                "stop_input" -> {
                    InputService.ctx?.disableSelf()
                    InputService.ctx = null
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                    )
                    result.success(true)
                }
                "cancel_notification" -> {
                    if (call.arguments is Int) {
                        val id = call.arguments as Int
                        mainService?.cancelNotification(id)
                    } else {
                        result.success(true)
                    }
                }
                "enable_soft_keyboard" -> {
                    // https://blog.csdn.net/hanye2020/article/details/105553780
                    if (call.arguments as Boolean) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    }
                    result.success(true)

                }
                "try_sync_clipboard" -> {
                    rdClipboardManager?.syncClipboard(true)
                    result.success(true)
                }
                GET_START_ON_BOOT_OPT -> {
                    val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                    result.success(prefs.getBoolean(KEY_START_ON_BOOT_OPT, true))
                }
                SET_START_ON_BOOT_OPT -> {
                    if (call.arguments is Boolean) {
                        val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                        val edit = prefs.edit()
                        edit.putBoolean(KEY_START_ON_BOOT_OPT, call.arguments as Boolean)
                        edit.apply()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                SYNC_APP_DIR_CONFIG_PATH -> {
                    if (call.arguments is String) {
                        val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                        val edit = prefs.edit()
                        edit.putString(KEY_APP_DIR_CONFIG_PATH, call.arguments as String)
                        edit.apply()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                GET_VALUE -> {
                    if (call.arguments is String) {
                        if (call.arguments == KEY_IS_SUPPORT_VOICE_CALL) {
                            result.success(isSupportVoiceCall())
                        } else {
                            result.error("-1", "No such key", null)
                        }
                    } else {
                        result.success(null)
                    }
                }
                "on_voice_call_started" -> {
                    onVoiceCallStarted()
                }
                "on_voice_call_closed" -> {
                    onVoiceCallClosed()
                }
                "ensure_ui_interactive" -> {
                    // 确保本地UI交互能力，即使在被远程控制期间
                    ensureUiInteractive()
                    result.success(true)
                }
                "get_device_sn" -> {
                    try {
                        Log.d("SunmiSN", "获取SN号")
                        val sn = getDeviceSN(this)
                        Log.d("SunmiSN", "获取到SN: '$sn'")
                        
                        // 通过事件通道发送SN到Flutter
                        Handler(Looper.getMainLooper()).post {
                            try {
                                MethodChannel(flutterEngine!!.dartExecutor.binaryMessenger, "mChannel").invokeMethod(
                                    "on_sn_received", 
                                    mapOf("sn" to sn)
                                )
                                Log.d("SunmiSN", "已发送SN给Flutter: '$sn'")
                            } catch (e: Exception) {
                                Log.e("SunmiSN", "发送SN失败: ${e.message}")
                            }
                        }
                        
                        // 返回成功
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e("SunmiSN", "获取SN号失败: ${e.message}")
                        result.error("SN_ERROR", "获取SN号失败", e.toString())
                    }
                }
                else -> {
                    result.error("-1", "No such method", null)
                }
            }
        }
    }

    private fun setCodecInfo() {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecs = codecList.codecInfos
        val codecArray = JSONArray()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val wh = getScreenSize(windowManager)
        var w = wh.first
        var h = wh.second
        val align = 64
        w = (w + align - 1) / align * align
        h = (h + align - 1) / align * align
        codecs.forEach { codec ->
            val codecObject = JSONObject()
            codecObject.put("name", codec.name)
            codecObject.put("is_encoder", codec.isEncoder)
            var hw: Boolean? = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                hw = codec.isHardwareAccelerated
            } else {
                // https://chromium.googlesource.com/external/webrtc/+/HEAD/sdk/android/src/java/org/webrtc/MediaCodecUtils.java#29
                // https://chromium.googlesource.com/external/webrtc/+/master/sdk/android/api/org/webrtc/HardwareVideoEncoderFactory.java#229
                if (listOf("OMX.google.", "OMX.SEC.", "c2.android").any { codec.name.startsWith(it, true) }) {
                    hw = false
                } else if (listOf("c2.qti", "OMX.qcom.video", "OMX.Exynos", "OMX.hisi", "OMX.MTK", "OMX.Intel", "OMX.Nvidia").any { codec.name.startsWith(it, true) }) {
                    hw = true
                }
            }
            if (hw != true) {
                return@forEach
            }
            codecObject.put("hw", hw)
            var mime_type = ""
            codec.supportedTypes.forEach { type ->
                if (listOf("video/avc", "video/hevc").contains(type)) { // "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01"
                    mime_type = type;
                }
            }
            if (mime_type.isNotEmpty()) {
                codecObject.put("mime_type", mime_type)
                val caps = codec.getCapabilitiesForType(mime_type)
                if (codec.isEncoder) {
                    // Encoder's max_height and max_width are interchangeable
                    if (!caps.videoCapabilities.isSizeSupported(w,h) && !caps.videoCapabilities.isSizeSupported(h,w)) {
                        return@forEach
                    }
                }
                codecObject.put("min_width", caps.videoCapabilities.supportedWidths.lower)
                codecObject.put("max_width", caps.videoCapabilities.supportedWidths.upper)
                codecObject.put("min_height", caps.videoCapabilities.supportedHeights.lower)
                codecObject.put("max_height", caps.videoCapabilities.supportedHeights.upper)
                val surface = caps.colorFormats.contains(COLOR_FormatSurface);
                codecObject.put("surface", surface)
                val nv12 = caps.colorFormats.contains(COLOR_FormatYUV420SemiPlanar)
                codecObject.put("nv12", nv12)
                if (!(nv12 || surface)) {
                    return@forEach
                }
                codecObject.put("min_bitrate", caps.videoCapabilities.bitrateRange.lower / 1000)
                codecObject.put("max_bitrate", caps.videoCapabilities.bitrateRange.upper / 1000)
                if (!codec.isEncoder) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        codecObject.put("low_latency", caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                    }
                }
                if (!codec.isEncoder) {
                    return@forEach
                }
                codecArray.put(codecObject)
            }
        }
        val result = JSONObject()
        result.put("version", Build.VERSION.SDK_INT)
        result.put("w", w)
        result.put("h", h)
        result.put("codecs", codecArray)
        FFI.setCodecInfo(result.toString())
    }

    private fun onVoiceCallStarted() {
        var ok = false
        mainService?.let {
            ok = it.onVoiceCallStarted()
        } ?: let {
            isAudioStart = true
            ok = audioRecordHandle.onVoiceCallStarted(null)
        }
        if (!ok) {
            // Rarely happens, So we just add log and msgbox here.
            Log.e(logTag, "onVoiceCallStarted fail")
            flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                "type" to "custom-nook-nocancel-hasclose-error",
                "title" to "Voice call",
                "text" to "Failed to start voice call."))
        } else {
            Log.d(logTag, "onVoiceCallStarted success")
        }
    }

    private fun onVoiceCallClosed() {
        var ok = false
        mainService?.let {
            ok = it.onVoiceCallClosed()
        } ?: let {
            isAudioStart = false
            ok = audioRecordHandle.onVoiceCallClosed(null)
        }
        if (!ok) {
            // Rarely happens, So we just add log and msgbox here.
            Log.e(logTag, "onVoiceCallClosed fail")
            flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                "type" to "custom-nook-nocancel-hasclose-error",
                "title" to "Voice call",
                "text" to "Failed to stop voice call."))
        } else {
            Log.d(logTag, "onVoiceCallClosed success")
        }
    }

    override fun onStop() {
        super.onStop()
        val disableFloatingWindow = FFI.getLocalOption("disable-floating-window") == "Y"
        if (!disableFloatingWindow && MainService.isReady) {
            startService(Intent(this, FloatingWindowService::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        stopService(Intent(this, FloatingWindowService::class.java))
    }

    // 确保应用自身UI在被远程控制期间仍然可交互
    private fun ensureUiInteractive() {
        Log.d(logTag, "确保本地UI交互能力")
        try {
            // 1. 暂时暂停输入服务的事件处理
            InputService.ctx?.let { service ->
                // 记录当前应用窗口位置和前台状态
                window.decorView.post {
                    val appPackageName = packageName
                    val isAppForeground = isAppInForeground(appPackageName)
                    
                    if (isAppForeground) {
                        Log.d(logTag, "应用在前台，临时调整输入事件处理模式")
                        
                        // 允许RustDesk应用自身接收本地UI事件
                        window.decorView.setOnTouchListener { _, event ->
                            // 仅处理我们应用自身的UI事件，不干扰远程控制事件
                            false // 返回false表示不消费事件，允许事件继续传递
                        }
                        
                        // 确保窗口具有焦点和交互能力
                        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    } else {
                        Log.d(logTag, "应用不在前台，无需调整UI交互")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "确保UI交互时出错: ${e.message}")
        }
    }
    
    // 检查应用是否在前台
    private fun isAppInForeground(packageName: String): Boolean {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val appProcesses = activityManager.runningAppProcesses ?: return false
            
            for (appProcess in appProcesses) {
                if (appProcess.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && 
                    appProcess.processName == packageName) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "检查应用前台状态时出错: ${e.message}")
        }
        return false
    }
}

