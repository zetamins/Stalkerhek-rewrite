package com.stalkerhek.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stalkerhek.tv.engine.EngineController
import com.stalkerhek.tv.engine.EngineState
import com.stalkerhek.tv.tv.QrCodeScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start the foreground service (idempotent — already running if boot-started)
        startService(Intent(this, EngineService::class.java))
        setContent {
            MaterialTheme {
                StalkerApp()
            }
        }
    }
}

@Composable
fun StalkerApp() {
    val state by EngineController.engineState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080C09)),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            is EngineState.Uninitialized, is EngineState.Initializing -> {
                LoadingScreen()
            }
            is EngineState.Ready -> {
                QrCodeScreen()
            }
            is EngineState.Error -> {
                val error = (state as EngineState.Error).message
                ErrorScreen(error)
            }
        }
    }
}

@Composable
fun LoadingScreen() {
    var dotCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            dotCount = (dotCount + 1) % 4
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Pulse dot
        val dots = listOf(
            Color(0xFF2D8A4E),
            Color(0xFF2D8A4E).copy(alpha = 0.6f),
            Color(0xFF2D8A4E).copy(alpha = 0.3f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dots.forEachIndexed { i, color ->
                Box(
                    modifier = Modifier
                        .size(if (i == 0) 14.dp else 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color)
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            "Stalkerhek",
            color = Color(0xFF2D8A4E),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp
        )

        Spacer(Modifier.height(8.dp))

        val loadingText = "Initializing engine" + ".".repeat(dotCount)
        Text(
            loadingText,
            color = Color(0xFF8BA38D),
            fontSize = 15.sp
        )
    }
}

@Composable
fun ErrorScreen(error: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF3D1A1A)),
            contentAlignment = Alignment.Center
        ) {
            Text("!", color = Color(0xFFE85D4D), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "Engine Error",
            color = Color(0xFFE85D4D),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            error,
            color = Color(0xFF8BA38D),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}
