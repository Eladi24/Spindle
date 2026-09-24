package io.github.eladimany.spindle.ui.permission

import android.Manifest
import android.os.Build

/** READ_MEDIA_AUDIO covers API 33+; below that, READ_EXTERNAL_STORAGE is required. */
val audioLibraryPermission: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
