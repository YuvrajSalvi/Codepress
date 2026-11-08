package com.codepress.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Small quiet icon button used for actions such as copy, run, stop, settings. */
@Composable
fun IconAction(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, modifier = modifier.size(40.dp), enabled = enabled) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(19.dp))
    }
}

/** The one accent-coloured icon button on a screen (send, run). */
@Composable
fun PrimaryIconButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(46.dp),
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun StatusIcon(status: ToolStatus, tint: Color, size: Dp = 15.dp) {
    when (status) {
        ToolStatus.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(size), strokeWidth = 2.dp, color = tint)
        ToolStatus.DONE -> Icon(Icons.Rounded.Check, contentDescription = "Done", tint = tint, modifier = Modifier.size(size))
        ToolStatus.FAILED, ToolStatus.DENIED -> Icon(Icons.Rounded.Close, contentDescription = "Failed", tint = tint, modifier = Modifier.size(size))
        ToolStatus.AWAITING -> Icon(Icons.Rounded.Info, contentDescription = "Needs approval", tint = tint, modifier = Modifier.size(size))
        ToolStatus.QUEUED -> Icon(Icons.Rounded.MoreHoriz, contentDescription = "Queued", tint = tint, modifier = Modifier.size(size))
    }
}
