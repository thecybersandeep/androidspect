package com.androidspect.data

import kotlinx.serialization.Serializable

@Serializable
data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val uid: Int,
    val targetSdk: Int,
    val minSdk: Int,
    val sourceDir: String,
    val dataDir: String,
    val nativeLibDir: String,
    val debuggable: Boolean,
    val system: Boolean,
    val permissions: List<String>,
    val firstInstallTime: Long,
    val lastUpdateTime: Long
)
