package com.finnah.linestop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.finnah.linestop.ui.MainScreen
import com.finnah.linestop.ui.theme.LineStopTheme

class MainActivity : ComponentActivity() {

    private val viewModel: LineStopViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LineStopTheme {
                MainScreen(viewModel)
            }
        }
    }
}
