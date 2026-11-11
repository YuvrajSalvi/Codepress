package com.codepress.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Css
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Html
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Javascript
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico")

fun isZip(path: String) = path.substringAfterLast('.', "").lowercase() == "zip"

/** File-type icon: folder, zip, image, html/css/js icons, or a short coloured badge such as PY, JV, C++. */
@Composable
fun FileIcon(path: String, isDir: Boolean, size: Dp = 36.dp) {
    val ext = path.substringAfterLast('.', "").lowercase()
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val vector: ImageVector? = when {
        isDir -> Icons.Rounded.Folder
        ext == "zip" -> Icons.Rounded.FolderZip
        ext in IMAGE_EXT -> Icons.Rounded.Image
        ext == "html" || ext == "htm" -> Icons.Rounded.Html
        ext == "css" -> Icons.Rounded.Css
        ext == "js" || ext == "mjs" -> Icons.Rounded.Javascript
        badgeFor(path).isEmpty() -> Icons.Rounded.Description
        else -> null
    }
    if (vector != null) {
        Icon(vector, contentDescription = null, tint = if (isDir) MaterialTheme.colorScheme.primary else tint, modifier = Modifier.size(size * 0.62f))
    } else {
        val color = when (ext) {
            "py" -> Color(0xFF4B8BBE); "java", "kt", "kts" -> Color(0xFFE76F51); "c", "h", "cpp", "cc", "hpp" -> Color(0xFF6C8EBF)
            "go" -> Color(0xFF00ADD8); "rs" -> Color(0xFFDEA584); "php" -> Color(0xFF8892BF); "sh", "bash" -> Color(0xFF7EE787)
            else -> Color(0xFF8A8F98)
        }
        Box(Modifier.size(size * 0.66f).background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            Text(badgeFor(path), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
