package com.carriez.flutter_hbb

/**
 * Handle remote input and dispatch android gesture
 *
 * Modified to use INJECT_EVENTS permission instead of AccessibilityService
 */

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent as KeyEventAndroid
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.hardware.input.InputManager
import android.media.AudioManager
import android.view.KeyCharacterMap
import androidx.annotation.RequiresApi
import java.util.*
import java.lang.Character
import kotlin.math.abs
import kotlin.math.max
import hbb.MessageOuterClass.KeyEvent
import hbb.MessageOuterClass.KeyboardMode
import kotlin.concurrent.thread
import java.lang.reflect.Method
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import io.flutter.plugin.common.MethodChannel
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import kotlin.math.roundToInt

// const val BUTTON_UP = 2
// const val BUTTON_BACK = 0x08

const val LEFT_DOWN = 9
const val LEFT_MOVE = 8
const val LEFT_UP = 10
const val RIGHT_UP = 18
// (BUTTON_BACK << 3) | BUTTON_UP
const val BACK_UP = 66
const val WHEEL_BUTTON_DOWN = 33
const val WHEEL_BUTTON_UP = 34
const val WHEEL_DOWN = 523331
const val WHEEL_UP = 963

const val TOUCH_SCALE_START = 1
const val TOUCH_SCALE = 2
const val TOUCH_SCALE_END = 3
const val TOUCH_PAN_START = 4
const val TOUCH_PAN_UPDATE = 5
const val TOUCH_PAN_END = 6

const val WHEEL_STEP = 120
const val WHEEL_DURATION = 50L
const val LONG_TAP_DELAY = 200L

// 定义InputManager的常量，以防止编译错误
private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

// 定义KeyboardMode的常量
private val LEGACY_MODE = KeyboardMode.Legacy.number
private val TRANSLATE_MODE = KeyboardMode.Translate.number
private val MAP_MODE = KeyboardMode.Map.number

// 添加重试队列
data class RetryEvent(
    val downTime: Long,
    val eventTime: Long,
    val action: Int,
    val x: Float,
    val y: Float,
    val retryCount: Int,
    val lastEventTime: Long = 0, // 上次尝试注入的时间
    val isSystemArea: Boolean = false // 是否是系统区域
)

// 修改TouchArea类，添加时间戳
data class TouchArea(
    val x: Float,
    val y: Float,
    val timestamp: Long = System.currentTimeMillis()
)

// InputService类用于处理输入事件
class InputService : AccessibilityService() {

    companion object {
        var ctx: InputService? = null
        val isOpen: Boolean
            get() = ctx != null
    }

    private val logTag = "input service"
    private var leftIsDown = false
    private val touchPath = Path()
    private var lastTouchGestureStartTime = 0L
    private var mouseX = 0
    private var mouseY = 0
    private var timer = Timer()
    private var recentActionTask: TimerTask? = null
    // 100(tap timeout) + 400(long press timeout)
    private val longPressDuration = ViewConfiguration.getTapTimeout().toLong() + ViewConfiguration.getLongPressTimeout().toLong()

    private var isWaitingLongPress = false
    
    // 追踪事件状态，避免重复注入
    private var lastActionType = -1
    private var lastEventTime = 0L
    private var pendingEventRetry: RetryEvent? = null
    private var isWaitingRetry = false
    private var eventInProcessing = false
    
    // 特殊区域定义 - 屏幕底部的系统按钮区域
    private val isSystemAreaEvent: (Float) -> Boolean = { y ->
        val screenHeight = SCREEN_INFO.height
        val systemAreaHeight = 100 // 预估的系统导航栏高度
        y > (screenHeight - systemAreaHeight)
    }

    private var lastX = 0
    private var lastY = 0

    private lateinit var volumeController: VolumeController
    private var inputManager: InputManager? = null
    private lateinit var appContext: Context
    private lateinit var handler: Handler
    
    // 事件处理状态
    private var lastDownTime = 0L   // 上次DOWN事件的时间戳
    
    // 添加临时暂停处理的变量
    private var isPaused = false
    private var savedLastActionType = -1
    private var savedLastEventTime = 0L
    
