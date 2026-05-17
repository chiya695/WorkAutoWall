package com.chiya.workautowall.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 壁纸操作工具类
 *
 * 封装所有壁纸相关操作，包括备份、恢复、设置、删除等。
 * 所有方法均返回 Boolean 表示成功/失败，内部 try-catch 防止崩溃。
 * 使用 BitmapFactory.Options.inSampleSize 防止 OOM。
 */
class WallpaperHelper(private val context: Context) {

    companion object {
        private const val TAG = "WallpaperHelper"
        private const val BACKUP_FILE_NAME = "wallpaper_backup.png"
        private const val WORK_WALLPAPER_FILE_NAME = "work_wallpaper.png"
        private const val OLD_BACKUP_FILE_NAME = "wallpaper_backup.jpg"
        private const val OLD_WORK_WALLPAPER_FILE_NAME = "work_wallpaper.jpg"
        private const val JPEG_QUALITY = 100
        private const val MAX_BITMAP_SIZE_MULTIPLIER = 4 // 降采样目标：屏幕分辨率的4倍
    }

    private val wallpaperManager = android.app.WallpaperManager.getInstance(context)

    /**
     * 获取当前系统壁纸 Bitmap
     *
     * 优先使用 getWallpaperFile()（不依赖 READ_EXTERNAL_STORAGE，MIUI 兼容），
     * 失败时降级为 getDrawable()。
     *
     * @return 当前壁纸 Bitmap，失败时返回 null
     */
    fun getCurrentWallpaper(): Bitmap? {
        // 方法1：通过 ParcelFileDescriptor 读取（推荐，不依赖 READ_EXTERNAL_STORAGE）
        try {
            val fd = wallpaperManager.getWallpaperFile(android.app.WallpaperManager.FLAG_SYSTEM)
            if (fd != null) {
                fd.use { pfd ->
                    val bitmap = android.graphics.BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
                    if (bitmap != null) {
                        Log.i(TAG, "Got wallpaper via getWallpaperFile()")
                        return bitmap
                    }
                }
            }
            Log.w(TAG, "getWallpaperFile() returned null")
        } catch (e: SecurityException) {
            Log.w(TAG, "getWallpaperFile() blocked, trying getDrawable()", e)
        } catch (e: Exception) {
            Log.w(TAG, "getWallpaperFile() failed, trying getDrawable()", e)
        }

        // 方法2：降级方案 - 标准方式（需要 READ_EXTERNAL_STORAGE）
        try {
            val drawable: Drawable? = wallpaperManager.drawable
            if (drawable != null) {
                val width = drawable.intrinsicWidth.coerceAtLeast(1)
                val height = drawable.intrinsicHeight.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                Log.i(TAG, "Got wallpaper via getDrawable()")
                return bitmap
            }
            Log.w(TAG, "WallpaperManager drawable is null")
        } catch (e: Exception) {
            Log.e(TAG, "getDrawable() also failed", e)
        }

        Log.e(TAG, "All methods to get current wallpaper failed")
        return null
    }

    /**
     * 备份当前壁纸到私有目录
     *
     * 将当前系统壁纸以 PNG 无损格式保存到 filesDir/wallpaper_backup.png
     *
     * @return true 表示备份成功，false 表示失败
     */
    fun backupCurrentWallpaper(): Boolean {
        return try {
            val bitmap = getCurrentWallpaper() ?: return false
            val backupFile = getBackupFile()
            FileOutputStream(backupFile).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            bitmap.recycle()
            Log.i(TAG, "Wallpaper backed up to: ${backupFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup wallpaper", e)
            false
        }
    }

    /**
     * 恢复备份壁纸为系统壁纸
     *
     * 读取备份文件，解码并设置为系统壁纸，然后删除备份文件。
     * 使用 inSampleSize 防止 OOM，降采样限制为屏幕分辨率的4倍以保持高质量。
     *
     * @return true 表示恢复成功，false 表示失败或无备份
     */
    fun restoreBackupWallpaper(): Boolean {
        return try {
            val backupFile = getBackupFile()
            if (!backupFile.exists()) {
                Log.w(TAG, "Backup file not found")
                return false
            }

            // 获取屏幕分辨率，用于降采样（限制为屏幕分辨率的4倍以保持高质量）
            val screenMetrics = getScreenMetrics()
            val bitmap = decodeSampledBitmap(
                backupFile,
                screenMetrics.widthPixels * MAX_BITMAP_SIZE_MULTIPLIER,
                screenMetrics.heightPixels * MAX_BITMAP_SIZE_MULTIPLIER
            )

            if (bitmap == null) {
                Log.e(TAG, "Failed to decode backup bitmap")
                return false
            }

            var setSuccess = false
            try {
                wallpaperManager.setBitmap(bitmap)
                setSuccess = true
            } catch (e: SecurityException) {
                Log.w(TAG, "setBitmap blocked, trying setStream fallback", e)
                try {
                    backupFile.inputStream().use { stream ->
                        wallpaperManager.setStream(stream)
                    }
                    setSuccess = true
                } catch (e2: Exception) {
                    Log.e(TAG, "Stream fallback also failed", e2)
                }
            }
            bitmap.recycle()

            if (setSuccess) {
                // 删除备份文件
                val deleted = backupFile.delete()
                Log.i(TAG, "Backup restored, backup file deleted: $deleted")
            }
            setSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore backup wallpaper", e)
            false
        }
    }

