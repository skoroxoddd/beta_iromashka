package com.iromashka.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iromashka.storage.Prefs

@Composable
fun RecoveryPromptScreen(
    onCreate: () -> Unit,
    onSkip: () -> Unit
) {
    val ctx = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Warning, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Text(
                "Защитите аккаунт",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                "Резервная фраза из 12 слов — единственный способ восстановить переписку, если вы забудете PIN или потеряете телефон. Без неё вся история чатов будет потеряна навсегда.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Создать фразу сейчас") }
            TextButton(
                onClick = {
                    Prefs.setRecoveryPromptSkipped(ctx, true)
                    onSkip()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Позже (на свой риск)") }
        }
    }
}
