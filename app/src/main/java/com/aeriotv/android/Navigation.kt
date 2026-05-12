package com.aeriotv.android

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.feature.main.MainScaffold
import com.aeriotv.android.feature.onboarding.ChooseSourceTypeScreen
import com.aeriotv.android.feature.onboarding.ConfigureSourceScreen
import com.aeriotv.android.feature.onboarding.WelcomeScreen
import com.aeriotv.android.feature.player.PlayerScreen
import com.aeriotv.android.feature.playlist.PlaylistViewModel

object Routes {
    const val PLAYLIST_GRAPH = "playlist_graph"
    const val BOOTSTRAP = "bootstrap"
    const val WELCOME = "welcome"
    const val CHOOSE_TYPE = "choose_type"
    const val CONFIGURE = "configure/{type}"
    const val MAIN = "main"
    const val PLAYER = "player/{channelId}"

    fun configure(type: SourceType) = "configure/${type.name}"
    fun player(channelId: String) = "player/${Uri.encode(channelId)}"
}

@Composable
fun AerioTVNavHost(
    initialUrl: String? = null,
    initialEpgUrl: String? = null,
    initialApiKey: String? = null,
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.PLAYLIST_GRAPH) {
        navigation(startDestination = Routes.BOOTSTRAP, route = Routes.PLAYLIST_GRAPH) {

            composable(Routes.BOOTSTRAP) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()

                // Debug-only auto-load. --es url + --es apikey => Dispatcharr API-key flow.
                // --es url + optional --es epg => M3U flow. Gated in MainActivity via
                // BuildConfig.DEBUG so release builds always ignore intent extras.
                LaunchedEffect(initialUrl, initialEpgUrl, initialApiKey) {
                    if (!initialUrl.isNullOrBlank() && state.url.isBlank()) {
                        if (!initialApiKey.isNullOrBlank()) {
                            vm.loadFromDispatcharr(initialUrl, initialApiKey)
                        } else {
                            vm.loadFromUrl(initialUrl, initialEpgUrl)
                        }
                    }
                }

                LaunchedEffect(state.phase) {
                    when (state.phase) {
                        PlaylistViewModel.Phase.Bootstrapping -> Unit
                        PlaylistViewModel.Phase.NeedsUrl -> navController.navigate(Routes.WELCOME) {
                            popUpTo(Routes.BOOTSTRAP) { inclusive = true }
                        }
                        PlaylistViewModel.Phase.ChannelsReady -> navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.BOOTSTRAP) { inclusive = true }
                        }
                    }
                }

                BootstrapSplash()
            }

            composable(Routes.WELCOME) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.phase) {
                    if (state.phase == PlaylistViewModel.Phase.ChannelsReady) {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.WELCOME) { inclusive = true }
                        }
                    }
                }

                WelcomeScreen(
                    onConnectServer = { navController.navigate(Routes.CHOOSE_TYPE) },
                    // "Skip for now" is iOS parity. With no playlist saved the channel
                    // list is empty; user can reach Settings -> Change playlist later.
                    onSkip = {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.WELCOME) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.CHOOSE_TYPE) { entry ->
                ChooseSourceTypeScreen(
                    onBack = { navController.popBackStack() },
                    onChoose = { type ->
                        navController.navigate(Routes.configure(type))
                    },
                )
            }

            composable(
                route = Routes.CONFIGURE,
                arguments = listOf(navArgument("type") { type = NavType.StringType }),
            ) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()
                val typeName = entry.arguments?.getString("type") ?: SourceType.M3uUrl.name
                val resolvedType = runCatching { SourceType.valueOf(typeName) }
                    .getOrElse { SourceType.M3uUrl }

                LaunchedEffect(state.phase) {
                    if (state.phase == PlaylistViewModel.Phase.ChannelsReady) {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.WELCOME) { inclusive = true }
                        }
                    }
                }

                ConfigureSourceScreen(
                    sourceType = resolvedType,
                    onBack = { navController.popBackStack() },
                    viewModel = vm,
                )
            }

            composable(Routes.MAIN) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.phase) {
                    if (state.phase == PlaylistViewModel.Phase.NeedsUrl) {
                        navController.navigate(Routes.WELCOME) {
                            popUpTo(Routes.MAIN) { inclusive = true }
                        }
                    }
                }

                MainScaffold(
                    onChannelClick = { channel ->
                        navController.navigate(Routes.player(channel.id))
                    },
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(navArgument("channelId") { type = NavType.StringType }),
            ) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()
                val channelId = Uri.decode(entry.arguments?.getString("channelId").orEmpty())
                val headers = remember(state.playlist?.apiKey, state.playlist?.sourceType) {
                    val pl = state.playlist
                    val key = pl?.apiKey?.takeIf { it.isNotBlank() }
                    val isDispatcharr = pl?.sourceType == SourceType.DispatcharrApiKey.name ||
                            pl?.sourceType == SourceType.DispatcharrUserPass.name
                    if (isDispatcharr && key != null) {
                        mapOf(
                            "X-API-Key" to key,
                            "Authorization" to "ApiKey $key",
                        )
                    } else emptyMap()
                }
                val playableChannels = state.channels.filter { it.url.isNotBlank() }
                PlayerScreen(
                    channels = playableChannels,
                    initialChannelId = channelId,
                    isLive = true,
                    httpHeaders = headers,
                    epgByChannel = state.epgByChannel,
                    onClose = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun BootstrapSplash() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
