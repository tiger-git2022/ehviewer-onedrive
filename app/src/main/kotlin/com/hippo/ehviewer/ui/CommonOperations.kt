/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.ui

import android.Manifest
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.write
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.LOCAL_FAVORITED
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.ehviewer.core.ui.component.LabeledCheckbox
import com.ehviewer.core.util.isAtLeastT
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.mapToLongArray
import com.ehviewer.core.util.toEpochMillis
import com.ehviewer.core.util.toLocalDateTime
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.exception.EhException
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.download.downloadDir
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.download.tempDownloadDir
import com.hippo.ehviewer.ui.destinations.ReaderScreenDestination
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.awaitResult
import com.hippo.ehviewer.ui.tools.awaitSelectDate
import com.hippo.ehviewer.ui.tools.awaitSelectItem
import com.hippo.ehviewer.ui.tools.awaitSelectItemWithCheckBox
import com.hippo.ehviewer.ui.tools.awaitSelectItemWithIcon
import com.hippo.ehviewer.ui.tools.awaitSelectItemWithIconInCircle
import com.hippo.ehviewer.util.FavouriteStatusRouter
import com.hippo.ehviewer.util.bgWork
import com.hippo.ehviewer.util.requestPermission
import com.hippo.ehviewer.util.restartApplication
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.string
import moe.tarsin.tip
import okio.Path
import splitties.init.appCtx

private fun removeNoMediaFile(downloadDir: Path) {
    (downloadDir / ".nomedia").delete()
}

private fun ensureNoMediaFile(downloadDir: Path) {
    (downloadDir / ".nomedia").apply { if (!exists()) write {} }
}

private val lck = Mutex()

suspend fun keepNoMediaFileStatus(downloadDir: Path = downloadLocation, mediaScan: Boolean = Settings.mediaScan.value) {
    if (downloadDir.isDirectory) {
        lck.withLock {
            if (mediaScan) {
                removeNoMediaFile(downloadDir)
            } else {
                ensureNoMediaFile(downloadDir)
            }
        }
    }
}

