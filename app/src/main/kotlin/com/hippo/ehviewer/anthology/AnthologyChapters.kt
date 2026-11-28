package com.hippo.ehviewer.anthology

import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.GalleryTag
import com.ehviewer.core.model.GalleryTagGroup
import com.ehviewer.core.model.PowerStatus
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.model.V1GalleryPreview
import com.ehviewer.core.model.V2GalleryPreview
import com.ehviewer.core.model.VoteStatus
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.fillInfo
import com.hippo.ehviewer.ui.screen.detailCache
import com.hippo.ehviewer.wnacg.isWnacg
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.tarsin.coroutines.runSuspendCatching
import splitties.init.appCtx

/**
 * Fork: splits a multi-artist anthology or magazine with a table-of-contents comment ([AnthologyToc]) into standalone
 * virtual chapter galleries. Each chapter gets a deterministic negative [gid] losslessly encoding `(parentGid, first,
 * last)`, so Room (`LocalFavorites`, `Downloads`, `Progress`), OneDrive sync, `AutoDownload`, and `ChapterCompare` all
 * treat it as an independent gallery without changing any database schema.
 */
const val CHAPTER_THUMB_PREFIX = "ehv-chapter-thumb://"

private const val CHAPTER_GID_TAG = 1L shl 52
const val MAX_CHAPTER_PARENT_GID = (1L shl 26) - 1
const val MAX_CHAPTER_PAGE = (1 shl 13) - 1

data class ChapterSlice(
    val gid: Long,
    val parentGid: Long,
    /** 1-based inclusive start page in the parent anthology. */
    val first: Int,
    /** 1-based inclusive end page in the parent anthology. */
    val last: Int,
) {
    val pageCount: Int get() = last - first + 1
    val parentFirstIndex: Int get() = first - 1
    val parentLastIndex: Int get() = last - 1
}

data class ChapterThumbRef(
    val parentGid: Long,
    val parentToken: String,
    /** 1-based page in the parent anthology. */
    val page: Int,
)

data class AnthologyChapterItem(
    val index: Int,
    val entry: TocEntry,
    val slice: ChapterSlice,
    val info: BaseGalleryInfo,
)

fun isAnthologyChapter(gid: Long): Boolean = AnthologyChapters.decode(gid) != null

val GalleryInfo.isAnthologyChapter: Boolean get() = isAnthologyChapter(gid)

@Serializable
private data class AnthologyCheckedData(
    val byParent: Map<Long, Set<Long>> = emptyMap(),
    val onlyUnchecked: Boolean = false,
)

object AnthologyChapters {
    private const val TAG = "AnthologyChapters"
    private const val PREVIEW_PAGE_SIZE = 20
    private const val CHECKED_FILE = "anthology_checked.json"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private val fileMutex = Mutex()
    private val previewMutex = Mutex()
    private val previewGate = Semaphore(3)

    // "(gid/pageIndex)" -> (totalPages, previews)
    private val previewCache = ConcurrentHashMap<String, Pair<Int, List<GalleryPreview>>>()
    private val fullCommentsFetched = ConcurrentHashMap.newKeySet<Long>()
    private val parentTitles = ConcurrentHashMap<Long, String>()
    private val parentDetails = ConcurrentHashMap<Long, GalleryDetail>()

    private val checkedFile get() = File(appCtx.filesDir, CHECKED_FILE)

    private val mutableCheckedByParent = MutableStateFlow<Map<Long, Set<Long>>>(emptyMap())
    val checkedByParent: StateFlow<Map<Long, Set<Long>>> = mutableCheckedByParent.asStateFlow()

    private val mutableOnlyUnchecked = MutableStateFlow(false)
    val onlyUnchecked: StateFlow<Boolean> = mutableOnlyUnchecked.asStateFlow()

