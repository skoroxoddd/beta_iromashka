package com.iromashka

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.*
import androidx.navigation.compose.*
import com.iromashka.storage.Prefs
import com.iromashka.storage.AppDatabase
import com.iromashka.ui.screens.*
import com.iromashka.ui.theme.AppTheme
import com.iromashka.viewmodel.AuthViewModel
import com.iromashka.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    private val authVm: AuthViewModel by viewModels()
    private val chatVm: ChatViewModel by viewModels()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore result — best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        requestNotifPermission()
        requestBatteryExemption()
        startBackgroundService()

        setContent {
            AppTheme {
                IcqNavHost(authVm = authVm, chatVm = chatVm)
            }
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestBatteryExemption() {
        runCatching {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
    }

    private fun startBackgroundService() {
        if (!Prefs.isLoggedIn(this)) return
        runCatching {
            val intent = com.iromashka.service.IromashkaForegroundService.startIntent(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
    }
}

@Composable
fun IcqNavHost(authVm: AuthViewModel, chatVm: ChatViewModel) {
    val ctx = LocalContext.current
    val navController = rememberNavController()

    var sessionPin by remember { mutableStateOf("") }
    var paidPhone by remember { mutableStateOf("") }
    var paidUin by remember { mutableStateOf(0L) }
    var migrationUin by remember { mutableStateOf(0L) }

    val startDest = if (Prefs.isLoggedIn(ctx)) "pin_unlock" else "login"

    val authFailed by chatVm.authFailed.collectAsState()
    LaunchedEffect(authFailed) {
        if (authFailed) {
            Prefs.clear(ctx)
            chatVm.disconnectWs()
            navController.navigate("login") {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDest) {

        composable("login") {
            LoginScreen(
                viewModel = authVm,
                onSuccess = { _, pin ->
                    sessionPin = pin
                    val activity = ctx as? FragmentActivity
                    // Init E2E keys and start WS right after login
                    chatVm.init(pin) { ok ->
                        if (ok) {
                            chatVm.connectWs()
                            // Offer biometric enrollment if available and not yet asked
                            if (activity != null) maybeOfferBiometricEnroll(activity, ctx, pin)
                        }
                    }
                    navController.navigate("contacts") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onRegister = { navController.navigate("phone_payment") },
                onForgotPin = { navController.navigate("recovery_restore") },
                onNeedsMigration = { uin, pin ->
                    sessionPin = pin
                    migrationUin = uin
                    navController.navigate("password_migration/$uin")
                }
            )
        }

        composable(
            "password_migration/{uin}",
            arguments = listOf(navArgument("uin") { type = NavType.LongType })
        ) { back ->
            val uin = back.arguments!!.getLong("uin")
            PasswordMigrationScreen(
                viewModel = authVm,
                uin = uin,
                onSuccess = {
                    // After setting password, init keys and go to contacts
                    chatVm.init(sessionPin) { initOk ->
                        if (!initOk) {
                            // Key missing locally — recover from server
                            chatVm.initWithServerRecovery(sessionPin) { ok ->
                                if (ok) {
                                    chatVm.connectWs()
                                    navController.navigate("contacts") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                            }
                        } else {
                            chatVm.connectWs()
                            navController.navigate("contacts") {
                                popUpTo("login") { inclusive = true }
                            }
                        }
                    }
                },
                onSkip = {
                    // Skip migration, init keys and go to contacts
                    chatVm.init(sessionPin) { initOk ->
                        if (!initOk) {
                            // Key missing locally — recover from server
                            chatVm.initWithServerRecovery(sessionPin) { ok ->
                                if (ok) {
                                    chatVm.connectWs()
                                    navController.navigate("contacts") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                            }
                        } else {
                            chatVm.connectWs()
                            navController.navigate("contacts") {
                                popUpTo("login") { inclusive = true }
                            }
                        }
                    }
                }
            )
        }

        composable("recovery_restore") {
            RestoreFromMnemonicScreen(
                onSuccess = {
                    chatVm.connectWs()
                    navController.navigate("contacts") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("recovery_generate") {
            GenerateMnemonicScreen(onBack = { navController.popBackStack() })
        }

        composable("recovery_prompt") {
            RecoveryPromptScreen(
                onCreate = {
                    navController.popBackStack()
                    navController.navigate("recovery_generate")
                },
                onSkip = { navController.popBackStack() }
            )
        }

        composable("phone_payment") {
            PhonePaymentScreen(
                viewModel = authVm,
                onPaid = { phone, uin ->
                    paidPhone = phone
                    paidUin = uin ?: 0L
                    if (uin != null && uin > 0) {
                        Prefs.updateUin(ctx, uin)
                        navController.navigate("uin_reveal") {
                            popUpTo("phone_payment") { inclusive = true }
                        }
                    } else {
                        navController.navigate("register") {
                            popUpTo("phone_payment") { inclusive = true }
                        }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("uin_reveal") {
            UinRevealScreen(
                uin = paidUin,
                onContinue = {
                    navController.navigate("register") {
                        popUpTo("uin_reveal") { inclusive = true }
                    }
                }
            )
        }

        composable("register") {
            RegisterScreen(
                viewModel = authVm,
                paidPhone = paidPhone,
                onSuccess = { _ ->
                    navController.navigate("pin_unlock") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("pin_unlock") {
            PinUnlockScreen(
                onForgotPin = { navController.navigate("recovery_restore") },
                onBiometricUnlock = { unlockToken, pin ->
                    MainScope().launch {
                        val ok = authVm.tryFastUnlockWithToken(unlockToken)
                        if (ok) {
                            chatVm.init(pin) { initOk ->
                                if (initOk) {
                                    chatVm.connectWs()
                                    navController.navigate("contacts") {
                                        popUpTo("pin_unlock") { inclusive = true }
                                    }
                                }
                            }
                        }
                    }
                },
                onUnlock = { pin ->
                    val wrappedPriv = Prefs.getWrappedPriv(ctx)
                    if (wrappedPriv.isNotEmpty()) {
                        // Normal path: key is local
                        chatVm.init(pin) { ok ->
                            if (ok) {
                                chatVm.connectWs()
                                navController.navigate("contacts") {
                                    popUpTo("pin_unlock") { inclusive = true }
                                }
                            }
                        }
                        true // don't show error immediately — result comes async
                    } else {
                        // Key was lost (reinstall/EncryptedSharedPreferences cleared) — recover from server
                        chatVm.initWithServerRecovery(pin) { ok ->
                            if (ok) {
                                chatVm.connectWs()
                                navController.navigate("contacts") {
                                    popUpTo("pin_unlock") { inclusive = true }
                                }
                            }
                        }
                        true // don't show error immediately — result comes async
                    }
                }
            )
        }

        composable("devices") {
            DevicesScreen(onBack = { navController.popBackStack() })
        }

        composable("contacts") {
            val uin = Prefs.getUin(ctx)
            val nickname = Prefs.getNickname(ctx)

            LaunchedEffect(Unit) {
                if (chatVm.shouldRefreshToken()) {
                    chatVm.refreshToken()
                }
                if (!Prefs.hasRecoveryPhrase(ctx) && !Prefs.getRecoveryPromptSkipped(ctx)) {
                    navController.navigate("recovery_prompt")
                }
            }

            ContactListScreen(
                myUin = uin,
                myNickname = nickname,
                viewModel = chatVm,
                onChatOpen = { toUin, toNick ->
                    navController.navigate("chat/$toUin/$toNick")
                },
                onGroupChatOpen = { groupId, groupName ->
                    navController.navigate("group_chat/$groupId/$groupName")
                },
                onDevices = { navController.navigate("devices") },
                onRecoveryGenerate = { navController.navigate("recovery_generate") },
                onLogout = {
                    chatVm.disconnectWs()
                    // Clear database
                    MainScope().launch {
                        withContext(Dispatchers.IO) {
                            AppDatabase.getInstance(ctx).clearAllTables()
                        }
                    }
                    AppDatabase.clearInstance()
                    Prefs.clear(ctx)
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            "chat/{toUin}/{toNick}",
            arguments = listOf(
                navArgument("toUin") { type = NavType.LongType },
                navArgument("toNick") { type = NavType.StringType }
            )
        ) { back ->
            val toUin = back.arguments!!.getLong("toUin")
            val toNick = back.arguments!!.getString("toNick") ?: ""

            LaunchedEffect(toUin) {
                chatVm.openChat(toUin)
            }

            ChatScreen(
                myUin = Prefs.getUin(ctx),
                toUin = toUin,
                toNickname = toNick,
                viewModel = chatVm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            "group_chat/{groupId}/{groupName}",
            arguments = listOf(
                navArgument("groupId") { type = NavType.LongType },
                navArgument("groupName") { type = NavType.StringType }
            )
        ) { back ->
            val groupId = back.arguments!!.getLong("groupId")
            val groupName = back.arguments!!.getString("groupName") ?: ""
            GroupChatScreen(
                groupId = groupId,
                groupName = groupName,
                viewModel = chatVm,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * G2: после первого PIN-логина — предложить включить биометрию.
 * Если в Prefs уже лежит "PLAIN:<token>", оборачиваем его Keystore-ключом
 * через BiometricPrompt и сохраняем cipher+iv. Дальше при холодном старте
 * PinUnlockScreen покажет кнопку "Войти по биометрии" и unlock_token будет
 * получен только после успешной аутентификации.
 */
private fun maybeOfferBiometricEnroll(activity: FragmentActivity?, ctx: android.content.Context, pin: String) {
    if (activity == null) return
    if (Prefs.isBiometricEnabled(ctx)) return
    if (Prefs.isBiometricPromptAsked(ctx)) return
    if (!com.iromashka.crypto.BiometricKeystore.canAuthenticate(ctx)) return
    val stored = Prefs.getUnlockTokenWrapped(ctx)
    if (!stored.startsWith("PLAIN:")) return
    val plainToken = stored.removePrefix("PLAIN:")
    val expires = Prefs.getUnlockTokenExpires(ctx)

    Prefs.setBiometricPromptAsked(ctx)

    // Wrap "<unlock_token>\n<pin>" together — biometric unlocks both at once.
    val payload = (plainToken + "\n" + pin).toByteArray(Charsets.UTF_8)
    com.iromashka.crypto.BiometricKeystore.wrapWithBiometric(
        activity = activity,
        plaintext = payload,
        title = "АйРомашка",
        subtitle = "Включить вход по биометрии?",
        onSuccess = { ct, iv ->
            Prefs.setUnlockToken(ctx, wrapped = ct, iv = iv, expiresAt = expires)
            Prefs.setBiometricEnabled(ctx, true)
            android.widget.Toast.makeText(ctx, "Биометрия включена", android.widget.Toast.LENGTH_SHORT).show()
        },
        onFail = { /* user declined — keep PLAIN token, will retry at next session */
            Prefs.setBiometricEnabled(ctx, false)
        }
    )
}
