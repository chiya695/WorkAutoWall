package com.chiya.workautowall.util

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences 封装类
 *
 * 提供应用配置的读写操作，包含三个配置键：
 * - delay_time_ms: 延迟退出时间（毫秒），默认 7000ms
 * - work_wallpaper_path: 工作壁纸文件绝对路径
 * - first_run: 是否首次运行（预留）
 */
class AppPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "wallpaper_switcher_prefs"
        private const val KEY_DELAY_TIME_MS = "delay_time_ms"
        private const val KEY_WORK_WALLPAPER_PATH = "work_wallpaper_path"
        private const val KEY_FIRST_RUN = "first_run"
        private const val DEFAULT_DELAY_TIME_MS = 7000L
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 获取延迟退出时间（毫秒）
     *
     * @return 延迟时间，范围 5000~10000ms，默认 7000ms
     */
    fun getDelayTimeMs(): Long {
        return prefs.getLong(KEY_DELAY_TIME_MS, DEFAULT_DELAY_TIME_MS)
    }

    /**
     * 设置延迟退出时间（毫秒）
     *
     * @param ms 延迟时间，自动钳制到 5000~10000ms 范围
     */
    fun setDelayTimeMs(ms: Long) {
        val clamped = ms.coerceIn(5000L, 10000L)
        prefs.edit().putLong(KEY_DELAY_TIME_MS, clamped).apply()
    }

    /**
     * 获取工作壁纸文件路径
     *
     * @return 工作壁纸绝对路径，未设置时返回 null
     */
    fun getWorkWallpaperPath(): String? {
        return prefs.getString(KEY_WORK_WALLPAPER_PATH, null)
    }

    /**
     * 设置工作壁纸文件路径
     *
     * @param path 工作壁纸文件绝对路径
     */
    fun setWorkWallpaperPath(path: String) {
        prefs.edit().putString(KEY_WORK_WALLPAPER_PATH, path).apply()
    }

    /**
     * 是否首次运行
     *
     * @return true 表示首次运行，false 表示非首次
     */
    fun isFirstRun(): Boolean {
        return prefs.getBoolean(KEY_FIRST_RUN, true)
    }

    /**
     * 设置首次运行标志
     *
     * @param value false 表示已完成首次运行
     */
    fun setFirstRun(value: Boolean) {
        prefs.edit().putBoolean(KEY_FIRST_RUN, value).apply()
    }
}