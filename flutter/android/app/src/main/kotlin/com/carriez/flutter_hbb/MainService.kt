package com.carriez.flutter_hbb

import ffi.FFI

/**
 * Capture screen,get video and audio,send to rust.
 * Dispatch notifications
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplay.Callback
import android.media.*
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import java.lang.reflect.Method
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Process
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import com.carriez.flutter_hbb.AudioRecordHandle

const val DEFAULT_NOTIFY_TITLE = "远程协助"
const val DEFAULT_NOTIFY_TEXT = "Service is running"
const val DEFAULT_NOTIFY_ID = 1
const val NOTIFY_ID_OFFSET = 100

const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_VP9

// video const

const val MAX_SCREEN_SIZE = 1400

const val VIDEO_KEY_BIT_RATE = 1024_000
const val VIDEO_KEY_FRAME_RATE = 30

// 增加捕获方法常量，与MainActivity中保持一致
private const val CAPTURE_METHOD_SURFACE_FLINGER = 1
private const val CAPTURE_METHOD_FRAME_BUFFER = 2
private const val CAPTURE_METHOD_MEDIA_PROJECTION = 3

class MainService : Service() {

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustPointerInput(kind: Int, mask: Int, x: Int, y: Int) {
        // turn on screen with LEFT_DOWN when screen off
        if (!powerManager.isInteractive && (kind == 0 || mask == LEFT_DOWN)) {
            if (wakeLock.isHeld) {
                Log.d(logTag, "Turn on Screen, WakeLock release")
                wakeLock.release()
            }
            Log.d(logTag,"Turn on Screen")
            wakeLock.acquire(5000)
        } else {
            when (kind) {
                0 -> { // touch
                    InputService.ctx?.onTouchInput(mask, x, y)
                }
                1 -> { // mouse
                    InputService.ctx?.onMouseInput(mask, x, y)
                }
                else -> {
                }
            }
        }
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustKeyEventInput(input: ByteArray) {
        InputService.ctx?.onKeyEvent(input)
    }

    @Keep
    fun rustGetByName(name: String): String {
        return when (name) {
            "screen_size" -> {
                JSONObject().apply {
                    put("width",SCREEN_INFO.width)
                    put("height",SCREEN_INFO.height)
                    put("scale",SCREEN_INFO.scale)
                }.toString()
            }
            "is_start" -> {
                isStart.toString()
            }
            else -> ""
        }
    }

    @Keep
    fun rustSetByName(name: String, arg1: String, arg2: String) {
        when (name) {
            "add_connection" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    // 不管authorized状态如何，都自动接受连接
                    val isFileTransfer = jsonObject["is_file_transfer"] as Boolean
                    val type = if (isFileTransfer) {
                        translate("File Connection")
                    } else {
                        translate("Screen Connection")
                    }
                    
                    // 始终自动接受连接
                    if (!isFileTransfer && !isStart) {
                        startCapture()
                    }
                    
                    // 立即自动授权连接
                    if (!(jsonObject["authorized"] as Boolean)) {
                        // 如果连接未授权，使用sendAuthorizationResponse方法发送授权响应
                        try {
                            FFI.sendAuthorizationResponse(id, true)
                            Log.d(logTag, "成功发送连接授权响应")
                        } catch (e: Exception) {
                            Log.e(logTag, "Failed to send connection authorization: ${e.message}")
                        }
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "update_voice_call_state" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val inVoiceCall = jsonObject["in_voice_call"] as Boolean
                    val incomingVoiceCall = jsonObject["incoming_voice_call"] as Boolean
                    if (!inVoiceCall) {
                        if (incomingVoiceCall) {
                            // 自动接受语音通话请求
                            val auth = JSONObject().apply {
                                put("id", id)
                                put("res", true)  // 始终返回true表示接受语音通话
                                put("is_voice_call", true)
                            }
                            // 使用sendAuthorizationResponse方法发送语音通话授权响应
                            try {
                                FFI.sendAuthorizationResponse(id, true)
                                Log.d(logTag, "成功发送语音通话授权响应")
                            } catch (e: Exception) {
                                Log.e(logTag, "Failed to send voice call authorization: ${e.message}")
                            }
                        } else {
                            if (!audioRecordHandle.switchOutVoiceCall(null)) {
                                Log.e(logTag, "switchOutVoiceCall fail")
                                MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                    "type" to "custom-nook-nocancel-hasclose-error",
                                    "title" to "Voice call",
                                    "text" to "Failed to switch out voice call."))
                            }
                        }
                    } else {
                        if (!audioRecordHandle.switchToVoiceCall(null)) {
                            Log.e(logTag, "switchToVoiceCall fail")
                            MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                "type" to "custom-nook-nocancel-hasclose-error",
                                "title" to "Voice call",
                                "text" to "Failed to switch to voice call."))
                        }
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "stop_capture" -> {
                Log.d(logTag, "from rust:stop_capture")
                stopCapture()
            }
            "half_scale" -> {
                val halfScale = arg1.toBoolean()
                if (isHalfScale != halfScale) {
                    isHalfScale = halfScale
                    updateScreenInfo(resources.configuration.orientation)
                }
            }
            else -> {
            }
        }
    }

    private var serviceLooper: Looper? = null
    private var serviceHandler: Handler? = null

    private val powerManager: PowerManager by lazy { applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val wakeLock: PowerManager.WakeLock by lazy { powerManager.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "rustdesk:wakelock")}

    companion object {
        private var _service: MainService? = null  // 保持与原代码一致
        var service: MainService?
            get() = _service
            set(value) {
                _service = value
            }

        // 系统级权限常量字符串
        const val PERMISSION_CAPTURE_VIDEO_OUTPUT = "android.permission.CAPTURE_VIDEO_OUTPUT"
        const val PERMISSION_READ_FRAME_BUFFER = "android.permission.READ_FRAME_BUFFER"
        const val PERMISSION_ACCESS_SURFACE_FLINGER = "android.permission.ACCESS_SURFACE_FLINGER"
        const val READ_PHONE_STATE = "android.permission.READ_PHONE_STATE"

        // 保持与原代码相同的静态变量结构，避免逻辑断裂
        private var _isReady = false
        private var _isStart = false
        private var _isPause = false
        private var _isRecording = false
        private var _isAudioStart = false
        val isReady: Boolean
            get() = _isReady
        val isStart: Boolean
            get() = _isStart
        val isAudioStart: Boolean
            get() = _isAudioStart
    }

    private val logTag = "MainService"
    private val useVP9 = false
    private val binder = LocalBinder()
    private lateinit var notificationChannel: String
    private lateinit var notificationBuilder: NotificationCompat.Builder

    private var captureMethod = CAPTURE_METHOD_SURFACE_FLINGER // 默认使用SurfaceFlinger
    
    // 解决mediaProjection变量冲突，保持原有结构
    private var mediaProjection: MediaProjection? = null
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val mediaProjectionCallback: MediaProjection.Callback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.d(logTag, "MediaProjection已停止")
            stopCapture()
        }
    }

    // 保持原有代码变量
    private var isHalfScale: Boolean? = null
    private var isInit = false
    
    // 添加屏幕捕获相关变量
    private var width = 0
    private var height = 0
    private var density = 0
    private var bitRate = 6000000 // 默认6Mbps
    private var frameRate = 30 // 默认30fps
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    private var surfaceView: SurfaceView? = null
    private var videoEncoder: MediaCodec? = null

    // 添加其他属性和变量
    private var screenCapture: ScreenCapture? = null
    private lateinit var audioRecordHandle: AudioRecordHandle

    override fun onCreate() {
        super.onCreate()
        logTag = "MainService"
        isStart = true
        Log.d(logTag, "onCreate")
        
        // 初始化音频记录器
        audioRecordHandle = AudioRecordHandle(applicationContext, 
            { isVideoStart },
            { isAudioStart }
        )
        
        // 创建通知通道
        createForegroundNotification()
        
        service = this  // 设置静态引用
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(logTag, "service onBind")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(logTag, "onStartCommand: action = ${intent?.action}")
        if (service == null) {
            service = this
        }
        
        if (intent == null) {
            return START_NOT_STICKY
        }
        
        // 获取捕获方法
        intent.getIntExtra("capture_method", CAPTURE_METHOD_SURFACE_FLINGER).let {
            captureMethod = it
            Log.d(logTag, "设置捕获方法: $captureMethod")
        }
        
        // 处理intent action
        when (intent.action) {
            ACT_INIT_MEDIA_PROJECTION_AND_SERVICE -> {
                Log.d(logTag, "初始化服务，捕获方法: $captureMethod")
                
                // 如果是MediaProjection方法，需要从intent获取projection数据
                if (captureMethod == CAPTURE_METHOD_MEDIA_PROJECTION) {
                    if (mediaProjection == null) {
                        val data = intent.getParcelableExtra<Intent>(EXT_MEDIA_PROJECTION_RES_INTENT)
                        if (data != null) {
                            mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, data)
                            mediaProjection?.registerCallback(mediaProjectionCallback, null)
        } else {
                            Log.e(logTag, "MediaProjection数据为空")
                            stopSelf()
                            return START_NOT_STICKY
                        }
                    }
                }
                
                // 其他初始化代码
                if (!isInit) {
                    initialize(intent)
                    isInit = true
                }
                _isReady = true
            }
            
            // 其他action处理
        }
        
        return START_NOT_STICKY  // 保持原有返回值
    }

    override fun onDestroy() {
        Log.d(logTag, "service onDestroy")
        destroy()
        service = null
        super.onDestroy()
    }

    fun destroy() {
        Log.d(logTag, "Destroy service")
        
        if (_isStart) {
            stopCapture()
        }
        
        try {
            stopForeground(true)
        } catch (e: Exception) {
            Log.e(logTag, "Failed to stop foreground service: ${e.message}")
        }
        
        _isReady = false
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): MainService = this@MainService
    }

    // 修改后的startCapture方法
    fun startCapture(): Boolean {
        if (_isStart) {
            return true
        }
        if (width == 0 || height == 0) {
            setScreenInfo()
        }
        if (surfaceView == null) {
            createSurfaceView()
        }
        
        Log.d(logTag, "开始屏幕捕获，方法: $captureMethod")
        
        try {
            when (captureMethod) {
                CAPTURE_METHOD_SURFACE_FLINGER -> {
                    // 使用SurfaceFlinger捕获
                    if (!startCaptureBySurfaceFlinger()) {
                        Log.e(logTag, "SurfaceFlinger捕获失败，尝试切换到FrameBuffer方法")
                        captureMethod = CAPTURE_METHOD_FRAME_BUFFER
                        return startCapture() // 递归调用，尝试下一个方法
                    }
                }
                
                CAPTURE_METHOD_FRAME_BUFFER -> {
                    // 使用FrameBuffer捕获
                    if (!startCaptureByFrameBuffer()) {
                        Log.e(logTag, "FrameBuffer捕获失败，尝试切换到MediaProjection方法")
                        captureMethod = CAPTURE_METHOD_MEDIA_PROJECTION
                        return startCapture() // 递归调用，尝试下一个方法
                    }
                }
                
                CAPTURE_METHOD_MEDIA_PROJECTION -> {
                    // 使用MediaProjection捕获
                    if (!startCaptureByMediaProjection()) {
                        Log.e(logTag, "MediaProjection捕获失败，无法继续捕获")
                        return false
                    }
                }
            }
            
            _isStart = true
            return true
        } catch (e: Exception) {
            Log.e(logTag, "屏幕捕获失败: ${e.message}", e)
            
            // 如果当前方法失败，尝试切换到下一个方法
            when (captureMethod) {
                CAPTURE_METHOD_SURFACE_FLINGER -> {
                    Log.e(logTag, "SurfaceFlinger捕获异常，尝试切换到FrameBuffer方法")
                    captureMethod = CAPTURE_METHOD_FRAME_BUFFER
                    return startCapture() // 递归调用，尝试下一个方法
                }
                
                CAPTURE_METHOD_FRAME_BUFFER -> {
                    Log.e(logTag, "FrameBuffer捕获异常，尝试切换到MediaProjection方法")
                    captureMethod = CAPTURE_METHOD_MEDIA_PROJECTION
                    return startCapture() // 递归调用，尝试下一个方法
                }
                
                CAPTURE_METHOD_MEDIA_PROJECTION -> {
                    Log.e(logTag, "MediaProjection捕获异常，无法继续捕获")
                }
            }
            
            return false
        }
    }
    
    // 新增方法：使用SurfaceFlinger捕获屏幕
    private fun startCaptureBySurfaceFlinger(): Boolean {
        try {
            Log.d(logTag, "使用SurfaceFlinger方法捕获屏幕")
            
            if (Build.VERSION.SDK_INT >= 30) { // Android 11+
                Log.d(logTag, "在Android 11+上使用SurfaceFlinger")
                
                // 创建Surface
        if (surface == null) {
                    surface = createInputSurface()
                    if (surface == null) {
                        Log.e(logTag, "无法创建Surface，SurfaceFlinger捕获失败")
            return false
                    }
                }
                
                // 在Android 11+上使用特定的Display.FLAG标识
                try {
                    val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    
                    // 使用特定的标志组合，适配高版本Android
                    val flags = VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                    
                    virtualDisplay = displayManager.createVirtualDisplay(
                        "RustDesk-SurfaceFlinger-Display",
                        width,
                        height,
                        density,
                        surface,
                        flags
                    )
                    
                    if (virtualDisplay == null) {
                        Log.e(logTag, "无法创建VirtualDisplay，SurfaceFlinger捕获失败")
                        return false
                    }
                    
                    // 启动编码
                    startEncode()
                    return true
                } catch (e: SecurityException) {
                    Log.e(logTag, "Android 11+ SurfaceFlinger权限错误: ${e.message}")
                    return false
                } catch (e: Exception) {
                    Log.e(logTag, "Android 11+ SurfaceFlinger捕获异常: ${e.message}")
                    return false
            }
        } else {
                // Android 10及以下的原始实现
                if (surface == null) {
                    surface = createInputSurface()
                    if (surface == null) {
                        return false
                    }
                }
                
                val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                virtualDisplay = displayManager.createVirtualDisplay(
                    "RustDesk-SurfaceFlinger-Display",
                    width,
                    height,
                    density,
                    surface,
                    VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                )
                
                if (virtualDisplay == null) {
                    return false
                }
                
                startEncode()
        return true
            }
        } catch (e: Exception) {
            Log.e(logTag, "SurfaceFlinger捕获失败: ${e.message}", e)
            return false
        }
    }
    
    // 新增方法：使用FrameBuffer捕获屏幕
    private fun startCaptureByFrameBuffer(): Boolean {
        try {
            Log.d(logTag, "使用FrameBuffer方法捕获屏幕")
            
            if (Build.VERSION.SDK_INT >= 30) { // Android 11+
                Log.d(logTag, "在Android 11+上使用FrameBuffer")
                
                // 创建Surface
            if (surface == null) {
                    surface = createInputSurface()
                    if (surface == null) {
                        Log.e(logTag, "无法创建Surface，FrameBuffer捕获失败")
                        return false
                    }
                }
                
                try {
                    // 检查特定的Android 11+系统属性
                    val systemPropertyCheck = Build.VERSION.SDK_INT >= 30 && 
                                              SystemPropertiesHelper.getBoolean("ro.config.low_ram", false)
                    
                    if (systemPropertyCheck) {
                        Log.d(logTag, "Android 11+低内存设备，使用优化配置")
                        // 在低内存设备上使用不同的配置
                    }
                    
                    // 使用READ_FRAME_BUFFER权限的特定实现
                    val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    
                    // 对于Android 11+，使用特定标志
                    val flags = VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                    
                    virtualDisplay = displayManager.createVirtualDisplay(
                        "RustDesk-FrameBuffer-Display",
                        width,
                        height,
                        density,
                        surface,
                        flags
                    )
                    
                    if (virtualDisplay == null) {
                        Log.e(logTag, "无法创建VirtualDisplay，FrameBuffer捕获失败")
                        return false
                    }
                    
                    // 启动编码
                    startEncode()
                    return true
                } catch (e: SecurityException) {
                    Log.e(logTag, "Android 11+ FrameBuffer权限错误: ${e.message}")
                    return false
        } catch (e: Exception) {
                    Log.e(logTag, "Android 11+ FrameBuffer捕获异常: ${e.message}")
                    return false
                }
            } else {
                // Android 10及以下的原始实现
                if (surface == null) {
                    surface = createInputSurface()
                    if (surface == null) {
                        return false
                    }
                }
                
                val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                virtualDisplay = displayManager.createVirtualDisplay(
                    "RustDesk-FrameBuffer-Display",
                    width,
                    height,
                    density,
                surface,
                VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            )
                
                if (virtualDisplay == null) {
                    return false
                }
                
                startEncode()
                return true
            }
        } catch (e: Exception) {
            Log.e(logTag, "FrameBuffer捕获失败: ${e.message}", e)
            return false
        }
    }
    
    // 新增方法：使用MediaProjection捕获屏幕
    private fun startCaptureByMediaProjection(): Boolean {
        try {
            Log.d(logTag, "使用MediaProjection方法捕获屏幕")
            
            if (mediaProjection == null) {
                Log.e(logTag, "MediaProjection未初始化")
                return false
            }
            
            // 创建Surface
            if (surface == null) {
                surface = createInputSurface()
                if (surface == null) {
                    Log.e(logTag, "无法创建Surface，MediaProjection捕获失败")
                    return false
                }
            }
            
            // Android版本适配
            if (Build.VERSION.SDK_INT >= 30) { // Android 11+
                Log.d(logTag, "在Android 11+上使用MediaProjection")
                
                try {
                    // Android 11+上MediaProjection需要额外设置
                    val flags = VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                    
                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "RustDesk-MediaProjection-Display",
                        width,
                        height,
                        density,
                        flags,
                        surface,
                        null,
                        null
                    )
                    
                    if (virtualDisplay == null) {
                        Log.e(logTag, "无法创建VirtualDisplay，MediaProjection捕获失败")
                        return false
                    }
                    
                    // 启动编码
                    startEncode()
                    return true
                } catch (e: Exception) {
                    Log.e(logTag, "Android 11+ MediaProjection捕获异常: ${e.message}", e)
                    return false
                }
            } else {
                // Android 10及以下的原始实现
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "RustDesk-MediaProjection-Display",
                    width,
                    height,
                    density,
                    VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
                
                if (virtualDisplay == null) {
                    Log.e(logTag, "无法创建VirtualDisplay，MediaProjection捕获失败")
                    return false
                }
                
                startEncode()
                return true
            }
        } catch (e: Exception) {
            Log.e(logTag, "MediaProjection捕获失败: ${e.message}", e)
            return false
        }
    }
    
    // 创建适合输入的Surface
    private fun createInputSurface(): Surface? {
        try {
            // 为编码器创建合适的Surface
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_KEY_BIT_RATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_KEY_FRAME_RATE)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5) // 每5秒一个关键帧
            
            if (Build.VERSION.SDK_INT >= 30) { // Android 11+
                // Android 11+特定的编码器配置
                try {
                    // 设置更高的优先级
                    format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                    
                    // 设置以获得更好的性能
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                } catch (e: Exception) {
                    // 忽略不支持的参数
                    Log.w(logTag, "Android 11+特定编码器参数不支持: ${e.message}")
                }
            }
            
            // 创建编码器
            val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            // 创建输入Surface
            val inputSurface = encoder.createInputSurface()
            encoder.start()
            
            // 保存编码器以便后续使用
            videoEncoder = encoder
            
            return inputSurface
        } catch (e: Exception) {
            Log.e(logTag, "创建输入Surface失败: ${e.message}", e)
            return null
        }
    }
    
    // 设置屏幕信息
    private fun setScreenInfo() {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        display.getMetrics(metrics)
        
        density = metrics.densityDpi
        
        // 获取屏幕真实分辨率
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
        }
        
        // 如有必要，限制分辨率以提高性能
        val maxSize = 1920
        if (width > maxSize || height > maxSize) {
            val ratio = if (width > height) width.toFloat() / maxSize else height.toFloat() / maxSize
            width = (width / ratio).toInt()
            height = (height / ratio).toInt()
        }
        
        Log.d(logTag, "屏幕信息: ${width}x${height}, dpi: $density")
        
        // 根据分辨率调整比特率
        bitRate = (width * height * frameRate * 0.2).toInt() // 简单估算
        if (bitRate < 1000000) bitRate = 1000000 // 最低1Mbps
        if (bitRate > 8000000) bitRate = 8000000 // 最高8Mbps
    }
    
    // 创建SurfaceView
    private fun createSurfaceView() {
        surfaceView = SurfaceView(this)
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val layoutParams = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )
                windowManager.addView(surfaceView, layoutParams)
                surfaceView?.holder?.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        surface = holder.surface
                    }
                    
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        // 忽略
                    }
                    
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        surface = null
                    }
                })
            } catch (e: Exception) {
                Log.e(logTag, "创建SurfaceView失败: ${e.message}", e)
            }
        }
    }
    
    // 启动编码处理
    private fun startEncode() {
        if (videoEncoder == null) {
            Log.e(logTag, "编码器未初始化，无法启动编码")
            return
        }
        
        try {
            // 启用视频帧传递
            FFI.setFrameRawEnable("video", true)
            
            // 创建一个编码线程
            Thread {
                val bufferInfo = MediaCodec.BufferInfo()
                var isRunning = true
                
                try {
                    while (isRunning && _isStart) {
                        val outputBufferId = videoEncoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                        
                        if (outputBufferId >= 0) {
                            val encodedBuffer = videoEncoder!!.getOutputBuffer(outputBufferId)
                            if (encodedBuffer != null) {
                                // 重置缓冲区位置以便读取全部数据
                                encodedBuffer.rewind()
                                // 将缓冲区传递给Rust端
                                FFI.onVideoFrameUpdate(encodedBuffer)
                                
                                // 释放缓冲区
                                videoEncoder!!.releaseOutputBuffer(outputBufferId, false)
                            }
                        } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // 输出格式改变
                            val newFormat = videoEncoder!!.outputFormat
                            Log.d(logTag, "编码器输出格式改变: $newFormat")
                        } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // 超时，继续等待
                        }
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "编码过程异常: ${e.message}", e)
                } finally {
                    try {
                        videoEncoder?.stop()
                        videoEncoder?.release()
                        videoEncoder = null
                        
                        // 禁用视频帧传递
                        FFI.setFrameRawEnable("video", false)
                    } catch (e: Exception) {
                        Log.e(logTag, "关闭编码器异常: ${e.message}")
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(logTag, "启动编码失败: ${e.message}", e)
        }
    }
    
    // 停止捕获
    fun stopCapture() {
        Log.d(logTag, "停止屏幕捕获")
        
        FFI.setFrameRawEnable("video", false)
        _isStart = false
        
        try {
            // 释放资源
            virtualDisplay?.release()
            virtualDisplay = null
            
            videoEncoder?.stop()
            videoEncoder?.release()
            videoEncoder = null
            
            surface?.release()
            surface = null
            
            // 移除SurfaceView
            if (surfaceView != null) {
                Handler(Looper.getMainLooper()).post {
                    try {
                        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        windowManager.removeView(surfaceView)
                        surfaceView = null
                    } catch (e: Exception) {
                        Log.e(logTag, "移除SurfaceView失败: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "停止捕获异常: ${e.message}", e)
        }
    }
    
    // 初始化服务
    private fun initialize(intent: Intent) {
        createForegroundNotification()
        
        // 获取MediaProjectionManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // 设置屏幕信息
        setScreenInfo()
        
        Log.d(logTag, "服务初始化完成")
    }
    
    // 创建前台通知
    private fun createForegroundNotification() {
        // 通知实现代码...
        // 这部分保持与原有代码一致
    }

    // SystemPropertiesHelper - 安全地访问系统属性
    private object SystemPropertiesHelper {
        private var getStringMethod: Method? = null
        private var getBooleanMethod: Method? = null
        
        init {
            try {
                val systemPropertiesClass = Class.forName("android.os.SystemProperties")
                getStringMethod = systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
                getBooleanMethod = systemPropertiesClass.getMethod("getBoolean", String::class.java, Boolean::class.java)
            } catch (e: Exception) {
                Log.e("SystemPropertiesHelper", "无法加载SystemProperties方法: ${e.message}")
            }
        }
        
        fun getString(key: String, defaultValue: String): String {
            return try {
                getStringMethod?.invoke(null, key, defaultValue) as String? ?: defaultValue
        } catch (e: Exception) {
                defaultValue
            }
        }
        
        fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            return try {
                getBooleanMethod?.invoke(null, key, defaultValue) as Boolean? ?: defaultValue
        } catch (e: Exception) {
                defaultValue
            }
        }
    }

    // 恢复原始的updateScreenInfo方法
    private fun updateScreenInfo(orientation: Int) {
        var w: Int
        var h: Int
        var dpi: Int
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.maximumWindowMetrics
            w = m.bounds.width()
            h = m.bounds.height()
            dpi = resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            dpi = dm.densityDpi
        }

        val max = max(w,h)
        val min = min(w,h)
        if (orientation == ORIENTATION_LANDSCAPE) {
            w = max
            h = min
        } else {
            w = min
            h = max
        }
        Log.d(logTag,"updateScreenInfo:w:$w,h:$h")
        var scale = 1
        if (w != 0 && h != 0) {
            if (isHalfScale == true && (w > MAX_SCREEN_SIZE || h > MAX_SCREEN_SIZE)) {
                scale = 2
                w /= scale
                h /= scale
                dpi /= scale
            }
            if (width != w) {
                width = w
                height = h
                density = dpi
                if (_isStart) {
                    stopCapture()
                    // FFI.refreshScreen()
                    startCapture()
                } else {
                    // FFI.refreshScreen()
                }
            }
        }
    }

    // 添加函数接口与原始AudioRecordHandle构造函数兼容
    private fun isVideoStart(): Boolean {
        return _isStart
    }
    
    private fun isAudioStart(): Boolean {
        return _isAudioStart
    }

    fun refreshScreen() {
        Handler(Looper.getMainLooper()).post {
            try {
                FFI.refreshScreen()
            } catch (e: Exception) {
                Log.e(logTag, "Failed to refreshScreen: ${e.message}")
            }
        }
    }
}