    @Volatile
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                if (checkedFile.exists()) {
                    val data = json.decodeFromString(AnthologyCheckedData.serializer(), checkedFile.readText())
                    mutableCheckedByParent.value = data.byParent
                    mutableOnlyUnchecked.value = data.onlyUnchecked
                }
            }.onFailure { logcat(TAG, it) }
            loaded = true
        }
    }

    /** Encodes `(parentGid, first, last)` (1-based inclusive) into a negative `Long` gid, or null if out of bounds. */
    fun encodeGid(parentGid: Long, first: Int, last: Int): Long? {
        if (parentGid !in 1..MAX_CHAPTER_PARENT_GID) return null
        if (first !in 1..MAX_CHAPTER_PAGE || last !in first..MAX_CHAPTER_PAGE) return null
        val packed = CHAPTER_GID_TAG or (parentGid shl 26) or (first.toLong() shl 13) or last.toLong()
        return -packed
    }

    /** Decodes a virtual chapter [gid] back into its [ChapterSlice], or null for any other gid. */
    fun decode(gid: Long): ChapterSlice? {
        if (gid > -CHAPTER_GID_TAG || gid == Long.MIN_VALUE) return null
        val raw = -gid
        if ((raw and CHAPTER_GID_TAG) == 0L || (raw ushr 53) != 0L) return null
        val parentGid = (raw ushr 26) and MAX_CHAPTER_PARENT_GID
        val first = ((raw ushr 13) and MAX_CHAPTER_PAGE.toLong()).toInt()
        val last = (raw and MAX_CHAPTER_PAGE.toLong()).toInt()
        if (parentGid <= 0L || first < 1 || last < first) return null
        return ChapterSlice(gid, parentGid, first, last)
    }

    fun thumbKeyOf(parentGid: Long, parentToken: String, page: Int): String =
        "${CHAPTER_THUMB_PREFIX}${parentGid}/${parentToken}/${page.coerceAtLeast(1)}.jpg"

    fun parseThumbKey(keyOrUrl: String): ChapterThumbRef? {
        if (!keyOrUrl.startsWith(CHAPTER_THUMB_PREFIX)) return null
        val body = keyOrUrl.removePrefix(CHAPTER_THUMB_PREFIX).removeSuffix(".jpg")
        val parts = body.split('/')
        if (parts.size != 3) return null
        val parentGid = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val token = parts[1].takeIf { it.isNotBlank() } ?: return null
        val page = parts[2].toIntOrNull()?.takeIf { it >= 1 } ?: return null
        return ChapterThumbRef(parentGid, token, page)
    }

    /** Default (romanized artist) and Japanese (written artist) titles for a [TocEntry]. */
    fun formatTitles(entry: TocEntry): Pair<String, String> {
        val written = entry.names.firstOrNull { !it.latin }?.text ?: entry.names.firstOrNull()?.text
        val latin = entry.names.firstOrNull { it.latin }?.text ?: written
        val workTitle = entry.title?.takeIf { it.isNotBlank() }
        val rangeLabel = "P.${entry.first}–${entry.last}"
        val jpn = when {
            written != null && workTitle != null -> "[$written] $workTitle"
            written != null -> "[$written] $rangeLabel"
            workTitle != null -> workTitle
            else -> rangeLabel
        }
        val def = when {
            latin != null && workTitle != null -> "[$latin] $workTitle"
            latin != null -> "[$latin] $rangeLabel"
            else -> jpn
        }
        return def to jpn
    }

    fun uploaderLabel(index: Int, entry: TocEntry): String {
        val written = entry.names.firstOrNull { !it.latin }?.text ?: entry.names.firstOrNull()?.text
        val pages = if (entry.first == entry.last) "P.${entry.first}" else "P.${entry.first}–${entry.last}"
        return if (written != null) "#${index + 1} · $written ($pages)" else "#${index + 1} · $pages"
    }

    /** Whether [item] matches [query] on artist names, titles, index, or page numbers. */
    fun matchesFilter(item: AnthologyChapterItem, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val lower = q.lowercase()
        if (item.info.title?.lowercase()?.contains(lower) == true) return true
        if (item.info.titleJpn?.lowercase()?.contains(lower) == true) return true
        if (item.entry.names.any { it.text.lowercase().contains(lower) }) return true
        if (item.entry.label.lowercase().contains(lower)) return true
        if ("#${item.index + 1}" == lower) return true
        q.toIntOrNull()?.let { page -> if (page in item.slice.first..item.slice.last) return true }
        return false
    }

    private fun resolveArtistTags(entry: TocEntry): List<Pair<TagNamespace, String>> {
        if (!EhTagDatabase.initialized) return emptyList()
        val found = LinkedHashSet<Pair<TagNamespace, String>>()
        entry.names.forEach { name ->
            runCatching {
                EhTagDatabase.findTag(TagNamespace.Artist, name.text)?.let { found += TagNamespace.Artist to it }
                    ?: EhTagDatabase.findTag(TagNamespace.Group, name.text)?.let { found += TagNamespace.Group to it }
            }
        }
        return found.toList()
    }

    private fun languageTagsOf(parent: GalleryDetail): List<String> =
        parent.tagGroups
            .filter { it.namespace == TagNamespace.Language }
            .flatMap { group -> group.tags.map { "${TagNamespace.Language.value}:${it.text}" } }

    /** Builds the list of virtual chapter items for [parent] and its parsed [contents]. */
    fun buildItems(
        parent: GalleryDetail,
        contents: AnthologyContents,
        localFavorites: Set<Long> = emptySet(),
        toDownload: Set<Long> = emptySet(),
    ): List<AnthologyChapterItem> {
        if (parentDetails.size >= 8 && !parentDetails.containsKey(parent.gid)) parentDetails.clear()
        parentDetails[parent.gid] = parent
        parentTitles[parent.gid] = EhUtils.getSuitableTitle(parent)
        val langTags = languageTagsOf(parent)
        return contents.entries.mapIndexedNotNull { index, entry ->
            val gid = encodeGid(parent.gid, entry.first, entry.last) ?: return@mapIndexedNotNull null
            val slice = ChapterSlice(gid, parent.gid, entry.first, entry.last)
            val (defaultTitle, jpnTitle) = formatTitles(entry)
            val artistTags = resolveArtistTags(entry).map { (ns, tag) -> "${ns.value}:$tag" }
            val simpleTags = (artistTags + langTags).distinct()
            val isLocalFav = gid in localFavorites
            val favName = if (isLocalFav) FavoriteFolders.localFolderName(gid, toDownload) else null
            val info = BaseGalleryInfo(
                gid = gid,
                token = parent.token,
                title = defaultTitle,
                titleJpn = jpnTitle,
                thumbKey = thumbKeyOf(parent.gid, parent.token, entry.first),
                category = parent.category,
                posted = parent.posted,
                uploader = uploaderLabel(index, entry),
                rating = parent.rating,
                simpleTags = simpleTags.ifEmpty { null },
                pages = slice.pageCount,
                simpleLanguage = parent.simpleLanguage,
                favoriteSlot = if (isLocalFav) GalleryInfo.LOCAL_FAVORITED else GalleryInfo.NOT_FAVORITED,
                favoriteName = favName,
            ).apply {
                if (simpleLanguage == null) generateSLang()
            }
            AnthologyChapterItem(index, entry, slice, info)
        }
    }

    fun parentTitleOf(parentGid: Long): String? =
        parentTitles[parentGid] ?: detailCache[parentGid]?.let(EhUtils::getSuitableTitle)

    fun pageUrl(gid: Long, token: String): String {
        val slice = decode(gid) ?: return EhUrl.getGalleryDetailUrl(gid, token)
        val pageIndex = slice.parentFirstIndex / PREVIEW_PAGE_SIZE
        return EhUrl.getGalleryDetailUrl(slice.parentGid, token, pageIndex)
    }

    /**
     * Returns the parsed [AnthologyContents] of [detail], fetching all comments (`hc=1`) once in the background when
     * the initial comments had none and the gallery has hidden comments that may hold a TOC.
     */
    suspend fun ensureContents(detail: GalleryDetail, forceAllComments: Boolean = false): AnthologyContents? {
        ensureLoaded()
        AnthologyMatches.contentsOf(detail.gid, detail.pages, detail.comments.comments)?.let { return it }
        if (!detail.comments.hasMore || isWnacg(detail.gid) || isAnthologyChapter(detail.gid)) return null
        if (!forceAllComments && !mayHaveHiddenToc(detail)) return null
        if (!forceAllComments && !fullCommentsFetched.add(detail.gid)) return null
        val full = runSuspendCatching {
            val url = EhUrl.getGalleryDetailUrl(detail.gid, detail.token, allComment = true)
            EhEngine.getGalleryDetail(url)
        }.onFailure {
            fullCommentsFetched.remove(detail.gid)
            logcat(TAG, it)
        }.getOrNull() ?: return null
        detail.comments = full.comments
        return AnthologyMatches.contentsOf(detail.gid, detail.pages, full.comments.comments)
    }

    private fun mayHaveHiddenToc(detail: GalleryDetail): Boolean {
        if (detail.pages >= 60) return true
        val artistCount = detail.tagGroups.filter { it.namespace == TagNamespace.Artist }.sumOf { it.tags.size }
        if (artistCount >= 2) return true
        return detail.tagGroups.any { group ->
            group.namespace == TagNamespace.Other && group.tags.any { tag ->
                tag.text.equals("anthology", ignoreCase = true) ||
                    tag.text.equals("compilation", ignoreCase = true) ||
                    tag.text.equals("tankoubon", ignoreCase = true)
            }
        }
    }

    /** Single preview for 1-based [page] of [parentGid], cached across chapters on the same preview sheet. */
    suspend fun previewOf(parentGid: Long, parentToken: String, page: Int): GalleryPreview? {
        val cachedDetail = detailCache[parentGid] ?: parentDetails[parentGid]
        if (cachedDetail != null && cachedDetail.previewList.isNotEmpty()) {
            val perPage = cachedDetail.previewList.size
            if ((page - 1) / perPage == 0) {
                cachedDetail.previewList.firstOrNull { it.position == page - 1 }?.let { return it }
            }
        }
        val firstSheet = fetchPreviewSheet(parentGid, parentToken, 0, cachedDetail) ?: return null
        val perPage = firstSheet.second.size.takeIf { it > 0 } ?: return null
        val targetSheetIndex = (page - 1) / perPage
        val sheet = if (targetSheetIndex == 0) {
            firstSheet.second
        } else {
            fetchPreviewSheet(parentGid, parentToken, targetSheetIndex, cachedDetail)?.second ?: return null
        }
        return sheet.firstOrNull { it.position == page - 1 }
    }

    private suspend fun fetchPreviewSheet(
        parentGid: Long,
        parentToken: String,
        sheetIndex: Int,
        cachedDetail: GalleryDetail? = detailCache[parentGid] ?: parentDetails[parentGid],
    ): Pair<Int, List<GalleryPreview>>? {
        if (sheetIndex == 0 && cachedDetail != null && cachedDetail.previewList.isNotEmpty()) {
            return cachedDetail.pages to cachedDetail.previewList
        }
        val key = "$parentGid/$sheetIndex"
        previewCache[key]?.let { return it }
        return previewMutex.withLock {
            previewCache[key]?.let { return@withLock it }
            runSuspendCatching {
                previewGate.withPermit {
                    val result = EhEngine.getPreviewList(EhUrl.getGalleryDetailUrl(parentGid, parentToken, sheetIndex))
                    (result.total to result.previews).also { previewCache[key] = it }
                }
            }.onFailure { logcat(TAG, it) }.getOrNull()
        }
    }

    /** Previews for local positions `[startLocal..endLocal]` (0-based within [slice]) remapped to `0 until slice.pageCount`. */
    suspend fun slicePreviews(
        slice: ChapterSlice,
        token: String,
        startLocal: Int,
        endLocal: Int,
        parentDetail: GalleryDetail? = detailCache[slice.parentGid] ?: parentDetails[slice.parentGid],
    ): List<GalleryPreview> {
        val clampedEnd = endLocal.coerceAtMost(slice.pageCount - 1)
        if (startLocal > clampedEnd) return emptyList()
        val firstSheet = fetchPreviewSheet(slice.parentGid, token, 0, parentDetail) ?: return emptyList()
        val perPage = firstSheet.second.size.takeIf { it > 0 } ?: return emptyList()
        val parentStart = slice.parentFirstIndex + startLocal
        val parentEnd = slice.parentFirstIndex + clampedEnd
        val firstSheetIdx = parentStart / perPage
        val lastSheetIdx = parentEnd / perPage
        val byParentPos = HashMap<Int, GalleryPreview>()
        for (sheetIdx in firstSheetIdx..lastSheetIdx) {
            val sheet = if (sheetIdx == 0) firstSheet.second else fetchPreviewSheet(slice.parentGid, token, sheetIdx, parentDetail)?.second.orEmpty()
            sheet.forEach { byParentPos[it.position] = it }
        }
        return (startLocal..clampedEnd).mapNotNull { localPos ->
            val p = byParentPos[slice.parentFirstIndex + localPos] ?: return@mapNotNull null
            when (p) {
                is V1GalleryPreview -> p.copy(position = localPos)
                is V2GalleryPreview -> p.copy(position = localPos)
            }
        }
    }

    /** Used by `GalleryDetailContent.collectPreviewItems` for chapters longer than one preview page. */
    suspend fun previewPage(gid: Long, token: String, page: Int, pageSize: Int): List<GalleryPreview> {
        val slice = decode(gid) ?: return emptyList()
        val start = page * pageSize
        val end = (start + pageSize - 1).coerceAtMost(slice.pageCount - 1)
        return slicePreviews(slice, token, start, end)
    }

    fun missingPreview(position: Int): GalleryPreview = V1GalleryPreview(url = "", position = position, pToken = "")

    /** Builds the synthetic [GalleryDetail] when opening a virtual single chapter in `GalleryDetailScreen`. */
    suspend fun detail(gid: Long, token: String, listedInfo: GalleryInfo?): GalleryDetail {
        ensureLoaded()
        val slice = checkNotNull(decode(gid)) { "Not an anthology chapter: $gid" }
        val effectiveToken = token.ifBlank { listedInfo?.token.orEmpty() }
        val parentDetail = detailCache[slice.parentGid] ?: parentDetails[slice.parentGid] ?: runSuspendCatching {
            EhEngine.getGalleryDetail(EhUrl.getGalleryDetailUrl(slice.parentGid, effectiveToken)).also {
                detailCache[it.gid] = it
                if (parentDetails.size >= 8 && !parentDetails.containsKey(it.gid)) parentDetails.clear()
                parentDetails[it.gid] = it
            }
        }.getOrNull()
        parentDetail?.let { parentTitles[it.gid] = EhUtils.getSuitableTitle(it) }

        val tocContents = parentDetail?.let { ensureContents(it) }
        val matchedIndex = tocContents?.entries?.indexOfFirst { it.first == slice.first && it.last == slice.last } ?: -1
        val matchedEntry = if (matchedIndex >= 0) tocContents?.entries?.get(matchedIndex) else null

        val (computedDef, computedJpn) = matchedEntry?.let(::formatTitles) ?: (null to null)
        val localFavorited = runCatching { EhDB.containLocalFavorites(gid) }.getOrDefault(false)
        val favSlot = if (localFavorited) GalleryInfo.LOCAL_FAVORITED else GalleryInfo.NOT_FAVORITED
        val favName = if (localFavorited) FavoriteFolders.localFolderName(gid) else null

        val artistPairs = matchedEntry?.let(::resolveArtistTags).orEmpty()
        val langGroups = parentDetail?.tagGroups?.filter { it.namespace == TagNamespace.Language }.orEmpty()
        val tagGroups = buildList {
            if (artistPairs.isNotEmpty()) {
                artistPairs.groupBy({ it.first }, { it.second }).forEach { (ns, tags) ->
                    add(GalleryTagGroup(ns, tags.distinct().map { GalleryTag(it, PowerStatus.Solid, VoteStatus.None) }))
                }
            } else {
                // Reconstruct artist/group tags from simpleTags when opened cold on a synced device
                listedInfo?.simpleTags.orEmpty().mapNotNull { raw ->
                    val parts = raw.split(':', limit = 2)
                    if (parts.size == 2) TagNamespace.from(parts[0])?.takeIf { it == TagNamespace.Artist || it == TagNamespace.Group }?.let { it to parts[1] } else null
                }.groupBy({ it.first }, { it.second }).forEach { (ns, tags) ->
                    add(GalleryTagGroup(ns, tags.distinct().map { GalleryTag(it, PowerStatus.Solid, VoteStatus.None) }))
                }
            }
            addAll(langGroups)
        }

        val simpleTags = tagGroups.flatMap { g -> g.tags.map { "${g.namespace.value}:${it.text}" } }.distinct()
        val base = BaseGalleryInfo(
            gid = gid,
            token = effectiveToken,
            title = computedDef ?: listedInfo?.title ?: "P.${slice.first}–${slice.last}",
            titleJpn = computedJpn ?: listedInfo?.titleJpn ?: computedDef ?: listedInfo?.title ?: "P.${slice.first}–${slice.last}",
            thumbKey = thumbKeyOf(slice.parentGid, effectiveToken, slice.first),
            category = parentDetail?.category ?: listedInfo?.category ?: 0,
            posted = parentDetail?.posted ?: listedInfo?.posted,
            uploader = if (matchedEntry != null) uploaderLabel(matchedIndex, matchedEntry) else listedInfo?.uploader ?: "P.${slice.first}–${slice.last}",
            rating = parentDetail?.rating ?: listedInfo?.rating ?: 0f,
            simpleTags = simpleTags.ifEmpty { listedInfo?.simpleTags },
            pages = slice.pageCount,
            simpleLanguage = parentDetail?.simpleLanguage ?: listedInfo?.simpleLanguage,
            favoriteSlot = favSlot,
            favoriteName = favName,
        )

        val initialCount = slice.pageCount.coerceAtMost(PREVIEW_PAGE_SIZE)
        val previews = slicePreviews(slice, effectiveToken, 0, initialCount - 1, parentDetail)
        check(previews.isNotEmpty()) { appCtx.getString(R.string.chapter_compare_no_thumbnails) }

        return GalleryDetail(
            galleryInfo = base,
            parent = parentDetail?.let(EhUtils::getSuitableTitle),
            language = parentDetail?.language,
            tagGroups = tagGroups,
            comments = GalleryCommentList(emptyList(), hasMore = false),
            previewList = previews,
        ).apply {
            fillInfo()
            favoriteSlot = favSlot
            favoriteName = favName
            if (simpleLanguage == null && parentDetail?.simpleLanguage != null) {
                simpleLanguage = parentDetail.simpleLanguage
            }
        }
    }

    fun checkedOf(parentGid: Long): Set<Long> {
        ensureLoaded()
        return mutableCheckedByParent.value[parentGid].orEmpty()
    }

    fun markChecked(chapterGid: Long) {
        ensureLoaded()
        val slice = decode(chapterGid) ?: return
        val current = mutableCheckedByParent.value[slice.parentGid].orEmpty()
        if (chapterGid in current) return
        mutableCheckedByParent.update { map ->
            map + (slice.parentGid to (map[slice.parentGid].orEmpty() + chapterGid))
        }
        saveChecked()
    }

    fun markAllChecked(parentGid: Long, chapterGids: Collection<Long>) {
        ensureLoaded()
        val target = chapterGids.toSet()
        if (target.isEmpty()) return
        mutableCheckedByParent.update { map ->
            map + (parentGid to (map[parentGid].orEmpty() + target))
        }
        saveChecked()
    }

    fun resetChecked(parentGid: Long) {
        ensureLoaded()
        if (parentGid !in mutableCheckedByParent.value) return
        mutableCheckedByParent.update { it - parentGid }
        saveChecked()
    }

    fun setOnlyUnchecked(value: Boolean) {
        ensureLoaded()
        if (mutableOnlyUnchecked.value == value) return
        mutableOnlyUnchecked.value = value
        saveChecked()
    }

    private fun saveChecked() {
        scope.launch {
            fileMutex.withLock {
                val snapshot = AnthologyCheckedData(
                    byParent = mutableCheckedByParent.value,
                    onlyUnchecked = mutableOnlyUnchecked.value,
                )
                runCatching {
                    val temp = File(appCtx.filesDir, "$CHECKED_FILE.tmp")
                    temp.writeText(json.encodeToString(AnthologyCheckedData.serializer(), snapshot))
                    if (!temp.renameTo(checkedFile)) {
                        temp.copyTo(checkedFile, overwrite = true)
                        temp.delete()
                    }
                }.onFailure { logcat(TAG, it) }
            }
        }
    }
}
