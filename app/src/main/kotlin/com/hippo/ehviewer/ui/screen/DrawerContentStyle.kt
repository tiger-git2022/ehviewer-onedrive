package com.hippo.ehviewer.ui.screen

import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.ehviewer.core.ui.component.LocalSideSheetGlass
import com.hippo.ehviewer.ui.glassOnSelectedColor
import com.hippo.ehviewer.ui.glassSelectedColor

@Composable
fun selectedListItemColor() = if (LocalSideSheetGlass.current != null) {
    // Fork: over liquid glass, a light in the glass
    ListItemDefaults.colors(
        containerColor = glassSelectedColor(),
        headlineColor = glassOnSelectedColor(),
        trailingIconColor = glassOnSelectedColor(),
    )
} else {
    ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
        trailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
fun listItemOnDrawerColor(selected: Boolean) = if (selected) {
    selectedListItemColor()
} else {
    ListItemDefaults.colors(
        // Fork: over liquid glass, the glass shows through
        containerColor = if (LocalSideSheetGlass.current != null) Color.Transparent else DrawerDefaults.modalContainerColor,
    )
}

@Composable
fun topBarOnDrawerColor() = if (LocalSideSheetGlass.current != null) {
    // Fork: over liquid glass, the glass shows through; scrolled, a little of the bar color keeps the title apart
    TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
    )
} else {
    TopAppBarDefaults.topAppBarColors(
        containerColor = DrawerDefaults.modalContainerColor,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
