package com.sean.i996

// Protocol data classes
sealed class Data

data class DataData(val id: Long, val data: ByteArray) : Data() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DataData
        if (id != other.id) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class DialData(val id: Long) : Data()
data class AcceptData(val id: Long) : Data()
data class CloseData(val id: Long) : Data()
data class ResetData(val id: Long) : Data()

data class ConnectData(val id: Long, val addr: ByteArray) : Data() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ConnectData
        if (id != other.id) return false
        if (!addr.contentEquals(other.addr)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + addr.contentHashCode()
        return result
    }
}

data class ConnectConfirmData(val id: Long, val err: ByteArray) : Data() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ConnectConfirmData
        if (id != other.id) return false
        if (!err.contentEquals(other.err)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + err.contentHashCode()
        return result
    }
}

data class DataConfirmData(val id: Long, val size: Int) : Data()
data class DataWindowData(val id: Long, val size: Int) : Data()
class PingData : Data()
class PongData : Data()
data class InfoData(val info: Info) : Data()