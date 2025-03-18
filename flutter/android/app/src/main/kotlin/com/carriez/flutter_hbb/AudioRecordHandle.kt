package com.carriez.flutter_hbb

import ffi.FFI

import android.Manifest
import android.content.Context
import android.media.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
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
    fun createAudioRecorder(inVoiceCall: Boolean, mediaProjection: MediaProjection?): Boolean {
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
            
            Log.d(logTag, "创建音频录制器，系统权限: $usesSystemPermissions, 媒体投影可用: ${mediaProjection != null}")
            
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
                            MediaRecorder.AudioSource.SYSTEM
                        } catch (e2: Exception) {
                            MediaRecorder.AudioSource.DEFAULT
                        }
                    }
                }
                mediaProjection != null -> null  // MediaProjection方式不需要设置音频源
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
                
                // 根据不同情况设置音频源或播放捕获配置
                if (inVoiceCall || usesSystemPermissions) {
                    builder.setAudioSource(audioSource!!)
                } else if (mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // 使用MediaProjection时的设置
                    try {
                        // 为定制系统配置全面捕获所有音频类型
                        val apcc = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_ALARM)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                            .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build()
                        
                        builder.setAudioPlaybackCaptureConfig(apcc)
                    } catch (e: Exception) {
                        Log.e(logTag, "设置音频播放捕获配置失败: ${e.message}")
                        return false
                    }
                }
                
                // 创建音频录制器
                try {
                    audioRecorder = builder.build()
                    
                    // 检查创建结果
                    if (audioRecorder?.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(logTag, "AudioRecord初始化失败: ${audioRecorder?.state}")
                        audioRecorder?.release()
                        audioRecorder = null
                        return false
                    }
                    
                    Log.d(logTag, "成功创建AudioRecord，缓冲区大小: $bufferSizeInBytes")
                    minBufferSize = bufferSizeInBytes
                    return true
                } catch (e: Exception) {
                    Log.e(logTag, "创建AudioRecord失败: ${e.message}")
                    return false
                }
            } catch (e: Exception) {
                Log.e(logTag, "配置AudioRecord失败: ${e.message}")
                return false
            }
        } catch (e: Exception) {
            Log.e(logTag, "创建音频录制器时发生异常: ${e.message}")
            return false
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun checkAudioReader() {
        try {
            if (audioReader != null && minBufferSize != 0) {
                return
            }
            // read f32 to byte , length * 4
            minBufferSize = 2 * 4 * AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_MASK,
                AUDIO_ENCODING
            )
            if (minBufferSize <= 0) {
                Log.d(logTag, "获取最小缓冲区大小失败! 值: $minBufferSize")
                minBufferSize = 8192 * 4  // 使用默认值
            }
            audioReader = AudioReader(minBufferSize, 4)
            Log.d(logTag, "初始化音频数据长度:$minBufferSize")
        } catch (e: Exception) {
            Log.e(logTag, "检查音频读取器失败: ${e.message}")
            minBufferSize = 8192 * 4
            audioReader = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun startAudioRecorder() {
        try {
            checkAudioReader()
            if (audioReader != null && audioRecorder != null && minBufferSize != 0) {
                try {
                    FFI.setFrameRawEnable("audio", true)
                    audioRecorder!!.startRecording()
                    
                    // 检查录制是否真正开始
                    if (audioRecorder!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Log.e(logTag, "AudioRecord未能开始录制")
                        FFI.setFrameRawEnable("audio", false)
                        audioRecorder?.release()
                        audioRecorder = null
                        return
                    }
                    
                    audioRecordStat = true
                    audioThread = thread {
                        try {
                            while (audioRecordStat) {
                                try {
                                    audioReader!!.readSync(audioRecorder!!)?.let {
                                        FFI.onAudioFrameUpdate(it)
                                    }
                                } catch (e: Exception) {
                                    Log.e(logTag, "音频读取错误: ${e.message}")
                                    // 短暂延迟后继续，避免CPU占用过高
                                    Thread.sleep(100)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(logTag, "音频线程异常: ${e.message}")
                        } finally {
                            // 释放资源
                            try {
                                audioRecorder?.release()
                            } catch (e: Exception) {
                                Log.e(logTag, "释放AudioRecord失败: ${e.message}")
                            }
                            audioRecorder = null
                            minBufferSize = 0
                            FFI.setFrameRawEnable("audio", false)
                            Log.d(logTag, "退出音频线程")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "启动音频录制失败:$e")
                    FFI.setFrameRawEnable("audio", false)
                    audioRecorder?.release()
                    audioRecorder = null
                }
            } else {
                Log.d(logTag, "无法启动音频录制，组件未初始化")
            }
        } catch (e: Exception) {
            Log.e(logTag, "启动音频录制器时发生异常: ${e.message}")
        }
    }

    fun onVoiceCallStarted(mediaProjection: MediaProjection?): Boolean {
        if (!isSupportVoiceCall()) {
            return false
        }
        // No need to check if video or audio is started here.
        if (!switchToVoiceCall(mediaProjection)) {
            return false
        }
        return true
    }

    fun onVoiceCallClosed(mediaProjection: MediaProjection?): Boolean {
        // Return true if not supported, because is was not started.
        if (!isSupportVoiceCall()) {
            return true
        }
        if (isVideoStart()) {
            switchOutVoiceCall(mediaProjection)
        }
        tryReleaseAudio()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun switchToVoiceCall(mediaProjection: MediaProjection?): Boolean {
        audioRecorder?.let {
            if (it.getAudioSource() == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                return true
            }
        }
        audioRecordStat = false
        audioThread?.join()
        audioThread = null

        if (!createAudioRecorder(true, mediaProjection)) {
            Log.e(logTag, "createAudioRecorder fail")
            return false
        }
        startAudioRecorder()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun switchOutVoiceCall(mediaProjection: MediaProjection?): Boolean {
        audioRecorder?.let {
            if (it.getAudioSource() != MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                return true
            }
        }
        audioRecordStat = false
        audioThread?.join()

        if (!createAudioRecorder(false, mediaProjection)) {
            Log.e(logTag, "createAudioRecorder fail")
            return false
        }
        startAudioRecorder()
        return true
    }

    fun tryReleaseAudio() {
        if (isAudioStart() || isVideoStart()) {
            return
        }
        audioRecordStat = false
        audioThread?.join()
    }

    fun destroy() {
        Log.d(logTag, "destroy audio record handle")

        audioRecordStat = false
        audioThread?.join()
    }
    
    // 判断是否支持语音通话的辅助方法
    private fun isSupportVoiceCall(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }
}
