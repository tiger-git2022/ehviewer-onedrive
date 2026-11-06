package com.hippo.ehviewer.wnacg

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryComment
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.GalleryTag
import com.ehviewer.core.model.GalleryTagGroup
import com.ehviewer.core.model.PowerStatus
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.model.V1GalleryPreview
import com.ehviewer.core.model.VoteStatus
import com.hippo.ehviewer.chapters.Wnacg
import com.hippo.ehviewer.client.data.fillInfo
import com.hippo.ehviewer.client.parser.ParserUtils

// Fork: the details of an album of 紳士漫畫, see WnacgGalleries.kt

/** The details of album [gid] from its page. [info] is the album as listed, for what its page does not tell. */
suspend fun wnacgDetail(gid: Long, info: GalleryInfo?): GalleryDetail {
    val aid = -gid
    val album = Wnacg.album(aid)
    val created = album.created.takeIf { it > 0 }?.let(ParserUtils::formatDate)
    val base = BaseGalleryInfo(
        gid = gid,
        token = WNACG_TOKEN,
        title = album.title.ifBlank { info?.title },
        thumbKey = album.cover ?: info?.thumbKey ?: album.thumbnails.firstOrNull()?.second,
        category = if (album.category > 0) wnacgCategory(album.category) else info?.category ?: wnacgCategory(0),
        // The list tells the time to the minute, the page only the day
        posted = info?.posted ?: created,
        uploader = album.uploader,
        rating = 0f,
        pages = album.pages.takeIf { it > 0 } ?: info?.pages ?: album.thumbnails.size,
        favoriteSlot = info?.favoriteSlot ?: GalleryInfo.NOT_FAVORITED,
    )
    val tagGroups = album.tags.takeIf { it.isNotEmpty() }?.let { tags ->
        listOf(GalleryTagGroup(TagNamespace.Temp, tags.map { GalleryTag(it, PowerStatus.Active, VoteStatus.None) }))
    }.orEmpty()
    val language = when (wnacgLanguage(album.category)) {
        "ZH" -> "Chinese"
        "EN" -> "English"
        "JA" -> "Japanese"
        else -> info?.simpleLanguage?.let { if (it == "ZH") "Chinese" else if (it == "EN") "English" else null }
    }
    val description = album.description.takeIf { it.isNotBlank() }?.let { text ->
        wnacgComment(Wnacg.Comment(-1, album.uploader.orEmpty(), album.created, text, 0), uploader = true)
    }
    val comments = listOfNotNull(description) + album.comments.map { wnacgComment(it) }
    return GalleryDetail(
        galleryInfo = base,
        language = language,
        tagGroups = tagGroups,
        comments = GalleryCommentList(comments, hasMore = album.commentCount > album.comments.size),
        previewList = wnacgPreviews(album.thumbnails),
    ).apply {
        fillInfo()
        // Its language is the one of its category, also for a title that tells another
        if (language == "Japanese") simpleLanguage = null
    }
}

/** All the comments of album [gid], the description of its uploader first when [detail] has it. */
suspend fun wnacgComments(gid: Long, detail: GalleryDetail): GalleryCommentList {
    val description = detail.comments.comments.filter { it.uploader }
    return GalleryCommentList(description + Wnacg.comments(-gid).map { wnacgComment(it) }, hasMore = false)
}

/** Page [index] (from 0) of the previews of album [gid]. */
suspend fun wnacgPreviewPage(gid: Long, index: Int): List<GalleryPreview> = wnacgPreviews(Wnacg.thumbnails(-gid, index + 1))

private fun wnacgPreviews(thumbnails: List<Pair<Int, String>>) = thumbnails.map { (page, url) ->
    // The token marks a preview of the site, see coil/Extensions.kt
    V1GalleryPreview(url = url, position = page - 1, pToken = WNACG_TOKEN)
}

private fun wnacgComment(comment: Wnacg.Comment, uploader: Boolean = false) = GalleryComment(
    id = comment.id,
    score = comment.score,
    editable = false,
    voteUpAble = false,
    voteUpEd = false,
    voteDownAble = false,
    voteDownEd = false,
    uploader = uploader,
    voteState = null,
    time = comment.time,
    user = comment.user,
    comment = comment.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>"),
    lastEdited = 0,
)
