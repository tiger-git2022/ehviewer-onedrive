package com.hippo.ehviewer.ui.settings

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import arrow.core.left
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.translate.CommentTranslateApi
import com.hippo.ehviewer.translate.CommentTranslations
import com.hippo.ehviewer.translate.TargetLanguage
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.tools.awaitInputText
import com.hippo.ehviewer.ui.tools.awaitSelectItem
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.DropdownListPreference
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.snackbar
import moe.tarsin.string

// A Korean comment for the test: "I uploaded the Korean version too. Thanks for always cheering us on!"
private const val TEST_SENTENCE = "한글판도 올렸습니다. 항상 응원해 주셔서 감사합니다!"

/** Fork: Settings → EH → Comment translation, see translate/CommentTranslations.kt. */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.CommentTranslateScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.comment_translate)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        Column(modifier = Modifier.padding(it).nestedScroll(scrollBehavior.nestedScrollConnection).verticalScroll(rememberScrollState())) {
            SwitchPreference(
                title = stringResource(id = R.string.comment_translate_auto),
                summary = stringResource(id = R.string.comment_translate_auto_summary),
                state = Settings.commentTranslateAuto.asMutableState(),
            )
            val target = Settings.commentTranslateTarget.asMutableState()
            val targets = TargetLanguage.entries.associate { language -> language.code to language.displayName }
            DropdownListPreference(
                state = target,
                items = targets,
                title = { Text(text = stringResource(id = R.string.comment_translate_target)) },
                summary = { Text(text = TargetLanguage.of(target.value).displayName) },
            )
            SwitchPreference(
                title = stringResource(id = R.string.comment_translate_in_detail),
                summary = stringResource(id = R.string.comment_translate_in_detail_summary),
                state = Settings.commentTranslateInDetail.asMutableState(),
            )

            Subtitle(stringResource(id = R.string.comment_translate_service))
            val notSet = stringResource(id = R.string.comment_translate_not_set)
            val apiUrl by Settings.commentTranslateApiUrl.collectAsState()
            val apiUrlTitle = stringResource(id = R.string.comment_translate_api_url)
            val apiUrlHint = stringResource(id = R.string.comment_translate_api_url_hint)
            Preference(title = apiUrlTitle, summary = apiUrl.ifBlank { apiUrlHint }) {
                launch {
                    val value = awaitInputText(initial = apiUrl, title = apiUrlTitle, hint = apiUrlHint).trim()
                    Settings.commentTranslateApiUrl.value = value
                    CommentTranslations.forgetFailures()
                }
            }
            val apiKey by Settings.commentTranslateApiKey.collectAsState()
            val apiKeyTitle = stringResource(id = R.string.comment_translate_api_key)
            val keySummary = if (apiKey.isBlank()) notSet else stringResource(id = R.string.comment_translate_key_set, apiKey.takeLast(4))
            Preference(title = apiKeyTitle, summary = keySummary + "\n" + stringResource(id = R.string.comment_translate_api_key_summary)) {
                launch {
                    val value = awaitInputText(initial = apiKey, title = apiKeyTitle).trim()
                    Settings.commentTranslateApiKey.value = value
                    CommentTranslations.forgetFailures()
                }
            }
            val model by Settings.commentTranslateModel.collectAsState()
            val modelTitle = stringResource(id = R.string.comment_translate_model)
            val modelHint = stringResource(id = R.string.comment_translate_model_hint)
            // The models the API offers (GET /models) to pick from, or typed in by hand when it lists none
            var fetchingModels by remember { mutableStateOf(false) }
            val manualInput = stringResource(id = R.string.comment_translate_model_manual)
            Preference(
                title = modelTitle,
                summary = if (fetchingModels) stringResource(id = R.string.comment_translate_model_fetching) else model.ifBlank { modelHint },
            ) {
                if (fetchingModels) return@Preference
                launch {
                    val config = CommentTranslations.config
                    val models = if (config.apiUrl.isBlank()) {
                        emptyList()
                    } else {
                        fetchingModels = true
                        runSuspendCatching { CommentTranslateApi.listModels(config) }
                            .onFailure { e -> snackbar(string(R.string.comment_translate_model_fetch_failed, e.message ?: e.toString())) }
                            .getOrDefault(emptyList())
                            .also { fetchingModels = false }
                    }
                    val picked = if (models.isEmpty()) {
                        null
                    } else {
                        val index = awaitSelectItem(models + manualInput, modelTitle.left(), selected = models.indexOf(model))
                        models.getOrNull(index)
                    }
                    val value = picked ?: awaitInputText(initial = model, title = modelTitle, hint = modelHint).trim()
                    Settings.commentTranslateModel.value = value
                    CommentTranslations.forgetFailures()
                }
            }
            var testing by remember { mutableStateOf(false) }
            var testResult by remember { mutableStateOf<String?>(null) }
            val testSummary = when {
                testing -> stringResource(id = R.string.comment_translate_testing)
                else -> testResult ?: stringResource(id = R.string.comment_translate_test_summary)
            }
            val notConfigured = stringResource(id = R.string.comment_translate_not_configured)
            Preference(title = stringResource(id = R.string.comment_translate_test), summary = testSummary) {
                val config = CommentTranslations.config
                if (!config.isComplete) {
                    launch { snackbar(notConfigured) }
                } else if (!testing) {
                    testing = true
                    launch {
                        val item = CommentTranslateApi.Item(1, TEST_SENTENCE)
                        val result = runSuspendCatching {
                            CommentTranslateApi.translate(config, listOf(item), CommentTranslations.target)[1] ?: error("No translation in the reply")
                        }.fold(
                            onSuccess = { text -> string(R.string.comment_translate_test_success, TEST_SENTENCE, text) },
                            onFailure = { e -> string(R.string.comment_translate_test_failed, e.message ?: e.toString()) },
                        )
                        testResult = result
                        testing = false
                    }
                }
            }

            var cacheVersion by remember { mutableIntStateOf(0) }
            var cacheInfo by remember { mutableStateOf<Pair<Int, Long>?>(null) }
            LaunchedEffect(cacheVersion) {
                cacheInfo = withContext(Dispatchers.IO) { CommentTranslations.cacheInfo() }
            }
            val context = LocalContext.current
            val cleared = stringResource(id = R.string.comment_translate_cache_cleared)
            Preference(
                title = stringResource(id = R.string.comment_translate_clear_cache),
                summary = cacheInfo?.let { (count, bytes) ->
                    stringResource(id = R.string.comment_translate_cache_summary, count, Formatter.formatFileSize(context, bytes))
                },
            ) {
                launch {
                    withContext(Dispatchers.IO) { CommentTranslations.clearCache() }
                    cacheVersion++
                    snackbar(cleared)
                }
            }
        }
    }
}

@Composable
private fun Subtitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}
