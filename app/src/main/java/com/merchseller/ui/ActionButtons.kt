package com.merchseller.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private val ActionHeight = 48.dp
private val ActionPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

@Composable
internal fun PrimaryAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val palette = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ActionHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = if (destructive) ButtonDefaults.buttonColors(
            containerColor = palette.error,
            contentColor = palette.onError
        ) else ButtonDefaults.buttonColors(),
        contentPadding = ActionPadding,
        content = content
    )
}

@Composable
internal fun SecondaryAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ActionHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = if (enabled) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else ButtonDefaults.outlinedButtonBorder,
        contentPadding = ActionPadding,
        content = content
    )
}

@Composable
internal fun QuietAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ActionHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        contentPadding = ActionPadding,
        content = content
    )
}

@Composable
internal fun ActionPair(
    minItemWidth: androidx.compose.ui.unit.Dp,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // При крупном шрифте кнопкам тоже нужно больше места.
        if (maxWidth >= minItemWidth * (2 * fontScale) + 8.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        }
    }
}
