package br.com.timasters.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.timasters.app.R

@Composable
fun AppIconDisplay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(108.dp),
        contentAlignment = Alignment.Center
    ) {
        // Background layer
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_background),
            contentDescription = "Background",
            modifier = Modifier.fillMaxSize()
        )
        // Foreground layer
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Foreground",
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1E1E2C)
@Composable
fun AppIconPreview() {
    Column(
        modifier = Modifier
            .background(Color(0xFF0F172A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("TI Masters - Adaptive App Icon Preview", color = Color.White, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Row {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Full 108dp", color = Color.LightGray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                AppIconDisplay(modifier = Modifier.size(108.dp))
            }
            Spacer(modifier = Modifier.width(24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Squircle Mask", color = Color.LightGray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                ) {
                    AppIconDisplay(modifier = Modifier.size(72.dp))
                }
            }
            Spacer(modifier = Modifier.width(24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Circle Mask", color = Color.LightGray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                ) {
                    AppIconDisplay(modifier = Modifier.size(72.dp))
                }
            }
        }
    }
}
