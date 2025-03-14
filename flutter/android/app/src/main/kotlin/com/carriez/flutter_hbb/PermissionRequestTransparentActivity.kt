package com.carriez.flutter_hbb

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log

class PermissionRequestTransparentActivity: Activity() {
    private val logTag = "permissionRequest"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(logTag, "onCreate PermissionRequestTransparentActivity: intent.action: ${intent.action}")

        when (intent.action) {
            ACT_USE_SYSTEM_PERMISSIONS -> {
                // 直接使用系统权限启动服务，完全跳过MediaProjection请求
                launchServiceWithSystemPermissions()
            }
            ACT_REQUEST_MEDIA_PROJECTION -> {
                // 兼容原来的MediaProjection权限请求方式
                requestMediaProjection()
            }
            else -> {
                // 默认直接尝试使用系统权限
                launchServiceWithSystemPermissions()
            }
        }
        finish()
    }

    private fun requestMediaProjection() {
        Log.d(logTag, "请求MediaProjection权限 - 传统方式")
        val mediaProjectionIntent = Intent(Intent(ACTION_MEDIA_PROJECTION))
        try {
            startActivityForResult(mediaProjectionIntent, REQ_REQUEST_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.e(logTag, "启动MediaProjection失败: ${e.message}")
            setResult(RES_FAILED)
            finish()
        }
    }

    private fun launchServiceWithSystemPermissions() {
        Log.d(logTag, "直接使用系统权限启动服务，无需用户确认")
        
        // 检查是否有系统级屏幕捕获权限
        val pm = packageManager
        val hasCaptureVideoOutput = pm.checkPermission("android.permission.CAPTURE_VIDEO_OUTPUT", packageName) == PackageManager.PERMISSION_GRANTED
        val hasReadFrameBuffer = pm.checkPermission("android.permission.READ_FRAME_BUFFER", packageName) == PackageManager.PERMISSION_GRANTED
        val hasAccessSurfaceFlinger = pm.checkPermission("android.permission.ACCESS_SURFACE_FLINGER", packageName) == PackageManager.PERMISSION_GRANTED
        
        Log.d(logTag, "系统权限状态: CAPTURE_VIDEO_OUTPUT=$hasCaptureVideoOutput, READ_FRAME_BUFFER=$hasReadFrameBuffer, ACCESS_SURFACE_FLINGER=$hasAccessSurfaceFlinger")
        
        // 创建一个自定义action的Intent，表明使用系统权限方式
        val serviceIntent = Intent(this, MainService::class.java)
        serviceIntent.action = ACT_USE_SYSTEM_PERMISSIONS
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_REQUEST_MEDIA_PROJECTION) {
            // 传统MediaProjection权限请求回调处理
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, MainService::class.java)
                serviceIntent.action = ACT_INIT_MEDIA_PROJECTION_AND_SERVICE
                serviceIntent.putExtra(EXT_MEDIA_PROJECTION_RES_INTENT, data)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                setResult(RES_FAILED)
            }
        }
        finish()
    }
}
