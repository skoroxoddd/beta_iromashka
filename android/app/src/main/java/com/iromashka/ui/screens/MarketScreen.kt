package com.iromashka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.viewmodel.ChatViewModel

data class MaskOption(
    val label: String,
    val mask: String,
    val price: Int,
    val desc: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val palette = LocalThemePalette.current

    var selectedLength by remember { mutableStateOf(6) }
    var selectedMask by remember { mutableStateOf<MaskOption?>(null) }
    var customMask by remember { mutableStateOf("") }

    val sixMasks = listOf(
        MaskOption("******", "******", 500, "Любой 6-значный"),
        MaskOption("1*****", "1*****", 800, "Начинается на 1"),
        MaskOption("**77**", "**77**", 900, "Счастливые 77"),
        MaskOption("***000", "***000", 1200, "Три нуля"),
        MaskOption("123***", "123***", 1500, "123..."),
        MaskOption("******", "777777", 5000, "Все семёрки"),
    )

    val sevenMasks = listOf(
        MaskOption("*******", "*******", 200, "Любой 7-значный"),
        MaskOption("1******", "1******", 400, "Начинается на 1"),
        MaskOption("***0000", "***0000", 600, "Четыре нуля"),
        MaskOption("123****", "123****", 800, "123..."),
    )

    val masks = if (selectedLength == 6) sixMasks else sevenMasks

    Column(
        modifier = Modifier.fillMaxSize().background(palette.background)
    ) {
        // Title bar
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(palette.titleBar)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                        tint = androidx.compose.ui.graphics.Color.White)
                }
                Text("Маркет UIN", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.weight(1f))
            }
        }

        HorizontalDivider(color = palette.divider, thickness = 0.5.dp)

        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {

            // Length selector
            Text("Длина UIN", fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6, 7).forEach { len ->
                    val sel = selectedLength == len
                    Surface(
                        modifier = Modifier.clickable { selectedLength = len; selectedMask = null; customMask = "" }
                            .weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = if (sel) palette.accent else palette.surface,
                        contentColor = if (sel) palette.background else palette.textPrimary,
                    ) {
                        Text("${len} цифр", fontSize = 14.sp,
                            modifier = Modifier.padding(12.dp),
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Preset masks
            Text("Шаблоны", fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 200.dp)
            ) {
                items(masks) { m ->
                    Surface(
                        modifier = Modifier.clickable { selectedMask = m; customMask = "" }
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = if (selectedMask?.mask == m.mask) palette.accent else palette.surface,
                        contentColor = if (selectedMask?.mask == m.mask)
                            palette.background else palette.textPrimary,
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(m.label, fontWeight = FontWeight.Bold,
                                fontSize = 16.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text(m.desc, fontSize = 10.sp,
                                color = if (selectedMask?.mask == m.mask)
                                    androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)
                                else palette.textSecondary)
                            Text("${m.price}₽", fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp, color = palette.onlineGreen)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Custom mask
            Text("Своя маска", fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = customMask,
                onValueChange = {
                    val clean = it.filter { c -> c == '*' || c.isDigit() }
                        .take(selectedLength)
                    customMask = clean
                    selectedMask = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Например: ${if (selectedLength == 6) "12****" else "7*****"}",
                    color = palette.textSecondary) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = palette.inputBg,
                    unfocusedContainerColor = palette.inputBg,
                    focusedIndicatorColor = palette.accent,
                    unfocusedIndicatorColor = palette.divider,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                ),
                maxLines = 1,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
            )
            Text("Используйте цифры и * (рандом), длина = $selectedLength",
                fontSize = 10.sp, color = palette.textSecondary)

            Spacer(Modifier.height(12.dp))

            // Check / Buy button
            val activeMask = customMask.ifBlank { selectedMask?.mask }
            if (activeMask != null && activeMask.length == selectedLength) {
                Button(
                    onClick = {
                        // Open in browser for payment or show price
                        viewModel.requestUinPurchase(selectedLength, activeMask)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (viewModel.marketUin != null) {
                        Text("Купить UIN ${viewModel.marketUin} за ${viewModel.marketPrice}₽")
                    } else if (viewModel.marketLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp),
                            color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Проверить и купить")
                    }
                }
            }

            if (viewModel.marketError != null) {
                Spacer(Modifier.height(8.dp))
                Text(viewModel.marketError!!, color = palette.errorRed, fontSize = 12.sp)
            }
        }
    }
}
