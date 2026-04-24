package com.iromashka.ui.smileys

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.iromashka.ui.theme.LocalThemePalette

@Composable
fun SmileyPickerPanel(onPick: (shortcode: String) -> Unit) {
    val ctx = LocalContext.current
    val palette = LocalThemePalette.current
    LaunchedEffect(Unit) { SmileyMap.load(ctx) }
    val items = remember { SmileyMap.all() }

    val loader = remember {
        ImageLoader.Builder(ctx)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
            }.build()
    }

    Surface(
        color = palette.surface,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        modifier = Modifier.fillMaxWidth().height(220.dp)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 44.dp),
            modifier = Modifier.fillMaxSize().padding(8.dp)
        ) {
            items(items, key = { it.second }) { (shortcode, file) ->
                Box(
                    modifier = Modifier.padding(4.dp).size(36.dp).clip(RoundedCornerShape(6.dp))
                        .background(palette.inputBg)
                        .clickable { onPick(shortcode) },
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx)
                            .data("file:///android_asset/smileys/$file")
                            .build(),
                        imageLoader = loader,
                        contentDescription = shortcode,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}
