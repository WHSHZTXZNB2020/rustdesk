package com.carriez.flutter_hbb

import ffi.FFI

import android.Manifest
import android.content.Context
import android.media.*
import android.content.pm.PackageManager
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import android.os.Build
import android.util.Log
import kotlin.concurrent.thread

const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_FLOAT  // 恢复使用FLOAT提供更好的音质
const val AUDIO_SAMPLE_RATE = 44100  // 使用标准CD质量采样率
const val AUDIO_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO  // 使用立体声提供更好音质

class AudioRecordHandle(private var context: Context, private var isVideoStart: ()->Boolean, private var isAudioStart: ()->Boolean) {
    private val logTag = "LOG_AUDIO_RECORD_HANDLE"

    private var audioRecorder: AudioRecord? = null
    private var audioReader: AudioReader? = null
    private var minBufferSize = 0
    private var audioRecordStat = false
    private var audioThread: Thread? = null
    private var usesSystemPermissions = false
    
    // 系统级权限常量字符串
    companion object {
        const val PERMISSION_CAPTURE_VIDEO_OUTPUT = "android.permission.CAPTURE_VIDEO_OUTPUT"
        const val PERMISSION_READ_FRAME_BUFFER = "android.permission.READ_FRAME_BUFFER"
        const val PERMISSION_ACCESS_SURFACE_FLINGER = "android.permission.ACCESS_SURFACE_FLINGER"
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun createAudioRecorder(inVoiceCall: Boolean, mediaProjection: Any?): Boolean {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Log.d(logTag, "Android版本过低，不支持音频捕获")
                return false
            }
            
            if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(logTag, "缺少RECORD_AUDIO权限，无法创建音频录制器")
                return false
            }

            // 检查系统级权限
            val captureVideoPermission = context.checkCallingOrSelfPermission(PERMISSION_CAPTURE_VIDEO_OUTPUT)
            val readFrameBufferPermission = context.checkCallingOrSelfPermission(PERMISSION_READ_FRAME_BUFFER)
            val accessSurfaceFlingerPermission = context.checkCallingOrSelfPermission(PERMISSION_ACCESS_SURFACE_FLINGER)
            usesSystemPermissions = captureVideoPermission == PackageManager.PERMISSION_GRANTED || 
                                   readFrameBufferPermission == PackageManager.PERMISSION_GRANTED ||
                                   accessSurfaceFlingerPermission == PackageManager.PERMISSION_GRANTED
            
            Log.d(logTag, "创建音频录制器，系统权限: $usesSystemPermissions")
            
