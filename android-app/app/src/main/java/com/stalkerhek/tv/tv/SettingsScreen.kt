package com.stalkerhek.tv.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.stalkerhek.tv.engine.EngineController
import com.stalkerhek.tv.engine.ProfileConfig
import com.stalkerhek.tv.engine.ProfileStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val profiles by EngineController.profiles.collectAsState()
    val activeProfile by EngineController.activeProfile.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF080C09)).padding(24.dp)
    ) {
        Text(
            "Settings",
            color = Color.White,
            fontSize = 24.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (profiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No profiles configured. Use the management UI to add a profile.", color = Color.Gray, fontSize = 16.sp)
            }
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles) { profile ->
                ProfileCard(
                    profile = profile,
                    status = activeProfile,
                    onStart = {
                        scope.launch { EngineController.startProfile(profile) }
                    },
                    onStop = {
                        scope.launch { EngineController.stopProfile(profile.id) }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProfileCard(
    profile: ProfileConfig,
    status: ProfileStatus?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val isRunning = status?.running == true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isFocused) Color(0xFF1A2C1F) else Color(0xFF111A14)
            )
            .padding(16.dp)
            .onFocusChanged { isFocused = it.isFocused },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(profile.name, color = Color.White, fontSize = 18.sp)
            Text(
                if (isRunning) "Running - ${status?.channelsCount ?: 0} channels"
                else "Stopped",
                color = if (isRunning) Color(0xFF2D8A4E) else Color.Gray,
                fontSize = 14.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isRunning) {
                Button(
                    onClick = onStop,
                ) {
                    Text("Stop", color = Color.White)
                }
            } else {
                Button(
                    onClick = onStart,
                ) {
                    Text("Start", color = Color.White)
                }
            }
        }
    }
}
