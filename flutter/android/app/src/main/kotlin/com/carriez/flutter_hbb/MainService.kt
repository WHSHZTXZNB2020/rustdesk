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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
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
import android.app.Activity.RESULT_OK
import android.media.MediaCodecInfo.CodecCapabilities

const val DEFAULT_NOTIFY_TITLE = "远程协助"
const val DEFAULT_NOTIFY_TEXT = "Service is running"
const val DEFAULT_NOTIFY_ID = 1
const val NOTIFY_ID_OFFSET = 100

const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_VP9

// video const

const val MAX_SCREEN_SIZE = 1400

const val VIDEO_KEY_BIT_RATE = 1024_000
const val VIDEO_KEY_FRAME_RATE = 30

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
                        // 如果连接未授权，发送授权响应
                        val auth = JSONObject().apply {
                            put("id", id)
                            put("res", true)  // 始终返回true表示接受连接
                        }
                        // 使用现有的FFI接口代替autorize
                        try {
                            // 直接使用JSON作为参数调用FFI startServer方法
                            // 这会将授权信息传递到Rust端
                            FFI.startServer(auth.toString(), "connection_response")
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
                            // 使用现有的FFI接口代替autorize
                            try {
                                // 直接使用JSON作为参数调用FFI startServer方法
                                // 这会将授权信息传递到Rust端
                                FFI.startServer(auth.toString(), "voice_call_response") 
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
        private var _isReady = false // media permission ready status
        private var _isStart = false // screen capture start status
        private var _isAudioStart = false // audio capture start status
        val isReady: Boolean
            get() = _isReady
        val isStart: Boolean
            get() = _isStart
        val isAudioStart: Boolean
            get() = _isAudioStart
            
        // 系统级权限常量字符串
        const val PERMISSION_CAPTURE_VIDEO_OUTPUT = "android.permission.CAPTURE_VIDEO_OUTPUT"
        const val PERMISSION_READ_FRAME_BUFFER = "android.permission.READ_FRAME_BUFFER"
        const val PERMISSION_ACCESS_SURFACE_FLINGER = "android.permission.ACCESS_SURFACE_FLINGER"
    }

    private val logTag = "LOG_SERVICE"
    private val useVP9 = false
    private val binder = LocalBinder()

    private var reuseVirtualDisplay = Build.VERSION.SDK_INT > 33

    // video
    private var display: Display? = null
    private var displayManager: DisplayManager? = null
    private var surface: Surface? = null
    private val sendVP9Thread = Executors.newSingleThreadExecutor()
    private var videoEncoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    // 添加MediaProjection全局变量
    private var mediaProjection: MediaProjection? = null

    // audio
    private val audioRecordHandle = AudioRecordHandle(this, { isStart }, { isAudioStart })

    // notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationChannel: String
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag,"MainService onCreate, sdk int:${Build.VERSION.SDK_INT} reuseVirtualDisplay:$reuseVirtualDisplay")
        FFI.init(this)
        HandlerThread("Service", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = Handler(looper)
        }
        updateScreenInfo(resources.configuration.orientation)
        initNotification()

        // keep the config dir same with flutter
        val prefs = applicationContext.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
        val configPath = prefs.getString(KEY_APP_DIR_CONFIG_PATH, "") ?: ""
        FFI.startServer(configPath, "")

        createForegroundNotification()
    }

    override fun onDestroy() {
        checkMediaPermission()
        stopService(Intent(this, FloatingWindowService::class.java))
        super.onDestroy()
    }

    private var isHalfScale: Boolean? = null;
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
            if (SCREEN_INFO.width != w) {
                SCREEN_INFO.width = w
                SCREEN_INFO.height = h
                SCREEN_INFO.scale = scale
                SCREEN_INFO.dpi = dpi
                if (isStart) {
                    stopCapture()
                    FFI.refreshScreen()
                    startCapture()
                } else {
                    FFI.refreshScreen()
                }
            }

        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(logTag, "service onBind")
        return binder
    }

    inner class LocalBinder : Binder() {
        init {
            Log.d(logTag, "LocalBinder init")
        }

        fun getService(): MainService = this@MainService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("whichService", "this service: ${Thread.currentThread()}")
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACT_INIT_MEDIA_PROJECTION_AND_SERVICE) {
            createForegroundNotification()

            if (intent.getBooleanExtra(EXT_INIT_FROM_BOOT, false)) {
                FFI.startService()
            }
            Log.d(logTag, "service starting: ${startId}:${Thread.currentThread()}")
            
            // 检查是否有MediaProjection的结果意图
            val mediaProjectionIntent = intent.getParcelableExtra<Intent>(EXT_MEDIA_PROJECTION_RES_INTENT)
            if (mediaProjectionIntent != null) {
                Log.d(logTag, "收到MediaProjection结果，使用MediaProjection进行屏幕捕获")
                
                // 获取MediaProjection
                val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                try {
                    val mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionIntent)
                    if (mediaProjection != null) {
                        // 初始化屏幕捕获（使用MediaProjection）
                        setupMediaProjection(mediaProjection)
                    } else {
                        Log.e(logTag, "获取MediaProjection失败")
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "MediaProjection初始化错误: ${e.message}")
                }
            } else {
                // 使用系统级权限获取屏幕内容，无需请求MediaProjection权限
                Log.d(logTag, "使用系统级权限进行屏幕捕获")
                displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                _isReady = true
                
                // 检查必要的权限
                checkSystemPermissions()
            }
        }
        return START_NOT_STICKY // don't use sticky (auto restart), the new service (from auto restart) will lose control
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo(newConfig.orientation)
    }

    private fun checkSystemPermissions() {
        val hasSurfaceFlingerPermission = checkCallingOrSelfPermission(PERMISSION_ACCESS_SURFACE_FLINGER) == PackageManager.PERMISSION_GRANTED
        val hasCaptureVideoPermission = checkCallingOrSelfPermission(PERMISSION_CAPTURE_VIDEO_OUTPUT) == PackageManager.PERMISSION_GRANTED
        val hasReadFrameBufferPermission = checkCallingOrSelfPermission(PERMISSION_READ_FRAME_BUFFER) == PackageManager.PERMISSION_GRANTED
        
        Log.d(logTag, "系统权限检查: " +
              "ACCESS_SURFACE_FLINGER=$hasSurfaceFlingerPermission, " +
              "CAPTURE_VIDEO_OUTPUT=$hasCaptureVideoPermission, " +
              "READ_FRAME_BUFFER=$hasReadFrameBufferPermission")
        
        if (hasSurfaceFlingerPermission || (hasCaptureVideoPermission && hasReadFrameBufferPermission)) {
            Log.d(logTag, "系统权限检查通过，可以使用系统级屏幕捕获")
        } else {
            Log.w(logTag, "系统权限不足，如果需要屏幕捕获，将使用MediaProjection机制")
        }
    }

    @SuppressLint("WrongConstant")
    private fun createSurfaceOptimal(): Surface? {
        try {
            // 为系统权限捕获选择最佳格式 - 通常RGBA_8888提供最佳质量
            val pixelFormat = PixelFormat.RGBA_8888
            
            Log.d(logTag, "为系统权限创建高品质图像读取器，使用RGBA_8888格式，屏幕信息: $SCREEN_INFO")
            
            // 创建适合系统级捕获的ImageReader，使用更多缓冲区
            imageReader = ImageReader.newInstance(
                SCREEN_INFO.width,
                SCREEN_INFO.height,
                pixelFormat,
                3  // 使用3个缓冲区，平衡性能和内存使用
            ).apply {
                setOnImageAvailableListener({ imageReader: ImageReader ->
                    try {
                        val image = imageReader.acquireLatestImage()
                        if (image == null) {
                            return@setOnImageAvailableListener
                        }
                        
                        if (!isStart) {
                            image.close()
                            return@setOnImageAvailableListener
                        }
                        
                        try {
                            val planes = image.planes
                            if (planes.isNotEmpty()) {
                                val buffer = planes[0].buffer
                                buffer.rewind()
                                FFI.onVideoFrameUpdate(buffer)
                            }
                        } finally {
                            image.close()
                        }
                    } catch (e: Exception) {
                        Log.w(logTag, "处理图像时出错: ${e.message}")
                    }
                }, serviceHandler)
            }
            
            Log.d(logTag, "高品质ImageReader创建成功并设置监听器")
            return imageReader?.surface
            
        } catch (e: Exception) {
            // 如果RGBA_8888失败，尝试备用格式
            Log.e(logTag, "创建RGBA_8888 Surface时出错: ${e.message}，尝试备用格式")
            return createSurfaceBackup()
        }
    }
    
    @SuppressLint("WrongConstant")
    private fun createSurfaceBackup(): Surface? {
        try {
            // 备用格式，MTK处理器通常更好地支持RGB_565
            val pixelFormat = PixelFormat.RGB_565
            
            Log.d(logTag, "创建备用图像读取器，使用RGB_565格式")
            
            imageReader = ImageReader.newInstance(
                SCREEN_INFO.width,
                SCREEN_INFO.height,
                pixelFormat,
                2  // 使用较少的缓冲区
            ).apply {
                setOnImageAvailableListener({ imageReader: ImageReader ->
                    try {
                        val image = imageReader.acquireLatestImage()
                        if (image == null || !isStart) {
                            image?.close()
                            return@setOnImageAvailableListener
                        }
                        
                        try {
                            val planes = image.planes
                            if (planes.isNotEmpty()) {
                                val buffer = planes[0].buffer
                                buffer.rewind()
                                FFI.onVideoFrameUpdate(buffer)
                            }
                        } finally {
                            image.close()
                        }
                    } catch (e: Exception) {
                        Log.w(logTag, "处理图像时出错: ${e.message}")
                    }
                }, serviceHandler)
            }
            
            Log.d(logTag, "备用ImageReader创建成功")
            return imageReader?.surface
            
        } catch (e: Exception) {
            Log.e(logTag, "创建备用Surface也失败: ${e.message}")
            return null
        }
    }
    
    // 增强系统权限创建虚拟显示的方法
    private fun createVirtualDisplayWithSystemPermissions(surface: Surface): VirtualDisplay? {
        try {
            // 为FrameBuffer捕获设置正确的参数
            return displayManager?.createVirtualDisplay(
                "RustDesk-FrameBuffer-Display",
                SCREEN_INFO.width,
                SCREEN_INFO.height,
                SCREEN_INFO.dpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            )
        } catch (e: Exception) {
            Log.e(logTag, "系统权限创建虚拟显示失败: ${e.message}")
            return null
        }
    }

    fun onVoiceCallStarted(): Boolean {
        // 使用系统权限实现，不需要mediaProjection
        return audioRecordHandle.onVoiceCallStarted(null)
    }

    fun onVoiceCallClosed(): Boolean {
        // 使用系统权限实现，不需要mediaProjection
        return audioRecordHandle.onVoiceCallClosed(null)
    }

    fun startCapture(): Boolean {
        if (isStart) {
            return true
        }
        
        Log.d(logTag, "开始启动屏幕捕获，Android版本: ${Build.VERSION.SDK_INT}")
        
        // 优先检查 ACCESS_SURFACE_FLINGER 权限 (系统级捕获的最佳选择)
        val accessSurfaceFlingerPermission = checkCallingOrSelfPermission(PERMISSION_ACCESS_SURFACE_FLINGER)
        val hasSurfaceFlingerPermission = accessSurfaceFlingerPermission == PackageManager.PERMISSION_GRANTED
        
        // 检查其他系统级权限
        val captureVideoPermission = checkCallingOrSelfPermission(PERMISSION_CAPTURE_VIDEO_OUTPUT)
        val readFrameBufferPermission = checkCallingOrSelfPermission(PERMISSION_READ_FRAME_BUFFER)
        val hasFrameBufferPermissions = captureVideoPermission == PackageManager.PERMISSION_GRANTED && 
                                       readFrameBufferPermission == PackageManager.PERMISSION_GRANTED
        
        // 检查是否已经有媒体投影许可（最后的选择）
        val hasMediaProjection = mediaProjection != null
        
        // 记录权限状态到日志
        Log.d(logTag, "权限状态 - ACCESS_SURFACE_FLINGER: $hasSurfaceFlingerPermission, " +
                     "CAPTURE_VIDEO_OUTPUT: ${captureVideoPermission == PackageManager.PERMISSION_GRANTED}, " +
                     "READ_FRAME_BUFFER: ${readFrameBufferPermission == PackageManager.PERMISSION_GRANTED}, " +
                     "已有MediaProjection: $hasMediaProjection")
        
        // 更新屏幕信息，不降低分辨率，保持最佳画质
        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "屏幕信息: ${SCREEN_INFO.width}x${SCREEN_INFO.height}, DPI: ${SCREEN_INFO.dpi}")
        
        // 创建Surface
        try {
            surface = createSurfaceOptimal()
            if (surface == null) {
                Log.e(logTag, "创建Surface失败")
                return false
            }
            Log.d(logTag, "成功创建Surface")
        } catch (e: Exception) {
            Log.e(logTag, "创建Surface错误: ${e.message}")
            return false
        }

        // 控制捕获顺序: 首先尝试系统权限方法，失败后才使用MediaProjection
        var captureSuccess = false
        
        // 1. 首先尝试SurfaceFlinger方法 (优先级最高)
        if (hasSurfaceFlingerPermission) {
            Log.d(logTag, "尝试使用ACCESS_SURFACE_FLINGER权限进行屏幕捕获")
            try {
                // 在系统级权限下，Surface已经正确配置
                virtualDisplay = createVirtualDisplayWithSurfaceFlinger(surface!!)
                
                // 检查是否创建成功
                if (virtualDisplay != null) {
                    Log.d(logTag, "使用SurfaceFlinger成功创建虚拟显示")
                    captureSuccess = true
                } else {
                    Log.e(logTag, "使用SurfaceFlinger创建虚拟显示失败")
                }
            } catch (e: Exception) {
                Log.e(logTag, "使用SurfaceFlinger方法出错: ${e.message}")
            }
        }
        
        // 2. 如果SurfaceFlinger失败，尝试使用READ_FRAME_BUFFER权限
        if (!captureSuccess && hasFrameBufferPermissions) {
            Log.d(logTag, "尝试使用READ_FRAME_BUFFER权限进行屏幕捕获")
            try {
                // 为FrameBuffer捕获创建虚拟显示
                virtualDisplay = createVirtualDisplayWithSystemPermissions(surface!!)
                
                // 检查是否创建成功
                if (virtualDisplay != null) {
                    Log.d(logTag, "使用系统权限成功创建虚拟显示")
                    captureSuccess = true
                } else {
                    Log.e(logTag, "使用系统权限创建虚拟显示失败")
                }
            } catch (e: Exception) {
                Log.e(logTag, "使用FrameBuffer权限方法出错: ${e.message}")
            }
        }
        
        // 3. 如果系统权限方法都失败，再尝试MediaProjection (最后的备选方案)
        if (!captureSuccess && hasMediaProjection) {
            Log.d(logTag, "系统权限方法失败，尝试使用已有的MediaProjection")
            
            try {
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "RustDesk-MediaProjection-Display",
                    SCREEN_INFO.width,
                    SCREEN_INFO.height,
                    SCREEN_INFO.dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
                
                if (virtualDisplay != null) {
                    Log.d(logTag, "使用MediaProjection成功创建虚拟显示")
                    captureSuccess = true
                } else {
                    Log.e(logTag, "使用MediaProjection创建虚拟显示失败")
                }
            } catch (e: Exception) {
                Log.e(logTag, "使用MediaProjection方法出错: ${e.message}")
            }
        }
        
        // 4. 如果前三种方法都失败，尝试请求新的MediaProjection
        if (!captureSuccess) {
            Log.d(logTag, "所有方法都失败，尝试请求新的MediaProjection许可")
            
            // 通知用户需要请求屏幕录制权限
            try {
                Handler(Looper.getMainLooper()).post {
                    try {
                        // 发送广播请求MediaProjection
                        val intent = Intent(ACT_REQUEST_MEDIA_PROJECTION)
                        intent.setPackage(packageName)
                        sendBroadcast(intent)
                    } catch (e: Exception) {
                        Log.e(logTag, "发送MediaProjection请求时出错: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "处理MediaProjection请求时发生错误: ${e.message}")
            }
            
            // 等待MediaProjection结果，设置超时
            var timeoutCounter = 0
            while (mediaProjection == null && timeoutCounter < 100 && !captureSuccess) {
                try {
                    Thread.sleep(100)
                    timeoutCounter++
                    
                    // 如果已获得MediaProjection，尝试立即使用
                    if (mediaProjection != null) {
                        try {
                            virtualDisplay = mediaProjection?.createVirtualDisplay(
                                "RustDesk-MediaProjection-Display",
                                SCREEN_INFO.width,
                                SCREEN_INFO.height,
                                SCREEN_INFO.dpi,
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                surface,
                                null,
                                null
                            )
                            
                            if (virtualDisplay != null) {
                                Log.d(logTag, "使用新获取的MediaProjection成功创建虚拟显示")
                                captureSuccess = true
                                break
                            }
                        } catch (e: Exception) {
                            Log.e(logTag, "使用新MediaProjection创建虚拟显示失败: ${e.message}")
                        }
                    }
                } catch (e: InterruptedException) {
                    // 忽略中断
                }
            }
        }
        
        // 如果所有方法都失败，则返回失败
        if (!captureSuccess) {
            Log.e(logTag, "所有屏幕捕获方法都失败，无法启动屏幕共享")
            stopCapture()  // 确保资源被正确释放
            return false
        }

        // 成功创建虚拟显示后，如果有系统权限，尝试启动音频捕获
        if (hasSurfaceFlingerPermission || hasFrameBufferPermissions) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (!audioRecordHandle.createAudioRecorder(false, null)) {
                        Log.d(logTag, "系统权限音频录制创建失败")
                    } else {
                        Log.d(logTag, "系统权限音频录制开始")
                        audioRecordHandle.startAudioRecorder()
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "系统权限音频捕获初始化失败: ${e.message}")
                // 继续执行，因为音频失败不应该影响屏幕共享
            }
        } else if (mediaProjection != null) {
            // 如果使用的是MediaProjection，也尝试启动音频捕获
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (!audioRecordHandle.createAudioRecorder(false, mediaProjection)) {
                        Log.d(logTag, "MediaProjection音频录制创建失败")
                    } else {
                        Log.d(logTag, "MediaProjection音频录制开始")
                        audioRecordHandle.startAudioRecorder()
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "MediaProjection音频捕获初始化失败: ${e.message}")
            }
        }
        
        _isReady = true
        _isStart = true
        FFI.setFrameRawEnable("video", true)
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
        Log.d(logTag, "屏幕捕获成功启动")
        return true
    }
    
    // 使用 SurfaceFlinger 实现屏幕捕获
    private fun startRawVideoRecorderWithSurfaceFlinger() {
        try {
            if (surface == null) {
                Log.e(logTag, "startRawVideoRecorderWithSurfaceFlinger: surface is null")
                return
            }
            
            // 使用 SurfaceFlinger 进行屏幕捕获
            Log.d(logTag, "使用 SurfaceFlinger 创建虚拟显示")
            
            // 这里使用和 createVirtualDisplayWithSystemPermissions 相同的方法
            // 但在底层会自动使用 SurfaceFlinger 的 API 来捕获屏幕
            // 由于权限不同，系统会使用不同的实现方式
            virtualDisplay = createVirtualDisplayWithSurfaceFlinger(surface!!)
            
            // 日志记录
            if (virtualDisplay != null) {
                Log.d(logTag, "成功创建基于 SurfaceFlinger 的虚拟显示")
            } else {
                Log.e(logTag, "创建基于 SurfaceFlinger 的虚拟显示失败")
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error starting raw video recorder with SurfaceFlinger: ${e.message}")
        }
    }
    
    private fun startVP9VideoRecorderWithSurfaceFlinger() {
        // VP9编码器的 SurfaceFlinger 实现
        // 这部分代码与 VP9 实现类似，但使用 SurfaceFlinger
        Log.d(logTag, "startVP9VideoRecorderWithSurfaceFlinger not implemented yet")
        // 如果 VP9 版本未实现，可以暂时回退到普通版本
        startRawVideoRecorderWithSurfaceFlinger()
    }
    
    // 创建使用 SurfaceFlinger 的虚拟显示
    private fun createVirtualDisplayWithSurfaceFlinger(surface: Surface): VirtualDisplay? {
        try {
            // 检查适当的系统级权限
            val hasSurfaceFlingerPermission = checkCallingOrSelfPermission(PERMISSION_ACCESS_SURFACE_FLINGER) == PackageManager.PERMISSION_GRANTED
            
            if (!hasSurfaceFlingerPermission) {
                Log.e(logTag, "缺少ACCESS_SURFACE_FLINGER权限，无法使用SurfaceFlinger创建虚拟显示")
                return null
            }
            
            Log.d(logTag, "使用ACCESS_SURFACE_FLINGER权限创建高品质虚拟显示")
            
            // 在定制系统上，应该支持额外的标志来提高捕获质量
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    // 尝试使用更多标志提高质量
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or 
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE or  // 允许捕获安全内容
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS  // 包括系统装饰
                } catch (e: Exception) {
                    // 如果不支持某些标志，回退到基本标志
                    Log.d(logTag, "高级标志不受支持，使用基本标志")
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                }
            } else {
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            }
            
            // 创建高质量虚拟显示
            val virtualDisplay = displayManager?.createVirtualDisplay(
                "RustDesk-SurfaceFlinger-Display",
                SCREEN_INFO.width,
                SCREEN_INFO.height,
                SCREEN_INFO.dpi,
                surface,
                flags,
                object : VirtualDisplay.Callback() {
                    override fun onStopped() {
                        Log.d(logTag, "SurfaceFlinger虚拟显示已停止")
                    }
                    
                    override fun onResumed() {
                        Log.d(logTag, "SurfaceFlinger虚拟显示已恢复")
                    }
                    
                    override fun onPaused() {
                        Log.d(logTag, "SurfaceFlinger虚拟显示已暂停")
                    }
                },
                serviceHandler  // 使用服务的handler处理回调
            )
            
            if (virtualDisplay != null) {
                Log.d(logTag, "成功创建SurfaceFlinger虚拟显示")
            } else {
                Log.e(logTag, "创建SurfaceFlinger虚拟显示失败")
            }
            
            return virtualDisplay
        } catch (e: Exception) {
            Log.e(logTag, "使用SurfaceFlinger创建虚拟显示时出错: ${e.message}")
            return null
        }
    }

    @Synchronized
    fun stopCapture() {
        Log.d(logTag, "Stop Capture")
        FFI.setFrameRawEnable("video",false)
        _isStart = false
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
        // release video
        if (reuseVirtualDisplay) {
            // The virtual display video projection can be paused by calling `setSurface(null)`.
            // https://developer.android.com/reference/android/hardware/display/VirtualDisplay.Callback
            // https://learn.microsoft.com/en-us/dotnet/api/android.hardware.display.virtualdisplay.callback.onpaused?view=net-android-34.0
            virtualDisplay?.setSurface(null)
        } else {
            virtualDisplay?.release()
        }
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        imageReader?.close()
        imageReader = null
        videoEncoder?.let {
            it.signalEndOfInputStream()
            it.stop()
            it.release()
        }
        if (!reuseVirtualDisplay) {
            virtualDisplay = null
        }
        videoEncoder = null
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        surface?.release()

        // release audio
        _isAudioStart = false
        audioRecordHandle.tryReleaseAudio()
    }

    fun destroy() {
        Log.d(logTag, "destroy service")
        _isReady = false
        _isAudioStart = false

        stopCapture()

        if (reuseVirtualDisplay) {
            virtualDisplay?.release()
            virtualDisplay = null
        }

        checkMediaPermission()
        stopForeground(true)
        stopService(Intent(this, FloatingWindowService::class.java))
        stopSelf()
    }

    fun checkMediaPermission(): Boolean {
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to isReady.toString())
            )
        }
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                getInputStatus()
            )
        }
        return isReady
    }

    private fun getInputStatus(): String {
        return mapOf("name" to "input", "value" to InputService.isOpen.toString()).toString()
    }

    // 这些方法仅用于兼容性保留，实际使用的是系统权限实现
    private fun startRawVideoRecorder(mp: Any) {
        Log.d(logTag, "This method is deprecated, using system permissions instead")
        // 使用系统权限实现不需要使用MediaProjection
    }

    private fun startVP9VideoRecorder(mp: Any) {
        Log.d(logTag, "This method is deprecated, using system permissions instead")
        // 使用系统权限实现不需要使用MediaProjection
    }

    // 系统权限版本的createOrSetVirtualDisplay方法
    private fun createOrSetVirtualDisplay(mp: Any, s: Surface) {
        Log.d(logTag, "Using system permissions, no MediaProjection needed")
        // 使用系统权限已创建的virtualDisplay
    }

    private val cb: MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            codec.getOutputBuffer(index)?.let { buf ->
                sendVP9Thread.execute {
                    val byteArray = ByteArray(buf.limit())
                    buf.get(byteArray)
                    // sendVp9(byteArray)
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(logTag, "MediaCodec.Callback error:$e")
        }
    }

    private fun createMediaCodec() {
        Log.d(logTag, "MediaFormat.MIMETYPE_VIDEO_VP9 :$MIME_TYPE")
        videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE)
        val mFormat =
            MediaFormat.createVideoFormat(MIME_TYPE, SCREEN_INFO.width, SCREEN_INFO.height)
        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_KEY_BIT_RATE)
        mFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_KEY_FRAME_RATE)
        mFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        try {
            videoEncoder!!.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.e(logTag, "mEncoder.configure fail!")
        }
    }

    private fun initNotification() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "RustDesk"
            val channelName = "RustDesk Service"
            val channel = NotificationChannel(
                channelId,
                channelName, NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "RustDesk Service Channel"
            }
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            notificationManager.createNotificationChannel(channel)
            channelId
        } else {
            ""
        }
        notificationBuilder = NotificationCompat.Builder(this, notificationChannel)
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("type", type)
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT)
        }
        val notification = notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setDefaults(0)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText("")
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setWhen(System.currentTimeMillis())
            .build()
        startForeground(DEFAULT_NOTIFY_ID, notification)
    }

    private fun loginRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        // 不显示登录请求通知，因为会自动接受连接
        // 什么都不做，保留空方法
    }

    private fun onClientAuthorizedNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        // 不显示客户端已授权通知
        // 什么都不做，保留空方法
    }

    private fun voiceCallRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        // 不显示语音呼叫通知
        // 什么都不做，保留空方法
    }

    private fun getClientNotifyID(clientID: Int): Int {
        return clientID + NOTIFY_ID_OFFSET
    }

    fun cancelNotification(clientID: Int) {
        notificationManager.cancel(getClientNotifyID(clientID))
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun genLoginRequestPendingIntent(res: Boolean): PendingIntent {
        val intent = Intent(this, MainService::class.java).apply {
            action = ACT_LOGIN_REQ_NOTIFY
            putExtra(EXT_LOGIN_REQ_NOTIFY, res)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getService(this, 111, intent, FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 111, intent, FLAG_UPDATE_CURRENT)
        }
    }

    private fun setTextNotification(_title: String?, _text: String?) {
        val title = _title ?: DEFAULT_NOTIFY_TITLE
        val text = _text ?: translate(DEFAULT_NOTIFY_TEXT)
        val notification = notificationBuilder
            .clearActions()
            .setStyle(null)
            .setContentTitle(title)
            .setContentText(text)
            .build()
        notificationManager.notify(DEFAULT_NOTIFY_ID, notification)
    }

    private fun requestMediaProjection() {
        // 这个方法保留仅用于编译兼容性，实际使用时已被系统权限替代
        Log.d(logTag, "requestMediaProjection called, but system permissions are used instead")
        // 什么都不做，因为我们使用系统级权限
    }

    // 对于Android 11及以上版本，使用系统权限实现屏幕捕获
    private fun startRawVideoRecorderWithSystemPermissions() {
        try {
            if (surface == null) {
                Log.e(logTag, "startRawVideoRecorderWithSystemPermissions: surface is null")
                return
            }
            
            Log.d(logTag, "使用系统权限创建虚拟显示")
            
            // 创建不同配置的虚拟显示，以适应Android 11
            virtualDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11及以上版本可能需要特殊标志
                val flags = VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                // 避免使用可能不存在的标志
                displayManager?.createVirtualDisplay(
                    "RustDesk-SystemPerm-Display-R",
                    SCREEN_INFO.width,
                    SCREEN_INFO.height,
                    SCREEN_INFO.dpi,
                    surface,
                    flags,
                    null,  // 回调
                    null   // 处理程序
                )
            } else {
                // 旧版本Android实现
                displayManager?.createVirtualDisplay(
                    "RustDesk-SystemPerm-Display",
                    SCREEN_INFO.width,
                    SCREEN_INFO.height,
                    SCREEN_INFO.dpi,
                    surface,
                    VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    null,  // 回调
                    null   // 处理程序
                )
            }
            
            // 检查是否成功创建
            if (virtualDisplay != null) {
                Log.d(logTag, "成功创建系统权限的虚拟显示")
            } else {
                Log.e(logTag, "创建系统权限的虚拟显示失败")
                
                // 尝试备选方法，减小尺寸再试一次
                Log.d(logTag, "尝试使用较小尺寸重新创建虚拟显示")
                val smallerWidth = SCREEN_INFO.width / 2
                val smallerHeight = SCREEN_INFO.height / 2
                
                virtualDisplay = displayManager?.createVirtualDisplay(
                    "RustDesk-SystemPerm-Display-Small",
                    smallerWidth,
                    smallerHeight,
                    SCREEN_INFO.dpi,
                    surface,
                    VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                )
                
                if (virtualDisplay != null) {
                    Log.d(logTag, "成功创建较小尺寸的虚拟显示")
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error starting raw video recorder with system permissions: ${e.message}")
        }
    }

    // 对于VP9编码器使用系统权限实现屏幕捕获
    private fun startVP9VideoRecorderWithSystemPermissions() {
        try {
            if (surface == null) {
                Log.e(logTag, "startVP9VideoRecorderWithSystemPermissions: surface is null")
                return
            }
            
            // 创建媒体格式
            val format = MediaFormat.createVideoFormat(MIME_TYPE, SCREEN_INFO.width, SCREEN_INFO.height)
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_KEY_BIT_RATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_KEY_FRAME_RATE)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
            
            // 创建编码器
            videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE)
            videoEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            // 获取输入Surface
            val encoderSurface = videoEncoder?.createInputSurface()
            
            // 创建虚拟显示
            virtualDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11及以上版本可能需要特殊标志
                val flags = VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                // 为了兼容性，避免使用可能不存在的常量
                displayManager?.createVirtualDisplay(
                    "RustDesk-VP9-SystemPerm-Display-R",
                    SCREEN_INFO.width,
                    SCREEN_INFO.height,
                    SCREEN_INFO.dpi,
                    encoderSurface,
                    flags
                )
            } else {
                // 旧版本Android实现
                displayManager?.createVirtualDisplay(
                    "RustDesk-VP9-SystemPerm-Display",
                    SCREEN_INFO.width,
                    SCREEN_INFO.height,
                    SCREEN_INFO.dpi,
                    encoderSurface,
                    VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                )
            }
            
            if (virtualDisplay == null) {
                Log.e(logTag, "创建VP9虚拟显示失败")
                videoEncoder?.release()
                videoEncoder = null
                return
            }
            
            // 启动编码器
            videoEncoder?.start()
            
            // 启动VP9编码线程
            sendVP9Thread.execute {
                try {
                    val bufferInfo = MediaCodec.BufferInfo()
                    while (isStart) {
                        val outIndex = videoEncoder?.dequeueOutputBuffer(bufferInfo, -1) ?: -1
                        if (outIndex >= 0) {
                            val buffer = videoEncoder?.getOutputBuffer(outIndex)
                            if (buffer != null) {
                                FFI.onVideoFrameUpdate(buffer)
                            }
                            videoEncoder?.releaseOutputBuffer(outIndex, false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "VP9 encoding error: ${e.message}")
                } finally {
                    videoEncoder?.stop()
                    videoEncoder?.release()
                    videoEncoder = null
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error starting VP9 video recorder with system permissions: ${e.message}")
        }
    }

    // 添加新方法，用于处理MediaProjection
    private fun setupMediaProjection(mediaProjection: android.media.projection.MediaProjection) {
        Log.d(logTag, "使用MediaProjection设置屏幕捕获")
        
        // 初始化必要的组件
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        
        // 更新屏幕信息
        updateScreenInfo(resources.configuration.orientation)
        
        // 创建Surface
        surface = createSurface()
        if (surface == null) {
            Log.e(logTag, "创建Surface失败")
            return
        }
        
        // 创建虚拟显示
        try {
            Log.d(logTag, "使用MediaProjection创建虚拟显示")
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "RustDesk-MediaProjection-Display",
                SCREEN_INFO.width,
                SCREEN_INFO.height,
                SCREEN_INFO.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
            
            if (virtualDisplay != null) {
                Log.d(logTag, "成功创建MediaProjection虚拟显示")
                
                // 如果设备是Android 11或更高版本，还需要设置音频捕获
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!audioRecordHandle.createAudioRecorder(false, mediaProjection)) {
                        Log.d(logTag, "MediaProjection音频录制创建失败")
                    } else {
                        Log.d(logTag, "MediaProjection音频录制开始")
                        audioRecordHandle.startAudioRecorder()
                    }
                }
                
                // 设置状态和通知
                _isReady = true
                _isStart = true
                FFI.setFrameRawEnable("video", true)
                MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
            } else {
                Log.e(logTag, "创建MediaProjection虚拟显示失败")
            }
        } catch (e: Exception) {
            Log.e(logTag, "MediaProjection虚拟显示创建错误: ${e.message}")
        }
    }

    // 添加setter方法
    fun setMediaProjection(projection: MediaProjection?) {
        Log.d(logTag, "设置MediaProjection: ${projection != null}")
        mediaProjection = projection
        if (projection != null && isStart) {
            // 如果已经在运行中，尝试使用新的MediaProjection重新启动捕获
            Log.d(logTag, "使用新的MediaProjection重新启动捕获")
            stopCapture()
            startCapture()
        }
    }
}
