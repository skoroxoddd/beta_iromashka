package com.iromashka

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.*
import androidx.navigation.compose.*
import com.iromashka.storage.Prefs
import com.iromashka.ui.screens.*
import com.iromashka.ui.theme.AppTheme
import com.iromashka.viewmodel.AuthViewModel
import com.iromashka.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {

    private val authVm: AuthViewModel by viewModels()
    private val chatVm: ChatViewModel by viewModels()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore result — best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotifPermission()

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
}

@Composable
fun IcqNavHost(authVm: AuthViewModel, chatVm: ChatViewModel) {
    val ctx = LocalContext.current
    val navController = rememberNavController()

    var sessionPin by remember { mutableStateOf("") }

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
                onSuccess = { _ ->
                    navController.navigate("contacts") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onRegister = { navController.navigate("register") }
            )
        }

        composable("register") {
            RegisterScreen(
                viewModel = authVm,
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
                onUnlock = { pin ->
                    sessionPin = pin
                    val ok = chatVm.init(pin)
                    if (ok) {
                        chatVm.connectWs()
                        navController.navigate("contacts") {
                            popUpTo("pin_unlock") { inclusive = true }
                        }
                    }
                    ok
                }
            )
        }

        composable("contacts") {
            val uin = Prefs.getUin(ctx)
            val nickname = Prefs.getNickname(ctx)

            LaunchedEffect(Unit) {
                if (chatVm.shouldRefreshToken()) {
                    chatVm.refreshToken()
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
                onLogout = {
                    chatVm.disconnectWs()
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
