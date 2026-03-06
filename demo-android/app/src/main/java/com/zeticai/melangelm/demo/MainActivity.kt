package com.zeticai.melangelm.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.zeticai.melangelm.demo.ui.DemoScreen

class MainActivity : ComponentActivity() {

    private val viewModel: DemoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface {
                    val uiState by viewModel.uiState.collectAsState()
                    DemoScreen(
                        uiState = uiState,
                        onSend = viewModel::send,
                        onClear = viewModel::clearHistory,
                        onCompressionRatioChange = viewModel::setCompressionRatio,
                        onSendExample = viewModel::sendExample,
                        onShowSettings = viewModel::showSettings,
                        onHideSettings = viewModel::hideSettings,
                        onUpdateApiKey = viewModel::updateApiKey,
                        onUpdateBaseUrl = viewModel::updateBaseUrl,
                        onUpdateModel = viewModel::updateModel
                    )
                }
            }
        }
    }
}
