package me.i996.client.util

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

object LogBuffer {
    private const val MAX_LINES = 500
    private val lines = CopyOnWriteArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun add(msg: String) {
        val entry = "[${fmt.format(Date())}] $msg"
        lines.add(entry)
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    fun getAll(): List<String> = lines.toList()

    fun clear() = lines.clear()
}
