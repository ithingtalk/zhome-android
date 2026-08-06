package com.ithingtalk.zhome.ui.screens.welcome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ithingtalk.zhome.R
import kotlinx.coroutines.delay

@Composable
fun WelcomeScreen(onTimeout: () -> Unit) {
    var countdown by remember { mutableIntStateOf(5) }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
        onTimeout()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Logo placeholder – replace with actual logo resource
            Text(
                text = "Zhome",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Smart NAS Manager",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = "Skip ($countdown)",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp)
                .clickable { onTimeout() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

