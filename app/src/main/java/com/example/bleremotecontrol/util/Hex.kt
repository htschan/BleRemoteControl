package com.example.bleremotecontrol.util

object Hex {
    private val HEX = "0123456789abcdef".toCharArray()
    fun toHex(b: ByteArray): String {
        val out = CharArray(b.size * 2)
        var i = 0
        b.forEach { v ->
            val x = v.toInt() and 0xff
            out[i++] = HEX[x ushr 4]
            out[i++] = HEX[x and 0x0f]
        }
        return String(out)
    }
}