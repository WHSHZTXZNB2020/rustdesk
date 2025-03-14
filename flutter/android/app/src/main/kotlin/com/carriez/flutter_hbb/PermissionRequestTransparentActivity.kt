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

        // 直接启动服务，系统权限在清单文件中已声明
        launchService()
        finish()
    }

    private fun launchService() {
        Log.d(logTag, "Launch MainService with system permissions")
        
        // 检查是否有系统级屏幕捕获权限
        val pm = packageManager
        val hasCaptureVideoOutput = pm.checkPermission("android.permission.CAPTURE_VIDEO_OUTPUT", packageName) == PackageManager.PERMISSION_GRANTED
        val hasReadFrameBuffer = pm.checkPermission("android.permission.READ_FRAME_BUFFER", packageName) == PackageManager.PERMISSION_GRANTED
        val hasAccessSurfaceFlinger = pm.checkPermission("android.permission.ACCESS_SURFACE_FLINGER", packageName) == PackageManager.PERMISSION_GRANTED
        
        Log.d(logTag, "System permissions: CAPTURE_VIDEO_OUTPUT=$hasCaptureVideoOutput, READ_FRAME_BUFFER=$hasReadFrameBuffer, ACCESS_SURFACE_FLINGER=$hasAccessSurfaceFlinger")
        
        val serviceIntent = Intent(this, MainService::class.java)
        serviceIntent.action = ACT_INIT_MEDIA_PROJECTION_AND_SERVICE
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}