package com.chiya.workautowall

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chiya.workautowall.databinding.ActivitySettingsBinding
import com.chiya.workautowall.util.AppPreferences
import com.chiya.workautowall.util.WallpaperHelper

/**
 * 设置 Activity
 *
 * 提供壁纸设置界面，包含以下功能：
 * 1. 壁纸预览：显示当前工作壁纸
 * 2. 选择工作壁纸：从文件选择器选择
 * 3. 备份管理：查看状态、删除备份（带确认）
 * 4. 延迟时间配置：SeekBar 5-10秒
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var wallpaperHelper: WallpaperHelper
    private lateinit var preferences: AppPreferences

    /**
     * 图片选择器 ActivityResultLauncher
     *
     * 使用 PickVisualMedia 启动系统照片选择器（Android 13+）
     * 兼容 HyperOS 3 相册，无需额外存储权限（系统 Picker 免权限）
     */
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { onWallpaperSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        // 初始化 View Binding
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化工具类
        wallpaperHelper = WallpaperHelper(this)
        preferences = AppPreferences(this)

        // 初始化视图
        initViews()
    }

    /**
     * 初始化所有视图和事件监听
     */
    private fun initViews() {
        // 加载壁纸预览
        loadWallpaperPreview()

        // 更新备份状态显示
        updateBackupStatus()

        // 初始化 SeekBar
        initSeekBar()

        // 选择工作壁纸按钮
        binding.btnSelectWallpaper.setOnClickListener {
            onSelectWallpaperClick()
        }

        // 手动设置工作壁纸按钮
        binding.btnApplyWorkWallpaper.setOnClickListener {
            onApplyWorkWallpaperClick()
        }

        // 手动恢复原壁纸按钮
        binding.btnRestoreOriginalWallpaper.setOnClickListener {
            onRestoreOriginalWallpaperClick()
        }

        // 删除备份按钮
        binding.btnDeleteBackup.setOnClickListener {
            onDeleteBackupClick()
        }
    }

    /**
     * 加载壁纸预览
     *
     * 从工作壁纸文件加载 Bitmap 并显示在 ImageView 中，
     * 文件不存在时显示占位图
     */
    private fun loadWallpaperPreview() {
        val bitmap: Bitmap? = wallpaperHelper.getWorkWallpaperBitmap()
        if (bitmap != null) {
            binding.ivWallpaperPreview.setImageBitmap(bitmap)
            Log.d(TAG, "Work wallpaper preview loaded")
        } else {
            binding.ivWallpaperPreview.setImageResource(R.drawable.ic_placeholder_wallpaper)
            Log.d(TAG, "No work wallpaper, showing placeholder")
        }
    }

    /**
     * 选择工作壁纸按钮点击事件
     *
     * 使用 PickVisualMedia 启动系统照片选择器（Android 13+）
     * 兼容 HyperOS 3 相册，提供更好的图片选择体验
     */
    private fun onSelectWallpaperClick() {
        Log.d(TAG, "Select wallpaper clicked")
        imagePickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    /**
     * 手动设置工作壁纸按钮点击事件
     *
     * 检查工作壁纸是否存在，存在则备份当前壁纸并设置工作壁纸
     */
    private fun onApplyWorkWallpaperClick() {
        Log.d(TAG, "Apply work wallpaper clicked")

        val workWallpaperPath = preferences.getWorkWallpaperPath()
        if (workWallpaperPath.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.no_work_wallpaper_hint), Toast.LENGTH_SHORT).show()
            return
        }

        val workFile = java.io.File(workWallpaperPath)
        if (!workFile.exists()) {
            Toast.makeText(this, getString(R.string.no_work_wallpaper_hint), Toast.LENGTH_SHORT).show()
            return
        }

        // 备份当前壁纸
        val backupSuccess = wallpaperHelper.backupCurrentWallpaper()
        if (!backupSuccess) {
            Toast.makeText(this, getString(R.string.wallpaper_switch_failed), Toast.LENGTH_SHORT).show()
            return
        }

        // 获取并设置工作壁纸
        val workBitmap = wallpaperHelper.getWorkWallpaperBitmap()
        if (workBitmap == null) {
            wallpaperHelper.deleteBackup()
            Toast.makeText(this, getString(R.string.wallpaper_switch_failed), Toast.LENGTH_SHORT).show()
            return
        }

        val setSuccess = wallpaperHelper.setWorkWallpaper(workBitmap)
        workBitmap.recycle()

        if (setSuccess) {
            Toast.makeText(this, getString(R.string.work_wallpaper_set_success), Toast.LENGTH_SHORT).show()
            updateBackupStatus()
        } else {
            wallpaperHelper.restoreBackupWallpaper()
            Toast.makeText(this, getString(R.string.wallpaper_switch_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 手动恢复原壁纸按钮点击事件
     *
     * 检查是否有备份，有则恢复备份壁纸
     */
    private fun onRestoreOriginalWallpaperClick() {
        Log.d(TAG, "Restore original wallpaper clicked")

        if (!wallpaperHelper.hasBackup()) {
            Toast.makeText(this, getString(R.string.no_backup_file_hint), Toast.LENGTH_SHORT).show()
            return
        }

        val restoreSuccess = wallpaperHelper.restoreBackupWallpaper()
        if (restoreSuccess) {
            Toast.makeText(this, getString(R.string.personal_wallpaper_restored), Toast.LENGTH_SHORT).show()
            updateBackupStatus()
        } else {
            Toast.makeText(this, getString(R.string.wallpaper_switch_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 壁纸选择完成回调
     *
     * @param uri 选中的图片 Uri
     */
    private fun onWallpaperSelected(uri: Uri) {
        Log.d(TAG, "Wallpaper selected: $uri")

        val success = wallpaperHelper.saveWorkWallpaperFromUri(uri)
        if (success) {
            // 保存路径到配置（使用 .png 格式，与 saveWorkWallpaperFromUri 一致）
            val workFile = filesDir.resolve("work_wallpaper.png")
            preferences.setWorkWallpaperPath(workFile.absolutePath)

            // 刷新预览
            loadWallpaperPreview()
            Toast.makeText(this, getString(R.string.work_wallpaper_set_success), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.work_wallpaper_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 删除备份按钮点击事件
     *
     * 先检查是否有备份，有则弹出确认对话框
     */
    private fun onDeleteBackupClick() {
        if (!wallpaperHelper.hasBackup()) {
            Toast.makeText(this, getString(R.string.no_backup_file_hint), Toast.LENGTH_SHORT).show()
            return
        }
        showDeleteConfirmDialog()
    }

    /**
     * 显示删除备份确认对话框
     *
     * 使用 Material AlertDialog.Builder
     */
    private fun showDeleteConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_delete_title))
            .setMessage(getString(R.string.confirm_delete_message))
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                performDeleteBackup()
            }
            .show()
    }

    /**
     * 执行删除备份操作
     */
    private fun performDeleteBackup() {
        val success = wallpaperHelper.deleteBackup()
        if (success) {
            Toast.makeText(this, getString(R.string.backup_deleted_success), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.backup_delete_failed), Toast.LENGTH_SHORT).show()
        }
        updateBackupStatus()
    }

    /**
     * 更新备份状态显示
     *
     * 根据备份文件存在性更新状态文字和删除按钮可用状态
     */
    private fun updateBackupStatus() {
        val hasBackup = wallpaperHelper.hasBackup()
        binding.tvBackupStatus.text = if (hasBackup) {
            getString(R.string.backup_status_yes)
        } else {
            getString(R.string.backup_status_no)
        }
        // 无备份时禁用删除按钮
        binding.btnDeleteBackup.isEnabled = hasBackup
        binding.btnDeleteBackup.alpha = if (hasBackup) 1.0f else 0.5f
    }

    /**
     * 初始化延迟时间 SeekBar
     *
     * SeekBar 范围 5-10 秒，步进 1 秒
     * OnSeekBarChangeListener 中实时更新显示值并保存配置
     */
    private fun initSeekBar() {
        val currentDelayMs = preferences.getDelayTimeMs()
        val currentSeconds = (currentDelayMs / 1000).toInt().coerceIn(5, 10)

        // SeekBar min=0, max=5 对应 5-10秒
        binding.seekBarDelay.max = 5
        binding.seekBarDelay.progress = currentSeconds - 5
        binding.tvDelayValue.text = getString(R.string.seconds_format, currentSeconds)

        binding.seekBarDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress + 5
                binding.tvDelayValue.text = getString(R.string.seconds_format, seconds)
                if (fromUser) {
                    saveDelayTime(seconds)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // 不需要处理
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 不需要处理，已在 onProgressChanged 中保存
            }
        })
    }

    /**
     * 保存延迟时间配置
     *
     * @param seconds 延迟秒数（5-10）
     */
    private fun saveDelayTime(seconds: Int) {
        val ms = (seconds * 1000).toLong()
        preferences.setDelayTimeMs(ms)
        Log.d(TAG, "Delay time saved: ${ms}ms")
    }
}