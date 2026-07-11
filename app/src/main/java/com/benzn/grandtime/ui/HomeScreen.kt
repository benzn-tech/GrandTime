package com.benzn.grandtime.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benzn.grandtime.core.AppState
import com.benzn.grandtime.core.LoginState
import com.benzn.grandtime.ui.theme.LocalFsColors

@Composable
fun HomeScreen() {
    val running by AppState.serviceRunning.collectAsStateWithLifecycle()
    val login by AppState.loginState.collectAsStateWithLifecycle()
    val fs = LocalFsColors.current

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        FsCard {
            FsCardTitle("Device")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (running) fs.successDot else MaterialTheme.colorScheme.outline)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (running) "Standing by" else "Service stopped",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (val s = login) {
                    is LoginState.LoggedIn -> "Signed in as ${s.displayName}"
                    LoginState.LoggedOut -> "Not signed in"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
