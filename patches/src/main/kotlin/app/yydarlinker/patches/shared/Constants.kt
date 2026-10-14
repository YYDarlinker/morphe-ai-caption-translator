package app.yydarlinker.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val YOUTUBE = Compatibility(
        name = "YouTube",
        packageName = "com.google.android.youtube",
        apkFileType = ApkFileType.APK_REQUIRED,
        appIconColor = 0xFF0033,
        signatures = setOf(
            "5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6",
            "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a"
        ),
        // The targets of the official stable patches (1.47.0) that the add-on was verified on, from 21.16 up.
        // 21.41.525 also patches; it is listed once the official stable patches list it.
        targets = listOf(
            AppTarget(version = "21.40.161", minSdk = 29, isExperimental = true),
            AppTarget(version = "21.39.522", minSdk = 29, isExperimental = true),
            AppTarget(version = "21.38.130", minSdk = 29, isExperimental = true),
            AppTarget(version = "21.28.208", minSdk = 29, isExperimental = true),
            AppTarget(version = "21.23.492", minSdk = 28, isExperimental = true),
            AppTarget(version = "21.20.405", minSdk = 28),
            AppTarget(version = "21.16.256", minSdk = 28)
        )
    )
}
