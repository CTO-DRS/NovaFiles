package com.novafiles.app.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.novafiles.app.core.fs.FileType

/** خريطة أنواع الملفات إلى أيقونة ولون موحدين في كل الشاشات */
object TypeIcons {

    fun icon(type: FileType): ImageVector = when (type) {
        FileType.FOLDER -> Icons.Filled.Folder
        FileType.IMAGE -> Icons.Filled.Image
        FileType.VIDEO -> Icons.Filled.Movie
        FileType.AUDIO -> Icons.Filled.MusicNote
        FileType.PDF -> Icons.Filled.PictureAsPdf
        FileType.ARCHIVE -> Icons.Filled.FolderZip
        FileType.APK -> Icons.Filled.Android
        FileType.CODE -> Icons.Filled.Code
        FileType.DOC, FileType.TEXT -> Icons.Filled.Description
        FileType.OTHER -> Icons.Filled.InsertDriveFile
    }

    fun color(type: FileType): Color = when (type) {
        FileType.FOLDER -> Color(0xFFF6B94C)
        FileType.IMAGE -> Color(0xFF4CAF7D)
        FileType.VIDEO -> Color(0xFF7E57C2)
        FileType.AUDIO -> Color(0xFFEF6C9A)
        FileType.PDF -> Color(0xFFE5534B)
        FileType.ARCHIVE -> Color(0xFFB0876A)
        FileType.APK -> Color(0xFF5FBF6F)
        FileType.CODE -> Color(0xFF42A5F5)
        FileType.DOC, FileType.TEXT -> Color(0xFF5C8AE6)
        FileType.OTHER -> Color(0xFF8E9AAF)
    }
}
