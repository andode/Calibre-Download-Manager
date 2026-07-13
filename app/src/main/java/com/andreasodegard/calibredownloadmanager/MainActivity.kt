package com.andreasodegard.calibredownloadmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.andreasodegard.calibredownloadmanager.ui.MainScreen
import com.andreasodegard.calibredownloadmanager.ui.theme.CalibreDownloadManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CalibreDownloadManagerTheme {
                val vm: AppViewModel = viewModel()
                MainScreen(vm)
            }
        }
    }
}