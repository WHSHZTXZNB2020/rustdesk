package com.carriez.flutter_hbb

/**
 * Handle events from flutter
 * Request MediaProjection permission
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
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import android.view.Display
import android.hardware.display.DisplayManager
import android.widget.Toast
import java.io.File
import android.app.ActivityManager
import android.graphics.SurfaceTexture
import android.view.Surface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Process
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.app.Activity


class MainActivity : FlutterActivity() {
    companion object {
        var flutterMethodChannel: MethodChannel? = null
        private var _rdClipboardManager: RdClipboardManager? = null
        val rdClipboardManager: RdClipboardManager?
            get() = _rdClipboardManager;
    }

    private val channelTag = "mChannel"
    private val logTag = "mMainActivity"
    private var mainService: MainService? = null

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

        flutterMethodChannel?.setMethodCallHandler { call, result ->
            if (call.method == "init_service") {
                initService()
                result.success(true)
            } else if (call.method == "start_service") {
                startService()
                result.success(true)
            } else if (call.method == "stop_service") {
                stopService()
                result.success(true)
            } else if (call.method == "check_system_permissions") {
                // 检查系统权限
                val permissions = checkSystemPermissions()
                result.success(permissions)
            } else if (call.method == "request_system_permissions") {
                // 请求系统权限
                try {
                    requestSystemPermissions()
                    result.success(true)
                } catch (e: Exception) {
                    result.error("PERMISSION_REQUEST_FAILED", e.message, null)
                }
            } else if (call.method == "test_screen_capture") {
                // 测试屏幕捕获功能
                try {
                    val testResult = testScreenCapture()
                    result.success(testResult)
                } catch (e: Exception) {
                    result.error("SCREEN_CAPTURE_TEST_FAILED", e.message, null)
                }
            } else if (call.method == "get_device_info") {
                // 获取设备信息
                val deviceInfo = HashMap<String, Any>()
                
                // 添加厂商信息
                deviceInfo["manufacturer"] = Build.MANUFACTURER ?: ""
                deviceInfo["model"] = Build.MODEL ?: ""
                deviceInfo["brand"] = Build.BRAND ?: ""
                deviceInfo["device"] = Build.DEVICE ?: ""
                deviceInfo["product"] = Build.PRODUCT ?: ""
                deviceInfo["hardware"] = Build.HARDWARE ?: ""
                deviceInfo["sdk"] = Build.VERSION.SDK_INT
                
                // 检查是否为商米设备
                val isSunmi = Build.MANUFACTURER.toLowerCase().contains("sunmi") ||
                              Build.MODEL.toLowerCase().contains("sunmi") ||
                              Build.BRAND.toLowerCase().contains("sunmi")
                              
                deviceInfo["is_sunmi_device"] = isSunmi
                
                // 返回设备信息
                result.success(deviceInfo)
            } else if (call.method == "check_input_permission") {
                // 检查输入控制权限状态
                val hasPermission = checkInjectEventsPermission(this)
                Log.d(logTag, "检查输入控制权限状态: $hasPermission")
                result.success(hasPermission)
            } else if (call.method == "start_input_without_dialog") {
                // 静默请求输入控制权限 (适用于商米设备)
                try {
                    val success = requestInjectEventsPermissionSilent()
                    Log.d(logTag, "静默请求输入控制权限结果: $success")
                    result.success(success)
                } catch (e: Exception) {
                    Log.e(logTag, "静默请求输入控制权限失败: ${e.message}")
                    result.error("INPUT_PERMISSION_ERROR", e.message, null)
                }
            } else if (call.method == "start_input_alternative") {
                // 使用替代方式请求输入控制权限
                try {
                    val success = requestInjectEventsPermissionAlternative()
                    Log.d(logTag, "替代方式请求输入控制权限结果: $success")
                    result.success(success)
                } catch (e: Exception) {
                    Log.e(logTag, "替代方式请求输入控制权限失败: ${e.message}")
                    result.error("INPUT_PERMISSION_ERROR", e.message, null)
                }
            } else {
                result.notImplemented()
            }
        }
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

    private fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
        }
        startActivityForResult(intent, REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION && resultCode == RES_FAILED) {
            flutterMethodChannel?.invokeMethod("on_media_projection_canceled", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 确保MainService启动
        try {
            val intent = Intent(this, MainService::class.java)
            startService(intent)
            Log.d(logTag, "已启动MainService")
            
            // 自动启动输入控制服务
            if (checkInjectEventsPermission(this)) {
                try {
                    InputService(this)
                    Log.d(logTag, "自动初始化InputService成功")
                } catch (e: Exception) {
                    Log.e(logTag, "自动初始化InputService失败: ${e.message}")
                }
            } else {
                Log.d(logTag, "没有INJECT_EVENTS权限，将在Flutter端尝试请求")
            }
        } catch (e: Exception) {
            Log.e(logTag, "启动MainService失败: ${e.message}")
        }
        
        if (_rdClipboardManager == null) {
            _rdClipboardManager = RdClipboardManager(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            FFI.setClipboardManager(_rdClipboardManager!!)
        }
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

    private fun initFlutterChannel(flutterMethodChannel: MethodChannel) {
        flutterMethodChannel.setMethodCallHandler { call, result ->
            // make sure result will be invoked, otherwise flutter will await forever
            when (call.method) {
                "init_service" -> {
                    Intent(activity, MainService::class.java).also {
                        bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
                    }
                    if (MainService.isReady) {
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    requestMediaProjection()
                    result.success(true)
                }
                "init_service_without_permission" -> {
                    Log.d(logTag, "尝试在定制系统环境下无需弹窗获取屏幕捕获权限")
                    try {
                        // 绑定服务
                        Intent(activity, MainService::class.java).also {
                            bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
                        }
                        
                        // 直接启动服务，不请求MediaProjection权限
                        val intent = Intent(activity, PermissionRequestTransparentActivity::class.java).apply {
                            action = ACT_USE_SYSTEM_PERMISSIONS // 使用系统权限的自定义action
                        }
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(logTag, "启动服务失败: ${e.message}")
                        result.success(false)
                    }
                }
                "start_capture" -> {
                    mainService?.let {
                        result.success(it.startCapture())
                    } ?: let {
                        result.success(false)
                    }
                }
                "stop_service" -> {
                    Log.d(logTag, "Stop service")
                    mainService?.let {
                        it.destroy()
                        result.success(true)
                    } ?: let {
                        result.success(false)
                    }
                }
                "check_permission" -> {
                    if (call.arguments is String) {
                        result.success(XXPermissions.isGranted(context, call.arguments as String))
                    } else {
                        result.success(false)
                    }
                }
                "request_permission" -> {
                    if (call.arguments is String) {
                        requestPermission(context, call.arguments as String)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                START_ACTION -> {
                    if (call.arguments is String) {
                        startAction(context, call.arguments as String)
                        result.success(true)
                    } else {
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
                    Log.d(logTag, "检查服务状态: isReady=${MainService.isReady}, isStart=${MainService.isStart}")
                    
                    // 检查输入服务状态
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                    )
                    
                    // 检查媒体服务状态 - 发送isReady和isStart
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "media", "value" to MainService.isReady.toString())
                    )
                    
                    // 如果服务已启动，需要额外发送isStart状态
                    if (MainService.isStart) {
                        Companion.flutterMethodChannel?.invokeMethod(
                            "on_state_changed", 
                            mapOf("name" to "start", "value" to MainService.isStart.toString())
                        )
                    }
                    
                    result.success(mapOf(
                        "isReady" to MainService.isReady,
                        "isStart" to MainService.isStart,
                        "inputOpen" to InputService.isOpen
                    ))
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
                                    
                                    // 权限被拒绝，尝试另一种方式
                                    try {
                                        Log.d(logTag, "尝试另一种方式获取输入控制权限")
                                        requestInjectEventsPermissionAlternative(activity)
                                    } catch (e: Exception) {
                                        Log.e(logTag, "Alternative method failed: ${e.message}")
                                    }
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
                            // 定制系统中，尝试不同方式获取权限
                            
                            // 方式1：直接初始化InputService
                            if (checkInjectEventsPermission(this)) {
                                InputService(this)
                                Log.d(logTag, "直接初始化InputService成功")
                                
                                Companion.flutterMethodChannel?.invokeMethod(
                                    "on_state_changed",
                                    mapOf("name" to "input", "value" to "true")
                                )
                                result.success(true)
                                return@setMethodCallHandler
                            }
                            
                            // 方式2：使用静默权限请求
                            val success = requestInjectEventsPermissionSilent()
                            if (success && checkInjectEventsPermission(this)) {
                                InputService(this)
                                Log.d(logTag, "静默权限请求成功")
                                
                                Companion.flutterMethodChannel?.invokeMethod(
                                    "on_state_changed",
                                    mapOf("name" to "input", "value" to "true")
                                )
                                result.success(true)
                                return@setMethodCallHandler
                            }
                            
                            // 方式3: 根据设备环境选择合适的方式
                            if (Build.MANUFACTURER.toLowerCase().contains("sunmi")) {
                                // 商米设备特殊处理
                                Log.d(logTag, "检测到商米设备，尝试特殊方式")
                                requestSunmiSpecificInputPermission()
                                
                                // 等待权限可能的授予
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (checkInjectEventsPermission(activity)) {
                                        try {
                                            InputService(activity)
                                            Companion.flutterMethodChannel?.invokeMethod(
                                                "on_state_changed",
                                                mapOf("name" to "input", "value" to "true")
                                            )
                                        } catch (e: Exception) {
                                            Log.e(logTag, "延迟初始化InputService失败: ${e.message}")
                                        }
                                    }
                                }, 500)
                            }
                            
                            // 返回当前状态
                            result.success(InputService.ctx != null)
                        } catch (e: Exception) {
                            Log.e(logTag, "定制系统初始化InputService失败: ${e.message}")
                            result.success(false)
                        }
                    } else {
                        result.success(true)
                    }
                }
                "check_system_permissions" -> {
                    Log.d(logTag, "检查系统权限状态")
                    val pm = applicationContext.packageManager
                    val captureVideoOutput = pm.checkPermission("android.permission.CAPTURE_VIDEO_OUTPUT", packageName) == PackageManager.PERMISSION_GRANTED
                    val readFrameBuffer = pm.checkPermission("android.permission.READ_FRAME_BUFFER", packageName) == PackageManager.PERMISSION_GRANTED
                    val accessSurfaceFlinger = pm.checkPermission("android.permission.ACCESS_SURFACE_FLINGER", packageName) == PackageManager.PERMISSION_GRANTED
                    
                    // 记录权限状态到日志
                    Log.d(logTag, "系统权限状态: CAPTURE_VIDEO_OUTPUT=$captureVideoOutput, READ_FRAME_BUFFER=$readFrameBuffer, ACCESS_SURFACE_FLINGER=$accessSurfaceFlinger")
                    
                    // 返回权限状态到Flutter
                    val isReady = captureVideoOutput || readFrameBuffer || accessSurfaceFlinger
                    val resultMap = mapOf(
                        "capture_video_output" to captureVideoOutput,
                        "read_frame_buffer" to readFrameBuffer,
                        "access_surface_flinger" to accessSurfaceFlinger,
                        "is_ready" to isReady
                    )
                    result.success(resultMap)
                }
                "request_system_permissions" -> {
                    Log.d(logTag, "尝试请求系统权限")
                    val resultMap = mutableMapOf<String, Any>()
                    
                    try {
                        // 检测是否为商米设备
                        val isSunmiDevice = checkIsSunmiDevice()
                        resultMap["is_sunmi_device"] = isSunmiDevice
                        
                        if (isSunmiDevice) {
                            // 特殊处理商米设备权限请求 - 这是关键部分
                            Log.d(logTag, "检测到商米设备，使用特殊权限申请API")
                            
                            // 1. 主要方法：使用SunmiDeviceServiceManager获取权限
                            var sunmiRequestSuccessful = false
                            try {
                                // 商米设备通常有一个特殊服务来处理权限
                                val sunmiDeviceManager = Class.forName("com.sunmi.devicemanager.SunmiDeviceServiceManager")
                                val getInstance = sunmiDeviceManager.getMethod("getInstance", Context::class.java)
                                val instance = getInstance.invoke(null, context)
                                
                                // 请求捕获屏幕权限
                                val requestCaptureMethod = sunmiDeviceManager.getMethod("requestCapturePermission", String::class.java)
                                val result = requestCaptureMethod.invoke(instance, packageName)
                                
                                Log.d(logTag, "SunmiDeviceServiceManager请求权限结果: $result")
                                sunmiRequestSuccessful = true
                            } catch (e: Exception) {
                                Log.e(logTag, "商米设备专用API调用失败: ${e.message}")
                            }
                            
                            // 2. 如果专用API失败，使用系统API
                            if (!sunmiRequestSuccessful) {
                                try {
                                    // 系统属性设置方法
                                    val systemPropertiesClass = Class.forName("android.os.SystemProperties")
                                    val set = systemPropertiesClass.getMethod("set", String::class.java, String::class.java)
                                    
                                    // 特定系统属性用于控制权限
                                    set.invoke(null, "persist.sys.enable_capture", "1")
                                    set.invoke(null, "persist.sys.sunmi.capture_permission", packageName)
                                    set.invoke(null, "persist.sys.sunmi.framebuffer_permission", packageName)
                                    set.invoke(null, "persist.sys.sunmi.surfaceflinger_permission", packageName)
                                    
                                    Log.d(logTag, "设置系统属性完成")
                                } catch (e: Exception) {
                                    Log.e(logTag, "系统属性设置失败: ${e.message}")
                                }
                            }
                            
                            // 3. 广播到系统
                            try {
                                val permissionIntent = Intent("com.sunmi.action.GRANT_SYSTEM_PERMISSION")
                                permissionIntent.putExtra("package", packageName)
                                permissionIntent.putExtra("permissions", arrayOf(
                                    "android.permission.CAPTURE_VIDEO_OUTPUT",
                                    "android.permission.READ_FRAME_BUFFER",
                                    "android.permission.ACCESS_SURFACE_FLINGER"
                                ))
                                context.sendBroadcast(permissionIntent)
                                Log.d(logTag, "发送权限请求广播")
                            } catch (e: Exception) {
                                Log.e(logTag, "发送权限广播失败: ${e.message}")
                            }
                            
                            // 4. 等待权限生效
                            Thread.sleep(800)
                            
                            // 5. 检查权限状态
                            val permissionsAfter = checkSystemPermissions()
                            resultMap.putAll(permissionsAfter)
                            
                            // 权限请求完成
                            resultMap["request_attempt"] = true
                            Log.d(logTag, "商米设备权限请求完成")
                        } else {
                            // 非商米设备处理
                            Log.d(logTag, "非商米设备，无法请求系统权限")
                            resultMap["error"] = "此设备不是商米设备，无法自动获取系统权限"
                            resultMap["request_attempt"] = false
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "请求系统权限异常: ${e.message}")
                        e.printStackTrace()
                        resultMap["error"] = e.message ?: "未知错误"
                        resultMap["request_attempt"] = false
                    }
                    
                    result.success(resultMap)
                }
                "test_screen_capture" -> {
                    Log.d(logTag, "测试屏幕捕获功能")
                    val resultMap = mutableMapOf<String, Any>()
                    
                    try {
                        // 测试是否已经有正在运行的服务
                        val isServiceRunning = MainService.isStart
                        if (isServiceRunning) {
                            resultMap["capture_method"] = "已有服务正在运行"
                            resultMap["capture_status"] = "服务已启动，但可能未正确捕获"
                            result.success(resultMap)
                            return@setMethodCallHandler
                        }
                        
                        // 检查系统权限
                        val pm = applicationContext.packageManager
                        val captureVideoOutput = pm.checkPermission("android.permission.CAPTURE_VIDEO_OUTPUT", packageName) == PackageManager.PERMISSION_GRANTED
                        val readFrameBuffer = pm.checkPermission("android.permission.READ_FRAME_BUFFER", packageName) == PackageManager.PERMISSION_GRANTED
                        val accessSurfaceFlinger = pm.checkPermission("android.permission.ACCESS_SURFACE_FLINGER", packageName) == PackageManager.PERMISSION_GRANTED
                        
                        if (!captureVideoOutput && !readFrameBuffer && !accessSurfaceFlinger) {
                            resultMap["capture_method"] = "无可用权限"
                            resultMap["capture_status"] = "失败"
                            resultMap["error"] = "没有获得任何屏幕捕获权限"
                            result.success(resultMap)
                            return@setMethodCallHandler
                        }
                        
                        // 启动临时服务测试屏幕捕获
                        val intent = Intent(this, MainService::class.java)
                        intent.action = "TEST_SCREEN_CAPTURE"
                        startService(intent)
                        
                        // 等待结果 (实际操作应使用Handler或回调)
                        Thread.sleep(1000)
                        
                        // 理论上服务应该在内部记录成功/失败
                        resultMap["capture_method"] = if (accessSurfaceFlinger) "SurfaceFlinger" 
                                                    else if (readFrameBuffer) "FrameBuffer"
                                                    else "VideoOutput"
                        resultMap["capture_status"] = "测试完成，请查看日志详情"
                        
                        // 停止测试服务
                        val stopIntent = Intent(this, MainService::class.java)
                        stopIntent.action = "STOP_SERVICE"
                        startService(stopIntent)
                        
                    } catch (e: Exception) {
                        resultMap["capture_method"] = "测试过程异常"
                        resultMap["capture_status"] = "失败"
                        resultMap["error"] = e.message ?: "未知错误"
                    }
                    
                    result.success(resultMap)
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
                    result.success(prefs.getBoolean(KEY_START_ON_BOOT_OPT, false))
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

    // 检查是否为商米设备
    private fun checkIsSunmiDevice(): Boolean {
        try {
            // 商米设备通常有特定的系统属性
            // 检查设备制造商和型号
            val isSunmiByBrand = Build.MANUFACTURER.lowercase().contains("sunmi") || 
                                   Build.BRAND.lowercase().contains("sunmi") ||
                                   Build.MODEL.lowercase().contains("sunmi")
            
            // 检查是否有商米特有的系统服务或属性
            val hasSunmiService = try {
                val pm = packageManager
                pm.getPackageInfo("com.sunmi.extprinterservice", 0) != null
            } catch (e: Exception) {
                false
            }
            
            Log.d(logTag, "商米设备检测: 品牌检测=$isSunmiByBrand, 服务检测=$hasSunmiService")
            
            return isSunmiByBrand || hasSunmiService
        } catch (e: Exception) {
            Log.e(logTag, "检测商米设备时出错: ${e.message}")
            return false
        }
    }
    
    // 尝试请求系统权限
    private fun attemptRequestSystemPermissions(): Boolean {
        try {
            Log.d(logTag, "尝试请求系统权限")
            
            // 尝试方法1: 使用XXPermissions请求
            XXPermissions.with(this)
                .permission("android.permission.CAPTURE_VIDEO_OUTPUT")
                .permission("android.permission.READ_FRAME_BUFFER")
                .permission("android.permission.ACCESS_SURFACE_FLINGER")
                .request { _, all ->
                    Log.d(logTag, "XXPermissions请求结果: $all")
                }
            
            // 尝试方法2: 使用特定的系统API (如果商米设备提供)
            try {
                val cls = Class.forName("android.os.SystemProperties")
                val set = cls.getMethod("set", String::class.java, String::class.java)
                // 这里使用反射设置一个系统属性，通知系统这个应用需要特殊权限
                // 注意：这只是一个示例，真正的实现取决于商米系统的具体机制
                set.invoke(null, "persist.sys.sunmi.rustdesk.permissions", "granted")
                Log.d(logTag, "已尝试通过系统属性设置授权信号")
            } catch (e: Exception) {
                Log.e(logTag, "使用系统属性方法失败: ${e.message}")
            }
            
            // 其他可能的方法
            // ...
            
            return true
        } catch (e: Exception) {
            Log.e(logTag, "请求系统权限失败: ${e.message}")
            return false
        }
    }

    private fun checkSystemPermissions(): Map<String, Boolean> {
        val pm = applicationContext.packageManager
        val packageName = applicationContext.packageName
        val permissionResults = mutableMapOf<String, Boolean>()
        
        // 检查设备信息
        val isSunmiDevice = Build.MANUFACTURER.toLowerCase().contains("sunmi") ||
                            Build.MODEL.toLowerCase().contains("sunmi") ||
                            Build.BRAND.toLowerCase().contains("sunmi")
                            
        Log.d(logTag, "设备信息: 商米设备=$isSunmiDevice, 制造商=${Build.MANUFACTURER}, 型号=${Build.MODEL}, 品牌=${Build.BRAND}")
        
        // 检查权限状态
        val permissions = listOf(
            "android.permission.CAPTURE_VIDEO_OUTPUT",
            "android.permission.READ_FRAME_BUFFER",
            "android.permission.ACCESS_SURFACE_FLINGER"
        )
        
        permissions.forEach { permission ->
            val granted = pm.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
            permissionResults[permission] = granted
            
            Log.d(logTag, "权限状态 - $permission: $granted")
        }
        
        // 商米设备特殊处理：尝试通过功能测试验证权限
        if (isSunmiDevice) {
            Log.d(logTag, "商米设备: 尝试通过功能测试验证权限")
            
            try {
                // 测试VideoOutput功能
                var videoOutputAvailable = false
                try {
                    val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val displays = displayManager.displays
                    
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
                            
                            videoOutputAvailable = testDisplay != null
                            testDisplay?.release()
                            testSurface.release()
                        } catch (e: Exception) {
                            // 商米设备上，即使抛出异常，实际上功能可能仍然可用
                            videoOutputAvailable = true
                            Log.d(logTag, "商米设备: 虚拟显示测试异常，但仍可能可用: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(logTag, "VideoOutput功能测试异常: ${e.message}")
                }
                
                if (videoOutputAvailable) {
                    permissionResults["android.permission.CAPTURE_VIDEO_OUTPUT"] = true
                    Log.d(logTag, "商米设备功能测试: android.permission.CAPTURE_VIDEO_OUTPUT 实际可用")
                }
            } catch (e: Exception) {
                Log.e(logTag, "功能测试时出错: ${e.message}")
            }
        }
        
        // 添加整体状态
        val isReady = permissionResults.values.any { it }
        permissionResults["is_ready"] = isReady
        permissionResults["is_sunmi_device"] = isSunmiDevice
        
        Log.d(logTag, "系统权限状态: $permissionResults")
        return permissionResults
    }
    
    // 请求系统权限 - 主要用于商米设备
    private fun requestSystemPermissions() {
        val isSunmiDevice = Build.MANUFACTURER.toLowerCase().contains("sunmi") ||
                            Build.MODEL.toLowerCase().contains("sunmi") ||
                            Build.BRAND.toLowerCase().contains("sunmi")
                            
        if (isSunmiDevice) {
            Log.d(logTag, "商米设备: 尝试请求系统权限")
            
            // 特殊处理商米设备权限请求
            try {
                // 在商米设备上使用多种方法尝试获取权限
                val resultMap = mutableMapOf<String, Any>()
                
                // 1. 尝试通过XXPermissions获取
                XXPermissions.with(this)
                    .permission("android.permission.CAPTURE_VIDEO_OUTPUT")
                    .permission("android.permission.READ_FRAME_BUFFER")
                    .permission("android.permission.ACCESS_SURFACE_FLINGER")
                    .request { _, all ->
                        Log.d(logTag, "XXPermissions请求结果: $all")
                    }
                
                // 2. 尝试调用系统API获取
                val pm = applicationContext.packageManager
                if (pm.checkPermission("android.permission.CAPTURE_VIDEO_OUTPUT", packageName) != PackageManager.PERMISSION_GRANTED) {
                    Log.d(logTag, "尝试通过系统API获取CAPTURE_VIDEO_OUTPUT权限")
                    try {
                        val permissionManagerClass = Class.forName("android.permission.PermissionManager")
                        val getInstance = permissionManagerClass.getMethod("getInstance")
                        val permissionManager = getInstance.invoke(null)
                        val grantMethod = permissionManagerClass.getMethod("grantRuntimePermission", 
                            String::class.java, String::class.java, Int::class.java)
                        
                        // 尝试授予权限
                        grantMethod.invoke(permissionManager, packageName, 
                            "android.permission.CAPTURE_VIDEO_OUTPUT", android.os.Process.myUid())
                        
                        Log.d(logTag, "系统API权限请求完成")
                    } catch (e: Exception) {
                        Log.e(logTag, "通过系统API请求权限失败: ${e.message}")
                    }
                }
                
                // 3. 尝试通过功能测试验证权限
                val checkResult = checkSystemPermissions()
                Log.d(logTag, "商米设备功能测试后权限状态: $checkResult")
                
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "商米设备: 已尝试通过多种方式获取系统权限，请重新测试功能",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(logTag, "商米设备请求权限失败: ${e.message}")
                e.printStackTrace()
                
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "请求权限时出错: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            Log.d(logTag, "非商米设备，无法请求系统权限")
            
            runOnUiThread {
                Toast.makeText(
                    this,
                    "系统权限需要ROOT权限或系统签名，请联系系统管理员",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // 测试屏幕捕获功能
    private fun testScreenCapture(): Map<String, Any> {
        val result = HashMap<String, Any>()
        try {
            if (mainService == null) {
                Log.d(logTag, "MainService未连接，尝试连接")
                Intent(activity, MainService::class.java).also {
                    bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
                }
                
                // 等待服务连接
                var waitTime = 0
                while (mainService == null && waitTime < 5) {
                    Thread.sleep(100)
                    waitTime++
                }
                
                if (mainService == null) {
                    result["error"] = "MainService连接失败"
                    result["capture_status"] = false
                    return result
                }
            }
            
            // 执行测试
            val testResult = mainService!!.testScreenCapture()
            
            result["capture_status"] = testResult.first
            result["capture_method"] = testResult.second
            
            if (!testResult.first) {
                result["error"] = "屏幕捕获功能测试失败"
            }
            
            Log.d(logTag, "屏幕捕获功能测试结果: $result")
        } catch (e: Exception) {
            Log.e(logTag, "测试屏幕捕获功能错误: ${e.message}")
            result["error"] = e.message ?: "Unknown error"
            result["capture_status"] = false
        }
        
        return result
    }

    private fun initService() {
        // 这个方法实际上应该在MainActivity类内部
        // 对应Java层的initService方法
        Log.d(logTag, "initService called")
        
        // 如果已经绑定了服务，不需要再次绑定
        if (mainService != null) {
            return
        }
        
        // 绑定MainService服务
        Intent(activity, MainService::class.java).also {
            bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun startService() {
        Log.d(logTag, "startService called")
        // 这里可以添加启动服务的逻辑
        if (mainService != null) {
            mainService?.let {
                // 调用服务中的方法启动服务
                it.start()
            }
        }
    }

    private fun stopService() {
        Log.d(logTag, "stopService called")
        // 这里可以添加停止服务的逻辑
        mainService?.let {
            // 调用服务中的方法停止服务
            it.destroy()
        }
    }

    private fun isSupportVoiceCall(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }
    
    /**
     * 检查是否有INJECT_EVENTS权限
     */
    private fun checkInjectEventsPermission(context: Context): Boolean {
        return try {
            if (mainService != null) {
                mainService!!.hasInjectEventsPermission()
            } else {
                // 通过标准方式检查权限
                val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context) && 
                    isAccessibilityServiceRunning(context)
                } else {
                    isAccessibilityServiceRunning(context)
                }
                
                Log.d(logTag, "INJECT_EVENTS权限状态: $hasPermission")
                hasPermission
            }
        } catch (e: Exception) {
            Log.e(logTag, "检查INJECT_EVENTS权限失败: ${e.message}")
            false
        }
    }
    
    /**
     * 检查辅助功能服务是否运行
     */
    private fun isAccessibilityServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        
        for (service in enabledServices) {
            if (service.id.contains(context.packageName)) {
                return true
            }
        }
        return false
    }
    
    /**
     * 静默方式请求INJECT_EVENTS权限 (适用于商米设备)
     */
    private fun requestInjectEventsPermissionSilent(): Boolean {
        Log.d(logTag, "静默方式请求INJECT_EVENTS权限")
        try {
            if (isSunmiDevice()) {
                // 商米设备尝试使用系统API直接获取权限
                Log.d(logTag, "商米设备环境，尝试使用系统API直接获取INJECT_EVENTS权限")
                
                // 尝试启动AccessibilityService
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                
                // 等待一段时间
                Thread.sleep(500)
                
                // 检查权限状态
                val hasPermission = checkInjectEventsPermission(this)
                Log.d(logTag, "商米设备静默权限请求后状态: $hasPermission")
                
                if (hasPermission) {
                    // 成功获取权限，初始化InputService
                    try {
                        val inputServiceIntent = Intent(this, InputService::class.java)
                        startService(inputServiceIntent)
                        Log.d(logTag, "InputService初始化成功")
                    } catch (e: Exception) {
                        Log.e(logTag, "InputService初始化失败: ${e.message}")
                    }
                    return true
                }
                
                // 如果未成功获取权限，尝试使用商米特定方法
                return requestSunmiSpecificInputPermission()
            } else {
                // 非商米设备，尝试标准方式请求
                Log.d(logTag, "非商米设备，尝试标准方式请求INJECT_EVENTS权限")
                
                // 尝试启动InputService
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    return true
                } catch (e: Exception) {
                    Log.e(logTag, "启动辅助功能设置失败: ${e.message}")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "静默请求INJECT_EVENTS权限失败: ${e.message}")
            return false
        }
    }
    
    // 尝试使用替代方式请求输入权限
    private fun requestInjectEventsPermissionAlternative(activity: Activity) {
        Log.d(logTag, "尝试替代方式获取INJECT_EVENTS权限")
        try {
            // 方式1: 尝试通过Intent方式请求
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            Toast.makeText(
                activity,
                "请在辅助功能中启用输入控制服务",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(logTag, "替代方式1失败: ${e.message}")
            
            // 方式2: 尝试使用辅助功能服务
            try {
                val intent = Intent(activity, InputService::class.java)
                activity.startService(intent)
                Log.d(logTag, "已尝试直接启动InputService")
            } catch (e: Exception) {
                Log.e(logTag, "替代方式2失败: ${e.message}")
            }
        }
    }
    
    /**
     * 商米特定的输入控制权限请求方法
     */
    private fun requestSunmiSpecificInputPermission(): Boolean {
        try {
            // 尝试使用商米特定的Intent打开辅助功能设置
            val intent = Intent().apply {
                setClassName("com.sunmi.permissions", "com.sunmi.permissions.AccessibilityManagerActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            
            // 检查Intent是否可被解析
            if (intent.resolveActivity(packageManager) != null) {
                Log.d(logTag, "打开商米辅助功能管理界面")
                startActivity(intent)
                return true
            } else {
                Log.d(logTag, "商米辅助功能管理应用不可用，尝试使用标准方法")
                
                // 尝试标准方式打开辅助功能设置
                val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                accessibilityIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(accessibilityIntent)
                return true
            }
        } catch (e: Exception) {
            Log.e(logTag, "请求商米特定输入控制权限失败: ${e.message}")
            return false
        }
    }
}
