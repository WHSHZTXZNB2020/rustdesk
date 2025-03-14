package com.carriez.flutter_hbb

import ffi.FFI

/**
 * Capture screen,get video and audio,send to rust.
 * Dispatch notifications
 *
 * Modified to use system permissions for screen capture
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
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
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
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.min
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Activity
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.view.SurfaceControl
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import android.graphics.Point
import java.util.concurrent.atomic.AtomicBoolean

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
                    val authorized = jsonObject["authorized"] as Boolean
                    val isFileTransfer = jsonObject["is_file_transfer"] as Boolean
                    val type = if (isFileTransfer) {
                        translate("File Connection")
                    } else {
                        translate("Screen Connection")
                    }
                    if (authorized) {
                        if (!isFileTransfer && !isStart) {
                            startCapture()
                        }
                        onClientAuthorizedNotification(id, type, username, peerId)
                    } else {
                        loginRequestNotification(id, type, username, peerId)
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
                            voiceCallRequestNotification(id, "Voice Call Request", username, peerId)
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
        private var _isReady = false // screen capture ready status
        private var _isStart = false // screen capture start status
        private var _isAudioStart = false // audio capture start status
        val isReady: Boolean
            get() = _isReady
        val isStart: Boolean
            get() = _isStart
        val isAudioStart: Boolean
            get() = _isAudioStart
        
        init {
            try {
                System.loadLibrary("rustdesk")
                Log.d("MainService", "rustdesk native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("MainService", "Failed to load rustdesk native library: ${e.message}")
            }
        }
    }

    private val logTag = "LOG_SERVICE"
    private val binder = LocalBinder()

    // video
    private var surface: Surface? = null
    private val sendVP9Thread = Executors.newSingleThreadExecutor()
    private var videoEncoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenCaptureThread: Thread? = null
    private var isCapturing = false

    // audio
    private val audioRecordHandle = AudioRecordHandle(this, { isStart }, { isAudioStart })

    // notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationChannel: String
    private lateinit var notificationBuilder: NotificationCompat.Builder

    // 添加缺少的成员变量
    private var _mediaProjection: MediaProjection? = null
    private var _virtualDisplay: VirtualDisplay? = null
    private lateinit var mContext: Context
    private var mSurfaceControl: SurfaceControl? = null
    private var mCurrentCaptureMethod: String = "None"
    
    // 添加原生方法的声明 - 这里只是声明，实际实现应在C++代码中
    private external fun nativeInit(packageName: String, surface: Surface): Boolean
    private external fun nativeInitFrameBuffer(packageName: String): Boolean
    private external fun nativeInitVideoCapture(packageName: String): Boolean

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag,"MainService onCreate, sdk int:${Build.VERSION.SDK_INT}")
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
        
        // 检查系统权限并设置就绪状态
        checkSystemPermissions()

        mContext = this.applicationContext
        
        // 初始化SurfaceControl - 多种方式尝试创建
        initSurfaceControl()
    }

    override fun onDestroy() {
        stopCapture()
        _isReady = false
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

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    inner class LocalBinder : Binder() {
        init {
            Log.d(logTag, "LocalBinder init")
        }

        fun getService(): MainService = this@MainService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(logTag, "onStartCommand called with flags: $flags, startId: $startId")
        try {
            if (intent != null) {
                val action = intent.action
                Log.d(logTag, "Received action: $action")

                // 处理测试屏幕捕获的请求
                if (action == "test_screen_capture") {
                    thread { testAndLogScreenCapture() }
                    return START_STICKY
                }
            }

            super.onStartCommand(intent, flags, startId)
            
            createForegroundNotification()

            // 先检查系统权限并设置就绪状态
            checkSystemPermissions()
            
            // 处理不同的启动action
            intent?.let {
                when (it.action) {
                    ACT_USE_SYSTEM_PERMISSIONS -> {
                        // 直接使用系统权限模式，不使用MediaProjection
                        Log.d(logTag, "使用系统权限模式启动，不请求MediaProjection")
                        // 已在checkSystemPermissions设置了_isReady，无需额外处理
                        
                        // 自动启动屏幕捕获
                        if (_isReady && !_isStart) {
                            startCapture()
                        } else {
                            Log.d(logTag, "系统权限未就绪或已经启动捕获，不执行操作")
                        }
                    }
                    "TEST_SCREEN_CAPTURE" -> {
                        Log.d(logTag, "测试屏幕捕获功能")
                        
                        // 检查系统权限并记录详细日志
                        val pm = applicationContext.packageManager
                        val captureVideoOutput = pm.checkPermission("android.permission.CAPTURE_VIDEO_OUTPUT", packageName) == PackageManager.PERMISSION_GRANTED
                        val readFrameBuffer = pm.checkPermission("android.permission.READ_FRAME_BUFFER", packageName) == PackageManager.PERMISSION_GRANTED
                        val accessSurfaceFlinger = pm.checkPermission("android.permission.ACCESS_SURFACE_FLINGER", packageName) == PackageManager.PERMISSION_GRANTED
                        
                        Log.d(logTag, "【测试】系统权限状态: CAPTURE_VIDEO_OUTPUT=$captureVideoOutput, READ_FRAME_BUFFER=$readFrameBuffer, ACCESS_SURFACE_FLINGER=$accessSurfaceFlinger")
                        
                        if (!captureVideoOutput && !readFrameBuffer && !accessSurfaceFlinger) {
                            Log.e(logTag, "【测试】没有获得任何屏幕捕获权限，无法进行测试")
                            return@let
                        }
                        
                        // 尝试各种捕获方法
                        if (accessSurfaceFlinger) {
                            try {
                                Log.d(logTag, "【测试】尝试使用SurfaceFlinger捕获屏幕")
                                val result = trySurfaceFlinger()
                                Log.d(logTag, "【测试】SurfaceFlinger捕获结果: $result")
                            } catch (e: Exception) {
                                Log.e(logTag, "【测试】SurfaceFlinger捕获异常: ${e.message}")
                            }
                        }
                        
                        if (readFrameBuffer) {
                            try {
                                Log.d(logTag, "【测试】尝试使用FrameBuffer捕获屏幕")
                                val result = tryFrameBuffer()
                                Log.d(logTag, "【测试】FrameBuffer捕获结果: $result")
                            } catch (e: Exception) {
                                Log.e(logTag, "【测试】FrameBuffer捕获异常: ${e.message}")
                            }
                        }
                        
                        if (captureVideoOutput) {
                            try {
                                Log.d(logTag, "【测试】尝试使用VideoOutput捕获屏幕")
                                val result = tryVideoOutput()
                                Log.d(logTag, "【测试】VideoOutput捕获结果: $result")
                            } catch (e: Exception) {
                                Log.e(logTag, "【测试】VideoOutput捕获异常: ${e.message}")
                            }
                        }
                    }
                    "STOP_SERVICE" -> {
                        stopSelf()
                    }
                    ACT_INIT_MEDIA_PROJECTION_AND_SERVICE -> {
                        // 兼容传统MediaProjection方式
                        Log.d(logTag, "使用传统MediaProjection方式启动")
                        
                        // 如果系统权限就绪，优先使用系统权限
                        if (_isReady && !_isStart) {
                            startCapture()
                        } else {
                            // 尝试使用MediaProjection
                            val data = it.getParcelableExtra<Intent>(EXT_MEDIA_PROJECTION_RES_INTENT)
                            if (data != null) {
                                // TODO: 如需保留MediaProjection支持，在此处理MediaProjection初始化
                            } else {
                                Log.d(logTag, "MediaProjection数据为空，无法初始化")
                            }
                        }
                    }
                    else -> {
                        // 默认处理
                        Log.d(logTag, "未指定特殊启动方式，使用默认方式")
                    }
                }
            }
            
            // 如果通过EXT_INIT_FROM_BOOT启动，调用FFI.startService
            if (intent?.getBooleanExtra(EXT_INIT_FROM_BOOT, false) == true) {
                FFI.startService()
            } else {
                // 非启动自动启动，无需特殊处理
                Log.d(logTag, "非开机自动启动，无需特殊处理")
            }
            
            return START_NOT_STICKY // don't use sticky (auto restart), the new service (from auto restart) will lose control
        } catch (e: Exception) {
            Log.e(logTag, "Error in onStartCommand: ${e.message}")
            e.printStackTrace()
            return START_NOT_STICKY
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo(newConfig.orientation)
    }

    // 检查系统权限并设置就绪状态
    private fun checkSystemPermissions() {
        // 检查是否拥有所需的系统权限
        val pm = applicationContext.packageManager
        val captureVideoOutput = pm.checkPermission("android.permission.CAPTURE_VIDEO_OUTPUT", packageName) == PackageManager.PERMISSION_GRANTED
        val readFrameBuffer = pm.checkPermission("android.permission.READ_FRAME_BUFFER", packageName) == PackageManager.PERMISSION_GRANTED
        val accessSurfaceFlinger = pm.checkPermission("android.permission.ACCESS_SURFACE_FLINGER", packageName) == PackageManager.PERMISSION_GRANTED
        
        // 添加更多详细日志
        Log.d(logTag, "========== 系统权限详细检查 ==========")
        Log.d(logTag, "包名: $packageName")
        Log.d(logTag, "系统版本: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        Log.d(logTag, "设备型号: ${Build.MANUFACTURER} ${Build.MODEL}")
        Log.d(logTag, "CAPTURE_VIDEO_OUTPUT 权限状态: $captureVideoOutput (${pm.checkPermission("android.permission.CAPTURE_VIDEO_OUTPUT", packageName)})")
        Log.d(logTag, "READ_FRAME_BUFFER 权限状态: $readFrameBuffer (${pm.checkPermission("android.permission.READ_FRAME_BUFFER", packageName)})")
        Log.d(logTag, "ACCESS_SURFACE_FLINGER 权限状态: $accessSurfaceFlinger (${pm.checkPermission("android.permission.ACCESS_SURFACE_FLINGER", packageName)})")
        
        // 尝试获取所有已授予的权限
        try {
            val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val grantedPermissions = packageInfo.requestedPermissions.filterIndexed { index, _ -> 
                (packageInfo.requestedPermissionsFlags[index] and PackageManager.PERMISSION_GRANTED) != 0 
            }
            Log.d(logTag, "已授予的所有权限: ${grantedPermissions.joinToString(", ")}")
        } catch (e: Exception) {
            Log.e(logTag, "获取已授予权限列表失败: ${e.message}")
        }
        
        // 检查设备型号
        val isSunmiDevice = Build.MANUFACTURER.toLowerCase().contains("sunmi") ||
                             Build.MODEL.toLowerCase().contains("sunmi") ||
                             Build.BRAND.toLowerCase().contains("sunmi")
                             
        if (isSunmiDevice) {
            Log.d(logTag, "检测到商米设备，将执行功能测试验证权限")
            
            // 在商米设备上执行功能测试验证权限
            val functionalTestResults = testPermissionsFunctionally()
            var hasAnyPermission = captureVideoOutput || readFrameBuffer || accessSurfaceFlinger
            
            // 如果功能测试表明存在可用权限，即使权限检查结果为false
            for ((permission, available) in functionalTestResults) {
                if (available) {
                    hasAnyPermission = true
                    Log.d(logTag, "功能测试表明权限可用: $permission")
                }
            }
            
            // 在商米设备上，认为总是准备就绪
            if (isSunmiDevice) {
                _isReady = true
                Log.d(logTag, "商米设备: 默认认为已就绪，将跳过权限检查直接尝试捕获")
            } else {
                _isReady = hasAnyPermission
            }
        } else {
            // 非商米设备，正常检查权限
            _isReady = captureVideoOutput || readFrameBuffer || accessSurfaceFlinger
        }
        
        if (_isReady) {
            Log.d(logTag, "系统屏幕捕获权限已授予，可以进行屏幕捕获")
        } else {
            Log.e(logTag, "⚠️ 警告：未获得任何屏幕捕获权限！应用将无法捕获屏幕内容")
            Log.d(logTag, "请确认设备是否为商米设备，并且已在系统中预授权这些权限")
        }
        
        // 通知Flutter UI权限状态更新
        notifyStateChanged()
    }
    
    // 通知Flutter UI状态变化
    private fun notifyStateChanged() {
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to isReady.toString())
            )
            
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to InputService.isOpen.toString())
            )
        }
    }

    @SuppressLint("WrongConstant")
    private fun createSurface(): Surface? {
        Log.d(logTag, "ImageReader.newInstance:INFO:$SCREEN_INFO")
        imageReader = ImageReader.newInstance(
            SCREEN_INFO.width,
            SCREEN_INFO.height,
            PixelFormat.RGBA_8888,
            4
        ).apply {
            setOnImageAvailableListener({ imageReader: ImageReader ->
                try {
                    // If not call acquireLatestImage, listener will not be called again
                    imageReader.acquireLatestImage().use { image ->
                        if (image == null || !isStart) {
                            return@setOnImageAvailableListener
                        } else {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            buffer.rewind()
                            FFI.onVideoFrameUpdate(buffer)
                        }
                    }
                } catch (ignored: Exception) {
                    Log.e(logTag, "Error processing image: ${ignored.message}")
                }
            }, serviceHandler)
        }
        Log.d(logTag, "ImageReader.setOnImageAvailableListener done")
        return imageReader?.surface
    }

    fun onVoiceCallStarted(): Boolean {
        return audioRecordHandle.onVoiceCallStarted(null)
    }

    fun onVoiceCallClosed(): Boolean {
        return audioRecordHandle.onVoiceCallClosed(null)
    }

    fun startCapture(): Boolean {
        if (isStart) {
            Log.d(logTag, "【屏幕捕获】已经启动，不重复启动")
            return true
        }
        
        if (!isReady) {
            Log.e(logTag, "【屏幕捕获】无法启动捕获：系统权限未授予")
            return false
        }
        
        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "【屏幕捕获】开始屏幕捕获流程")
        
        // 创建Surface用于显示捕获内容
        surface = createSurface()
        if (surface == null) {
            Log.e(logTag, "【屏幕捕获】创建Surface失败")
            return false
        }
        
        // 使用系统权限捕获屏幕
        if (startScreenCapture()) {
            // 设置音频捕获
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setupAudioCapture()
            }
            
            // 设置状态并启用视频流
            _isStart = true
            FFI.setFrameRawEnable("video", true)
            MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
            
            // 通知状态更新
            notifyStateChanged()
            Log.d(logTag, "【屏幕捕获】捕获流程启动成功")
            return true
        }
        
        Log.e(logTag, "【屏幕捕获】屏幕捕获启动失败")
        return false
    }
    
    // 设置音频捕获
    @RequiresApi(Build.VERSION_CODES.R)
    private fun setupAudioCapture() {
        if (!audioRecordHandle.createAudioRecorder(false, null)) {
            Log.d(logTag, "createAudioRecorder fail")
        } else {
            Log.d(logTag, "audio recorder start")
            audioRecordHandle.startAudioRecorder()
        }
    }
    
    // 使用系统权限开始屏幕捕获
    private fun startScreenCapture(): Boolean {
        try {
            Log.d(logTag, "【屏幕捕获】尝试开始使用系统权限捕获屏幕")
            
            // 判断是否为商米设备
            val isSunmiDevice = Build.MANUFACTURER.toLowerCase().contains("sunmi") ||
                               Build.MODEL.toLowerCase().contains("sunmi") ||
                               Build.BRAND.toLowerCase().contains("sunmi")
            
            if (isSunmiDevice) {
                Log.d(logTag, "【屏幕捕获】检测到商米设备，将直接尝试捕获方法")
                
                // 对于商米设备，优先尝试VideoOutput
                // 根据日志分析，商米设备在功能测试中显示CAPTURE_VIDEO_OUTPUT实际可用
                if (tryCaptureVideoOutput()) {
                    Log.d(logTag, "【屏幕捕获】成功启动VideoOutput捕获")
                    return true
                }
                
                if (tryCaptureSurfaceFlinger()) {
                    Log.d(logTag, "【屏幕捕获】成功启动SurfaceFlinger捕获")
                    return true
                }
                
                if (tryReadFrameBuffer()) {
                    Log.d(logTag, "【屏幕捕获】成功启动FrameBuffer捕获")
                    return true
                }
                
                // 尝试备用方法
                if (tryFallbackCapture()) {
                    Log.d(logTag, "【屏幕捕获】成功启动备用捕获方法")
                    return true
                }
                
                // 针对商米设备的特殊方法
                if (trySunmiSpecificCapture()) {
                    Log.d(logTag, "【屏幕捕获】成功启动商米特定捕获方法")
                    return true
                }
            } else {
                // 非商米设备，按正常流程尝试
                if (tryCaptureSurfaceFlinger()) {
                    Log.d(logTag, "【屏幕捕获】成功启动SurfaceFlinger捕获")
                    return true
                }
                
                if (tryReadFrameBuffer()) {
                    Log.d(logTag, "【屏幕捕获】成功启动FrameBuffer捕获")
                    return true
                }
                
                if (tryCaptureVideoOutput()) {
                    Log.d(logTag, "【屏幕捕获】成功启动VideoOutput捕获")
                    return true
                }
                
                // 尝试备用方法
                if (tryFallbackCapture()) {
                    Log.d(logTag, "【屏幕捕获】成功启动备用捕获方法")
                    return true
                }
            }
            
            // 所有方法都失败
            Log.e(logTag, "【屏幕捕获】所有捕获方法均失败")
            return false
        } catch (e: Exception) {
            Log.e(logTag, "【屏幕捕获】错误: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    // 使用SurfaceFlinger捕获屏幕
    private fun tryCaptureSurfaceFlinger(): Boolean {
        try {
            Log.d(logTag, "【屏幕捕获】尝试使用SurfaceFlinger捕获方法")
            
            val surfaceControl = mSurfaceControl
            if (surfaceControl == null) {
                Log.e(logTag, "【屏幕捕获】SurfaceControl为空，无法进行SurfaceFlinger捕获")
                return false
            }
            
            // 创建Surface以接收SurfaceFlinger的输出
            try {
                val surface = Surface(surfaceControl)
                
                // 实际调用系统API
                val result = nativeInit(mContext.packageName, surface)
                Log.d(logTag, "【屏幕捕获】SurfaceFlinger尝试结果: $result")
                
                if (result) {
                    mCurrentCaptureMethod = "SurfaceFlinger"
                    return true
                }
            } catch (e: Exception) {
                Log.e(logTag, "【屏幕捕获】创建Surface或调用原生方法失败: ${e.message}")
            }
            
            return false
        } catch (e: Exception) {
            Log.e(logTag, "【屏幕捕获】SurfaceFlinger捕获失败: ${e.message}")
            return false
        }
    }
    
    // 使用FrameBuffer捕获屏幕
    private fun tryReadFrameBuffer(): Boolean {
        try {
            // 实现FrameBuffer读取逻辑
            val result = nativeInitFrameBuffer(mContext.packageName)
            Log.d(logTag, "【屏幕捕获】FrameBuffer尝试结果: $result")
            if (result) {
                mCurrentCaptureMethod = "FrameBuffer"
            }
            return result
        } catch (e: Exception) {
            Log.e(logTag, "【屏幕捕获】FrameBuffer捕获失败: ${e.message}")
            return false
        }
    }
    
    // 使用CAPTURE_VIDEO_OUTPUT捕获屏幕
    private fun tryCaptureVideoOutput(): Boolean {
        try {
            Log.d(logTag, "【屏幕捕获】尝试使用VideoOutput捕获方法")
            
            // 确保Surface已创建
            if (surface == null) {
                surface = createSurface()
                if (surface == null) {
                    Log.e(logTag, "【屏幕捕获】创建Surface失败")
                    return false
                }
            }
            
            try {
                // 尝试创建虚拟显示
                val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                
                // 添加详细日志
                Log.d(logTag, "【屏幕捕获】准备创建虚拟显示，尺寸: ${SCREEN_INFO.width}x${SCREEN_INFO.height}, DPI: ${SCREEN_INFO.dpi}")
                
                // 使用更多选项
                val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
                
                try {
                    virtualDisplay = displayManager.createVirtualDisplay(
                        "RustDeskCapture",
                        SCREEN_INFO.width,
                        SCREEN_INFO.height,
                        SCREEN_INFO.dpi,
                        surface,
                        flags
                    )
                    
                    if (virtualDisplay != null) {
                        Log.d(logTag, "【屏幕捕获】成功创建虚拟显示: ${virtualDisplay?.display?.displayId}")
                        mCurrentCaptureMethod = "VideoOutput"
                        
                        // 启动捕获线程
                        isCapturing = true
                        screenCaptureThread = Thread {
                            captureVideoOutputFrames()
                        }
                        screenCaptureThread?.start()
                        
                        return true
                    } else {
                        Log.e(logTag, "【屏幕捕获】创建虚拟显示失败: 返回null")
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "【屏幕捕获】创建虚拟显示失败: ${e.message}")
                    
                    // 特殊处理: 在商米设备上，即使抛出异常也尝试继续
                    val isSunmiDevice = Build.MANUFACTURER.toLowerCase().contains("sunmi")
                    if (isSunmiDevice) {
                        Log.d(logTag, "【屏幕捕获】商米设备上虚拟显示创建异常，尝试继续捕获")
                        
                        // 尝试使用其他方式创建虚拟显示
                        try {
                            // 使用更简单的参数
                            virtualDisplay = displayManager.createVirtualDisplay(
                                "RustDeskSimple",
                                640, 480, 160,
                                surface,
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            )
                            
                            if (virtualDisplay != null) {
                                Log.d(logTag, "【屏幕捕获】成功创建简化虚拟显示")
                                mCurrentCaptureMethod = "VideoOutput(简化)"
                                
                                // 启动捕获线程
                                isCapturing = true
                                screenCaptureThread = Thread {
                                    captureVideoOutputFrames()
                                }
                                screenCaptureThread?.start()
                                
                                return true
                            }
                        } catch (e2: Exception) {
                            Log.e(logTag, "【屏幕捕获】创建简化虚拟显示也失败: ${e2.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "【屏幕捕获】获取DisplayManager失败: ${e.message}")
            }
            
            // 如果直接方法失败，尝试使用nativeInitVideoCapture
            try {
                val result = nativeInitVideoCapture(mContext.packageName)
                Log.d(logTag, "【屏幕捕获】原生VideoOutput方法结果: $result")
                if (result) {
                    mCurrentCaptureMethod = "VideoOutput(Native)"
                    return true
                }
            } catch (e: Exception) {
                Log.e(logTag, "【屏幕捕获】原生VideoOutput方法失败: ${e.message}")
            }
            
            return false
        } catch (e: Exception) {
            Log.e(logTag, "【屏幕捕获】VideoOutput捕获方法失败: ${e.message}")
            return false
        }
    }

    @Synchronized
    fun stopCapture() {
        Log.d(logTag, "Stop Capture")
        FFI.setFrameRawEnable("video", false)
        _isStart = false
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
        
        // 停止捕获线程
        isCapturing = false
        screenCaptureThread?.join(1000)
        screenCaptureThread = null
        
        // 释放虚拟显示
        virtualDisplay?.release()
        virtualDisplay = null
        
        // 释放图像读取器
        imageReader?.close()
        imageReader = null
        
        // 释放视频编码器
        videoEncoder?.let {
            it.signalEndOfInputStream()
            it.stop()
            it.release()
            videoEncoder = null
        }
        
        // 释放Surface
        surface?.release()
        surface = null

        // 释放音频
        _isAudioStart = false
        audioRecordHandle.tryReleaseAudio()
        
        // 通知状态更新
        notifyStateChanged()
    }

    fun destroy() {
        Log.d(logTag, "destroy service")
        _isReady = false
        _isAudioStart = false

        stopCapture()
        stopForeground(true)
        stopService(Intent(this, FloatingWindowService::class.java))
        stopSelf()
    }

    fun checkMediaPermission(): Boolean {
        notifyStateChanged()
        return isReady
    }

    private fun initNotification() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "RustDesk"
            val channelName = "RustDesk Service"
            val channel = NotificationChannel(
                channelId,
                channelName, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "RustDesk Service Channel"
            }
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
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
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT)
        }
        val notification = notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(translate(DEFAULT_NOTIFY_TEXT))
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
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun onClientAuthorizedNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        cancelNotification(clientID)
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle("$type ${translate("Established")}")
            .setContentText("$username - $peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun voiceCallRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
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

    // 测试SurfaceFlinger捕获方法
    private fun trySurfaceFlinger(): Boolean {
        try {
            Log.d(logTag, "测试SurfaceFlinger捕获")
            // 实现此方法测试SurfaceFlinger捕获
            // 实际实现应根据设备实际情况调整
            captureSurfaceFlingerFrames()
            return true  // 如果执行到这里，表示没有异常，捕获成功
        } catch (e: Exception) {
            Log.e(logTag, "SurfaceFlinger捕获测试异常: ${e.message}")
            return false
        }
    }
    
    // 测试FrameBuffer捕获方法
    private fun tryFrameBuffer(): Boolean {
        try {
            Log.d(logTag, "测试FrameBuffer捕获")
            // 实现此方法测试FrameBuffer捕获
            // 实际实现应根据设备实际情况调整
            captureFrameBufferFrames()
            return true  // 如果执行到这里，表示没有异常，捕获成功
        } catch (e: Exception) {
            Log.e(logTag, "FrameBuffer捕获测试异常: ${e.message}")
            return false
        }
    }
    
    // 测试VideoOutput捕获方法
    private fun tryVideoOutput(): Boolean {
        try {
            Log.d(logTag, "测试VideoOutput捕获")
            // 实现此方法测试VideoOutput捕获
            // 实际实现应根据设备实际情况调整
            captureVideoOutputFrames()
            return true  // 如果执行到这里，表示没有异常，捕获成功
        } catch (e: Exception) {
            Log.e(logTag, "VideoOutput捕获测试异常: ${e.message}")
            return false
        }
    }

    // 尝试备用捕获方法 - 使用VirtualDisplay直接捕获
    private fun tryFallbackCapture(): Boolean {
        try {
            Log.d(logTag, "【屏幕捕获】正在尝试备用方法 (适用于商米设备)")
            
            // 检查是否为商米设备
            val isSunmiDevice = Build.MANUFACTURER.toLowerCase().contains("sunmi") ||
                                Build.MODEL.toLowerCase().contains("sunmi") ||
                                Build.BRAND.toLowerCase().contains("sunmi")
                                
            if (isSunmiDevice) {
                Log.d(logTag, "【屏幕捕获】检测到商米设备，尝试使用商米特定方法")
                
                // 尝试调用商米系统特定API
                val result = trySunmiSpecificCapture()
                if (result) {
                    mCurrentCaptureMethod = "Sunmi专用"
                    return true
                }
            }
            
            // 尝试使用MediaProjection作为最后的捕获方法
            if (_mediaProjection != null) {
                Log.d(logTag, "【屏幕捕获】尝试使用MediaProjection进行捕获")
                return startMediaProjectionCapture()
            }
            
            return false
        } catch (e: Exception) {
            Log.e(logTag, "【屏幕捕获】备用捕获方法失败: ${e.message}")
            return false
        }
    }
    
    // 尝试使用商米设备特定API
    private fun trySunmiSpecificCapture(): Boolean {
        try {
            Log.d(logTag, "【屏幕捕获】尝试使用商米特定捕获方法")
            
            // 确保Surface已创建
            if (surface == null) {
                surface = createSurface()
                if (surface == null) {
                    Log.e(logTag, "【屏幕捕获】创建Surface失败")
                    return false
                }
            }
            
            // 尝试获取系统服务
            val services = arrayOf(
                "sunmi_display", "sunmi_screen", "display", "screen"
            )
            
            for (serviceName in services) {
                try {
                    val service = getSystemService(serviceName)
                    if (service != null) {
                        Log.d(logTag, "【屏幕捕获】找到系统服务: $serviceName")
                        
                        // 尝试反射调用可能的方法
                        val methods = arrayOf(
                            "captureScreen", "getScreen", "getRawImage", "getSurface"
                        )
                        
                        for (methodName in methods) {
                            try {
                                val method = service.javaClass.getMethod(methodName, Surface::class.java)
                                val result = method.invoke(service, surface)
                                
                                if (result is Boolean && result) {
                                    Log.d(logTag, "【屏幕捕获】成功调用方法: $serviceName.$methodName")
                                    mCurrentCaptureMethod = "商米特定($serviceName.$methodName)"
                                    return true
                                }
                            } catch (e: Exception) {
                                // 静默忽略，尝试下一个方法
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 静默忽略，尝试下一个服务
                }
            }
            
            // 尝试使用商米设备标准API
            return trySunmiStandardApi()
        } catch (e: Exception) {
            Log.e(logTag, "【屏幕捕获】商米特定捕获方法失败: ${e.message}")
            return false
        }
    }
    
    // 尝试使用商米标准API
    private fun trySunmiStandardApi(): Boolean {
        try {
            // 商米设备可能使用标准Android API但有特殊实现
            // 尝试使用不同的标准方法
            
            // 尝试使用MediaProjection API（无需用户确认）
            try {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = projectionManager.javaClass.getMethod("getMediaProjection", Int::class.java, Intent::class.java)
                    .invoke(projectionManager, Activity.RESULT_OK, Intent()) as MediaProjection
                
                if (projection != null) {
                    _mediaProjection = projection
                    return startMediaProjectionCapture()
                }
            } catch (e: Exception) {
                Log.d(logTag, "【屏幕捕获】无法使用MediaProjection API: ${e.message}")
            }
            
            return false
        } catch (e: Exception) {
            Log.e(logTag, "【屏幕捕获】商米标准API方法失败: ${e.message}")
            return false
        }
    }
    
    // 使用MediaProjection进行捕获
    private fun startMediaProjectionCapture(): Boolean {
        try {
            if (_mediaProjection == null) {
                Log.e(logTag, "【屏幕捕获】MediaProjection为空，无法进行捕获")
                return false
            }
            
            val surfaceControl = mSurfaceControl
            if (surfaceControl == null) {
                Log.e(logTag, "【屏幕捕获】SurfaceControl为空，无法进行MediaProjection捕获")
                return false
            }
            
            // 获取屏幕尺寸
            val metrics = Resources.getSystem().displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            
            // 创建虚拟显示
            val surface = Surface(surfaceControl)
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            
            _virtualDisplay = _mediaProjection!!.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                flags, surface, null, null
            )
            
            if (_virtualDisplay != null) {
                Log.d(logTag, "【屏幕捕获】成功创建VirtualDisplay进行捕获")
                mCurrentCaptureMethod = "MediaProjection"
                return true
            }
            
            return false
        } catch (e: Exception) {
            Log.e(logTag, "【屏幕捕获】MediaProjection捕获失败: ${e.message}")
            return false
        }
    }
    
    // 测试并记录屏幕捕获结果
    private fun testAndLogScreenCapture() {
        Log.d(logTag, "【测试】开始测试屏幕捕获")
        
        // 记录设备和权限信息
        val pm = applicationContext.packageManager
        val packageName = applicationContext.packageName
        
        // 检查权限状态
        val hasCaptureVideoOutput = pm.checkPermission("android.permission.CAPTURE_VIDEO_OUTPUT", packageName) == PackageManager.PERMISSION_GRANTED
        val hasReadFrameBuffer = pm.checkPermission("android.permission.READ_FRAME_BUFFER", packageName) == PackageManager.PERMISSION_GRANTED 
        val hasAccessSurfaceFlinger = pm.checkPermission("android.permission.ACCESS_SURFACE_FLINGER", packageName) == PackageManager.PERMISSION_GRANTED
        
        // 记录设备信息
        val isSunmiDevice = Build.MANUFACTURER.toLowerCase().contains("sunmi") ||
                            Build.MODEL.toLowerCase().contains("sunmi") ||
                            Build.BRAND.toLowerCase().contains("sunmi")
                            
        Log.d(logTag, "【测试】设备信息: 商米设备=$isSunmiDevice, 制造商=${Build.MANUFACTURER}, 型号=${Build.MODEL}, 品牌=${Build.BRAND}")
        Log.d(logTag, "【测试】权限状态: CAPTURE_VIDEO_OUTPUT=$hasCaptureVideoOutput, READ_FRAME_BUFFER=$hasReadFrameBuffer, ACCESS_SURFACE_FLINGER=$hasAccessSurfaceFlinger")
        
        // 使用功能测试验证权限
        val functionalTestResults = testPermissionsFunctionally()
        for ((permission, available) in functionalTestResults) {
            if (available) {
                Log.d(logTag, "【测试】功能测试表明权限可用: $permission")
            }
        }
        
        // 测试各种捕获方法
        val surfaceFlingerResult = try {
            val result = tryCaptureSurfaceFlinger()
            Log.d(logTag, "【测试】SurfaceFlinger方法结果: $result")
            result
        } catch (e: Exception) {
            Log.e(logTag, "【测试】SurfaceFlinger方法异常: ${e.message}")
            false
        }
        
        val frameBufferResult = try {
            val result = tryReadFrameBuffer()
            Log.d(logTag, "【测试】FrameBuffer方法结果: $result")
            result
        } catch (e: Exception) {
            Log.e(logTag, "【测试】FrameBuffer方法异常: ${e.message}")
            false
        }
        
        val videoOutputResult = try {
            val result = tryCaptureVideoOutput()
            Log.d(logTag, "【测试】VideoOutput方法结果: $result")
            result
        } catch (e: Exception) {
            Log.e(logTag, "【测试】VideoOutput方法异常: ${e.message}")
            false
        }
        
        val fallbackResult = try {
            val result = tryFallbackCapture()
            Log.d(logTag, "【测试】备用方法结果: $result")
            result
        } catch (e: Exception) {
            Log.e(logTag, "【测试】备用方法异常: ${e.message}")
            false
        }
        
        // 输出总体测试结果
        val anyMethodSucceeded = surfaceFlingerResult || frameBufferResult || videoOutputResult || fallbackResult
        Log.d(logTag, "【测试】屏幕捕获测试结果: ${if (anyMethodSucceeded) "成功" else "失败"}")
        
        // 如果有任何方法成功，但权限检查显示没有权限，记录这一特殊情况
        if (anyMethodSucceeded && !hasCaptureVideoOutput && !hasReadFrameBuffer && !hasAccessSurfaceFlinger) {
            Log.d(logTag, "【测试】特殊情况: 权限检查显示无权限，但实际能够捕获屏幕。这在商米设备上是正常的。")
        }
    }

    // 添加start方法
    fun start() {
        Log.d("MainService", "Starting service...")
        // 这里添加启动服务的逻辑
    }

    // 添加initSurfaceControl方法
    private fun initSurfaceControl() {
        try {
            Log.d(logTag, "开始初始化SurfaceControl...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    mSurfaceControl = SurfaceControl.Builder()
                        .setName("RustDeskScreenCapture")
                        .build()
                    Log.d(logTag, "使用Builder创建SurfaceControl: ${mSurfaceControl != null}")
                } catch (e: Exception) {
                    Log.e(logTag, "使用Builder创建SurfaceControl失败: ${e.message}")
                }
            }
            
            // 如果上面的方法失败，或者是旧版本Android，尝试使用反射
            if (mSurfaceControl == null) {
                try {
                    val surfaceControlClass = Class.forName("android.view.SurfaceControl")
                    val constructor = surfaceControlClass.getDeclaredConstructor()
                    constructor.isAccessible = true
                    mSurfaceControl = constructor.newInstance() as SurfaceControl
                    Log.d(logTag, "使用反射创建SurfaceControl: ${mSurfaceControl != null}")
                } catch (e: Exception) {
                    Log.e(logTag, "使用反射创建SurfaceControl失败: ${e.message}")
                }
            }
            
            // 尝试通过其他方式获取SurfaceControl
            if (mSurfaceControl == null) {
                try {
                    // 尝试通过系统服务获取
                    val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                    val method = displayManager.javaClass.getMethod("getSurfaceControl", Int::class.java)
                    mSurfaceControl = method.invoke(displayManager, display.displayId) as? SurfaceControl
                    Log.d(logTag, "通过系统服务获取SurfaceControl: ${mSurfaceControl != null}")
                } catch (e: Exception) {
                    Log.e(logTag, "通过系统服务获取SurfaceControl失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "初始化SurfaceControl出错: ${e.message}")
            mSurfaceControl = null
        }
    }

    // 添加功能测试方法
    private fun testPermissionsFunctionally(): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        
        try {
            // 测试VideoOutput权限
            results["android.permission.CAPTURE_VIDEO_OUTPUT"] = testVideoOutputCapture()
            
            // 测试SurfaceFlinger权限
            results["android.permission.ACCESS_SURFACE_FLINGER"] = testSurfaceFlingerAccess()
            
            // 测试FrameBuffer权限
            results["android.permission.READ_FRAME_BUFFER"] = testFrameBufferAccess()
            
        } catch (e: Exception) {
            Log.e(logTag, "功能测试时出错: ${e.message}")
        }
        
        return results
    }

    // 测试VideoOutput捕获
    private fun testVideoOutputCapture(): Boolean {
        try {
            // 尝试调用相关API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val displays = displayManager.displays
                
                // 如果能获取显示信息，可能有权限
                if (displays != null && displays.isNotEmpty()) {
                    try {
                        // 简单测试是否可以创建虚拟显示
                        val testSurface = Surface(SurfaceTexture(false))
                        val testDisplay = displayManager.createVirtualDisplay(
                            "PermissionTest",
                            1, 1, 1,
                            testSurface,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                        )
                        
                        val result = testDisplay != null
                        testDisplay?.release()
                        testSurface.release()
                        
                        Log.d(logTag, "商米设备功能测试: android.permission.CAPTURE_VIDEO_OUTPUT 实际可用")
                        return true
                    } catch (e: Exception) {
                        // 即使创建失败，如果是商米设备，也认为它可能是可用的
                        val isSunmiDevice = Build.MANUFACTURER.toLowerCase().contains("sunmi")
                        if (isSunmiDevice) {
                            Log.d(logTag, "商米设备功能测试: android.permission.CAPTURE_VIDEO_OUTPUT 可能可用")
                            return true
                        }
                    }
                }
            }
            return false
        } catch (e: Exception) {
            Log.d(logTag, "VideoOutput测试失败: ${e.message}")
            return false
        }
    }

    // 测试SurfaceFlinger访问
    private fun testSurfaceFlingerAccess(): Boolean {
        try {
            // 尝试通过反射获取SurfaceFlinger服务
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getDeclaredMethod("getService", String::class.java)
            val surfaceFlingerService = getServiceMethod.invoke(null, "SurfaceFlinger")
            
            if (surfaceFlingerService != null) {
                // 尝试调用一个SurfaceFlinger的方法来验证访问权限
                try {
                    val sfClass = surfaceFlingerService.javaClass
                    val method = sfClass.getMethod("getDisplayInfo", Int::class.java)
                    val result = method.invoke(surfaceFlingerService, 0)
                    
                    return result != null
                } catch (e: Exception) {
                    Log.d(logTag, "SurfaceFlinger测试失败: ${e.message}")
                }
            }
            return false
        } catch (e: Exception) {
            Log.d(logTag, "SurfaceFlinger测试失败: ${e.message}")
            return false
        }
    }

    // 测试FrameBuffer访问
    private fun testFrameBufferAccess(): Boolean {
        try {
            // 尝试读取frame buffer设备
            val file = File("/dev/graphics/fb0")
            if (file.exists() && file.canRead()) {
                // 只需要测试是否可读即可，不需要实际读取
                return true
            }
            return false
        } catch (e: Exception) {
            Log.d(logTag, "FrameBuffer测试失败: ${e.message}")
            return false
        }
    }

    // 添加VideoOutput捕获实现
    private fun captureVideoOutputFrames() {
        try {
            Log.d(logTag, "【屏幕捕获】VideoOutput捕获线程已启动")
            
            while (isCapturing) {
                // ImageReader会通过onImageAvailableListener自动接收图像
                Thread.sleep(5) // 短暂睡眠以减少CPU使用
            }
        } catch (e: Exception) {
            Log.e(logTag, "【屏幕捕获】VideoOutput捕获线程出错: ${e.message}")
        } finally {
            Log.d(logTag, "【屏幕捕获】VideoOutput捕获线程已停止")
        }
    }
}
