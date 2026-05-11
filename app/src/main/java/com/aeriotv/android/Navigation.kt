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
import com.aeriotv.android.feature.main.MainScaffold
import com.aeriotv.android.feature.onboarding.UrlEntryScreen
import com.aeriotv.android.feature.player.PlayerScreen
import com.aeriotv.android.feature.playlist.PlaylistViewModel

object Routes {
    const val PLAYLIST_GRAPH = "playlist_graph"
    const val BOOTSTRAP = "bootstrap"
    const val URL_ENTRY = "url_entry"
    const val MAIN = "main"
    const val PLAYER = "player/{url}"

    fun player(url: String) = "player/${Uri.encode(url)}"
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

                // One-shot debug entry. --es url + optional --es epg loads an M3U source.
                // --es url + --es apikey loads a Dispatcharr API-key source. Both gated
                // behind BuildConfig.DEBUG in MainActivity so release builds ignore them.
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
                        PlaylistViewModel.Phase.NeedsUrl -> navController.navigate(Routes.URL_ENTRY) {
                            popUpTo(Routes.BOOTSTRAP) { inclusive = true }
                        }
                        PlaylistViewModel.Phase.ChannelsReady -> navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.BOOTSTRAP) { inclusive = true }
                        }
                    }
                }

                BootstrapSplash()
            }

            composable(Routes.URL_ENTRY) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.phase) {
                    if (state.phase == PlaylistViewModel.Phase.ChannelsReady) {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.URL_ENTRY) { inclusive = true }
                        }
                    }
                }

                UrlEntryScreen(viewModel = vm)
            }

            composable(Routes.MAIN) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.phase) {
                    if (state.phase == PlaylistViewModel.Phase.NeedsUrl) {
                        navController.navigate(Routes.URL_ENTRY) {
                            popUpTo(Routes.MAIN) { inclusive = true }
                        }
                    }
                }

                MainScaffold(
                    onChannelClick = { channel ->
                        navController.navigate(Routes.player(channel.url))
                    },
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(navArgument("url") { type = NavType.StringType }),
            ) { entry ->
                val parent = remember(entry) {
                    navController.getBackStackEntry(Routes.PLAYLIST_GRAPH)
                }
                val vm: PlaylistViewModel = hiltViewModel(parent)
                val state by vm.state.collectAsStateWithLifecycle()
                val encoded = entry.arguments?.getString("url").orEmpty()
                val url = Uri.decode(encoded)
                // Derive HTTP headers from the active source so mpv can authenticate against
                // the server when fetching /proxy/ts/stream/<uuid>. Phase 4a only fills the
                // Dispatcharr API-key headers; Phase 4b will add Bearer JWT, etc.
                val headers = remember(state.playlist?.apiKey, state.playlist?.sourceType) {
                    val pl = state.playlist
                    val key = pl?.apiKey?.takeIf { it.isNotBlank() }
                    if (pl?.sourceType == com.aeriotv.android.core.data.SourceType.DispatcharrApiKey.name && key != null) {
                        mapOf(
                            "X-API-Key" to key,
                            "Authorization" to "ApiKey $key",
                        )
                    } else emptyMap()
                }
                PlayerScreen(streamUrl = url, isLive = true, httpHeaders = headers)
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
