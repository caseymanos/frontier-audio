package com.frontieraudio.heartbeat.location

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val timestampMillis: Long
)