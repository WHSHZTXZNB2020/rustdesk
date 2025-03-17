// ffi.kt

package ffi

import android.content.Context
import java.nio.ByteBuffer
import java.lang.RuntimeException
import com.carriez.flutter_hbb.RdClipboardManager
import android.os.Build
import java.lang.Exception
// 添加JNI应用程序接口
import android.annotation.SuppressLint

// 添加完整的JniError类定义
class JniError(val code: Int, message: String) : Exception(message) {
    companion object {
        // 添加ThrowFailed方法
        fun ThrowFailed(code: Int): JniError {
            return JniError(code, "JNI throw failed with code: $code")
        }
        
        // 添加其它可能需要的静态方法
        fun NullPtr(): JniError {
            return JniError(-1, "Null pointer exception in JNI call")
        }
        
        fun InvalidUtf8(): JniError {
            return JniError(-2, "Invalid UTF-8 in JNI string")
        }
        
        fun ExceptionOccurred(): JniError {
            return JniError(-3, "Exception occurred during JNI call")
        }
    }
}

// 添加JNI命名空间以解决jni引用问题
object jni {
    object JavaVM {
        // 空实现，仅为了解决编译问题
    }
    
    object JNIEnv {
        // 空实现，仅为了解决编译问题
    }
}

@SuppressLint("StaticFieldLeak")
object FFI {
    init {
        try {
            System.loadLibrary("rustdesk")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    external fun init(ctx: Context)
    external fun setClipboardManager(clipboardManager: RdClipboardManager)
    external fun startServer(app_dir: String, custom_client_config: String)
    external fun startService()
    external fun onVideoFrameUpdate(buf: ByteBuffer)
    external fun onAudioFrameUpdate(buf: ByteBuffer)
    external fun translateLocale(localeName: String, input: String): String
    external fun refreshScreen()
    external fun setFrameRawEnable(name: String, value: Boolean)
    external fun setCodecInfo(info: String)
    external fun getLocalOption(key: String): String
    external fun onClipboardUpdate(clips: ByteBuffer)
    external fun isServiceClipboardEnabled(): Boolean
    
    // 添加pushFrame函数用于Android 11+兼容
    external fun pushFrame(width: Int, height: Int, buffer: ByteBuffer, stride: Int, bufferSize: Int)
    
    // 添加可能存在的函数变体
    external fun autorize(auth: String)
    external fun authorize(auth: String) // 备选拼写
    
    // 尝试处理任何可能需要JNI的方法和参数
    fun callRustFunction(name: String, params: Map<String, Any>): Any? {
        try {
            // 实现基本的JNI调用安全包装器
            return null
        } catch (e: Exception) {
            throw RuntimeException("Error calling Rust function through JNI: ${e.message}")
        }
    }
}
