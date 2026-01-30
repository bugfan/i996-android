package com.i996.nat

import android.content.Context
import android.content.SharedPreferences

/**
 * 日志缓存，用于在 Service 和 Activity 之间传递日志
 */
object LogCache {
    private const val PREFS_NAME = "log_cache"
    private const val KEY_LOG_QUEUE = "log_queue"
    private const val MAX_LOGS = 100

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 添加日志到缓存
     */
    fun addLog(context: Context, log: String) {
        val prefs = getPrefs(context)
        val queue = getLogQueue(prefs).toMutableList()

        // 添加新日志
        queue.add(log)

        // 限制日志数量
        while (queue.size > MAX_LOGS) {
            queue.removeAt(0)
        }

        // 保存
        saveLogQueue(prefs, queue)
    }

    /**
     * 获取所有日志并清空缓存
     */
    fun getAndClearLogs(context: Context): List<String> {
        val prefs = getPrefs(context)
        val queue = getLogQueue(prefs)

        // 清空
        prefs.edit().remove(KEY_LOG_QUEUE).apply()

        return queue
    }

    /**
     * 获取日志队列（不解码）
     */
    private fun getLogQueue(prefs: SharedPreferences): List<String> {
        val json = prefs.getString(KEY_LOG_QUEUE, null) ?: return emptyList()
        try {
            // 简单的分隔符方案：使用 ||| 分隔每条日志
            if (json.isEmpty()) return emptyList()
            return json.split("|||")
        } catch (e: Exception) {
            return emptyList()
        }
    }

    /**
     * 保存日志队列
     */
    private fun saveLogQueue(prefs: SharedPreferences, queue: List<String>) {
        val json = queue.joinToString("|||")
        prefs.edit().putString(KEY_LOG_QUEUE, json).apply()
    }
}
