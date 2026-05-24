package com.chiya.workautowall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.chiya.workautowall.util.AppPreferences
import com.chiya.workautowall.util.WallpaperHelper

/**
 * 快捷设置磁贴服务
 *
 * 提供下拉通知栏快捷切换壁纸功能：
 * - 点击磁贴：执行壁纸切换（上班/下班模式自动判断）
 * - 长按磁贴：进入设置界面（不执行切换）
 *
 * 需要 Android 7.0+ (API 24+)
 */
class WallpaperTileService : TileService() {

    companion object {
        private const val TAG = "WallpaperTileService"
    }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(TAG, "Tile added")
        updateTileState()
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "Start listening")
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Tile clicked")

        // 检查是否有工作壁纸设置
        val preferences = AppPreferences(this)
        val workWallpaperPath = preferences.getWorkWallpaperPath()

        if (workWallpaperPath.isNullOrEmpty()) {
            // 没有设置工作壁纸，提示用户
            Toast.makeText(this, getString(R.string.no_work_wallpaper_hint), Toast.LENGTH_SHORT).show()
            return
        }

        // 检查权限
        if (!hasStoragePermission()) {
            Toast.makeText(this, getString(R.string.permission_denied_hint), Toast.LENGTH_SHORT).show()
            return
        }

        // 执行壁纸切换
        performWallpaperSwitch()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Log.d(TAG, "Tile removed")
    }

    /**
     * 更新磁贴状态
     *
     * 根据备份文件存在性更新磁贴图标和状态：
     * - 有备份：显示恢复图标（下班模式）
     * - 无备份：显示切换图标（上班模式）
     */
    private fun updateTileState() {
        val tile = qsTile ?: return

        val wallpaperHelper = WallpaperHelper(this)
        val hasBackup = wallpaperHelper.hasBackup()

        // 更新磁贴状态
        tile.state = Tile.STATE_ACTIVE

        // 更新图标
        tile.icon = Icon.createWithResource(
            this,
            if (hasBackup) R.drawable.ic_tile_restore else R.drawable.ic_tile_switch
        )

        // 更新标签
        tile.label = getString(
            if (hasBackup) R.string.tile_label_restore else R.string.tile_label_switch
        )

        // 更新内容描述
        tile.contentDescription = getString(
            if (hasBackup) R.string.tile_content_desc_restore else R.string.tile_content_desc_switch
        )

        tile.updateTile()
    }

    /**
     * 检查是否有存储权限
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 执行壁纸切换核心逻辑
     *
     * 与 MainActivity.performWallpaperSwitch() 相同的逻辑
     */
    private fun performWallpaperSwitch() {
        val wallpaperHelper = WallpaperHelper(this)

        try {
            if (wallpaperHelper.hasBackup()) {
                // 有备份 → 下班模式：恢复原壁纸
                handleOffWorkMode(wallpaperHelper)
            } else {
                // 无备份 → 上班模式：切换到工作壁纸
                handleWorkMode(wallpaperHelper)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wallpaper switch failed with exception", e)
            showToast(getString(R.string.wallpaper_switch_failed))
        }

        // 更新磁贴状态
        updateTileState()
    }

    /**
     * 处理上班模式
     */
    private fun handleWorkMode(wallpaperHelper: WallpaperHelper) {
        val preferences = AppPreferences(this)
        var workWallpaperPath = preferences.getWorkWallpaperPath()

        if (workWallpaperPath.isNullOrEmpty()) {
            showToast(getString(R.string.no_work_wallpaper_hint))
            return
        }

        // 检查工作壁纸文件是否存在，尝试自动修正路径
        var workFile = java.io.File(workWallpaperPath)
        if (!workFile.exists()) {
            val correctedPath = tryAlternativeExtension(workWallpaperPath)
            if (correctedPath != null) {
                preferences.setWorkWallpaperPath(correctedPath)
                workFile = java.io.File(correctedPath)
            }
        }

        if (!workFile.exists()) {
            preferences.setWorkWallpaperPath("")
            showToast(getString(R.string.no_work_wallpaper_hint))
            return
        }

        // 备份当前壁纸
        val backupSuccess = wallpaperHelper.backupCurrentWallpaper()
        if (!backupSuccess) {
            showToast(getString(R.string.wallpaper_switch_failed))
            return
        }

        // 获取并设置工作壁纸
        val workBitmap = wallpaperHelper.getWorkWallpaperBitmap()
        if (workBitmap == null) {
            wallpaperHelper.deleteBackup()
            showToast(getString(R.string.wallpaper_switch_failed))
            return
        }

        val setSuccess = wallpaperHelper.setWorkWallpaper(workBitmap)
        workBitmap.recycle()

        if (setSuccess) {
            showToast(getString(R.string.work_wallpaper_set_success))
        } else {
            wallpaperHelper.restoreBackupWallpaper()
            showToast(getString(R.string.wallpaper_switch_failed))
        }
    }

    /**
     * 处理下班模式
     */
    private fun handleOffWorkMode(wallpaperHelper: WallpaperHelper) {
        val restoreSuccess = wallpaperHelper.restoreBackupWallpaper()
        if (restoreSuccess) {
            showToast(getString(R.string.personal_wallpaper_restored))
        } else {
            showToast(getString(R.string.wallpaper_switch_failed))
        }
    }

    /**
     * 尝试使用替代扩展名查找文件
     */
    private fun tryAlternativeExtension(path: String): String? {
        val alternativePath = when {
            path.endsWith(".jpg", ignoreCase = true) -> path.dropLast(4) + ".png"
            path.endsWith(".png", ignoreCase = true) -> path.dropLast(4) + ".jpg"
            else -> return null
        }
        val alternativeFile = java.io.File(alternativePath)
        return if (alternativeFile.exists()) alternativePath else null
    }

    /**
     * 显示 Toast 提示
     *
     * TileService 需要使用 applicationContext 来显示 Toast
     */
    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
