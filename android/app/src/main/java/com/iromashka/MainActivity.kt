package com.iromashka

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.*
import androidx.navigation.compose.*
import com.iromashka.service.IromashkaForegroundService
import com.iromashka.storage.AppDatabase
import com.iromashka.storage.Prefs
import com.iromashka.ui.screens.*
import com.iromashka.ui.theme.AppTheme
import com.iromashka.viewmodel.AuthViewModel
import com.iromashka.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val authVm: AuthViewModel by viewModels()
    private val chatVm: ChatViewModel by viewModels()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best effort */ }

    private val contactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled in ContactListScreen */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FLAG_SECURE — защита от скриншотов
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AppTheme {
                IromashkaNavHost(authVm = authVm, chatVm = chatVm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isLoggedIn(this)) {
            requestContactsPermission()
        }
    }

    private fun requestContactsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            contactsPermission.launch(arrayOf(Manifest.permission.READ_CONTACTS))
        }
    }
}

@Composable
fun IromashkaNavHost(authVm: AuthViewModel, chatVm: ChatViewModel) {
    val ctx = LocalContext.current
    val navController = rememberNavController()

    val startDest = if (Prefs.isLoggedIn(ctx)) "pin_unlock" else "login"

    val authState by authVm.state.collectAsState()

    LaunchedEffect(authState) {
        if (authState is com.iromashka.viewmodel.AuthState.Success) {
            val s = authState as com.iromashka.viewmodel.AuthState.Success
            // Login/register completed — navigate handled by screen callbacks
        }
    }

    NavHost(navController = navController, startDestination = startDest) {

        composable("login") {
            val chatVm: com.iromashka.viewmodel.ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            LoginScreen(
                viewModel = authVm,
                onSuccess = { uin, pin ->
                    navController.navigate("contacts") {
                        popUpTo("login") { inclusive = true }
                    }
                    chatVm.connectWs()
                    val svcIntent = IromashkaForegroundService.startIntent(ctx, pin)
                    ctx.startForegroundService(svcIntent)
                },
                onRegister = { navController.navigate("register") }
            )
        }

        composable("register") {
            val chatVm: com.iromashka.viewmodel.ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            RegisterScreen(
                viewModel = authVm,
                onSuccess = { uin, pin ->
                    navController.navigate("contacts") {
                        popUpTo("register") { inclusive = true }
                    }
                    chatVm.connectWs()
                    val svcIntent = IromashkaForegroundService.startIntent(ctx, pin)
                    ctx.startForegroundService(svcIntent)
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("pin_unlock") {
            PinUnlockScreen(
                onUnlock = { pin ->
                    val ok = chatVm.initSession(pin)
                    if (ok) {
                        chatVm.connectWs()
                        navController.navigate("contacts") {
                            popUpTo("pin_unlock") { inclusive = true }
                        }
                    }
                    ok
                },
                onWipeAndLogout = {
                    val db = AppDatabase.getInstance(ctx)
                    GlobalScope.launch(Dispatchers.IO) {
                        db.messageDao().deleteAll()
                        db.groupMessageDao().clearAllGroups()
                    }
                    authVm.logout()
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onLogout = {
                    authVm.logout()
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable("contacts") {
            val uin = Prefs.getUin(ctx)
            val nickname = Prefs.getNickname(ctx)
            ContactListScreen(
                myUin = uin,
                myNickname = nickname.ifBlank { "UIN $uin" },
                viewModel = chatVm,
                onChatOpen = { toUin, toNick ->
                    navController.navigate("chat/$toUin/$toNick")
                },
                onGroupChatOpen = { groupId, groupName ->
                    navController.navigate("group_chat/$groupId/$groupName")
                },
                onLogout = {
                    val svcIntent = IromashkaForegroundService.stopIntent(ctx)
                    ctx.startForegroundService(svcIntent)
                    chatVm.disconnectWs()
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onPhoneLookup = {
                    navController.navigate("phone_lookup")
                },
                onSettings = {
                    navController.navigate("settings")
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

            LaunchedEffect(toUin) { chatVm.openChat(toUin) }

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

        composable("phone_lookup") {
            PhoneLookupScreen(
                viewModel = chatVm,
                onBack = { navController.popBackStack() },
                onChatOpen = { uin, nick ->
                    navController.navigate("chat/$uin/$nick") {
                        popUpTo("contacts")
                    }
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLogout = {
                    val svcIntent = IromashkaForegroundService.stopIntent(ctx)
                    ctx.startForegroundService(svcIntent)
                    chatVm.disconnectWs()
                    authVm.logout()
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
