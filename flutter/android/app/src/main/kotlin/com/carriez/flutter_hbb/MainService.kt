package com.carriez.flutter_hbb

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Point
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

const val REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION = 101
const val REQ_REQUEST_MEDIA_PROJECTION = 102
const val REQ_REQUEST_PERMISSION = 103
const val ACT_REQUEST_MEDIA_PROJECTION = "request_media_projection"
const val ACT_INIT_MEDIA_PROJECTION_AND_SERVICE = "init_mp_and_service"
const val ACT_INIT_SERVICE = "init_service"

const val RES_FAILED = -1

const val EXT_MEDIA_PROJECTION_RES_INTENT = "media_projection_res_intent"

class MainService : Service() {
    companion object {
        var isReady = false
        const val logTag = "MainService"
        private var serviceInstance: MainService? = null
        private var flutterMethodChannel: io.flutter.plugin.common.MethodChannel? = null
    }

    private var isVideoAccepted = true
    private var isAudioAccepted = true
    private var isCapturing = false
    private var notifInstance: NotificationUtils? = null
    private var width = 0
    private var height = 0
    private var virtualDisplay: VirtualDisplay? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var surface: Surface? = null
    private var imageReader: FFI.ImageReader? = null
    private var audioRecordHandle: AudioRecordHandle? = null

    // 这些变量是用于系统级权限捕获的
    private var useSystemPermissionCapture = false
    private var hasAccessSurfaceFlinger = false
    
    // 移除MediaProjection相关变量
    
    private val binder = MainBinder()
    