            // 创建音频格式 - 定制系统应该支持高质量设置
            val audioFormat = try {
                AudioFormat.Builder()
                    .setEncoding(AUDIO_ENCODING)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AUDIO_CHANNEL_MASK)
                    .build()
            } catch (e: Exception) {
                Log.e(logTag, "创建高质量音频格式失败: ${e.message}，尝试备用格式")
                // 备用音频格式，更兼容但质量较低
                try {
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(16000)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                } catch (e2: Exception) {
                    Log.e(logTag, "创建备用音频格式也失败: ${e2.message}")
                    return false
                }
            }
            
            // 获取最小缓冲区大小
            val minBuffSize = AudioRecord.getMinBufferSize(
                audioFormat.sampleRate,
                audioFormat.channelMask,
                audioFormat.encoding
            )
            
            if (minBuffSize <= 0) {
                Log.e(logTag, "获取最小音频缓冲区大小失败: $minBuffSize")
                return false
            }
            
            // 系统级权限下应优先使用REMOTE_SUBMIX，为定制系统优化
            val audioSource = when {
                inVoiceCall -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                usesSystemPermissions -> {
                    // 系统权限下，不同设备可能支持不同音频源
                    try {
                        MediaRecorder.AudioSource.REMOTE_SUBMIX
                    } catch (e: Exception) {
                        // 如果REMOTE_SUBMIX不支持，回退到其他音频源
                        try {
                            MediaRecorder.AudioSource.MIC
                        } catch (e2: Exception) {
                            MediaRecorder.AudioSource.DEFAULT
                        }
                    }
                }
                else -> {
                    Log.d(logTag, "没有可用的音频捕获方式")
                    return false
                }
            }
            
            try {
                // 增加缓冲区大小，提高稳定性
                val bufferSizeInBytes = minBuffSize * 4
                
                val builder = AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSizeInBytes)
                
                // 设置音频源
                builder.setAudioSource(audioSource)
                
                // 构建AudioRecord
                audioRecorder = builder.build()
                
                if (audioRecorder == null) {
                    Log.e(logTag, "创建AudioRecord失败")
                    return false
                }
                
                minBufferSize = minBuffSize
                
                // 创建音频读取器
                audioReader = AudioReader(audioRecorder!!, minBufferSize, audioFormat.encoding, audioFormat.channelCount)
                
                // 记录成功创建
                Log.d(logTag, "成功创建音频录制器: 采样率=${audioFormat.sampleRate}, 通道=${audioFormat.channelCount}, 编码=${audioFormat.encoding}, 缓冲区大小=$bufferSizeInBytes")
                return true
                
            } catch (e: Exception) {
                Log.e(logTag, "创建AudioRecord时出错: ${e.message}")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(logTag, "初始化音频录制器时出错: ${e.message}")
            return false
        }
    }
    
    fun startRecord() {
        try {
            if (audioRecorder == null || audioReader == null) {
                Log.e(logTag, "无法启动录制，AudioRecord或AudioReader未初始化")
                return
            }
            
            if (audioRecordStat) {
                Log.d(logTag, "音频录制已经在运行，无需重复启动")
                return
            }
            
            // 启动AudioRecord
            audioRecorder?.startRecording()
            audioRecordStat = true
            
            // 启动读取线程
            audioThread = thread(start = true) {
                Log.d(logTag, "音频录制线程启动")
                try {
                    while (audioRecordStat && isVideoStart() && audioRecorder != null && audioReader != null) {
                        try {
                            // 读取并发送音频数据
                            val buffer = audioReader!!.read() ?: continue
                            FFI.sendAudio(buffer)
                        } catch (e: Exception) {
                            Log.e(logTag, "音频录制线程出错: ${e.message}")
                            break
                        }
                    }
                } finally {
                    Log.d(logTag, "音频录制线程结束")
                    stopRecord()
                }
            }
            
            Log.d(logTag, "音频录制成功启动")
            
        } catch (e: Exception) {
            Log.e(logTag, "启动音频录制时出错: ${e.message}")
            stopRecord()
        }
    }
    
    fun stopRecord() {
        try {
            audioRecordStat = false
            
            try {
                audioThread?.join(1000)
                audioThread = null
            } catch (e: Exception) {
                // 忽略线程关闭异常
            }
            
            try {
                if (audioRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecorder?.stop()
                }
            } catch (e: Exception) {
                Log.e(logTag, "停止AudioRecord时出错: ${e.message}")
            }
            
            try {
                audioRecorder?.release()
                audioRecorder = null
            } catch (e: Exception) {
                Log.e(logTag, "释放AudioRecord时出错: ${e.message}")
            }
            
            audioReader = null
            
            Log.d(logTag, "音频录制已停止并释放资源")
            
        } catch (e: Exception) {
            Log.e(logTag, "停止音频录制时出错: ${e.message}")
        }
    }
    
    fun onVoiceCallStarted(mediaProjection: Any?): Boolean {
        return switchToVoiceCall(mediaProjection)
    }
    
    fun onVoiceCallClosed(mediaProjection: Any?): Boolean {
        return switchOutVoiceCall(mediaProjection)
    }
    
    fun switchToVoiceCall(mediaProjection: Any?): Boolean {
        try {
            // 先停止现有录制
            stopRecord()
            
            // 创建语音通话录制器
            if (!createAudioRecorder(true, mediaProjection)) {
                Log.e(logTag, "创建语音通话录制器失败")
                return false
            }
            
            // 启动录制
            startRecord()
            return true
            
        } catch (e: Exception) {
            Log.e(logTag, "切换到语音通话模式时出错: ${e.message}")
            return false
        }
    }
    
    fun switchOutVoiceCall(mediaProjection: Any?): Boolean {
        try {
            // 先停止语音通话录制
            stopRecord()
            
            // 如果视频仍在运行，恢复普通音频录制
            if (isVideoStart()) {
                if (!createAudioRecorder(false, mediaProjection)) {
                    Log.e(logTag, "创建普通音频录制器失败")
                    return false
                }
                
                // 启动录制
                startRecord()
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(logTag, "切换出语音通话模式时出错: ${e.message}")
            return false
        }
    }
}

class AudioReader(private val audioRecord: AudioRecord, private val minBufferSize: Int, private val encoding: Int, private val channelCount: Int) {
    
    fun read(): ByteArray? {
        try {
            val bufferSize = minBufferSize * channelCount
            
            // 根据编码类型选择合适的读取方法
            return when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> readFloat(bufferSize)
                AudioFormat.ENCODING_PCM_16BIT -> read16Bit(bufferSize)
                AudioFormat.ENCODING_PCM_8BIT -> read8Bit(bufferSize)
                else -> {
                    // 默认使用16位格式
                    read16Bit(bufferSize)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioReader", "读取音频数据时出错: ${e.message}")
            return null
        }
    }
    
    private fun readFloat(bufferSize: Int): ByteArray? {
        val floatBuffer = FloatArray(bufferSize)
        val bytesRead = audioRecord.read(floatBuffer, 0, bufferSize, AudioRecord.READ_BLOCKING)
        
        if (bytesRead <= 0) {
            return null
        }
        
        // 将float转换为ByteArray
        val byteBuffer = java.nio.ByteBuffer.allocate(bytesRead * Float.SIZE_BYTES)
        byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        
        for (i in 0 until bytesRead) {
            byteBuffer.putFloat(floatBuffer[i])
        }
        
        return byteBuffer.array()
    }
    
    private fun read16Bit(bufferSize: Int): ByteArray? {
        val shortBuffer = ShortArray(bufferSize)
        val bytesRead = audioRecord.read(shortBuffer, 0, bufferSize, AudioRecord.READ_BLOCKING)
        
        if (bytesRead <= 0) {
            return null
        }
        
        // 将short转换为ByteArray
        val byteBuffer = java.nio.ByteBuffer.allocate(bytesRead * Short.SIZE_BYTES)
        byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        
        for (i in 0 until bytesRead) {
            byteBuffer.putShort(shortBuffer[i])
        }
        
        return byteBuffer.array()
    }
    
    private fun read8Bit(bufferSize: Int): ByteArray? {
        val byteBuffer = ByteArray(bufferSize)
        val bytesRead = audioRecord.read(byteBuffer, 0, bufferSize, AudioRecord.READ_BLOCKING)
        
        return if (bytesRead <= 0) null else byteBuffer
    }
}
