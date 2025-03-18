package ffi

/**
 * External rust function interfaces
 */
class FFI {
    companion object {
        // 添加新方法用于发送编码帧
        @JvmStatic
        external fun pushEncodedVideoFrame(data: ByteArray, isKeyFrame: Boolean)
        
        // ... existing code ...
    }
} 