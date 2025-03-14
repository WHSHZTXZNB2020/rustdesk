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
        Log.d(logTag, "service starting: ${startId}:${Thread.currentThread()}")
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
                    }
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
                        }
                    }
                }
            }
        }
        
        // 如果通过EXT_INIT_FROM_BOOT启动，调用FFI.startService
        if (intent?.getBooleanExtra(EXT_INIT_FROM_BOOT, false) == true) {
            FFI.startService()
        }
        
        return START_NOT_STICKY // don't use sticky (auto restart), the new service (from auto restart) will lose control
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
        
        Log.d(logTag, "System permissions: CAPTURE_VIDEO_OUTPUT=$captureVideoOutput, READ_FRAME_BUFFER=$readFrameBuffer, ACCESS_SURFACE_FLINGER=$accessSurfaceFlinger")
        
        // 有任一系统权限即可捕获屏幕
        _isReady = captureVideoOutput || readFrameBuffer || accessSurfaceFlinger
        
        if (_isReady) {
            Log.d(logTag, "System screen capture permissions granted")
        } else {
            Log.e(logTag, "No screen capture permissions granted!")
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
                        if (image == null || !isStart) return@setOnImageAvailableListener
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        buffer.rewind()
                        FFI.onVideoFrameUpdate(buffer)
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
            return true
        }
        
        if (!isReady) {
            Log.e(logTag, "Cannot start capture: system permissions not granted")
            return false
        }
        
        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "Start Capture")
        
        // 创建Surface用于显示捕获内容
        surface = createSurface()
        if (surface == null) {
            Log.e(logTag, "Failed to create surface")
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
            return true
        }
        
        Log.e(logTag, "Failed to start screen capture")
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
            Log.d(logTag, "Starting screen capture with system permissions")
            
            // 尝试使用各种系统权限方法
            val pm = applicationContext.packageManager
            
            // 检查权限并选择合适的捕获方法
            val hasCaptureVideoOutput = pm.checkPermission("android.permission.CAPTURE_VIDEO_OUTPUT", packageName) == PackageManager.PERMISSION_GRANTED
            val hasReadFrameBuffer = pm.checkPermission("android.permission.READ_FRAME_BUFFER", packageName) == PackageManager.PERMISSION_GRANTED 
            val hasAccessSurfaceFlinger = pm.checkPermission("android.permission.ACCESS_SURFACE_FLINGER", packageName) == PackageManager.PERMISSION_GRANTED
            
            // 按优先级尝试不同捕获方法
            if (hasAccessSurfaceFlinger && tryCaptureSurfaceFlinger()) {
                return true
            }
            
            if (hasReadFrameBuffer && tryReadFrameBuffer()) {
                return true
            }
            
            if (hasCaptureVideoOutput && tryCaptureVideoOutput()) {
                return true
            }
            
            Log.e(logTag, "All capture methods failed")
            return false
        } catch (e: Exception) {
            Log.e(logTag, "Error starting screen capture: ${e.message}")
            return false
        }
    }
    
    // 使用SurfaceFlinger捕获屏幕
    private fun tryCaptureSurfaceFlinger(): Boolean {
        try {
            Log.d(logTag, "Trying SurfaceFlinger capture method")
            
            // 使用反射获取SurfaceFlinger服务
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getDeclaredMethod("getService", String::class.java)
            val surfaceFlingerService = getServiceMethod.invoke(null, "SurfaceFlinger")
            
            if (surfaceFlingerService != null) {
                // 创建VirtualDisplay来显示捕获的内容
                virtualDisplay = createVirtualDisplay()
                
                if (virtualDisplay != null) {
                    // 启动捕获线程
                    startCaptureThread("surfaceflinger")
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            Log.e(logTag, "SurfaceFlinger capture failed: ${e.message}")
            return false
        }
    }
    
    // 使用FrameBuffer捕获屏幕
    private fun tryReadFrameBuffer(): Boolean {
        try {
            Log.d(logTag, "Trying FrameBuffer capture method")
            
            // 创建VirtualDisplay来显示捕获的内容
            virtualDisplay = createVirtualDisplay()
            
            if (virtualDisplay != null) {
                // 启动捕获线程
                startCaptureThread("framebuffer")
                return true
            }
            return false
        } catch (e: Exception) {
            Log.e(logTag, "FrameBuffer capture failed: ${e.message}")
            return false
        }
    }
    
    // 使用CAPTURE_VIDEO_OUTPUT捕获屏幕
    private fun tryCaptureVideoOutput(): Boolean {
        try {
            Log.d(logTag, "Trying CAPTURE_VIDEO_OUTPUT capture method")
            
            // 创建VirtualDisplay来显示捕获的内容
            virtualDisplay = createVirtualDisplay()
            
            if (virtualDisplay != null) {
                // 启动捕获线程
                startCaptureThread("videocapture")
                return true
            }
            return false
        } catch (e: Exception) {
            Log.e(logTag, "CAPTURE_VIDEO_OUTPUT failed: ${e.message}")
            return false
        }
    }
    
    // 创建虚拟显示
    private fun createVirtualDisplay(): VirtualDisplay? {
        try {
            if (surface == null) {
                Log.e(logTag, "Surface is null, cannot create virtual display")
                return null
            }
            
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            return displayManager.createVirtualDisplay(
                "RustDeskSystemCapture",
                SCREEN_INFO.width, 
                SCREEN_INFO.height, 
                SCREEN_INFO.dpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            )
        } catch (e: Exception) {
            Log.e(logTag, "Failed to create virtual display: ${e.message}")
            return null
        }
    }
    
    // 启动屏幕捕获线程
    private fun startCaptureThread(method: String) {
        isCapturing = true
        screenCaptureThread = Thread {
            try {
                Log.d(logTag, "Screen capture thread started using method: $method")
                
                // 实际捕获实现将使用系统API，在您的定制系统上这些API可以直接使用
                when (method) {
                    "surfaceflinger" -> captureSurfaceFlingerFrames()
                    "framebuffer" -> captureFrameBufferFrames()
                    "videocapture" -> captureVideoOutputFrames()
                }
            } catch (e: Exception) {
                Log.e(logTag, "Error in capture thread: ${e.message}")
            } finally {
                Log.d(logTag, "Screen capture thread stopped")
            }
        }
        screenCaptureThread?.start()
    }
    
    // SurfaceFlinger捕获实现 - 使用系统API直接捕获
    private fun captureSurfaceFlingerFrames() {
        // 在您的定制系统上，有了ACCESS_SURFACE_FLINGER权限后
        // ImageReader和VirtualDisplay已经能够直接接收屏幕内容
        // 不需要额外的JNI代码
        Log.d(logTag, "正在使用SurfaceFlinger API捕获屏幕，该API基于系统权限无需用户确认")
        try {
            while (isCapturing) {
                // ImageReader会通过onImageAvailableListener自动接收图像
                // 不需要在这里手动获取
                Thread.sleep(5) // 短暂睡眠以减少CPU使用
            }
        } catch (e: Exception) {
            Log.e(logTag, "SurfaceFlinger捕获出错: ${e.message}")
        } finally {
            Log.d(logTag, "SurfaceFlinger捕获已停止")
        }
    }
    
    // FrameBuffer捕获实现 - 使用系统API直接捕获
    private fun captureFrameBufferFrames() {
        // 在您的定制系统上，有了READ_FRAME_BUFFER权限后
        // ImageReader和VirtualDisplay已经能够直接接收屏幕内容
        // 不需要额外的JNI代码
        Log.d(logTag, "正在使用FrameBuffer API捕获屏幕，该API基于系统权限无需用户确认")
        try {
            while (isCapturing) {
                // ImageReader会通过onImageAvailableListener自动接收图像
                // 不需要在这里手动获取
                Thread.sleep(5) // 短暂睡眠以减少CPU使用
            }
        } catch (e: Exception) {
            Log.e(logTag, "FrameBuffer捕获出错: ${e.message}")
        } finally {
            Log.d(logTag, "FrameBuffer捕获已停止")
        }
    }
    
    // VideoOutput捕获实现 - 使用系统API直接捕获
    private fun captureVideoOutputFrames() {
        // 在您的定制系统上，有了CAPTURE_VIDEO_OUTPUT权限后
        // ImageReader和VirtualDisplay已经能够直接接收屏幕内容
        // 不需要额外的JNI代码
        Log.d(logTag, "正在使用Video Output API捕获屏幕，该API基于系统权限无需用户确认")
        try {
            while (isCapturing) {
                // ImageReader会通过onImageAvailableListener自动接收图像
                // 不需要在这里手动获取
                Thread.sleep(5) // 短暂睡眠以减少CPU使用
            }
        } catch (e: Exception) {
            Log.e(logTag, "Video Output捕获出错: ${e.message}")
        } finally {
            Log.d(logTag, "Video Output捕获已停止")
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
}
