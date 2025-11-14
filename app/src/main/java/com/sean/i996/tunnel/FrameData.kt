package com.sean.i996.tunnel

// Base interface for all frame types
interface FrameData {
    val connectionId: Long
}

// Control frames
data class FramePing(override val connectionId: Long = 0L) : FrameData
data class FramePong(override val connectionId: Long = 0L) : FrameData
data class FrameTunnelCloseConfirm(override val connectionId: Long = 0L) : FrameData

// Info frame
data class FrameInfo(override val connectionId: Long = 0L, val data: ByteArray) : FrameData {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FrameInfo
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        return data.contentHashCode()
    }
}

// Data frame
data class TunnelFrameData(override val connectionId: Long, val data: ByteArray) : FrameData {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TunnelFrameData
        if (connectionId != other.connectionId) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = connectionId.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

// Connection control frames
data class FrameAccept(override val connectionId: Long) : FrameData
data class FrameClose(override val connectionId: Long) : FrameData
data class FrameReset(override val connectionId: Long) : FrameData
data class FrameDataConfirm(override val connectionId: Long, val windowSize: Int) : FrameData