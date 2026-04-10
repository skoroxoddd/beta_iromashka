package com.iromashka.ui.screens

import android.Manifest
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.iromashka.model.DiscoveredContactItem
import com.iromashka.model.PhoneLookupResult
import com.iromashka.storage.Prefs
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.viewmodel.ChatViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneLookupScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onChatOpen: (Long, String) -> Unit
) {
    val palette = LocalThemePalette.current
    val ctx = LocalContext.current

    var phoneContacts by remember { mutableStateOf<List<PhoneLookupResult>>(emptyList()) }
    var discoveredByPhone by remember { mutableStateOf<Map<String, DiscoveredContactItem>>(emptyMap()) }
    var searching by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }

    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            phoneContacts = loadPhoneContacts(ctx)
        }
    }

    LaunchedEffect(Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.READ_CONTACTS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            hasPermission = true
            phoneContacts = loadPhoneContacts(ctx)
        } else {
            contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    val doDiscover: () -> Unit = {
        val phones = phoneContacts.map { it.phone }.filter { it.length >= 10 }
        if (phones.isNotEmpty()) {
            searching = true
            CoroutineScope(Dispatchers.IO).launch {
                val results = viewModel.discoverContacts(phones)
                discoveredByPhone = results.associateBy { it.phone }
                withContext(Dispatchers.Main) {
                    searching = false
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(palette.background)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(palette.titleBar))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                }
                Text("Телефонная книга", color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    modifier = Modifier.padding(start = 8.dp))
            }
        }

        if (!hasPermission) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lock, null,
                        tint = palette.textSecondary, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Нет доступа к контактам", color = palette.textSecondary)
                    Text("Разрешите доступ в настройках", color = palette.textSecondary,
                        fontSize = 13.sp)
                }
            }
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { doDiscover() },
                enabled = phoneContacts.isNotEmpty() && !searching,
                modifier = Modifier.weight(1f)
            ) {
                if (searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp), color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Найти UIN (${phoneContacts.size} контактов)")
                }
            }
        }

        if (phoneContacts.isEmpty()) {
            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Text("Телефонная книга пуста", color = palette.textSecondary)
            }
        } else {
            val onlineCount = phoneContacts.count { discoveredByPhone.containsKey(it.phone) }
            Text("Найдено: $onlineCount из ${phoneContacts.size} контактов",
                fontSize = 13.sp, color = palette.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(phoneContacts, key = { it.phone }) { contact ->
                    val found = discoveredByPhone[contact.phone]
                    val hasUin = found != null
                    val displayName = contact.contactName.ifBlank { contact.phone }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = hasUin) {
                                found?.let { onChatOpen(it.uin, it.nickname.ifBlank { displayName }) }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(if (hasUin) palette.accent else Color(0xFFBDBDBD)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                displayName.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold, fontSize = 16.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(displayName, fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp, color = palette.textPrimary)
                            Text(contact.phone, fontSize = 12.sp, color = palette.textSecondary)
                        }
                        if (hasUin) {
                            Surface(
                                color = Color(0xFFE8F5E9),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("UIN: ${found!!.uin}",
                                    color = Color(0xFF2E7D32),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        } else {
                            Text("Не зарегистрирован",
                                fontSize = 11.sp, color = Color(0xFF9E9E9E))
                        }
                    }
                    HorizontalDivider(color = palette.divider, thickness = 0.5.dp)
                }
            }
        }
    }
}

private fun loadPhoneContacts(ctx: android.content.Context): List<PhoneLookupResult> {
    val results = mutableListOf<PhoneLookupResult>()
    val contentResolver = ctx.contentResolver
    val cursor = contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        ),
        null, null,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
    )

    cursor?.use {
        val nameIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val phoneIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (it.moveToNext()) {
            val name = it.getString(nameIdx)
            val phone = normalizePhone(it.getString(phoneIdx))
            if (phone.isNotEmpty()) {
                results.add(PhoneLookupResult(
                    phone = phone,
                    contactName = name
                ))
            }
        }
    }
    return results.distinctBy { it.phone }
}

private fun normalizePhone(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return when {
        digits.startsWith("8") && digits.length == 11 -> "+7" + digits.drop(1)
        digits.startsWith("7") && digits.length == 11 -> "+7" + digits.drop(1)
        digits.length == 10 -> "+7$digits"
        digits.startsWith("7") -> "+$digits"
        else -> "+$digits"
    }
}