fun getFavoriteIcon(favorited: Boolean) = if (favorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder

context(_: DialogState, _: MainActivity)
suspend fun startDownload(forceDefault: Boolean, vararg galleryInfos: BaseGalleryInfo) {
    if (isAtLeastT) {
        requestPermission(Manifest.permission.POST_NOTIFICATIONS)
    }
    val (toStart, toAdd) = galleryInfos.partition { DownloadManager.containDownloadInfo(it.gid) }
    if (toStart.isNotEmpty()) {
        val list = toStart.mapToLongArray(GalleryInfo::gid)
        DownloadService.startRangeDownload(list)
    }
    if (toAdd.isEmpty()) {
        return tip(R.string.added_to_download_list)
    }
    var justStart = forceDefault
    var label: String? = null
    // Get default download label
    if (!justStart && Settings.hasDefaultDownloadLabel) {
        label = Settings.defaultDownloadLabel
        justStart = label == null || DownloadManager.containLabel(label)
    }
    // If there is no other label, just use null label
    if (!justStart && DownloadManager.labelList.isEmpty()) {
        justStart = true
        label = null
    }
    if (justStart) {
        // Got default label
        for (gi in toAdd) {
            DownloadService.startDownload(gi, label)
        }
        // Notify
        tip(R.string.added_to_download_list)
    } else {
        // Let use chose label
        val list = DownloadManager.labelList
        val items = buildList {
            add(string(R.string.default_download_label_name))
            list.forEach {
                add(it.label)
            }
        }
        val (selected, checked) = awaitSelectItemWithCheckBox(
            items,
            title = R.string.download,
            checkBoxText = R.string.remember_download_label,
        )
        val label1 = if (selected == 0) null else items[selected].takeIf { DownloadManager.containLabel(it) }
        // Start download
        for (gi in toAdd) {
            DownloadService.startDownload(gi, label1)
        }
        // Save settings
        if (checked) {
            Settings.hasDefaultDownloadLabel = true
            Settings.defaultDownloadLabel = label1
        } else {
            Settings.hasDefaultDownloadLabel = false
        }
        tip(R.string.added_to_download_list)
    }
}

/**
 * Adds [galleryInfo] to the default collection or removes it from the favorites; with no default collection, or with
 * [choose] (fork: a long press on the favorite button), asks for the collection.
 */
context(_: DialogState)
suspend fun modifyFavorites(galleryInfo: GalleryInfo, choose: Boolean = false): Boolean {
    val localFavorited = EhDB.containLocalFavorites(galleryInfo.gid)
    if (Settings.hasSignedIn.value) {
        val isFavorited = galleryInfo.favoriteSlot != NOT_FAVORITED
        val defaultFavSlot = Settings.defaultFavSlot.value
        if (defaultFavSlot == -2 || choose) {
            val localFav = getFavoriteIcon(localFavorited) to appCtx.getString(R.string.local_favorites)
            val cloudFav = Settings.favCat.mapIndexed { index, name ->
                getFavoriteIcon(galleryInfo.favoriteSlot == index) to name
            }
            val items = buildList {
                if (isFavorited) {
                    val remove = Icons.Default.HeartBroken to appCtx.getString(R.string.remove_from_favourites)
                    add(remove)
                }
                add(localFav)
                addAll(cloudFav)
            }
            if (galleryInfo.favoriteSlot >= 0 && galleryInfo.favoriteNote == null) {
                galleryInfo.favoriteNote = bgWork { EhEngine.getFavoriteNote(galleryInfo.gid, galleryInfo.token) }
            }
            // Fork: no favorite note to write, the note already there is kept
            val slot = awaitSelectItemWithIconInCircle(items, title = R.string.add_favorites_dialog_title)
            return doModifyFavorites(galleryInfo, if (isFavorited) slot - 2 else slot - 1, localFavorited, galleryInfo.favoriteNote.orEmpty())
        } else {
            return doModifyFavorites(galleryInfo, if (isFavorited) NOT_FAVORITED else defaultFavSlot, localFavorited)
        }
    } else {
        return doModifyFavorites(galleryInfo, LOCAL_FAVORITED, localFavorited)
    }
}

/**
 * Fork: a tap on the favorite button of a gallery page. Opened galleries already go to the local favorites (see
 * [AutoLocalFavorite]), so a tap moves a local favorite into the default cloud collection (asking for one when there is
 * no default cloud collection), and a cloud favorite back to the local favorites only; a gallery in no favorites goes to
 * the local favorites. Signed out it adds or removes the local favorite, as before. Returns true when it added.
 */
context(_: DialogState)
suspend fun cycleFavorite(galleryInfo: GalleryInfo): Boolean {
    if (!Settings.hasSignedIn.value) return modifyFavorites(galleryInfo)
    val localFavorited = EhDB.containLocalFavorites(galleryInfo.gid)
    return when {
        galleryInfo.favoriteSlot >= 0 -> {
            // Leaves the cloud favorites and stays (or becomes) a local favorite
            if (!localFavorited) EhDB.putLocalFavorites(galleryInfo)
            doModifyFavorites(galleryInfo, NOT_FAVORITED, localFavorited = true)
        }
        !localFavorited && galleryInfo.favoriteSlot == NOT_FAVORITED -> {
            doModifyFavorites(galleryInfo, LOCAL_FAVORITED, localFavorited = false)
        }
        else -> {
            val slot = Settings.defaultFavSlot.value
            val added = if (slot in 0..9) {
                doModifyFavorites(galleryInfo, slot, localFavorited)
            } else {
                modifyFavorites(galleryInfo, choose = true)
            }
            // A gallery is in one place: once in the cloud favorites it leaves the local ones (back there when it leaves
            // the cloud again, above)
            if (galleryInfo.favoriteSlot >= 0 && EhDB.containLocalFavorites(galleryInfo.gid)) {
                EhDB.removeLocalFavorites(galleryInfo)
            }
            added
        }
    }
}

private suspend fun doModifyFavorites(
    galleryInfo: GalleryInfo,
    slot: Int = NOT_FAVORITED,
    localFavorited: Boolean = true,
    note: String = "",
) = with(galleryInfo) {
    val add = when (slot) {
        NOT_FAVORITED -> { // Remove from cloud favorites first
            if (favoriteSlot > LOCAL_FAVORITED) {
                EhEngine.modifyFavorites(gid, token)
                favoriteSlot = if (localFavorited) LOCAL_FAVORITED else NOT_FAVORITED
                favoriteName = null
                favoriteNote = null
            } else {
                EhDB.removeLocalFavorites(galleryInfo)
                favoriteSlot = NOT_FAVORITED
            }
            false
        }
        LOCAL_FAVORITED -> {
            if (localFavorited) {
                EhDB.removeLocalFavorites(galleryInfo)
            } else {
                EhDB.putLocalFavorites(galleryInfo)
            }
            // Keep cloud favorite slot
            if (favoriteSlot == NOT_FAVORITED) {
                favoriteSlot = LOCAL_FAVORITED
            } else if (favoriteSlot == LOCAL_FAVORITED) {
                favoriteSlot = NOT_FAVORITED
            }
            !localFavorited
        }
        in 0..9 -> {
            EhEngine.modifyFavorites(gid, token, slot, note)
            favoriteSlot = slot
            favoriteName = Settings.favCat[slot]
            favoriteNote = note
            true
        }
        else -> throw EhException("Invalid favorite slot!")
    }
    FavouriteStatusRouter.notify(galleryInfo)
    add
}

suspend fun removeFromFavorites(galleryInfo: GalleryInfo) = doModifyFavorites(
    galleryInfo = galleryInfo,
    localFavorited = EhDB.containLocalFavorites(galleryInfo.gid),
)

context(_: DestinationsNavigator)
fun navToReader(info: BaseGalleryInfo, page: Int = -1) = navToReader(ReaderScreenArgs.Gallery(info, page))

context(_: DestinationsNavigator)
fun navToReader(path: String) = navToReader(ReaderScreenArgs.Archive(path))

context(nav: DestinationsNavigator)
private fun navToReader(args: ReaderScreenArgs) = nav.navigate(ReaderScreenDestination(args)) { launchSingleTop = true }

context(_: DialogState, _: MainActivity, _: DestinationsNavigator)
suspend fun doGalleryInfoAction(info: BaseGalleryInfo) {
    val downloaded = DownloadManager.getDownloadState(info.gid) != DownloadInfo.STATE_INVALID
    val favorited = info.favoriteSlot != NOT_FAVORITED
    suspend fun addToFavorites(choose: Boolean) {
        runSuspendCatching {
            // The collection picker also offers to remove it
            val added = modifyFavorites(info, choose)
            tip(if (added) R.string.add_to_favorite_success else R.string.remove_from_favorite_success)
        }.onFailure {
            tip(R.string.add_to_favorite_failure)
        }
    }
    val actions = mutableListOf<GalleryInfoAction>()
    fun action(icon: ImageVector, text: Int, run: suspend () -> Unit) = actions.add(GalleryInfoAction(icon, text, run))
    action(Icons.AutoMirrored.Default.MenuBook, R.string.read) {
        EhDB.putHistoryInfo(info)
        navToReader(info)
    }
    if (downloaded) {
        action(Icons.Default.Delete, R.string.delete_downloads) { confirmRemoveDownload(info) }
    } else {
        action(Icons.Default.Download, R.string.download) { startDownload(false, info) }
    }
    if (favorited) {
        action(Icons.Default.HeartBroken, R.string.remove_from_favourites) {
            runSuspendCatching {
                removeFromFavorites(info)
                tip(R.string.remove_from_favorite_success)
            }.onFailure {
                tip(R.string.remove_from_favorite_failure)
            }
        }
    }
    // Fork: the collection to add to (or to move to), besides the default one; signed out, only the local favorites
    if (Settings.hasSignedIn.value) {
        action(Icons.AutoMirrored.Default.PlaylistAdd, R.string.add_to_favourites_choose) { addToFavorites(choose = true) }
    }
    if (downloaded) {
        action(Icons.AutoMirrored.Default.DriveFileMove, R.string.download_move_dialog_title) { showMoveDownloadLabel(info) }
    }
    // Fork: last, the local favorites, like opening the gallery does (AutoLocalFavorite); the cloud collections are in
    // the item above
    if (!favorited) {
        action(Icons.Default.Favorite, R.string.add_to_favourites) {
            runSuspendCatching {
                if (!EhDB.containLocalFavorites(info.gid)) doModifyFavorites(info, LOCAL_FAVORITED, localFavorited = false)
                tip(R.string.add_to_favorite_success)
            }.onFailure {
                tip(R.string.add_to_favorite_failure)
            }
        }
    }
    val selected = awaitSelectItemWithIcon(actions.map { it.icon to it.text }, EhUtils.getSuitableTitle(info))
    actions[selected].run()
}

private class GalleryInfoAction(val icon: ImageVector, val text: Int, val run: suspend () -> Unit)


context(_: DialogState)
private suspend fun confirmRemoveDownload(text: String): Boolean {
    val checked = awaitResult(
        initial = Settings.removeImageFiles,
        title = R.string.download_remove_dialog_title,
    ) {
        Column {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LabeledCheckbox(
                modifier = Modifier.fillMaxWidth(),
                checked = expectedValue,
                onCheckedChange = { expectedValue = it },
                label = stringResource(id = R.string.download_remove_dialog_check_text),
                indication = null,
            )
        }
    }
    Settings.removeImageFiles = checked
    return checked
}

context(_: DialogState)
suspend fun confirmRemoveDownload(info: GalleryInfo) {
    val text = appCtx.getString(R.string.download_remove_dialog_message, EhUtils.getSuitableTitle(info))
    val checked = confirmRemoveDownload(text)
    withIOContext {
        DownloadManager.deleteDownload(info.gid, checked)
    }
}

/**
 * Fork: [onConfirmed] once confirmed, before anything is deleted, so the list can drop them at once. The files are
 * deleted a few galleries at a time, on even if the screen is left, and one that cannot be deleted does not stop the
 * others. Returns how many galleries kept files that could not be deleted.
 */
context(_: DialogState)
suspend fun confirmRemoveDownloadRange(list: Collection<DownloadInfo>, onConfirmed: () -> Unit = {}): Int {
    val text = appCtx.getString(R.string.download_remove_dialog_message_2, list.size)
    val checked = confirmRemoveDownload(text)
    onConfirmed()
    return withContext(NonCancellable + Dispatchers.IO) {
        DownloadManager.deleteRangeDownload(list.mapToLongArray(DownloadInfo::gid))
        if (!checked) return@withContext 0
        val limit = Semaphore(REMOVE_FILES_PARALLELISM)
        list.map { info ->
            async {
                limit.withPermit {
                    runCatching {
                        info.downloadDir?.delete()
                        info.tempDownloadDir?.delete()
                        EhDB.removeDownloadDirname(info.gid)
                    }.onFailure { logcat(it) }.isFailure
                }
            }
        }.awaitAll().count { it }
    }
}

// Each a directory of images; through the system file picker every file is its own call
private const val REMOVE_FILES_PARALLELISM = 4

context(_: DialogState)
suspend fun showMoveDownloadLabel(info: GalleryInfo) {
    val defaultLabel = appCtx.getString(R.string.default_download_label_name)
    val labels = buildList {
        add(defaultLabel)
        DownloadManager.labelList.forEach {
            add(it.label)
        }
    }
    val selected = awaitSelectItem(labels, R.string.download_move_dialog_title)
    val downloadInfo = DownloadManager.getDownloadInfo(info.gid) ?: return
    val label = if (selected == 0) null else labels[selected]
    DownloadManager.changeLabel([downloadInfo], label)
}

context(_: DialogState)
suspend fun showMoveDownloadLabelList(list: Collection<DownloadInfo>): String? {
    val defaultLabel = appCtx.getString(R.string.default_download_label_name)
    val labels = buildList {
        add(defaultLabel)
        DownloadManager.labelList.forEach {
            add(it.label)
        }
    }
    val selected = awaitSelectItem(labels, R.string.download_move_dialog_title)
    val label = if (selected == 0) null else labels[selected]
    DownloadManager.changeLabel(list, label)
    return label
}

context(_: DialogState)
suspend fun awaitSelectDate(): String {
    val initial = LocalDate(2007, 3, 21)
    val yesterday = Clock.System.todayIn(TimeZone.UTC).minus(1, DateTimeUnit.DAY)
    val initialMillis = initial.toEpochMillis()
    val yesterdayMillis = yesterday.toEpochMillis()
    val dateRange = initialMillis..yesterdayMillis
    val dateMillis = awaitSelectDate(
        title = R.string.go_to,
        yearRange = initial.year..yesterday.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in dateRange
        },
    )
    return dateMillis.toLocalDateTime().date.toString()
}

context(_: Context, _: DialogState)
suspend fun showRestartDialog() {
    awaitConfirmationOrCancel {
        Text(stringResource(R.string.settings_restart))
    }
    restartApplication()
}
