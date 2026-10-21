package com.yundac.apkcloner.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Long.readableSize(): String {
    if (this < 1024) return "$this B"
    val units = arrayOf("KiB", "MiB", "GiB")
    var value = this.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

fun Long.readableDate(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(this))
