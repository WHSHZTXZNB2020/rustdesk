package com.carriez.flutter_hbb

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import android.hardware.display.DisplayManager
import android.view.Surface
import android.media.MediaCodec
import android.view.SurfaceView

/**
 * 屏幕捕获类，支持多种捕获方式
 */
class ScreenCapture(
    private val context: Context,
    private val onFrameAvailable: (Surface?) -> Unit,
    private val onStopped: () -> Unit
) {
    private val logTag = "ScreenCapture"
    private var isRunning = false
    private var mediaProjection: MediaProjection? = null
    private var surface: Surface? = null
    
    // 设置MediaProjection
    fun setMediaProjection(projection: MediaProjection?) {
        this.mediaProjection = projection
    }
    
    // 设置Surface
    fun setSurface(surface: Surface?) {
        this.surface = surface
    }
    
    // 启动捕获
    fun start() {
        if (isRunning) {
            Log.w(logTag, "已经在运行中")
            return
        }
        
        if (surface == null) {
            Log.e(logTag, "Surface未设置")
            return
        }
        
        isRunning = true
        try {
            // 通知帧可用
            onFrameAvailable(surface)
        } catch (e: Exception) {
            Log.e(logTag, "启动捕获失败: ${e.message}")
            stop()
        }
    }
    
    // 停止捕获
    fun stop() {
        if (!isRunning) {
            return
        }
        
        isRunning = false
        try {
            // 释放资源
            mediaProjection?.stop()
            mediaProjection = null
            surface = null
            
            // 通知已停止
            onStopped()
        } catch (e: Exception) {
            Log.e(logTag, "停止捕获失败: ${e.message}")
        }
    }
    
    // 是否正在运行
    fun isRunning(): Boolean {
        return isRunning
    }
} 