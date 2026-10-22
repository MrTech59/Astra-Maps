package com.astramaps.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.astramaps.app.ui.home.HomeScreen
import com.astramaps.app.ui.home.HomeViewModel
import com.astramaps.app.ui.theme.AstraTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as AstraApplication).container

        setContent {
            AstraTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: HomeViewModel = viewModel(
                        factory = HomeViewModel.factory(container),
                    )
                    val packs by viewModel.installedPackIds.collectAsState()
                    HomeScreen(installedPackIds = packs)
                }
            }
        }
    }
}