    // 记录已点击过的区域，避免重复点击导致卡住
    private val recentlyTouchedAreas = mutableListOf<TouchArea>()
    private var lastTouchCleanTime = 0L
    
    private lateinit var mWindowManager: WindowManager
    private var imageView: ImageView? = null
    private var rootView: FrameLayout? = null

    private var pendingDoubleChecker: Handler? = null
    private var pendingRequestFocus: Handler? = null
    private var pendingCheck: Runnable? = null
    private var pendingFocus: Runnable? = null
    
    // 提供公开的无参构造函数
    constructor() : super() {
        Log.d(logTag, "InputService created with default constructor")
    }
    
    // 提供带Context参数的公开构造函数
    constructor(context: Context) : super() {
        Log.d(logTag, "InputService created with context constructor")
        initializeWithContext(context)
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(logTag, "InputService onCreate called")
        
        // 如果还未初始化，则使用applicationContext初始化
        if (!::appContext.isInitialized) {
            initializeWithContext(applicationContext)
        }
    }

    private fun initializeWithContext(context: Context) {
        ctx = this
        appContext = context.applicationContext
        handler = Handler(Looper.getMainLooper())
        volumeController = VolumeController(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        Log.d(logTag, "InputService initialized with INJECT_EVENTS permission")
    }

    override fun onBind(intent: Intent?): IBinder? {
        // 我们不需要绑定，所以返回null
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        disableSelf()
    }

    // 临时暂停输入事件处理，让应用自身的UI能够响应
    fun temporarilyResetState() {
        Log.d(logTag, "临时暂停输入事件处理")
        savedLastActionType = lastActionType
        savedLastEventTime = lastEventTime
        
        // 清除已点击区域记录
        recentlyTouchedAreas.clear()
        lastTouchCleanTime = SystemClock.uptimeMillis()
        
        // 将lastActionType设置为一个特殊值，表示暂停状态
        isPaused = true
        lastActionType = -999
        lastEventTime = 0L
        
        // 重置按键状态
        leftIsDown = false
        isWaitingLongPress = false
    }
    
    // 恢复输入事件处理
    fun restoreState() {
        if (!isPaused) return
        
        Log.d(logTag, "恢复输入事件处理")
        lastActionType = savedLastActionType
        lastEventTime = savedLastEventTime
        isPaused = false
        
        // 确保所有按键都释放
        if (lastActionType == MotionEvent.ACTION_DOWN) {
            // 如果之前是按下状态，强制发送一个UP事件
            handler.post {
                injectMotionEvent(MotionEvent.ACTION_UP, mouseX.toFloat(), mouseY.toFloat())
            }
        }
    }

    // 检查当前触摸位置是否在最近点击过的区域内
    private fun isTouchInRecentArea(x: Float, y: Float): Boolean {
        // 定期清理超过2秒的区域记录
        val now = SystemClock.uptimeMillis()
        if (now - lastTouchCleanTime > 2000) {
            val expiredTime = now - 2000
            recentlyTouchedAreas.removeAll { it.timestamp < expiredTime }
            lastTouchCleanTime = now
            if (recentlyTouchedAreas.isEmpty()) {
                return false
            }
        }
        
        // 检查是否在任何已记录区域内
        for (area in recentlyTouchedAreas) {
            val distance = Math.sqrt(Math.pow((x - area.x).toDouble(), 2.0) + 
                                    Math.pow((y - area.y).toDouble(), 2.0))
            if (distance < 60) {
                Log.d(logTag, "触摸点在最近点击过的区域内: ($x,$y) 在区域 (${area.x},${area.y})")
                return true
            }
        }
        return false
    }
    
    // 添加当前触摸位置到记录列表
    private fun addTouchArea(x: Float, y: Float) {
        // 使用适当的半径（像素），根据设备DPI可能需要调整
        // 为系统按钮区域使用更大的半径
        recentlyTouchedAreas.add(TouchArea(x, y, SystemClock.uptimeMillis()))
        
        // 最多保留10个区域记录
        if (recentlyTouchedAreas.size > 10) {
            recentlyTouchedAreas.removeAt(0)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onMouseInput(event: Map<String, Any?>, result: MethodChannel.Result) {
        // 添加日志记录收到的鼠标输入事件
        Log.d(logTag, "收到鼠标输入事件: $event")
        
        try {
            val action = (event["type"] as Number?)?.toInt() ?: return result.success(false)
            val mask = (event["mask"] as Number?)?.toInt() ?: 0
            val x = (event["x"] as Number?)?.toFloat()?.roundToInt() ?: 0
            val y = (event["y"] as Number?)?.toFloat()?.roundToInt() ?: 0
            val button = event["button"] as? String ?: ""
            
            // 增加对LEFT_DOWN和LEFT_UP的去重逻辑
            if (action == MouseEvent.ACTION_LEFT_DOWN.ordinal || action == MouseEvent.ACTION_LEFT_UP.ordinal) {
                val now = System.currentTimeMillis()
                // 对于DOWN和UP事件，检查是否在100ms内有相同类型的事件
                if (lastActionType == action && now - lastEventTime < 100) {
                    Log.d(logTag, "跳过重复的鼠标事件 - 类型: $action, 上次时间差: ${now - lastEventTime}ms")
                    result.success(true)
                    return
                }
                
                // 更新最后的动作类型和时间
                lastActionType = action
                lastEventTime = now
            }
            
            // 根据动作类型执行不同操作
            when (action) {
                MouseEvent.ACTION_MOVE.ordinal -> {
                    Log.d(logTag, "移动鼠标到 x: $x, y: $y")
                    if (mask == 0) {
                        simulateMove(x, y)
                    } else {
                        simulateDrag(x, y)
                    }
                    result.success(true)
                }
                MouseEvent.ACTION_DOWN.ordinal -> {
                    Log.d(logTag, "鼠标按下 x: $x, y: $y, 按钮: $button")
                    when (button) {
                        "left" -> {
                            simulateClick(x, y, false)
                            result.success(true)
                        }
                        "right" -> {
                            simulateClick(x, y, true)
                            result.success(true)
                        }
                        else -> {
                            result.success(false)
                        }
                    }
                }
                
                MouseEvent.ACTION_LEFT_DOWN.ordinal -> {
                    Log.d(logTag, "左键按下 x: $x, y: $y")
                    
                    // 检查是否是系统区域或应用图标区域
                    val isSystemArea = isSystemAreaEvent(y.toFloat())
                    val isAppIconArea = isSystemAreaEvent(y.toFloat())
                    
                    if (isSystemArea) {
                        Log.d(logTag, "系统区域左键按下，延迟处理")
                        // 对于系统区域的点击，我们直接处理UP事件，延迟DOWN事件
                        result.success(true)
                        return
                    }
                    
                    if (isAppIconArea) {
                        Log.d(logTag, "应用图标区域左键按下")
                        // 对于应用图标区域，确保完整发送点击
                        val injectResult = injectMotionEvent(
                            mapOf(
                                "action" to MotionEvent.ACTION_DOWN,
                                "x" to x.toFloat(),
                                "y" to y.toFloat(),
                                "downTime" to System.currentTimeMillis(),
                                "eventTime" to System.currentTimeMillis()
                            )
                        )
                        result.success(injectResult)
                        return
                    }
                    
                    mouseLeftDown(x, y)
                    result.success(true)
                }
                
                MouseEvent.ACTION_LEFT_UP.ordinal -> {
                    Log.d(logTag, "左键抬起 x: $x, y: $y")
                    
                    // 检查是否是系统区域或应用图标区域
                    val isSystemArea = isSystemAreaEvent(y.toFloat())
                    val isAppIconArea = isSystemAreaEvent(y.toFloat())
                    
                    if (isSystemArea) {
                        Log.d(logTag, "系统区域左键抬起，使用dispatchClick")
                        // 对于系统区域的点击，直接使用dispatchClick处理
                        val success = dispatchClick(x.toFloat(), y.toFloat())
                        result.success(success)
                        return
                    }
                    
                    if (isAppIconArea) {
                        Log.d(logTag, "应用图标区域左键抬起")
                        // 对于应用图标区域，确保完整发送点击
                        val injectResult = injectMotionEvent(
                            mapOf(
                                "action" to MotionEvent.ACTION_UP,
                                "x" to x.toFloat(),
                                "y" to y.toFloat(),
                                "downTime" to lastEventTime,
                                "eventTime" to System.currentTimeMillis()
                            )
                        )
                        result.success(injectResult)
                        return
                    }
                    
                    mouseLeftUp()
                    result.success(true)
                }
                
                MouseEvent.ACTION_WHEEL.ordinal -> {
                    val value = (event["value"] as Number?)?.toFloat() ?: 0f
                    Log.d(logTag, "滚轮事件 值: $value")
                    mouseWheel(value)
                    result.success(true)
                }
                
                MouseEvent.ACTION_LONG_TAP.ordinal -> {
                    Log.d(logTag, "长按事件 x: $x, y: $y")
                    // 对于长按，我们确保使用直接点击而不是合成事件
                    dispatchClick(x.toFloat(), y.toFloat())
                    result.success(true)
                }
                
                else -> {
                    result.success(false)
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "处理鼠标输入异常", e)
            result.success(false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onTouchInput(mask: Int, x: Int, y: Int) {
        // 当输入服务处于暂停状态时不处理任何事件
        if (isPaused) {
            Log.d(logTag, "输入服务暂停中，跳过触摸输入事件")
            return
        }
        
        // 添加日志便于调试
        Log.d(logTag, "接收到触摸输入事件: mask=$mask, x=$x, y=$y")
        
        // 检查是否在最近点击过的区域，如果是TOUCH_PAN_START则自动暂停
        if (mask == TOUCH_PAN_START && isTouchInRecentArea(x.toFloat(), y.toFloat())) {
            Log.d(logTag, "触摸在最近交互过的区域，自动暂停事件处理")
            temporarilyResetState()
            
            // 300毫秒后恢复，给应用UI足够的处理时间
            handler.postDelayed({
                restoreState()
            }, 300)
            return
        }
        
        // 记录当前系统时间，用于时间间隔计算
        val currentTime = SystemClock.uptimeMillis()
        
        when (mask) {
            TOUCH_SCALE_START -> {
                // Handle pinch to zoom start
                lastX = x
                lastY = y
                Log.d(logTag, "TOUCH_SCALE_START: 初始化缩放开始点")
            }
            TOUCH_SCALE -> {
                // Handle pinch to zoom
                val deltaX = x - lastX
                val deltaY = y - lastY
                lastX = x
                lastY = y
                
                try {
                    // Simulate pinch gesture
                    val downTime = SystemClock.uptimeMillis()
                    val eventTime = SystemClock.uptimeMillis()
                    
                    // 添加调试日志
                    Log.d(logTag, "TOUCH_SCALE: 执行缩放, deltaX=$deltaX, deltaY=$deltaY")
                    
                    // This is a simplified implementation - in a real app you'd need to track multiple pointers
                    val properties = arrayOf(MotionEvent.PointerProperties(), MotionEvent.PointerProperties())
                    properties[0].id = 0
                    properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER
                    properties[1].id = 1
                    properties[1].toolType = MotionEvent.TOOL_TYPE_FINGER
                    
                    val pointerCoords = arrayOf(MotionEvent.PointerCoords(), MotionEvent.PointerCoords())
                    pointerCoords[0].x = x.toFloat() - deltaX
                    pointerCoords[0].y = y.toFloat() - deltaY
                    pointerCoords[0].pressure = 1f
                    pointerCoords[0].size = 1f
                    
                    pointerCoords[1].x = x.toFloat() + deltaX
                    pointerCoords[1].y = y.toFloat() + deltaY
                    pointerCoords[1].pressure = 1f
                    pointerCoords[1].size = 1f
                    
                    val event = MotionEvent.obtain(
                        downTime, eventTime,
                        MotionEvent.ACTION_MOVE, 2, properties,
                        pointerCoords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                    )
                    
                    injectEvent(event)
                    event.recycle()
                } catch (e: Exception) {
                    Log.e(logTag, "Error during touch scale: ${e.message}")
                }
            }
            TOUCH_SCALE_END -> {
                // Handle pinch to zoom end
                Log.d(logTag, "TOUCH_SCALE_END: 缩放手势结束")
            }
            TOUCH_PAN_START -> {
                // 增强防重复触发逻辑
                if (lastActionType == MotionEvent.ACTION_DOWN && 
                    (currentTime - lastEventTime) < 200) {
                    Log.d(logTag, "跳过重复的TOUCH_PAN_START事件，距上次: ${currentTime - lastEventTime}ms")
                    return
                }
                
                // 记录触摸区域
                addTouchArea(x.toFloat(), y.toFloat())
                
                // 添加调试日志
                Log.d(logTag, "TOUCH_PAN_START: 开始平移手势 at ($x, $y)")
                
                // Handle pan start
                lastX = x
                lastY = y
                injectMotionEvent(MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
            }
            TOUCH_PAN_UPDATE -> {
                // Handle pan update
                // 添加调试日志
                if (lastX != 0 && lastY != 0) {
                    val deltaX = x - lastX
                    val deltaY = y - lastY
                    Log.d(logTag, "TOUCH_PAN_UPDATE: 平移更新, 移动: ($deltaX, $deltaY)")
                }
                
                injectMotionEvent(MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat())
                lastX = x
                lastY = y
            }
            TOUCH_PAN_END -> {
                // 增强防重复触发逻辑
                if (lastActionType == MotionEvent.ACTION_UP && 
                    (currentTime - lastEventTime) < 200) {
                    Log.d(logTag, "跳过重复的TOUCH_PAN_END事件，距上次: ${currentTime - lastEventTime}ms")
                    return
                }
                
                // 添加调试日志
                Log.d(logTag, "TOUCH_PAN_END: 平移手势结束 at ($x, $y)")
                
                // Handle pan end
                injectMotionEvent(MotionEvent.ACTION_UP, x.toFloat(), y.toFloat())
            }
        }
    }

    fun onKeyEvent(input: ByteArray) {
        // 当输入服务处于暂停状态时不处理任何事件
        if (isPaused) {
            Log.d(logTag, "输入服务暂停中，跳过键盘输入事件")
            return
        }
        
        try {
            val keyEvent = KeyEvent.parseFrom(input)
            
            when (keyEvent.getMode().number) {
                LEGACY_MODE -> {
                    // 使用getChr()方法获取键码
                    val keyCode = keyEvent.getChr()
                    val down = keyEvent.getDown()
                    
                    // 处理文本输入
                    if (keyEvent.hasChr() && (down || keyEvent.getPress())) {
                        val chr = keyEvent.getChr()
                        if (chr != 0) {
                            injectText(String(Character.toChars(chr)))
                            return
                        }
                    }
                    
                    // 处理普通按键
                    if (down) {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_DOWN)
                    } else {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_UP)
                    }
                }
                TRANSLATE_MODE -> {
                    // 处理文本输入
                    if (keyEvent.hasSeq() && keyEvent.getSeq().isNotEmpty()) {
                        injectText(keyEvent.getSeq())
                        return
                    }
                    
                    // 处理普通按键
                    // 使用getChr()方法获取键码
                    val keyCode = keyEvent.getChr()
                    if (keyEvent.getDown()) {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_DOWN)
                    } else {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_UP)
                    }
                }
                MAP_MODE -> {
                    // 处理文本输入
                    if (keyEvent.hasSeq() && keyEvent.getSeq().isNotEmpty()) {
                        injectText(keyEvent.getSeq())
                        return
                    }
                    
                    // 处理普通按键
                    // 使用getChr()方法获取键码
                    val keyCode = keyEvent.getChr()
                    if (keyEvent.getDown()) {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_DOWN)
                    } else {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_UP)
                    }
                }
                else -> {
                    // Unsupported mode
                    Log.e(logTag, "Unsupported keyboard mode: ${keyEvent.getMode()}")
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Failed to parse key event: ${e.message}")
        }
    }

    // Helper methods for event injection
    
    private fun injectEvent(event: InputEvent): Boolean {
        try {
            // 由于无法使用 InputManager.injectInputEvent 方法，我们使用反射来调用它
            inputManager?.let { manager ->
                val method = InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
                return (method.invoke(manager, event, INJECT_INPUT_EVENT_MODE_ASYNC) as Boolean)
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error injecting event via reflection: ${e.message}")
        }
        return false
    }
    
    private fun injectMotionEvent(action: Int, x: Float, y: Float, isLongPress: Boolean = false): Boolean {
        try {
            // 添加更多详细的日志信息
            val actionName = when (action) {
                MotionEvent.ACTION_DOWN -> "ACTION_DOWN"
                MotionEvent.ACTION_UP -> "ACTION_UP"
                MotionEvent.ACTION_MOVE -> "ACTION_MOVE"
                else -> "未知动作($action)"
            }
            
            Log.d(logTag, "注入动作事件: $actionName at ($x, $y), isLongPress=$isLongPress")
            
            // 检查事件防重复（相同类型的事件在短时间内只处理一次）
            val now = SystemClock.uptimeMillis()
            
            // 对于ACTION_DOWN和ACTION_UP做更严格的检查，因为这些可能导致重复点击问题
            if (!isLongPress && 
                (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) && 
                action == lastActionType && 
                (now - lastEventTime) < 250) {
                Log.d(logTag, "跳过重复的动作事件: $actionName, 距上次: ${now - lastEventTime}ms")
                return true
            }
            
            // 对于MOVE事件，我们允许更频繁的注入，但仍然防止过于频繁
            if (action == MotionEvent.ACTION_MOVE && 
                action == lastActionType && 
                (now - lastEventTime) < 16) { // 约60fps
                // 不记录日志，避免日志过多
                return true
            }
            
            lastActionType = action
            lastEventTime = now
            
            val downTime = if (action == MotionEvent.ACTION_DOWN) now else lastDownTime
            if (action == MotionEvent.ACTION_DOWN) {
                lastDownTime = downTime
            }
            
            val eventTime = now
            
            // 创建MotionEvent
            val event = MotionEvent.obtain(
                downTime, eventTime, action, x, y, 0
            )
            
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            
            // 注入事件
            val result = injectEvent(event)
            event.recycle()
            
            if (!result) {
                Log.e(logTag, "注入动作事件失败: $actionName")
                
                // 如果是UP事件，我们需要确保它被发送，尝试再次发送
                if (action == MotionEvent.ACTION_UP) {
                    Log.d(logTag, "尝试再次发送UP事件")
                    
                    // 短暂延迟后再次尝试
                    Thread.sleep(10)
                    
                    val retryEvent = MotionEvent.obtain(
                        downTime, SystemClock.uptimeMillis(), action, x, y, 0
                    )
                    retryEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                    val retryResult = injectEvent(retryEvent)
                    retryEvent.recycle()
                    
                    return retryResult
                }
            }
            
            return result
            
        } catch (e: Exception) {
            Log.e(logTag, "Error injecting motion event: ${e.message}")
            return false
        }
    }
    
    private fun injectLongPress(x: Float, y: Float): Boolean {
        // Simulate a long press by sending down, waiting, then up
        val downResult = injectMotionEvent(MotionEvent.ACTION_DOWN, x, y)
        
        handler.postDelayed({
            injectMotionEvent(MotionEvent.ACTION_UP, x, y)
        }, longPressDuration)
        
        return downResult
    }
    
    private fun injectScroll(x: Float, y: Float, hScroll: Float, vScroll: Float): Boolean {
        try {
            val downTime = SystemClock.uptimeMillis()
            val eventTime = SystemClock.uptimeMillis()
            
            val properties = arrayOf(MotionEvent.PointerProperties().apply { 
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE 
            })
            
            val coords = arrayOf(MotionEvent.PointerCoords().apply { 
                this.x = x
                this.y = y
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            })
            
            val event = MotionEvent.obtain(
                downTime, eventTime, MotionEvent.ACTION_SCROLL, 1, 
                properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            
            val result = injectEvent(event)
            event.recycle()
            return result
        } catch (e: Exception) {
            Log.e(logTag, "Error injecting scroll event: ${e.message}")
            return false
        }
    }
    
    private fun injectKeyEvent(keyCode: Int, action: Int = KeyEventAndroid.ACTION_DOWN): Boolean {
        try {
            val downTime = SystemClock.uptimeMillis()
            val eventTime = SystemClock.uptimeMillis()
            
            val event = KeyEventAndroid(
                downTime, eventTime, action, keyCode, 0, 0,
                KeyEventAndroid.KEYCODE_UNKNOWN, 0, 0, InputDevice.SOURCE_KEYBOARD
            )
            
            val result = injectEvent(event)
            
            // If this is a key down, automatically send a key up after a short delay
            if (action == KeyEventAndroid.ACTION_DOWN) {
                handler.postDelayed({
                    injectKeyEvent(keyCode, KeyEventAndroid.ACTION_UP)
                }, 10)
            }
            
            return result
        } catch (e: Exception) {
            Log.e(logTag, "Error injecting key event: ${e.message}")
            return false
        }
    }
    
    private fun injectText(text: String): Boolean {
        if (text.isEmpty()) return false
        
        Log.d(logTag, "Injecting text: $text")
        
        // 在后台线程中处理文本注入，避免阻塞主线程
        thread(start = true) {
            try {
                // 使用KeyCharacterMap将文本转换为一系列KeyEvent
                val charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                
                for (char in text) {
                    try {
                        val events = charMap.getEvents(charArrayOf(char))
                        if (events != null) {
                            for (event in events) {
                                injectEvent(event)
                                // 添加短暂延迟以确保事件按顺序处理
                                Thread.sleep(5)
                            }
                        } else {
                            // 如果无法获取事件，尝试使用Unicode输入
                            val unicodeEvents = getEventsForChar(char)
                            for (event in unicodeEvents) {
                                injectEvent(event)
                                Thread.sleep(5)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "Error processing character '$char': ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "Error in text injection: ${e.message}")
            }
        }
        
        return true
    }
    
    private fun getEventsForChar(char: Char): Array<KeyEventAndroid> {
        val code = char.code
        
        // 对于基本ASCII字符，我们可以直接映射
        if (code < 128) {
            val keyCode = when (char) {
                in 'a'..'z' -> KeyEventAndroid.KEYCODE_A + (char - 'a')
                in 'A'..'Z' -> KeyEventAndroid.KEYCODE_A + (char - 'A')
                in '0'..'9' -> KeyEventAndroid.KEYCODE_0 + (char - '0')
                ' ' -> KeyEventAndroid.KEYCODE_SPACE
                '.' -> KeyEventAndroid.KEYCODE_PERIOD
                ',' -> KeyEventAndroid.KEYCODE_COMMA
                '\n' -> KeyEventAndroid.KEYCODE_ENTER
                else -> KeyEventAndroid.KEYCODE_UNKNOWN
            }
            
            if (keyCode != KeyEventAndroid.KEYCODE_UNKNOWN) {
                val time = SystemClock.uptimeMillis()
                return arrayOf(
                    KeyEventAndroid(time, time, KeyEventAndroid.ACTION_DOWN, keyCode, 0, 0),
                    KeyEventAndroid(time, time, KeyEventAndroid.ACTION_UP, keyCode, 0, 0)
                )
            }
        }
        
        // 只使用简单的方式，避免使用Builder
        val time = SystemClock.uptimeMillis()
        return createFallbackKeyEvents(code)
    }
    
    private fun createFallbackKeyEvents(code: Int): Array<KeyEventAndroid> {
        val time = SystemClock.uptimeMillis()
        return try {
            arrayOf(
                KeyEventAndroid(time, time, KeyEventAndroid.ACTION_DOWN, 
                              KeyEventAndroid.KEYCODE_UNKNOWN, 1, 0, 0, code, 0),
                KeyEventAndroid(time, time, KeyEventAndroid.ACTION_UP, 
                              KeyEventAndroid.KEYCODE_UNKNOWN, 0, 0, 0, 0, 0)
            )
        } catch (e: Exception) {
            Log.e(logTag, "Error creating fallback key events: ${e.message}")
            // 最后的备选方案 - 只发送基本按键
            arrayOf(
                KeyEventAndroid(time, time, KeyEventAndroid.ACTION_DOWN, 
                              KeyEventAndroid.KEYCODE_UNKNOWN, 0, 0),
                KeyEventAndroid(time, time, KeyEventAndroid.ACTION_UP, 
                              KeyEventAndroid.KEYCODE_UNKNOWN, 0, 0)
            )
        }
    }

    fun disableSelf() {
        try {
            handler.removeCallbacksAndMessages(null)
            timer.cancel()
            ctx = null
        } catch (e: Exception) {
            Log.e(logTag, "Error in disableSelf: ${e.message}")
        }
    }

    private fun dispatchClick(x: Float, y: Float): Boolean {
        Log.d(logTag, "Dispatch click - x: $x, y: $y")
        // 检查是否有最近的类似位置的点击
        val currentTime = System.currentTimeMillis()
        val nearbyClicks = recentlyTouchedAreas.filter { 
            val xDiff = Math.abs(it.x - x)
            val yDiff = Math.abs(it.y - y)
            val timeDiff = currentTime - it.timestamp
            
            // 检查时间和距离，特别是系统区域的点击需要更严格的检查
            val isNearby = xDiff < 20 && yDiff < 20 && timeDiff < 500
            isNearby
        }
        
        // 如果有最近的类似点击，跳过
        if (nearbyClicks.isNotEmpty()) {
            Log.d(logTag, "跳过重复点击 - 最近已有类似位置的点击")
            return false
        }
        
        // 记录当前点击
        addTouchArea(x, y)
        // 清理超过1秒的记录
        val expiredTime = currentTime - 1000
        recentlyTouchedAreas.removeIf { it.timestamp < expiredTime }
        
        // 判断是否是系统区域事件
        val isSystemArea = isSystemAreaEvent(y.toFloat())
        val isAppIconArea = isSystemAreaEvent(y.toFloat())
        
        // 针对系统区域的点击，使用更大的触摸半径
        val touchRadius = if (isSystemArea) 10f else 5f
        
        if (pendingCheck != null && pendingDoubleChecker != null) {
            pendingDoubleChecker!!.removeCallbacks(pendingCheck!!)
            pendingCheck = null
        }
        
        // 创建手势路径
        val clickPath = Path()
        clickPath.moveTo(x, y)
        
        // 创建手势描述
        val gestureBuilder = GestureDescription.Builder()
        val gestureStroke = StrokeDescription(
            clickPath,
            0,
            if (isSystemArea) 50 else 10,
            isSystemArea
        )
        gestureBuilder.addStroke(gestureStroke)
        
        // 执行手势
        Log.d(logTag, "执行点击手势 - x: $x, y: $y, 系统区域: $isSystemArea, 应用图标区域: $isAppIconArea")
        return dispatchGesture(
            gestureBuilder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(logTag, "点击手势完成")
                }
                
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.d(logTag, "点击手势被取消")
                }
            },
            null
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun dispatchSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long
    ): Boolean {
        val swipePath = Path()
        swipePath.moveTo(x1, y1)
        swipePath.lineTo(x2, y2)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(StrokeDescription(swipePath, 0, duration))
        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    override fun onServiceConnected() {
        Log.d(logTag, "onServiceConnected")
        super.onServiceConnected()
        mWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        rootView = FrameLayout(this)
        imageView = ImageView(this)
        imageView?.layoutParams = FrameLayout.LayoutParams(300, 300, Gravity.CENTER)

        rootView?.addView(imageView)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_PHONE else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        mWindowManager.addView(rootView, layoutParams)
        showFloatBall(false)
        if (pendingDoubleChecker == null) {
            pendingDoubleChecker = Handler(Looper.getMainLooper())
        }
        if (pendingRequestFocus == null) {
            pendingRequestFocus = Handler(Looper.getMainLooper())
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }
}
