package com.stalkerhek.tv.tv

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.stalkerhek.tv.engine.EngineController
import com.stalkerhek.tv.util.getLocalIpAddress

@Composable
fun QrCodeScreen() {
    val profile by EngineController.activeProfile.collectAsState()
    val localIp = remember { getLocalIpAddress() }
    val mgmtPort = 4400
    val hlsPort = profile?.hlsAddr?.substringAfter(":")?.toIntOrNull() ?: 4600
    val proxyPort = profile?.proxyAddr?.substringAfter(":")?.toIntOrNull() ?: 4800

    val mgmtUrl = "http://$localIp:$mgmtPort"
    val hlsUrl = "http://$localIp:$hlsPort"
    val proxyUrl = "http://$localIp:$proxyPort"

    val qrBitmap = remember(mgmtUrl) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(mgmtUrl, BarcodeFormat.QR_CODE, 512, 512)
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
            for (x in 0 until 512) {
                for (y in 0 until 512) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (_: Exception) { null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080C09))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo / Title
        Text(
            "Stalkerhek",
            color = Color(0xFF2D8A4E),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp
        )

        Spacer(Modifier.height(4.dp))

        Text(
            "Management Dashboard",
            color = Color(0xFF8BA38D),
            fontSize = 14.sp,
        )

        Spacer(Modifier.height(28.dp))

        // QR Code
        Box(
            modifier = Modifier
                .size(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            qrBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Dashboard QR Code",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Dashboard URL
        Text(
            mgmtUrl,
            color = Color(0xFF2D8A4E),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(4.dp))

        Text(
            "Scan or open in browser to manage filters and profiles",
            color = Color(0xFF6B806D),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        // Server info cards
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoRow(label = "Dashboard", value = mgmtUrl)
            InfoRow(label = "Proxy", value = proxyUrl)
            InfoRow(label = "HLS Stream", value = hlsUrl)
            if (profile != null) {
                InfoRow(
                    label = "Profile",
                    value = if (profile!!.running) "Active — ${profile!!.channelsCount} channels" else "Inactive"
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0C120E), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Color(0xFF6B806D),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            value,
            color = Color(0xFFE2ECE3),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
