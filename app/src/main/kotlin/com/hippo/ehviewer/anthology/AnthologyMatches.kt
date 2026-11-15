package com.hippo.ehviewer.anthology

import android.text.Spanned
import android.text.style.URLSpan
import androidx.core.text.getSpans
import androidx.core.text.parseAsHtml
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryComment
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.chapters.ChapterSource
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.FavListUrlBuilder
import com.hippo.ehviewer.wnacg.isWnacg
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splitties.init.appCtx

/** Fork: the contents list of a gallery: the comment [commentId] listing them and its [entries]. */
data class AnthologyContents(val commentId: Long, val entries: List<TocEntry>)

/** Fork: the favorites found for the entries of a contents list, by [TocEntry.key]. */
@Serializable
data class AnthologyMatchResult(
    val gid: Long,
    val matches: Map<String, List<ChapterSource>>,
    val time: Long = System.currentTimeMillis(),
    val version: Int = VERSION,
) {
    companion object {
        const val VERSION = 1
    }
}

sealed interface AnthologyMatchState {
    /** [done] of the [total] names searched. */
    data class Running(val done: Int, val total: Int) : AnthologyMatchState
    data class Done(val result: AnthologyMatchResult) : AnthologyMatchState
    data class Failed(val message: String) : AnthologyMatchState
}

/**
 * Fork: the works of an anthology or a magazine issue the user has as favorites of their own, found by the contents
 * list a comment gives ([AnthologyToc]): the favorites of each artist (romanized and as written, the cloud ones
 * searched on the site and the local ones), the one of the same title ([AnthologyTitles]). The reverse of the
 * tankoubon compare of chapters/ChapterCompare.kt, by titles instead of thumbnails.
 */
object AnthologyMatches {
    private const val TAG = "AnthologyMatches"

    /** The pages of favorites searched at most per name. */
    private const val MAX_FAVORITE_PAGES = 3

    /** Between two searches, not to be taken for a crawler. */
    private const val SEARCH_GAP = 400L

    /** Found again after a day: favorites are added since. */
    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    /** The favorites of a name searched lately, kept a while: the artists of one magazine are those of the next. */
    private const val NAME_CACHE_MS = 15 * 60 * 1000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private val resultDir get() = File(appCtx.filesDir, "anthology_match")
    private val jobs = HashMap<Long, Job>()
    private val searchGate = Mutex()
    private val byName = ConcurrentHashMap<String, Pair<Long, List<GalleryInfo>>>()
    private val contentsCache = ConcurrentHashMap<Long, Pair<String, AnthologyContents?>>()

    private val mutableStates = MutableStateFlow<Map<Long, AnthologyMatchState>>(emptyMap())
    val states: StateFlow<Map<Long, AnthologyMatchState>> = mutableStates.asStateFlow()

    /** The text of a comment as shown, with its links, see [AnthologyToc.parse]. */
    fun textOf(comment: GalleryComment): Pair<String, List<TextLink>> = textOf(comment.comment.parseAsHtml())

    fun textOf(spanned: Spanned): Pair<String, List<TextLink>> {
        val links = spanned.getSpans<URLSpan>().map { TextLink(spanned.getSpanStart(it), spanned.getSpanEnd(it), it.url) }
        return spanned.toString() to links
    }

    /** The contents list among [comments] of the gallery [gid] of [pages] pages: the longest one. */
    fun contentsOf(gid: Long, pages: Int, comments: List<GalleryComment>): AnthologyContents? {
        val signature = comments.joinToString(",") { "${it.id}:${it.comment.length}" } + "/$pages"
        contentsCache[gid]?.takeIf { it.first == signature }?.let { return it.second }
        val found = comments.mapNotNull { comment ->
            val (text, links) = textOf(comment)
            runCatching { AnthologyToc.parse(text, links, gid, pages) }.onFailure { logcat(TAG, it) }.getOrNull()?.let { AnthologyContents(comment.id, it) }
        }.maxByOrNull { it.entries.size }
        contentsCache[gid] = signature to found
        return found
    }

    /** The result kept for [gid], loaded into [states] once; null when there is none or it is old. */
    private fun load(gid: Long): AnthologyMatchResult? {
        (mutableStates.value[gid] as? AnthologyMatchState.Done)?.let { return it.result }
        val file = File(resultDir, "$gid.json")
        val result = runCatching { json.decodeFromString(AnthologyMatchResult.serializer(), file.readText()) }.getOrNull() ?: return null
        if (result.version < AnthologyMatchResult.VERSION) return null
        mutableStates.update { if (gid in it) it else it + (gid to AnthologyMatchState.Done(result)) }
        return result
    }

