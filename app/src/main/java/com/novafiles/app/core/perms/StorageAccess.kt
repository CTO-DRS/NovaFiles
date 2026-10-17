package com.novafiles.app.core.perms

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/** إدارة صلاحية الوصول للتخزين بطريقة كل نظام (26 → 35+) */
object StorageAccess {

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** طلب الصلاحية المناسبة حسب إصدار أندرويد */
    fun request(activity: Activity, requestCode: Int = 4242) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:${activity.packageName}"))
                activity.startActivityForResult(intent, requestCode)
            } catch (_: Exception) {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
            }
        } else {
            ActivityCompatCompat.request(activity, requestCode)
        }
    }

    private object ActivityCompatCompat {
        fun request(activity: Activity, requestCode: Int) {
            activity.requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                requestCode,
            )
        }
    }
}
