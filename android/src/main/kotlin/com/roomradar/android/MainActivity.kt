package com.roomradar.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.roomradar.android.ui.RadarApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { enableEdgeToEdge() }
        setContent {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RadarApp()
                }
            }
        }
    }
}