    /** Finds the favorites of the [contents] of [gid]; again when [force], or when what was kept is old or of other entries. */
    fun start(gid: Long, contents: AnthologyContents, force: Boolean = false) {
        synchronized(jobs) {
            if (jobs[gid]?.isActive == true) return
            val kept = load(gid)
            val keys = contents.entries.map { it.key }.toSet()
            if (!force && kept != null && System.currentTimeMillis() - kept.time < KEEP_MS && keys.all { it in kept.matches }) return
            jobs[gid] = scope.launch {
                try {
                    val result = match(gid, contents.entries)
                    runCatching {
                        resultDir.mkdirs()
                        File(resultDir, "$gid.json").writeText(json.encodeToString(AnthologyMatchResult.serializer(), result))
                    }.onFailure { logcat(TAG, it) }
                    set(gid, AnthologyMatchState.Done(result))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(TAG, e)
                    // What was found before stays shown
                    if (kept == null) set(gid, AnthologyMatchState.Failed(e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    private fun set(gid: Long, state: AnthologyMatchState) = mutableStates.update { it + (gid to state) }

    private suspend fun match(gid: Long, entries: List<TocEntry>): AnthologyMatchResult {
        // An entry of no names (a title alone) is searched by its title
        val queries = entries.filter { it.title != null }.associateWith { entry ->
            entry.names.map { it.text }.ifEmpty { listOf(entry.title!!) }.distinctBy { it.lowercase() }
        }
        val names = queries.values.flatten().distinctBy { it.lowercase() }
        val kept = load(gid)
        if (kept == null) set(gid, AnthologyMatchState.Running(0, names.size))
        val local = runCatching { EhDB.getLocalFavoritesWithGallery().map { it.second } }.onFailure { logcat(TAG, it) }.getOrDefault(emptyList())
        val found = HashMap<String, List<GalleryInfo>>()
        names.forEachIndexed { index, name ->
            found[name.lowercase()] = favoritesOf(name, local)
            if (kept == null) set(gid, AnthologyMatchState.Running(index + 1, names.size))
        }
        // Japanese titles for those without, to tell the work by its title as listed
        val all = found.values.flatten().distinctBy { it.gid }.filter { it.gid != gid && !isWnacg(it.gid) }
        val withoutJpn = all.filter { it.titleJpn.isNullOrBlank() }
        if (withoutJpn.isNotEmpty()) runCatching { EhEngine.fillGalleryListByApi(withoutJpn, EhUrl.referer) }.onFailure { logcat(TAG, it) }
        val matches = entries.associate { entry ->
            val title = entry.title
            val sources = if (title == null) {
                emptyList()
            } else {
                queries[entry].orEmpty().flatMap { found[it.lowercase()].orEmpty() }.distinctBy { it.gid }
                    .filter { it.gid != gid && !isWnacg(it.gid) }
                    .filter { info -> listOfNotNull(info.title, info.titleJpn).any { AnthologyTitles.matches(title, it) } }
                    .map { ChapterSource(it.gid, it.token, it.titleJpn?.takeIf(String::isNotBlank) ?: it.title ?: it.gid.toString(), it.thumbKey) }
            }
            entry.key to sources
        }
        return AnthologyMatchResult(gid, matches)
    }

    /** The favorites of [name]: the cloud ones searched on the site, the local ones by their titles and tags. */
    private suspend fun favoritesOf(name: String, local: List<GalleryInfo>): List<GalleryInfo> {
        val key = name.lowercase()
        byName[key]?.takeIf { System.currentTimeMillis() - it.first < NAME_CACHE_MS }?.let { return it.second }
        val found = LinkedHashMap<Long, GalleryInfo>()
        if (Settings.hasSignedIn.value) {
            searchGate.withLock {
                runCatching {
                    val builder = FavListUrlBuilder(FavListUrlBuilder.FAV_CAT_ALL, name)
                    repeat(MAX_FAVORITE_PAGES) {
                        val result = EhEngine.getFavorites(builder.build())
                        delay(SEARCH_GAP)
                        result.galleryInfoList.forEach { found.putIfAbsent(it.gid, it) }
                        val next = result.next ?: return@runCatching
                        builder.setIndex(next, true)
                    }
                }.onFailure { if (it is CancellationException) throw it else logcat(TAG, it) }
            }
        }
        local.filter { it.has(key) }.forEach { found.putIfAbsent(it.gid, it) }
        val list = found.values.map {
            BaseGalleryInfo(gid = it.gid, token = it.token, title = it.title, titleJpn = it.titleJpn, thumbKey = it.thumbKey, pages = it.pages)
        }
        byName[key] = System.currentTimeMillis() to list
        return list
    }

    private fun GalleryInfo.has(name: String) = title?.lowercase()?.contains(name) == true || titleJpn?.lowercase()?.contains(name) == true ||
        simpleTags?.any { it.lowercase().substringAfter(':') == name } == true
}
