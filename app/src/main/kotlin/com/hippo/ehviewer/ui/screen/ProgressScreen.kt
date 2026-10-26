package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.icons.EhIcons
import com.ehviewer.core.ui.icons.big.SadAndroid
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.restore.PandaChaikaApi
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.ShapeLoadingIndicator
import com.hippo.ehviewer.util.displayString
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.CancellationException
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.navigate

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.ProgressScreen(gid: Long, token: String, page: Int, navigator: DestinationsNavigator) = Screen(navigator) {
    val wrong = stringResource(id = R.string.error_something_wrong_happened)
    var error by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(error) {
        if (error.isEmpty()) {
            if (gid == -1L || token == "invalid" || page == -1) {
                error = wrong
            } else {
                runSuspendCatching {
                    try {
                        EhEngine.getGalleryToken(gid, token, page) to page
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Fork: a page of a gallery E-Hentai removed: its token from the backup on PandaChika, whose
                        // details then open from there (GalleryDetailScreen), not the page: it reads once downloaded
                        val galleryToken = runSuspendCatching { PandaChaikaApi.gallery(gid) }.getOrNull()?.ehToken ?: throw e
                        galleryToken to 0
                    }
                }.onSuccess { (galleryToken, toPage) ->
                    withUIContext {
                        navigator.popBackStack()
                        navigate(gid asDstPageTo toPage with galleryToken)
                    }
                }.onFailure {
                    error = it.displayString()
                }
            }
        }
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (error.isNotBlank()) {
            Column(
                modifier = Modifier.clickable { error = "" },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = EhIcons.Big.Default.SadAndroid,
                    contentDescription = null,
                    modifier = Modifier.padding(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = error,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        } else {
            ShapeLoadingIndicator()
        }
    }
}
