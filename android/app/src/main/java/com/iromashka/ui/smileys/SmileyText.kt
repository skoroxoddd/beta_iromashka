package com.iromashka.ui.smileys

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest

@Composable
fun SmileyText(text: String, color: Color, fontSize: TextUnit = 14.sp) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { SmileyMap.load(ctx) }

    val loader = remember {
        ImageLoader.Builder(ctx).components {
            if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
            else add(GifDecoder.Factory())
        }.build()
    }

    val (annotated, files) = remember(text) { tokenize(text) }

    val inline: Map<String, InlineTextContent> = files.associateWith { file ->
        InlineTextContent(
            placeholder = Placeholder(width = 22.sp, height = 22.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.Center)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data("file:///android_asset/smileys/$file").build(),
                imageLoader = loader,
                contentDescription = null,
                modifier = Modifier
            )
        }
    }

    Text(text = annotated, color = color, fontSize = fontSize, inlineContent = inline)
}

private fun tokenize(text: String): Pair<AnnotatedString, Set<String>> {
    // Skip smiley substitution for very long strings (e.g. inline base64 images,
    // 1.5MB+ data URIs). The naive O(text * keys) scan would otherwise freeze
    // the main thread for several seconds per recomposition.
    if (text.length > 1000) return AnnotatedString(text) to emptySet()
    // Skip for any HTML media tag — those are rendered via MediaBubble, this is a fallback path.
    val trimmed = text.trimStart()
    if (trimmed.startsWith("<img") || trimmed.startsWith("<audio") || trimmed.startsWith("<video")) {
        return AnnotatedString(text) to emptySet()
    }
    val all = SmileyMap.all()
    if (all.isEmpty()) return AnnotatedString(text) to emptySet()
    val sortedKeys = all.map { it.first }.sortedByDescending { it.length }
    val files = mutableSetOf<String>()
    val builder = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val matched = sortedKeys.firstOrNull { key ->
                key.isNotEmpty() && i + key.length <= text.length &&
                    text.regionMatches(i, key, 0, key.length, ignoreCase = false)
            }
            if (matched != null) {
                val file = SmileyMap.fileFor(matched)!!
                files.add(file)
                appendInlineContent(file, matched)
                i += matched.length
            } else {
                append(text[i])
                i++
            }
        }
    }
    return builder to files
}
