package com.stalkerhek.tv.tv

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.stalkerhek.tv.engine.Category
import com.stalkerhek.tv.engine.Channel
import com.stalkerhek.tv.engine.EngineController

@Composable
fun ChannelGridScreen(
    navController: NavController,
    mediaType: String = "itv"
) {
    val context = LocalContext.current
    val profileId by EngineController.activeProfileId.collectAsState()
    val profileStatus by EngineController.activeProfile.collectAsState()
    val hlsAddr = profileStatus?.hlsAddr ?: ":4600"

    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var selectedGenre by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profileId, mediaType) {
        if (profileId == 0) return@LaunchedEffect
        isLoading = true
        errorMsg = null
        try {
            channels = EngineController.getChannels(profileId, mediaType)
            if (mediaType == "vod" || mediaType == "series") {
                categories = EngineController.getCategories(profileId, mediaType)
            }
        } catch (e: Exception) {
            errorMsg = e.message
        }
        isLoading = false
    }

    val filteredChannels = channels
        .filter { it.enabled }
        .let { if (selectedGenre.isEmpty()) it else it.filter { ch -> ch.genreId == selectedGenre } }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF080C09)).padding(16.dp)) {
        // Top bar
        Text(
            text = when (mediaType) {
                "vod" -> "Movies"
                "series" -> "Series"
                else -> "Channels"
            },
            color = Color.White,
            fontSize = 24.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Genre filter row
        if (categories.isNotEmpty() || mediaType == "itv") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                val allCategories = listOf(Category("", "All")) + categories
                allCategories.forEach { cat ->
                    val isSelected = selectedGenre == cat.id
                    Box(
                        modifier = Modifier
                            .width(120.dp).height(36.dp)
                            .clickable { selectedGenre = if (isSelected) "" else cat.id }
                            .background(
                                if (isSelected) Color(0xFF2D8A4E) else Color(0xFF1A2C1F),
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            cat.title.ifEmpty { cat.name }.ifEmpty { "Other" },
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        when {
            isLoading -> LoadingGrid()
            errorMsg != null -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) { Text("Error: $errorMsg", color = Color.Red, fontSize = 18.sp) }
            filteredChannels.isEmpty() -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) { Text("No channels available", color = Color.Gray, fontSize = 18.sp) }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredChannels) { channel ->
                    ChannelCard(channel, onClick = {
                        val streamUrl = "http://127.0.0.1$hlsAddr/${channel.title}"
                        val intent = Intent(context, PlayerActivity::class.java).apply {
                            putExtra("url", streamUrl)
                            putExtra("title", channel.title)
                        }
                        context.startActivity(intent)
                    })
                }
            }
        }
    }
}

@Composable
fun ChannelCard(channel: Channel, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .aspectRatio(16f / 9f)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .background(
                if (isFocused) Color(0xFF2D8A4E) else Color(0xFF111A14),
                RoundedCornerShape(8.dp)
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AsyncImage(
                model = channel.logo.ifEmpty { null },
                contentDescription = channel.title,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(4.dp))
            Text(
                channel.title,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun LoadingGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(12) {
            Box(
                Modifier.aspectRatio(16f / 9f).background(
                    Color(0xFF1A2C1F),
                    shape = RoundedCornerShape(8.dp)
                )
            )
        }
    }
}

@Suppress("unused")
private fun idFromStatus(status: Any): Int {
    return try { EngineController.profiles.value.firstOrNull()?.id ?: 0 } catch (_: Exception) { 0 }
}
