package com.pravera.flutter_foreground_task.models

data class TodayNotificationData(
    val day: Int,
    val solar: String,
    val hijri: String,
    val gregorian: String,
    val titleColor :String,
    val subtitleColor:String,
)