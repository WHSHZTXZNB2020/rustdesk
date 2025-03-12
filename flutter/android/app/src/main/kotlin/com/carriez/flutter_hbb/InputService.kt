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

// InputService类用于处理输入事件
class InputService : Service {

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

    private var lastX = 0
    private var lastY = 0

    private lateinit var volumeController: VolumeController
    private var inputManager: InputManager? = null
    private lateinit var appContext: Context
    private lateinit var handler: Handler
    
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

    @RequiresApi(Build.VERSION_CODES.N)
    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        val x = max(0, _x)
        val y = max(0, _y)

        if (mask == 0 || mask == LEFT_MOVE) {
            val oldX = mouseX
            val oldY = mouseY
            mouseX = x * SCREEN_INFO.scale
            mouseY = y * SCREEN_INFO.scale
            if (isWaitingLongPress) {
                val delta = abs(oldX - mouseX) + abs(oldY - mouseY)
                Log.d(logTag,"delta:$delta")
                if (delta > 8) {
                    isWaitingLongPress = false
                }
            }
            
            // Move mouse pointer
            injectMotionEvent(MotionEvent.ACTION_MOVE, mouseX.toFloat(), mouseY.toFloat())
        }

        // left button down, was up
        if (mask == LEFT_DOWN) {
            isWaitingLongPress = true
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (isWaitingLongPress) {
                        isWaitingLongPress = false
                        // Long press
                        injectMotionEvent(MotionEvent.ACTION_DOWN, mouseX.toFloat(), mouseY.toFloat(), true)
                    }
                }
            }, longPressDuration)

            leftIsDown = true
            // Touch down
            injectMotionEvent(MotionEvent.ACTION_DOWN, mouseX.toFloat(), mouseY.toFloat())
            return
        }

        // left down, was down
        if (leftIsDown) {
            // Continue touch/drag
            injectMotionEvent(MotionEvent.ACTION_MOVE, mouseX.toFloat(), mouseY.toFloat())
        }

        // left up, was down
        if (mask == LEFT_UP) {
            if (leftIsDown) {
                leftIsDown = false
                isWaitingLongPress = false
                // Touch up
                injectMotionEvent(MotionEvent.ACTION_UP, mouseX.toFloat(), mouseY.toFloat())
                return
            }
        }

        if (mask == RIGHT_UP) {
            // Right click - simulate long press
            injectLongPress(mouseX.toFloat(), mouseY.toFloat())
            return
        }

        if (mask == BACK_UP) {
            // Back button
            injectKeyEvent(KeyEventAndroid.KEYCODE_BACK)
            return
        }

        // wheel button actions
        if (mask == WHEEL_BUTTON_DOWN) {
            timer.purge()
            recentActionTask = object : TimerTask() {
                override fun run() {
                    // Recent apps
                    injectKeyEvent(KeyEventAndroid.KEYCODE_APP_SWITCH)
                    recentActionTask = null
                }
            }
            timer.schedule(recentActionTask, LONG_TAP_DELAY)
        }

        if (mask == WHEEL_BUTTON_UP) {
            if (recentActionTask != null) {
                recentActionTask!!.cancel()
                // Home button
                injectKeyEvent(KeyEventAndroid.KEYCODE_HOME)
            }
            return
        }

        // Scroll actions
        if (mask == WHEEL_DOWN) {
            injectScroll(mouseX.toFloat(), mouseY.toFloat(), 0f, -WHEEL_STEP.toFloat())
        }

        if (mask == WHEEL_UP) {
            injectScroll(mouseX.toFloat(), mouseY.toFloat(), 0f, WHEEL_STEP.toFloat())
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onTouchInput(mask: Int, x: Int, y: Int) {
        when (mask) {
            TOUCH_SCALE_START -> {
                // Handle pinch to zoom start
                lastX = x
                lastY = y
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
            }
            TOUCH_PAN_START -> {
                // Handle pan start
                lastX = x
                lastY = y
                injectMotionEvent(MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
            }
            TOUCH_PAN_UPDATE -> {
                // Handle pan update
                injectMotionEvent(MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat())
                lastX = x
                lastY = y
            }
            TOUCH_PAN_END -> {
                // Handle pan end
                injectMotionEvent(MotionEvent.ACTION_UP, x.toFloat(), y.toFloat())
            }
        }
    }

    fun onKeyEvent(input: ByteArray) {
        try {
            val keyEvent = KeyEvent.parseFrom(input)
            
            // 添加详细日志
            Log.d(logTag, "收到键盘事件: mode=${keyEvent.getMode().number}, " +
                  "chr=${keyEvent.getChr()}, down=${keyEvent.getDown()}, " +
                  "press=${keyEvent.getPress()}, seq='${keyEvent.getSeq()}'")
            
            when (keyEvent.getMode().number) {
                LEGACY_MODE -> {
                    // 使用getChr()方法获取键码
                    val keyCode = keyEvent.getChr()
                    val down = keyEvent.getDown()
                    
                    // 检查是否是我们需要特殊处理的键
                    val isSpecialKey = keyCode == 32 || keyCode == 13 || keyCode == 8 || 
                                      (keyCode >= 65456 && keyCode <= 65465) ||  // 数字键盘0-9
                                      keyCode == 65453 || keyCode == 65451 || keyCode == 65450 || // 数字键盘运算符
                                      keyCode == 65455 || keyCode == 65454 || keyCode == 65452 || // 数字键盘其他键
                                      keyCode == 65515 || keyCode == 65516 || // 上下箭头
                                      keyCode == 65514 || keyCode == 65517 || // 左右箭头
                                      keyCode == 65511 || keyCode == 65512 || // Page Up/Down
                                      keyCode == 65509 || keyCode == 65510    // Home/End
                    
                    if (isSpecialKey) {
                        // 处理特殊键
                        var keyName = when (keyCode) {
                            32 -> "空格"
                            13 -> "回车"
                            8 -> "删除键"
                            65515 -> "上箭头"
                            65516 -> "下箭头"
                            65514 -> "左箭头"
                            65517 -> "右箭头"
                            in 65456..65465 -> "小键盘${keyCode - 65456}"
                            65453 -> "小键盘减号"
                            65451 -> "小键盘加号"
                            65450 -> "小键盘乘号"
                            65455 -> "小键盘除号"
                            65454 -> "小键盘回车"
                            65452 -> "小键盘点号"
                            65511 -> "Page Up"
                            65512 -> "Page Down"
                            65509 -> "Home"
                            65510 -> "End"
                            else -> "未知特殊键"
                        }
                        
                        Log.d(logTag, "处理特殊键: keyCode=$keyCode ($keyName), down=$down")
                        
                        if (down || keyEvent.getPress()) {
                            injectKeyEvent(keyCode, KeyEventAndroid.ACTION_DOWN)
                        } else {
                            injectKeyEvent(keyCode, KeyEventAndroid.ACTION_UP)
                        }
                        return
                    }
                    
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
                        val seq = keyEvent.getSeq()
                        Log.d(logTag, "处理文本序列: '$seq'")
                        
                        // 特殊处理可能包含特殊字符的文本
                        if (seq.contains('\b') || seq.contains('\n') || seq.contains(' ')) {
                            injectText(seq)
                            return
                        }
                        
                        injectText(seq)
                        return
                    }
                    
                    // 处理普通按键
                    val keyCode = keyEvent.getChr()
                    
                    // 检查是否是我们需要特殊处理的键
                    val isSpecialKey = keyCode == 32 || keyCode == 13 || keyCode == 8 || 
                                      (keyCode >= 65456 && keyCode <= 65465) ||  // 数字键盘0-9
                                      keyCode == 65453 || keyCode == 65451 || keyCode == 65450 || // 数字键盘运算符
                                      keyCode == 65455 || keyCode == 65454 || keyCode == 65452 || // 数字键盘其他键
                                      keyCode == 65515 || keyCode == 65516 || // 上下箭头
                                      keyCode == 65514 || keyCode == 65517 || // 左右箭头
                                      keyCode == 65511 || keyCode == 65512 || // Page Up/Down
                                      keyCode == 65509 || keyCode == 65510    // Home/End
                    
                    if (isSpecialKey) {
                        Log.d(logTag, "处理特殊键(Translate模式): keyCode=$keyCode, down=${keyEvent.getDown()}")
                        
                        if (keyEvent.getDown()) {
                            injectKeyEvent(keyCode, KeyEventAndroid.ACTION_DOWN)
                        } else {
                            injectKeyEvent(keyCode, KeyEventAndroid.ACTION_UP)
                        }
                        return
                    }
                    
                    if (keyEvent.getDown()) {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_DOWN)
                    } else {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_UP)
                    }
                }
                MAP_MODE -> {
                    // MAP模式类似于TRANSLATE模式的处理
                    if (keyEvent.hasSeq() && keyEvent.getSeq().isNotEmpty()) {
                        injectText(keyEvent.getSeq())
                        return
                    }
                    
                    val keyCode = keyEvent.getChr()
                    
                    // 检查是否是我们需要特殊处理的键
                    val isSpecialKey = keyCode == 32 || keyCode == 13 || keyCode == 8 || 
                                      (keyCode >= 65456 && keyCode <= 65465) ||  // 数字键盘0-9
                                      keyCode == 65453 || keyCode == 65451 || keyCode == 65450 || // 数字键盘运算符
                                      keyCode == 65455 || keyCode == 65454 || keyCode == 65452 || // 数字键盘其他键
                                      keyCode == 65515 || keyCode == 65516 || // 上下箭头
                                      keyCode == 65514 || keyCode == 65517 || // 左右箭头
                                      keyCode == 65511 || keyCode == 65512 || // Page Up/Down
                                      keyCode == 65509 || keyCode == 65510    // Home/End
                    
                    if (isSpecialKey) {
                        Log.d(logTag, "处理特殊键(Map模式): keyCode=$keyCode, down=${keyEvent.getDown()}")
                        
                        if (keyEvent.getDown()) {
                            injectKeyEvent(keyCode, KeyEventAndroid.ACTION_DOWN)
                        } else {
                            injectKeyEvent(keyCode, KeyEventAndroid.ACTION_UP)
                        }
                        return
                    }
                    
                    if (keyEvent.getDown()) {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_DOWN)
                    } else {
                        injectKeyEvent(keyCode, KeyEventAndroid.ACTION_UP)
                    }
                }
                else -> {
                    // Unsupported mode
                    Log.e(logTag, "不支持的键盘模式: ${keyEvent.getMode()}")
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "解析键盘事件失败: ${e.message}")
        }
    }

    // Helper methods for event injection
    
    private fun injectEvent(event: InputEvent): Boolean {
        try {
            // 由于无法直接使用 InputManager.injectInputEvent 方法，我们使用反射来调用它
            inputManager?.let { manager ->
                val method = InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
                return (method.invoke(manager, event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC) as Boolean)
            }
        } catch (e: Exception) {
            Log.e(logTag, "通过反射注入事件失败: ${e.message}")
        }
        return false
    }
    
    private fun injectMotionEvent(action: Int, x: Float, y: Float, isLongPress: Boolean = false): Boolean {
        try {
            val downTime = SystemClock.uptimeMillis()
            val eventTime = SystemClock.uptimeMillis()
            
            val event = MotionEvent.obtain(
                downTime, eventTime, action, x, y, 0
            )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            
            val result = injectEvent(event)
            event.recycle()
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
            Log.d(logTag, "注入键盘事件: keyCode=$keyCode, action=$action")
            
            // 根据特殊键的keyCode映射到Android键码
            val androidKeyCode = when (keyCode) {
                32 -> KeyEventAndroid.KEYCODE_SPACE
                13 -> KeyEventAndroid.KEYCODE_ENTER
                8 -> KeyEventAndroid.KEYCODE_DEL
                9 -> KeyEventAndroid.KEYCODE_TAB
                // 数字键盘
                65456 -> KeyEventAndroid.KEYCODE_NUMPAD_0
                65457 -> KeyEventAndroid.KEYCODE_NUMPAD_1
                65458 -> KeyEventAndroid.KEYCODE_NUMPAD_2
                65459 -> KeyEventAndroid.KEYCODE_NUMPAD_3
                65460 -> KeyEventAndroid.KEYCODE_NUMPAD_4
                65461 -> KeyEventAndroid.KEYCODE_NUMPAD_5
                65462 -> KeyEventAndroid.KEYCODE_NUMPAD_6
                65463 -> KeyEventAndroid.KEYCODE_NUMPAD_7
                65464 -> KeyEventAndroid.KEYCODE_NUMPAD_8
                65465 -> KeyEventAndroid.KEYCODE_NUMPAD_9
                65453 -> KeyEventAndroid.KEYCODE_NUMPAD_SUBTRACT
                65451 -> KeyEventAndroid.KEYCODE_NUMPAD_ADD
                65450 -> KeyEventAndroid.KEYCODE_NUMPAD_MULTIPLY
                65455 -> KeyEventAndroid.KEYCODE_NUMPAD_DIVIDE
                65454 -> KeyEventAndroid.KEYCODE_NUMPAD_ENTER
                65452 -> KeyEventAndroid.KEYCODE_NUMPAD_DOT
                // 箭头键
                65515 -> KeyEventAndroid.KEYCODE_DPAD_UP
                65516 -> KeyEventAndroid.KEYCODE_DPAD_DOWN
                65514 -> KeyEventAndroid.KEYCODE_DPAD_LEFT
                65517 -> KeyEventAndroid.KEYCODE_DPAD_RIGHT
                // 功能键
                65511 -> KeyEventAndroid.KEYCODE_PAGE_UP
                65512 -> KeyEventAndroid.KEYCODE_PAGE_DOWN
                65509 -> KeyEventAndroid.KEYCODE_MOVE_HOME
                65510 -> KeyEventAndroid.KEYCODE_MOVE_END
                else -> {
                    // 对于其他键，尝试使用默认的映射或fallback
                    Log.d(logTag, "使用默认键码映射: keyCode=$keyCode")
                    keyCode
                }
            }
            
            Log.d(logTag, "键码映射: 原始键码=$keyCode, Android键码=$androidKeyCode")
            
            val eventTime = SystemClock.uptimeMillis()
            val flags = KeyEventAndroid.FLAG_SOFT_KEYBOARD or KeyEventAndroid.FLAG_KEEP_TOUCH_MODE
            
            val event = KeyEventAndroid(
                eventTime, 
                eventTime, 
                action, 
                androidKeyCode, 
                0,  // repeat
                0,  // metaState
                KeyCharacterMap.VIRTUAL_KEYBOARD, 
                0,  // scancode
                flags,
                InputDevice.SOURCE_KEYBOARD
            )
            
            val result = injectEvent(event)
            Log.d(logTag, "键盘事件注入${if (result) "成功" else "失败"}: action=$action, keyCode=$androidKeyCode")
            
            // 如果注入失败，尝试使用fallback方法
            if (!result && action == KeyEventAndroid.ACTION_DOWN) {
                Log.d(logTag, "尝试使用fallback方法注入键盘事件")
                val events = createFallbackKeyEvents(keyCode)
                events.forEach { keyEvent ->
                    val fallbackResult = injectEvent(keyEvent)
                    Log.d(logTag, "Fallback键盘事件注入${if (fallbackResult) "成功" else "失败"}: action=${keyEvent.action}, keyCode=${keyEvent.keyCode}")
                }
            }
            
            return result
        } catch (e: Exception) {
            Log.e(logTag, "注入键盘事件失败: ${e.message}")
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
                        when (char) {
                            ' ' -> {
                                // 特殊处理空格键
                                injectKeyEvent(32)
                                Thread.sleep(15)
                            }
                            '\n' -> {
                                // 特殊处理回车键
                                injectKeyEvent(13)
                                Thread.sleep(15)
                            }
                            '\b' -> {
                                // 特殊处理删除键
                                injectKeyEvent(8)
                                Thread.sleep(15)
                            }
                            else -> {
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
        val time = SystemClock.uptimeMillis()
        
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
                '\t' -> KeyEventAndroid.KEYCODE_TAB
                '\b' -> KeyEventAndroid.KEYCODE_DEL
                else -> KeyEventAndroid.KEYCODE_UNKNOWN
            }
            
            if (keyCode != KeyEventAndroid.KEYCODE_UNKNOWN) {
                // 为特殊键添加额外的标志
                val flags = if (keyCode in arrayOf(
                    KeyEventAndroid.KEYCODE_SPACE,
                    KeyEventAndroid.KEYCODE_ENTER,
                    KeyEventAndroid.KEYCODE_DEL,
                    KeyEventAndroid.KEYCODE_TAB
                )) {
                    KeyEventAndroid.FLAG_SOFT_KEYBOARD or KeyEventAndroid.FLAG_KEEP_TOUCH_MODE
                } else {
                    0
                }
                
                return arrayOf(
                    KeyEventAndroid(time, time, KeyEventAndroid.ACTION_DOWN, keyCode, 1, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD),
                    KeyEventAndroid(time, time, KeyEventAndroid.ACTION_UP, keyCode, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD)
                )
            }
        }
        
        // 创建备用按键事件
        return createFallbackKeyEvents(code)
    }
    
    private fun createFallbackKeyEvents(keyCode: Int): Array<KeyEventAndroid> {
        try {
            // 根据特殊键的keyCode映射到Android键码
            val androidKeyCode = when (keyCode) {
                32 -> KeyEventAndroid.KEYCODE_SPACE
                13 -> KeyEventAndroid.KEYCODE_ENTER
                8 -> KeyEventAndroid.KEYCODE_DEL
                9 -> KeyEventAndroid.KEYCODE_TAB
                // 数字键盘
                65456 -> KeyEventAndroid.KEYCODE_NUMPAD_0
                65457 -> KeyEventAndroid.KEYCODE_NUMPAD_1
                65458 -> KeyEventAndroid.KEYCODE_NUMPAD_2
                65459 -> KeyEventAndroid.KEYCODE_NUMPAD_3
                65460 -> KeyEventAndroid.KEYCODE_NUMPAD_4
                65461 -> KeyEventAndroid.KEYCODE_NUMPAD_5
                65462 -> KeyEventAndroid.KEYCODE_NUMPAD_6
                65463 -> KeyEventAndroid.KEYCODE_NUMPAD_7
                65464 -> KeyEventAndroid.KEYCODE_NUMPAD_8
                65465 -> KeyEventAndroid.KEYCODE_NUMPAD_9
                65453 -> KeyEventAndroid.KEYCODE_NUMPAD_SUBTRACT
                65451 -> KeyEventAndroid.KEYCODE_NUMPAD_ADD
                65450 -> KeyEventAndroid.KEYCODE_NUMPAD_MULTIPLY
                65455 -> KeyEventAndroid.KEYCODE_NUMPAD_DIVIDE
                65454 -> KeyEventAndroid.KEYCODE_NUMPAD_ENTER
                65452 -> KeyEventAndroid.KEYCODE_NUMPAD_DOT
                // 箭头键
                65515 -> KeyEventAndroid.KEYCODE_DPAD_UP
                65516 -> KeyEventAndroid.KEYCODE_DPAD_DOWN
                65514 -> KeyEventAndroid.KEYCODE_DPAD_LEFT
                65517 -> KeyEventAndroid.KEYCODE_DPAD_RIGHT
                // 功能键
                65511 -> KeyEventAndroid.KEYCODE_PAGE_UP
                65512 -> KeyEventAndroid.KEYCODE_PAGE_DOWN
                65509 -> KeyEventAndroid.KEYCODE_MOVE_HOME
                65510 -> KeyEventAndroid.KEYCODE_MOVE_END
                else -> {
                    // 对于普通字符，使用原始键码
                    Log.d(logTag, "Fallback: 使用普通字符映射 keyCode=$keyCode")
                    keyCode
                }
            }
            
            // 记录键码映射
            val keyName = when (androidKeyCode) {
                KeyEventAndroid.KEYCODE_SPACE -> "空格"
                KeyEventAndroid.KEYCODE_ENTER, KeyEventAndroid.KEYCODE_NUMPAD_ENTER -> "回车"
                KeyEventAndroid.KEYCODE_DEL -> "删除键"
                KeyEventAndroid.KEYCODE_TAB -> "Tab键"
                KeyEventAndroid.KEYCODE_NUMPAD_0 -> "小键盘0"
                KeyEventAndroid.KEYCODE_NUMPAD_1 -> "小键盘1"
                KeyEventAndroid.KEYCODE_NUMPAD_2 -> "小键盘2"
                KeyEventAndroid.KEYCODE_NUMPAD_3 -> "小键盘3"
                KeyEventAndroid.KEYCODE_NUMPAD_4 -> "小键盘4"
                KeyEventAndroid.KEYCODE_NUMPAD_5 -> "小键盘5"
                KeyEventAndroid.KEYCODE_NUMPAD_6 -> "小键盘6"
                KeyEventAndroid.KEYCODE_NUMPAD_7 -> "小键盘7"
                KeyEventAndroid.KEYCODE_NUMPAD_8 -> "小键盘8"
                KeyEventAndroid.KEYCODE_NUMPAD_9 -> "小键盘9"
                KeyEventAndroid.KEYCODE_NUMPAD_SUBTRACT -> "小键盘减号"
                KeyEventAndroid.KEYCODE_NUMPAD_ADD -> "小键盘加号"
                KeyEventAndroid.KEYCODE_NUMPAD_MULTIPLY -> "小键盘乘号"
                KeyEventAndroid.KEYCODE_NUMPAD_DIVIDE -> "小键盘除号"
                KeyEventAndroid.KEYCODE_NUMPAD_DOT -> "小键盘点号"
                KeyEventAndroid.KEYCODE_DPAD_UP -> "上箭头"
                KeyEventAndroid.KEYCODE_DPAD_DOWN -> "下箭头"
                KeyEventAndroid.KEYCODE_DPAD_LEFT -> "左箭头"
                KeyEventAndroid.KEYCODE_DPAD_RIGHT -> "右箭头"
                KeyEventAndroid.KEYCODE_PAGE_UP -> "Page Up"
                KeyEventAndroid.KEYCODE_PAGE_DOWN -> "Page Down"
                KeyEventAndroid.KEYCODE_MOVE_HOME -> "Home"
                KeyEventAndroid.KEYCODE_MOVE_END -> "End"
                else -> "普通字符($androidKeyCode)"
            }
            
            Log.d(logTag, "Fallback键码映射: 原始键码=$keyCode, Android键码=$androidKeyCode ($keyName)")
            
            val eventTime = SystemClock.uptimeMillis()
            // 使用特殊标志以指示这些事件来自软键盘
            val flags = KeyEventAndroid.FLAG_SOFT_KEYBOARD or KeyEventAndroid.FLAG_KEEP_TOUCH_MODE
            
            val downEvent = KeyEventAndroid(
                eventTime, 
                eventTime, 
                KeyEventAndroid.ACTION_DOWN, 
                androidKeyCode, 
                0,  // repeat
                0,  // metaState
                KeyCharacterMap.VIRTUAL_KEYBOARD, 
                0,  // scancode
                flags,
                InputDevice.SOURCE_KEYBOARD
            )
            
            val upEvent = KeyEventAndroid(
                eventTime, 
                eventTime, 
                KeyEventAndroid.ACTION_UP, 
                androidKeyCode, 
                0,  // repeat
                0,  // metaState
                KeyCharacterMap.VIRTUAL_KEYBOARD, 
                0,  // scancode
                flags,
                InputDevice.SOURCE_KEYBOARD
            )
            
            return arrayOf(downEvent, upEvent)
        } catch (e: Exception) {
            Log.e(logTag, "创建Fallback键盘事件失败: ${e.message}")
            return emptyArray()
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
}
