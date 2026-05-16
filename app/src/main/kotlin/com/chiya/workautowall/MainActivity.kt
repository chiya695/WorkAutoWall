package com.chiya.workautowall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chiya.workautowall.util.AppPreferences
import com.chiya.workautowall.util.WallpaperHelper

/**
 * 主 Activity - 透明主题
 *
 * 应用入口 Activity，采用透明主题实现无界面启动。
 * 核心功能：
 * 1. 根据备份文件存在性判断上班/下班状态
 * 2. 执行壁纸切换操作
 * 3. 延迟退出机制（Handler.postDelayed）
 * 4. 延迟期间重入检测（singleTop + 静态标志位）
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /**
         * 延迟退出状态标志位
         *
         * 用于检测延迟期间用户再次打开APP的场景。
         * 为 true 时说明当前正在延迟退出中，重入应跳转设置界面。
         * 进程存活期间有效，进程被杀后自动重置。
         */
        @Volatile
        var isDelaying: Boolean = false

        /**
         * 延迟结束时间（基于 SystemClock.elapsedRealtime）
         *
         * 用于在 Activity 被 config change 销毁重建后，
         * 计算剩余延迟时间并重新调度 finish。
         */
        private var delayEndTime: Long = 0L

        /**
         * 上次壁纸切换完成时间（基于 SystemClock.elapsedRealtime）
         *
         * 防止 MIUI/HyperOS 在 finish() 后自动重建 Activity 导致的死循环：
         * 如果重建发生距上次切换不到 delayTime+2s，直接 finish 跳过。
         */
        private var lastSwitchTime: Long = 0L
    }

    private lateinit var wallpaperHelper: WallpaperHelper
    private lateinit var preferences: AppPreferences
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 运行时权限请求回调
     *
     * 使用 ActivityResultContracts.RequestMultiplePermissions 处理多权限请求结果。
     * MIUI 的 WallpaperManager.getDrawable() 需要 READ_EXTERNAL_STORAGE，
     * API 33+ 需要 READ_MEDIA_IMAGES，两者都需要授予。
     */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.i(TAG, "All storage permissions granted, proceeding with wallpaper switch")
            performWallpaperSwitch()
        } else {
            Log.w(TAG, "Storage permissions denied: $permissions")
            Toast.makeText(this, getString(R.string.permission_denied_hint), Toast.LENGTH_LONG).show()
            navigateToSettings()
        }
    }

    /**
     * 所有文件访问权限设置回调
     *
     * 用于 Android 11+ (API 30+) 的 MANAGE_EXTERNAL_STORAGE 权限。
     * HyperOS 3 + Android 16 上，WallpaperManager 仍检查已移除的 READ_EXTERNAL_STORAGE，
     * 必须使用"所有文件访问"权限绕过此限制。
     */
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            Log.i(TAG, "All files access granted, proceeding with wallpaper switch")
            performWallpaperSwitch()
        } else {
            Log.w(TAG, "All files access denied")
            Toast.makeText(this, getString(R.string.permission_denied_hint), Toast.LENGTH_LONG).show()
            navigateToSettings()
        }
    }

    /**
     * 延迟退出 Runnable
     *
     * 在指定延迟时间后调用 finish() 退出 Activity
     */
    private val finishRunnable = Runnable {
        Log.d(TAG, "Delayed finish executing")
        isDelaying = false
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate, isDelaying=$isDelaying, savedState=${savedInstanceState != null}")

        // 初始化工具类
        wallpaperHelper = WallpaperHelper(this)
        preferences = AppPreferences(this)

        // 检测是否延迟期间重入或Activity被重建
        if (isDelaying) {
            val now = SystemClock.elapsedRealtime()
            val remaining = delayEndTime - now
            if (remaining > 0) {
                // Activity 在延迟期间被重建（如 config change），直接跳转设置界面
                // 这样用户能看到界面，而不是卡在透明Activity
                Log.i(TAG, "Activity recreated during delay, navigating to settings")
                isDelaying = false
                navigateToSettings()
            } else {
                // 延迟已过期但 Activity 被 MIUI 重建，直接结束防循环
                Log.i(TAG, "Delay expired, finishing to prevent loop")
                isDelaying = false
                finish()
            }
            return
        }

        // 防循环安全阀：如果距上次壁纸切换不足 delayTime+2s，说明是 MIUI 重建的，
        // 直接 finish 跳过，避免重复执行壁纸切换。
        val now = SystemClock.elapsedRealtime()
        if (lastSwitchTime > 0 && now - lastSwitchTime < preferences.getDelayTimeMs() + 2000) {
            Log.w(TAG, "Switch completed ${(now - lastSwitchTime)}ms ago, skipping to prevent loop")
            finish()
            return
        }

        // 检查存储读取权限，然后执行壁纸切换逻辑
        checkStoragePermissionAndProceed()
    }

    /**
     * 处理 singleTop 启动模式下的重入 Intent
     *
     * 当 Activity 已在栈顶且再次被启动时调用，
     * 用于在延迟期间检测重入并跳转设置。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent, isDelaying=$isDelaying")

        // 延迟期间重入：取消延迟，跳转设置
        if (isDelaying) {
            cancelDelayedFinish()
            navigateToSettings()
        }
    }

    /**
     * 检查存储读取权限并继续执行
     *
     * 权限策略：
     * - Android 11+ (API 30+)：优先使用 MANAGE_EXTERNAL_STORAGE（所有文件访问）
     *   解决 HyperOS 3 + Android 16 上 WallpaperManager 检查已移除权限的问题
     * - Android 10 及以下：使用传统的 READ_EXTERNAL_STORAGE
     * - READ_MEDIA_IMAGES 用于从 Uri 保存壁纸的场景
     */
    private fun checkStoragePermissionAndProceed() {
        // Android 11+ (API 30+)：优先检查所有文件访问权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Log.d(TAG, "All files access already granted")
                performWallpaperSwitch()
            } else {
                Log.i(TAG, "Requesting all files access permission")
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    manageStorageLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch all files access settings, trying fallback", e)
                    // 降级：打开通用的应用详情设置页面
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    manageStorageLauncher.launch(intent)
                }
            }
            return
        }

        // Android 10 及以下：使用传统权限
        val requiredPermissions = listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            Log.d(TAG, "READ_EXTERNAL_STORAGE already granted")
            performWallpaperSwitch()
        } else {
            Log.i(TAG, "Requesting READ_EXTERNAL_STORAGE")
            requestPermissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    /**
     * 执行壁纸切换核心逻辑
     *
     * 根据备份文件存在性判断当前状态：
     * - 无备份 → 上班模式：备份当前壁纸，设置工作壁纸
     * - 有备份 → 下班模式：恢复备份壁纸，删除备份
     */
    private fun performWallpaperSwitch() {
        try {
            if (wallpaperHelper.hasBackup()) {
                // 有备份 → 下班模式：恢复原壁纸
                handleOffWorkMode()
            } else {
                // 无备份 → 上班模式：切换到工作壁纸
                handleWorkMode()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wallpaper switch failed with exception", e)
            showErrorAndExit(getString(R.string.wallpaper_switch_failed))
        }
    }

    /**
     * 处理上班模式
     *
     * 流程：检查工作壁纸 → 备份当前壁纸 → 设置工作壁纸 → Toast → 延迟退出
     * 每一步失败都会尝试恢复到之前的状态
     */
    private fun handleWorkMode() {
        // 检查是否已设置工作壁纸
        val workWallpaperPath = preferences.getWorkWallpaperPath()
        Log.d(TAG, "Work wallpaper path: $workWallpaperPath")

        if (workWallpaperPath.isNullOrEmpty()) {
            Log.w(TAG, "No work wallpaper configured")
            Toast.makeText(this, getString(R.string.no_work_wallpaper_hint), Toast.LENGTH_SHORT).show()
            navigateToSettings()
            return
        }

        // 检查工作壁纸文件是否存在
        val workFile = java.io.File(workWallpaperPath)
        if (!workFile.exists()) {
            Log.e(TAG, "Work wallpaper file does not exist: $workWallpaperPath")
            preferences.setWorkWallpaperPath("")
            Toast.makeText(this, getString(R.string.no_work_wallpaper_hint), Toast.LENGTH_SHORT).show()
            navigateToSettings()
            return
        }

        // 备份当前壁纸
        val backupSuccess = wallpaperHelper.backupCurrentWallpaper()
        if (!backupSuccess) {
            Log.e(TAG, "Failed to backup current wallpaper")
            showErrorAndExit(getString(R.string.wallpaper_switch_failed))
            return
        }

        // 获取并设置工作壁纸
        val workBitmap = wallpaperHelper.getWorkWallpaperBitmap()
        if (workBitmap == null) {
            Log.e(TAG, "Failed to load work wallpaper bitmap")
            // 备份已创建，但工作壁纸加载失败，删除备份恢复原状
            wallpaperHelper.deleteBackup()
            showErrorAndExit(getString(R.string.wallpaper_switch_failed))
            return
        }

        val setSuccess = wallpaperHelper.setWorkWallpaper(workBitmap)
        workBitmap.recycle()

        if (setSuccess) {
            Toast.makeText(this, getString(R.string.work_wallpaper_set_success), Toast.LENGTH_SHORT).show()
            lastSwitchTime = SystemClock.elapsedRealtime()
            // 直接退出，下次打开时进入设置界面
            finish()
        } else {
            Log.e(TAG, "Failed to set work wallpaper, attempting to restore backup")
            // 设置失败，尝试恢复备份
            val restoreSuccess = wallpaperHelper.restoreBackupWallpaper()
            if (!restoreSuccess) {
                Log.e(TAG, "Also failed to restore backup! Backup file may be left behind.")
            }
            showErrorAndExit(getString(R.string.wallpaper_switch_failed))
            return
        }
    }

    /**
     * 处理下班模式
     *
     * 流程：恢复备份壁纸 → 删除备份 → Toast → 延迟退出
     */
    private fun handleOffWorkMode() {
        val restoreSuccess = wallpaperHelper.restoreBackupWallpaper()
        if (restoreSuccess) {
            Toast.makeText(this, getString(R.string.personal_wallpaper_restored), Toast.LENGTH_SHORT).show()
            lastSwitchTime = SystemClock.elapsedRealtime()
            // 直接退出，下次打开时进入设置界面
            finish()
        } else {
            Log.e(TAG, "Failed to restore backup wallpaper")
            showErrorAndExit(getString(R.string.wallpaper_switch_failed))
            return
        }
    }

    /**
     * 启动延迟退出
     *
     * 通过 Handler.postDelayed 延迟指定时间后 finish()
     * 同时记录 delayEndTime，用于 Activity 重建后计算剩余时间。
     *
     * @param delayMs 延迟毫秒数
     */
    private fun startDelayedFinish(delayMs: Long) {
        Log.d(TAG, "Starting delayed finish: ${delayMs}ms")
        isDelaying = true
        delayEndTime = SystemClock.elapsedRealtime() + delayMs
        handler.postDelayed(finishRunnable, delayMs)
    }

    /**
     * 取消延迟退出
     */
    private fun cancelDelayedFinish() {
        Log.d(TAG, "Cancelling delayed finish")
        handler.removeCallbacks(finishRunnable)
        isDelaying = false
    }

    /**
     * 跳转到设置界面
     */
    private fun navigateToSettings() {
        isDelaying = false
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
        finish()
    }

    /**
     * 显示错误提示并退出
     *
     * 显示错误提示后直接退出，避免用户需要手动关闭
     *
     * @param message 错误提示信息
     */
    private fun showErrorAndExit(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        // 移除延迟回调，防止内存泄漏
        handler.removeCallbacks(finishRunnable)
        // 注意：不要重置 isDelaying！
        // 壁纸变更会触发 config change 导致 Activity 销毁重建，
        // 如果这里重置为 false，新实例会重新执行壁纸切换逻辑，形成死循环。
        // 保持 isDelaying=true 让新实例走"延迟期间重入"分支，跳转设置页面。
    }
}