package com.ehviewer.core.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerState2
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalSideDrawer
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

private typealias Sheet = @Composable ColumnScope.(DrawerState2) -> Unit

val LocalSideSheetContainer = staticCompositionLocalOf<SnapshotStateList<Sheet>> { error("") }
val LocalSideSheetState = staticCompositionLocalOf<DrawerState2> { error("") }

@Composable
fun ProvideSideSheetContent(content: Sheet) {
    val container = LocalSideSheetContainer.current
    DisposableEffect(content) {
        container.add(0, content)
        onDispose { container.remove(content) }
    }
}

@Composable
fun MutableSideSheet(drawerState: DrawerState2, modifier: Modifier, enabled: Boolean, content: @Composable () -> Unit) {
    val sheet = remember { mutableStateListOf<Sheet>() }
    val f = sheet.firstOrNull()
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    // Fork: liquid glass from the app, see FloatingGlass
    val glass = LocalSideSheetGlass.current
    val sheetColor = DrawerDefaults.modalContainerColor
    val shape = ShapeDefaults.Large.copy(topEnd = CornerSize(0), bottomEnd = CornerSize(0))
    CompositionLocalProvider(LocalSideSheetContainer provides sheet) {
        ModalSideDrawer(
            drawerContent = {
                if (f != null) {
                    ModalDrawerSheet(
                        modifier = Modifier.widthIn(max = width - 112.dp).floatingGlass(glass, shape, sheetColor.copy(alpha = GLASS_SHEET_TINT), panel = true),
                        drawerShape = shape,
                        drawerContainerColor = if (glass != null) Color.Transparent else sheetColor,
                        drawerContentColor = MaterialTheme.colorScheme.contentColorFor(sheetColor),
                        drawerTonalElevation = if (glass != null) 0.dp else DrawerDefaults.ModalDrawerElevation,
                        windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.End),
                    ) {
                        f(drawerState)
                    }
                }
            },
            modifier = modifier,
            drawerState = drawerState,
            gesturesEnabled = f != null && enabled,
            content = content,
        )
    }
}

/** Fork: how much of the drawer color lies over the glass of a drawer, enough to read its lists. */
const val GLASS_SHEET_TINT = 0.6f