    private var sessionId: Long = 0
    private var display: Display? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag, "onCreate")

        // 记录服务实例
        serviceInstance = this

        // 初始化音频录制句柄
        audioRecordHandle = AudioRecordHandle(this, { isAudioAccepted }, { false })
    }

    override fun onDestroy() {
        Log.d(logTag, "onDestroy")

        isReady = false
        serviceInstance = null
        stopCapture()
        super.onDestroy()

        // 释放wakeLock
        if (wakeLock != null && wakeLock!!.isHeld) {
            wakeLock!!.release()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    // 使用系统权限检查功能
    private fun checkSystemPermissions(): String {
        // 检查系统级权限 ACCESS_SURFACE_FLINGER（最优先）
        val hasSurfaceFlingerPermission = checkCallingOrSelfPermission(PERMISSION_ACCESS_SURFACE_FLINGER) == PackageManager.PERMISSION_GRANTED
        
        // 检查其他系统级权限
        val captureVideoPermission = checkCallingOrSelfPermission(PERMISSION_CAPTURE_VIDEO_OUTPUT)
        val readFrameBufferPermission = checkCallingOrSelfPermission(PERMISSION_READ_FRAME_BUFFER)
        val hasOtherPermissions = captureVideoPermission == PackageManager.PERMISSION_GRANTED && 
                               readFrameBufferPermission == PackageManager.PERMISSION_GRANTED
        
        useSystemPermissionCapture = hasSurfaceFlingerPermission || hasOtherPermissions
        hasAccessSurfaceFlinger = hasSurfaceFlingerPermission
        
        return "ACCESS_SURFACE_FLINGER=$hasSurfaceFlingerPermission, " +
                "CAPTURE_VIDEO_OUTPUT=${captureVideoPermission == PackageManager.PERMISSION_GRANTED}, " +
                "READ_FRAME_BUFFER=${readFrameBufferPermission == PackageManager.PERMISSION_GRANTED}"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(logTag, "onStartCommand: intent=${intent?.action}")
        
        // 检查系统级权限
        val permissionStatus = checkSystemPermissions()
        Log.d(logTag, "系统权限检查: $permissionStatus")
        
        if (!useSystemPermissionCapture) {
            Log.w(logTag, "系统权限不足，无法启动屏幕捕获服务")
            
            // 通知Flutter层权限不足
            try {
                Handler(Looper.getMainLooper()).post {
                    MainActivity.flutterMethodChannel?.invokeMethod(
                        "on_permission_error",
                        mapOf("message" to "系统权限不足，无法启动服务")
                    )
                }
            } catch (e: Exception) {
                Log.e(logTag, "通知权限错误失败: ${e.message}")
            }
            
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (intent != null) {
            when (intent.action) {
                ACT_INIT_SERVICE -> {
                    initService()
                }
                else -> {
                    initService()
                }
            }
        }
        
        return START_STICKY
    }

    private fun initService() {
        try {
            if (isCapturing) {
                return
            }
            isReady = true
            wakeLock = acquireWakeLock()
            
            // 更新通知
            notifInstance = NotificationUtils(applicationContext)
            val notification = notifInstance!!.createNotification(this)
            startForeground(NotificationUtils.notificationId, notification)
            
            startCapture()
        } catch (e: Exception) {
            Log.e(logTag, "初始化服务失败: ${e.message}")
            stopSelf()
        }
    }

    private fun startCapture() {
        if (isCapturing) {
            Log.d(logTag, "已经在捕获中，不需要重新启动")
            return
        }
        
        Log.d(logTag, "开始屏幕捕获")
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        display = windowManager.defaultDisplay
        
        // 获取屏幕尺寸
        val wh = getDeviceWH(display!!)
        width = wh.first
        height = wh.second
        
        try {
            // 创建虚拟显示
            val captureSuccess = createVirtualDisplay(width, height)
            
            if (captureSuccess) {
                isCapturing = true
                Log.d(logTag, "屏幕捕获成功启动")
                
                // 如果有系统权限，尝试启动音频捕获
                if (useSystemPermissionCapture && isAudioAccepted) {
                    try {
                        if (!audioRecordHandle!!.createAudioRecorder(false, null)) {
                            Log.d(logTag, "音频录制创建失败")
                        } else {
                            Log.d(logTag, "音频录制开始")
                            audioRecordHandle!!.startRecord()
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "音频捕获初始化失败: ${e.message}")
                    }
                }
            } else {
                Log.e(logTag, "虚拟显示创建失败，屏幕捕获无法启动")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(logTag, "启动捕获时出错: ${e.message}")
            stopSelf()
        }
    }

    private fun stopCapture() {
        Log.d(logTag, "停止屏幕捕获")
        
        // 停止音频录制
        audioRecordHandle?.stopRecord()
            
        // 停止视频捕获
        try {
            isCapturing = false
            
            // 释放虚拟显示
            if (virtualDisplay != null) {
                virtualDisplay!!.release()
                virtualDisplay = null
            }
            
            // 释放surface
            if (surface != null) {
                surface!!.release()
                surface = null
            }
            
            // 释放ImageReader
            if (imageReader != null) {
                imageReader!!.close()
                imageReader = null
            }
            
            Log.d(logTag, "屏幕捕获已停止")
        } catch (e: Exception) {
            Log.e(logTag, "停止捕获时出错: ${e.message}")
        }
        
        // 通知已停止
        try {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to "false")
            )
        } catch (e: Exception) {
            Log.e(logTag, "通知状态变更失败: ${e.message}")
        }
    }

    private fun createVirtualDisplay(width: Int, height: Int): Boolean {
        try {
            // 记录尝试状态
            var captureSuccess = false
            
            // 优先检查 ACCESS_SURFACE_FLINGER 权限 (系统级捕获的最佳选择)
            val accessSurfaceFlingerPermission = checkCallingOrSelfPermission(PERMISSION_ACCESS_SURFACE_FLINGER)
            val hasSurfaceFlingerPermission = accessSurfaceFlingerPermission == PackageManager.PERMISSION_GRANTED
            
            // 检查其他系统级权限
            val captureVideoPermission = checkCallingOrSelfPermission(PERMISSION_CAPTURE_VIDEO_OUTPUT)
            val readFrameBufferPermission = checkCallingOrSelfPermission(PERMISSION_READ_FRAME_BUFFER)
            val hasCapturePermissions = captureVideoPermission == PackageManager.PERMISSION_GRANTED && 
                                    readFrameBufferPermission == PackageManager.PERMISSION_GRANTED
            
            Log.d(logTag, "权限状态 - ACCESS_SURFACE_FLINGER: $hasSurfaceFlingerPermission, " +
                         "CAPTURE_VIDEO_OUTPUT: ${captureVideoPermission == PackageManager.PERMISSION_GRANTED}, " +
                         "READ_FRAME_BUFFER: ${readFrameBufferPermission == PackageManager.PERMISSION_GRANTED}")
            
            // 准备ImageReader
            imageReader = FFI.createImageReader(width, height)
            if (imageReader == null) {
                Log.e(logTag, "创建ImageReader失败")
                return false
            }
            
            surface = imageReader!!.getSurface()
            
            // 控制捕获顺序: 首先尝试系统权限方法
            if (hasSurfaceFlingerPermission) {
                // 1. 使用ACCESS_SURFACE_FLINGER权限（最佳方案）
                Log.d(logTag, "尝试使用ACCESS_SURFACE_FLINGER权限进行屏幕捕获")
                try {
                    virtualDisplay = createHighQualityVirtualDisplay(width, height)
                    captureSuccess = virtualDisplay != null
                    
                    if (captureSuccess) {
                        Log.d(logTag, "使用ACCESS_SURFACE_FLINGER权限成功创建虚拟显示")
                    } else {
                        Log.e(logTag, "使用ACCESS_SURFACE_FLINGER权限创建虚拟显示失败")
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "使用ACCESS_SURFACE_FLINGER方法出错: ${e.message}")
                }
            }
            
            if (!captureSuccess && hasCapturePermissions) {
                // 2. 尝试使用其他系统级权限（备选方案）
                Log.d(logTag, "尝试使用CAPTURE_VIDEO_OUTPUT和READ_FRAME_BUFFER权限")
                try {
                    virtualDisplay = createSystemPermissionVirtualDisplay(width, height)
                    captureSuccess = virtualDisplay != null
                    
                    if (captureSuccess) {
                        Log.d(logTag, "使用CAPTURE_VIDEO_OUTPUT和READ_FRAME_BUFFER权限成功创建虚拟显示")
                    } else {
                        Log.e(logTag, "使用CAPTURE_VIDEO_OUTPUT和READ_FRAME_BUFFER权限创建虚拟显示失败")
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "使用系统权限方法出错: ${e.message}")
                }
            }
            
            // 移除MediaProjection相关代码
            
            return captureSuccess
        } catch (e: Exception) {
            Log.e(logTag, "创建虚拟显示时出错: ${e.message}")
            return false
        }
    }

    // 使用ACCESS_SURFACE_FLINGER权限创建高质量虚拟显示
    private fun createHighQualityVirtualDisplay(width: Int, height: Int): VirtualDisplay? {
        val hasSurfaceFlingerPermission = checkCallingOrSelfPermission(PERMISSION_ACCESS_SURFACE_FLINGER) == PackageManager.PERMISSION_GRANTED
        if (!hasSurfaceFlingerPermission) {
            Log.e(logTag, "缺少ACCESS_SURFACE_FLINGER权限，无法使用SurfaceFlinger创建虚拟显示")
            return null
        }
        
        Log.d(logTag, "使用ACCESS_SURFACE_FLINGER权限创建高品质虚拟显示")
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        
        return displayManager.createVirtualDisplay(
            "RustDesk-FrameBuffer-Display",
            width,
            height,
            getDensityDpi(),
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
        )
    }
    
    // 使用CAPTURE_VIDEO_OUTPUT和READ_FRAME_BUFFER权限创建虚拟显示
    private fun createSystemPermissionVirtualDisplay(width: Int, height: Int): VirtualDisplay? {
        val captureVideoPermission = checkCallingOrSelfPermission(PERMISSION_CAPTURE_VIDEO_OUTPUT)
        val readFrameBufferPermission = checkCallingOrSelfPermission(PERMISSION_READ_FRAME_BUFFER)
        
        if (captureVideoPermission != PackageManager.PERMISSION_GRANTED || 
            readFrameBufferPermission != PackageManager.PERMISSION_GRANTED) {
            Log.e(logTag, "缺少CAPTURE_VIDEO_OUTPUT或READ_FRAME_BUFFER权限，无法使用系统权限创建虚拟显示")
            return null
        }
        
        Log.d(logTag, "使用系统权限创建虚拟显示")
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        
        return displayManager.createVirtualDisplay(
            "RustDesk-System-Display",
            width,
            height,
            getDensityDpi(),
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
        )
    }
    
    // 移除setupMediaProjection方法
    
    private fun acquireWakeLock(): PowerManager.WakeLock {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RustDesk::MainServiceWakeLock"
        )
        wakeLock.acquire()
        return wakeLock
    }

    private fun getDensityDpi(): Int {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            display?.getMetrics(metrics)
        }
        return metrics.densityDpi
    }

    private fun getDeviceWH(display: Display): Pair<Int, Int> {
        val point = Point()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display.getRealSize(point)
        } else {
            @Suppress("DEPRECATION")
            display.getRealSize(point)
        }
        
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getMetrics(metrics)
        
        return Pair(point.x, point.y)
    }

    // 更新屏幕方向
    fun updateOrientationChange() {
        try {
            val newDisplay = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
            val oldOrientation = getScreenOrientation(display!!)
            val newOrientation = getScreenOrientation(newDisplay)
            
            if (oldOrientation != newOrientation) {
                Log.d(logTag, "屏幕方向变更，重新启动捕获")
                
                // 保存当前显示引用
                display = newDisplay
                
                // 停止当前捕获
                if (virtualDisplay != null) {
                    virtualDisplay!!.release()
                    virtualDisplay = null
                }
                
                if (surface != null) {
                    surface!!.release()
                    surface = null
                }
                
                if (imageReader != null) {
                    imageReader!!.close()
                    imageReader = null
                }
                
                // 重新启动捕获
                val wh = getDeviceWH(display!!)
                width = wh.first
                height = wh.second
                createVirtualDisplay(width, height)
                
                // 通知方向变更
                try {
                    MainActivity.flutterMethodChannel?.invokeMethod(
                        "on_orientation_changed",
                        mapOf("width" to width, "height" to height)
                    )
                } catch (e: Exception) {
                    Log.e(logTag, "通知方向变更失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "更新方向时出错: ${e.message}")
        }
    }

    // 获取屏幕方向
    private fun getScreenOrientation(display: Display): Int {
        return when (display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 1
            Surface.ROTATION_180 -> 2
            Surface.ROTATION_270 -> 3
            else -> 0
        }
    }

    fun onVoiceCallStarted(): Boolean {
        return audioRecordHandle?.onVoiceCallStarted(null) ?: false
    }

    fun onVoiceCallClosed(): Boolean {
        return audioRecordHandle?.onVoiceCallClosed(null) ?: false
    }

    // 移除MediaProjection相关功能
    
    inner class MainBinder : Binder() {
        val service: MainService
            get() = this@MainService
    }

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MainService.MainBinder
            serviceInstance = binder.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceInstance = null
        }
    }
}

const val PERMISSION_CAPTURE_VIDEO_OUTPUT = "android.permission.CAPTURE_VIDEO_OUTPUT"
const val PERMISSION_READ_FRAME_BUFFER = "android.permission.READ_FRAME_BUFFER"
const val PERMISSION_ACCESS_SURFACE_FLINGER = "android.permission.ACCESS_SURFACE_FLINGER"
