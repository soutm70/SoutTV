package com.aeriotv.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.aeriotv.android.feature.player.PlayerScreen
import com.aeriotv.android.ui.theme.AerioTVTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AerioTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Phase 1a proof-of-concept: PlayerScreen auto-loads a public
                    // HLS test stream on launch. Phase 2 will replace this with a
                    // home/channel-list navigation graph.
                    PlayerScreen()
                }
            }
        }
    }
}