    /**
     * 设置指定 Bitmap 为系统壁纸
     *
     * @param bitmap 要设置的壁纸 Bitmap
     * @return true 表示设置成功，false 表示失败
     */
    fun setWorkWallpaper(bitmap: Bitmap): Boolean {
        return try {
            wallpaperManager.setBitmap(bitmap)
            Log.i(TAG, "Work wallpaper set successfully")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: MIUI may have blocked wallpaper setting. Try using WallpaperManager.setStream instead.", e)
            // 降级方案：通过 InputStream 设置壁纸
            try {
                val workFile = getWorkWallpaperFile()
                if (workFile.exists()) {
                    workFile.inputStream().use { stream ->
                        wallpaperManager.setStream(stream)
                    }
                    Log.i(TAG, "Work wallpaper set via stream fallback")
                    true
                } else {
                    false
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Stream fallback also failed", e2)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set work wallpaper", e)
            false
        }
    }

    /**
     * 获取备份文件大小（字节）
     *
     * @return 备份文件大小，如果不存在返回 0
     */
    fun getBackupFileSize(): Long {
        return try {
            val backupFile = getBackupFile()
            if (backupFile.exists()) backupFile.length() else 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get backup file size", e)
            0L
        }
    }

    /**
     * 获取工作壁纸文件大小（字节）
     *
     * @return 工作壁纸文件大小，如果不存在返回 0
     */
    fun getWorkWallpaperFileSize(): Long {
        return try {
            val workFile = getWorkWallpaperFile()
            if (workFile.exists()) workFile.length() else 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get work wallpaper file size", e)
            0L
        }
    }

    /**
     * 检查备份文件是否存在
     *
     * @return true 表示有备份（下班状态），false 表示无备份（上班状态）
     */
    fun hasBackup(): Boolean {
        return getBackupFile().exists()
    }

    /**
     * 获取工作壁纸 Bitmap
     *
     * 从私有目录读取工作壁纸文件，使用 inSampleSize 降采样。
     * 降采样限制为屏幕分辨率的4倍以保持高质量。
     *
     * @return 工作壁纸 Bitmap，文件不存在或读取失败时返回 null
     */
    fun getWorkWallpaperBitmap(): Bitmap? {
        return try {
            val workFile = getWorkWallpaperFile()
            if (!workFile.exists()) {
                Log.w(TAG, "Work wallpaper file not found")
                return null
            }
            val screenMetrics = getScreenMetrics()
            decodeSampledBitmap(
                workFile,
                screenMetrics.widthPixels * MAX_BITMAP_SIZE_MULTIPLIER,
                screenMetrics.heightPixels * MAX_BITMAP_SIZE_MULTIPLIER
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get work wallpaper bitmap", e)
            null
        }
    }

    /**
     * 从 Uri 保存工作壁纸到私有目录
     *
     * 从 content:// Uri 读取图片，降采样后保存到 work_wallpaper.png（无损格式）
     * 注意：先将 Uri 内容拷贝到临时文件，再从临时文件解码。
     * 避免对同一个 content:// URI 打开两次流（部分 ROM 的 content provider 不支持）。
     * 降采样限制为屏幕分辨率的4倍以保持高质量。
     *
     * @param uri 图片 Uri
     * @return true 表示保存成功，false 表示失败
     */
    fun saveWorkWallpaperFromUri(uri: Uri): Boolean {
        return try {
            val workFile = getWorkWallpaperFile()
            val tempFile = File(context.cacheDir, "temp_wallpaper_input")

            // 第一步：将 Uri 内容拷贝到临时文件（只打开一次流）
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.e(TAG, "Failed to open input stream from uri")
                return false
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                Log.e(TAG, "Temp file is empty or missing after copy")
                return false
            }

            // 第二步：从临时文件解码获取尺寸信息
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(tempFile.absolutePath, options)

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.e(TAG, "Invalid image dimensions: ${options.outWidth}x${options.outHeight}")
                tempFile.delete()
                return false
            }

            val screenMetrics = getScreenMetrics()
            options.inSampleSize = calculateInSampleSize(
                options,
                screenMetrics.widthPixels * MAX_BITMAP_SIZE_MULTIPLIER,
                screenMetrics.heightPixels * MAX_BITMAP_SIZE_MULTIPLIER
            )
            options.inJustDecodeBounds = false

            // 第三步：从临时文件解码实际 Bitmap
            val bitmap: Bitmap? = BitmapFactory.decodeFile(tempFile.absolutePath, options)
            tempFile.delete()

            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from temp file")
                return false
            }

            // 第四步：保存到工作壁纸文件（PNG 无损格式）
            FileOutputStream(workFile).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            bitmap.recycle()

            Log.i(TAG, "Work wallpaper saved to: ${workFile.absolutePath}, size=${workFile.length()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save work wallpaper from uri", e)
            false
        }
    }

    /**
     * 删除备份文件
     *
     * @return true 表示删除成功，false 表示失败或文件不存在
     */
    fun deleteBackup(): Boolean {
        return try {
            val backupFile = getBackupFile()
            if (backupFile.exists()) {
                val deleted = backupFile.delete()
                Log.i(TAG, "Backup file deleted: $deleted")
                deleted
            } else {
                Log.w(TAG, "Backup file not found for deletion")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete backup", e)
            false
        }
    }

    /**
     * 获取备份文件对象（支持向后兼容旧格式）
     *
     * 优先返回新格式文件，如果不存在则检查旧格式并迁移
     */
    private fun getBackupFile(): File {
        val newFile = File(context.filesDir, BACKUP_FILE_NAME)
        if (newFile.exists()) {
            return newFile
        }

        // 检查旧格式文件
        val oldFile = File(context.filesDir, OLD_BACKUP_FILE_NAME)
        if (oldFile.exists()) {
            Log.i(TAG, "Found old backup file, migrating to new format")
            // 尝试重命名为新格式
            if (oldFile.renameTo(newFile)) {
                Log.i(TAG, "Backup file migrated successfully")
                return newFile
            } else {
                Log.w(TAG, "Failed to rename old backup file, using old file")
                return oldFile
            }
        }

        return newFile
    }

    /**
     * 获取工作壁纸文件对象（支持向后兼容旧格式）
     *
     * 优先返回新格式文件，如果不存在则检查旧格式并迁移
     */
    private fun getWorkWallpaperFile(): File {
        val newFile = File(context.filesDir, WORK_WALLPAPER_FILE_NAME)
        if (newFile.exists()) {
            return newFile
        }

        // 检查旧格式文件
        val oldFile = File(context.filesDir, OLD_WORK_WALLPAPER_FILE_NAME)
        if (oldFile.exists()) {
            Log.i(TAG, "Found old work wallpaper file, migrating to new format")
            // 尝试重命名为新格式
            if (oldFile.renameTo(newFile)) {
                Log.i(TAG, "Work wallpaper file migrated successfully")
                return newFile
            } else {
                Log.w(TAG, "Failed to rename old work wallpaper file, using old file")
                return oldFile
            }
        }

        return newFile
    }

    /**
     * 获取屏幕分辨率
     *
     * API 30+ 使用 WindowManager.currentWindowMetrics（推荐方式），
     * 低版本保留 defaultDisplay.getMetrics() 实现。
     */
    private fun getScreenMetrics(): DisplayMetrics {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            DisplayMetrics().apply {
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                densityDpi = context.resources.displayMetrics.densityDpi
            }
        } else {
            @Suppress("DEPRECATION")
            DisplayMetrics().also { metrics ->
                windowManager.defaultDisplay.getMetrics(metrics)
            }
        }
    }

    /**
     * 计算 inSampleSize 值
     *
     * 根据目标宽高和原始图片尺寸计算合适的降采样比例
     *
     * @param options 包含原始图片尺寸信息的 Options
     * @param reqWidth 目标宽度
     * @param reqHeight 目标高度
     * @return inSampleSize 值（总是2的幂）
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * 解码降采样后的 Bitmap
     *
     * @param file 图片文件
     * @param reqWidth 目标宽度
     * @param reqHeight 目标高度
     * @return 降采样后的 Bitmap，失败时返回 null
     */
    private fun decodeSampledBitmap(
        file: File,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        return try {
            // 第一次解码：仅获取尺寸
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            // 计算降采样比例
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            // 第二次解码：获取实际 Bitmap
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode sampled bitmap", e)
            null
        }
    }
}