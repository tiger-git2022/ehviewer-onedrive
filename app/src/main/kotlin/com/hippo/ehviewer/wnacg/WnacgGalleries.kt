package com.hippo.ehviewer.wnacg

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.chapters.Wnacg
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.parser.ParserUtils

// Fork: an album of 紳士漫畫 (wnacg) shows as a gallery: in the results of a search, its details, its reader and its
// download. Its gid is the album id made negative, which no gallery of E-Hentai has, so the database, the history and
// the downloads keep it as they keep the others, without a change of their schema.

/** The token of an album of 紳士漫畫, in place of the one of a gallery of E-Hentai. */
const val WNACG_TOKEN = "wnacg"

/** The gid of album [aid]. */
fun wnacgGid(aid: Long) = -aid

/** Whether [gid] is the one of an album of 紳士漫畫. */
fun isWnacg(gid: Long) = gid < -1

val GalleryInfo.isWnacg get() = isWnacg(gid)

/** The album id of a gallery of 紳士漫畫. */
val GalleryInfo.wnacgAid get() = -gid

/** The categories of the site (the N of its "cate-N") as those of E-Hentai. */
fun wnacgCategory(cate: Int): Int = when (cate) {
    5, 1, 12, 16 -> EhUtils.DOUJINSHI
    6, 9, 13, 17, 7, 10, 14, 18, 19, 20, 21 -> EhUtils.MANGA
    2 -> EhUtils.ARTIST_CG
    3 -> EhUtils.COSPLAY
    else -> EhUtils.MISC
}

/** The language its category tells ("ZH", "EN"), "JA" for Japanese, null when it does not tell. */
fun wnacgLanguage(cate: Int): String? = when (cate) {
    1, 9, 10 -> "ZH"
    12, 13, 14 -> "JA"
    16, 17, 18 -> "EN"
    else -> null
}

/** The categories of E-Hentai the site has albums of. */
const val WNACG_CATEGORIES = EhUtils.DOUJINSHI or EhUtils.MANGA or EhUtils.ARTIST_CG or EhUtils.COSPLAY or EhUtils.MISC

fun Wnacg.Album.toGalleryInfo() = BaseGalleryInfo(
    gid = wnacgGid(aid),
    token = WNACG_TOKEN,
    title = title,
    thumbKey = cover,
    category = wnacgCategory(category),
    posted = created.takeIf { it > 0 }?.let(ParserUtils::formatDate),
    rating = 0f,
    pages = pages,
).apply {
    generateSLang()
    when (wnacgLanguage(category)) {
        "ZH" -> simpleLanguage = "ZH"
        "EN" -> simpleLanguage = "EN"
        "JA" -> simpleLanguage = null
    }
}
