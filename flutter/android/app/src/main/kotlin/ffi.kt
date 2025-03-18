// ffi.kt

package ffi

import android.content.Context
import java.nio.ByteBuffer

import com.carriez.flutter_hbb.RdClipboardManager

object FFI {
    init {
        System.loadLibrary("rustdesk")
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
    
    // 授权相关方法
    external fun sendAuthorizationResponse(id: Int, res: Boolean)
    
    // 语音通话相关
    external fun onVoiceCallStarted()
    external fun onVoiceCallClosed()
    
    // 通知相关
    external fun cancelNotification(id: Int)
    
    // 权限检查相关
    external fun checkMediaPermission(): Boolean
    
    // 授权方法(备选名称，以支持不同的实现)
    external fun authorize(id: Int)
    external fun autorize(auth: String)
}
