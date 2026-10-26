package com.hippo.ehviewer.restore

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryComment
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryTag
import com.ehviewer.core.model.GalleryTagGroup
import com.ehviewer.core.model.PowerStatus
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.model.VoteStatus
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.fillInfo
import com.hippo.ehviewer.util.FileUtils
import moe.tarsin.coroutines.runSuspendCatching

// Fork: the details of a gallery E-Hentai removed, from its backup on PandaChika, see RestoredGalleries

/** A restored gallery: one E-Hentai removed, shown from PandaChika. */
val GalleryInfo.isRestored get() = RestoredGalleries.isRestored(gid)

/**
 * The details of [gid] from PandaChika, once E-Hentai said it is unavailable; null when PandaChika has no backup or
 * cannot be reached. [info] is the gallery as listed, for its cover.
 */
suspend fun restoredDetail(gid: Long, token: String, info: GalleryInfo?): GalleryDetail? {
    val gallery = runSuspendCatching { PandaChaikaApi.gallery(gid) }
        .onFailure { logcat("RestoredDetail", it) }
        .getOrNull()
        // Offline, the one kept when it was restored
        ?: RestoredGalleries.takeIf { it.isRestored(gid) }?.get(gid)
        ?: return null
    // The search lists the cover, /api does not
    val found = RestoredGalleries[gid]
    val merged = if (gallery.thumbnailUrl == null && gallery.thumbnail == null && found != null) {
        gallery.copy(thumbnailUrl = found.thumbnailUrl, thumbnail = found.thumbnail)
    } else {
        gallery
    }
    RestoredGalleries.restore(gid, merged)
    return merged.toDetail(gid, token, info)
}

private suspend fun PandaChaikaApi.Gallery.toDetail(gid: Long, token: String, info: GalleryInfo?): GalleryDetail {
    val base = toGalleryInfo()?.takeIf { it.gid == gid } ?: BaseGalleryInfo(gid = gid, token = token, title = title, titleJpn = titleJpn, pages = filecount)
    base.token = token.ifBlank { base.token }
    base.thumbKey = info?.thumbKey ?: base.thumbKey
    if (base.thumbKey == null) {
        // E-Hentai's API still has the cover of a removed gallery
        runSuspendCatching { EhEngine.fillGalleryListByApi(listOf(base), EhUrl.referer) }.onFailure { logcat("RestoredDetail", it) }
    }
    val tags = tags.mapNotNull { tag ->
        val ns = tag.substringBefore(':', "")
        val namespace = if (ns.isEmpty()) TagNamespace.Temp else TagNamespace.from(ns) ?: return@mapNotNull null
        namespace to tag.substringAfter(':').replace('_', ' ')
    }
    val tagGroups = tags.groupBy({ it.first }, { it.second }).map { (namespace, texts) ->
        GalleryTagGroup(namespace, texts.map { GalleryTag(it, PowerStatus.Active, VoteStatus.None) })
    }
    val language = tags.firstOrNull { (ns, text) -> ns == TagNamespace.Language && text != "translated" && text != "rewrite" }
        ?.second?.replaceFirstChar { it.uppercase() }
    val comments = comment?.takeIf { it.isNotBlank() }?.let {
        listOf(
            GalleryComment(
                id = 0,
                score = 0,
                editable = false,
                voteUpAble = false,
                voteUpEd = false,
                voteDownAble = false,
                voteDownEd = false,
                uploader = true,
                voteState = null,
                time = posted * 1000,
                user = uploader,
                comment = it.escapeHtml().replace("\n", "<br>"),
                lastEdited = 0,
            ),
        )
    }.orEmpty()
    return GalleryDetail(
        galleryInfo = base,
        language = language,
        size = filesize.takeIf { it > 0 }?.let(FileUtils::humanReadableByteCount),
        tagGroups = tagGroups,
        comments = GalleryCommentList(comments, hasMore = false),
        previewList = emptyList(),
    ).apply { fillInfo() }
}

private fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
