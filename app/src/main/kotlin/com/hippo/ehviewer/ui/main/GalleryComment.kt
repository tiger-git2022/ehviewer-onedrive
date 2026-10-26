package com.hippo.ehviewer.ui.main

import android.text.Html
import android.text.TextUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.parseAsHtml
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryComment
import com.hippo.ehviewer.translate.CommentTranslation
import com.hippo.ehviewer.translate.CommentTranslations
import com.hippo.ehviewer.ui.legacy.CoilImageGetter
import com.hippo.ehviewer.ui.legacy.LinkifyTextView
import com.hippo.ehviewer.util.ReadableTime

@Composable
fun GalleryCommentCard(
    modifier: Modifier = Modifier,
    comment: GalleryComment,
    onCardClick: () -> Unit,
    onUserClick: () -> Unit,
    onUrlClick: (String) -> Unit,
    maxLines: Int = Int.MAX_VALUE,
    ellipsis: Boolean = false,
    processComment: @Composable (GalleryComment, Html.ImageGetter) -> CharSequence = { c, ig -> c.comment.parseAsHtml(imageGetter = ig) },
    // Fork: the translation of the comment (see translate/CommentTranslations.kt), shown in place of it unless showOriginal
    translation: CommentTranslation? = null,
    showOriginal: Boolean = false,
    onToggleOriginal: () -> Unit = {},
    onRetryTranslation: () -> Unit = {},
) = with(comment) {
    val targetUrl = remember { mutableStateOf<String?>(null) }
    Card(
        onClick = {
            targetUrl.value?.also {
                onUrlClick(it)
                targetUrl.value = null
            } ?: onCardClick()
        },
        modifier = modifier,
    ) {
        val margin = dimensionResource(id = com.hippo.ehviewer.R.dimen.keyline_margin)
        Row(
            modifier = Modifier.padding(horizontal = margin, vertical = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ProvideTextStyle(value = MaterialTheme.typography.titleSmall) {
                val userText = if (uploader) stringResource(id = R.string.comment_user_uploader, user.orEmpty()) else user.orEmpty()
                Text(
                    text = userText,
                    modifier = Modifier.clickable(onClick = onUserClick),
                )
                Text(
                    text = ReadableTime.getTimeAgo(time),
                )
            }
        }
        val textColor = LocalContentColor.current.toArgb()
        val linkTextColor = MaterialTheme.colorScheme.primary.toArgb()
        val redrawSignal = remember { mutableStateOf(Unit, neverEqualPolicy()) }
        // Fork: the translation as the text of the comment, so the score and the edit time follow it as they do the original
        val shown = if (translation is CommentTranslation.Done && !showOriginal) comment.copy(comment = translation.text.toCommentHtml()) else comment
        val commentText = processComment(shown, CoilImageGetter { redrawSignal.value = Unit })
        AndroidView(
            factory = { context ->
                LinkifyTextView(context) { targetUrl.value = it }.apply {
                    setTextColor(textColor)
                    setLinkTextColor(linkTextColor)
                    this.maxLines = maxLines
                    if (ellipsis) ellipsize = TextUtils.TruncateAt.END
                }
            },
            modifier = Modifier.padding(start = margin, end = margin, bottom = 8.dp),
        ) { view ->
            view.text = commentText
            redrawSignal.value
        }
        if (translation != null) {
            TranslationLine(translation, showOriginal, onToggleOriginal, onRetryTranslation, Modifier.padding(start = margin, end = margin, bottom = 10.dp))
        }
    }
}

/** Fork: plain text of a translation as the HTML of a comment. */
private fun String.toCommentHtml() = TextUtils.htmlEncode(this).replace("\n", "<br>")

/**
 * Fork: below a translated comment, where it was translated from and a link to the original ("译自 韩语 · 显示原文"),
 * the translation running or failed (with a retry). Nothing for a comment in the target language.
 */
@Composable
private fun TranslationLine(
    translation: CommentTranslation,
    showOriginal: Boolean,
    onToggleOriginal: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = translation.language?.let { CommentTranslations.languageName(it) }
    val (label, action, onAction) = when (translation) {
        is CommentTranslation.NotNeeded -> return
        is CommentTranslation.Pending -> Triple(
            language?.let { stringResource(R.string.comment_translating_from, it) } ?: stringResource(R.string.comment_translating),
            null,
            null,
        )
        is CommentTranslation.Done -> if (showOriginal) {
            Triple(
                language?.let { stringResource(R.string.comment_original_in, it) } ?: stringResource(R.string.comment_original),
                stringResource(R.string.comment_show_translation),
                onToggleOriginal,
            )
        } else {
            Triple(
                language?.let { stringResource(R.string.comment_translated_from, it) } ?: stringResource(R.string.comment_translated),
                stringResource(R.string.comment_show_original),
                onToggleOriginal,
            )
        }
        is CommentTranslation.Failed -> Triple(stringResource(R.string.comment_translate_failed, translation.message), stringResource(R.string.comment_translate_retry), onRetry)
    }
    val failed = translation is CommentTranslation.Failed
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (action != null && onAction != null) {
            Text(
                text = " · ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = action,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}
