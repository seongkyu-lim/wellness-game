package com.example.wellnessgame.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.wellnessgame.R
import com.example.wellnessgame.auth.SessionState
import com.example.wellnessgame.ui.components.CelButton
import com.example.wellnessgame.ui.components.CelCard
import com.example.wellnessgame.ui.components.PillBadge
import com.example.wellnessgame.ui.components.displayName
import com.example.wellnessgame.ui.theme.Wellness

@Composable
fun AccountScreen(
    session: SessionState,
    onLogOut: () -> Unit,
    onOpenHealthSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Wellness.colors
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.account_title),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary,
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), tint = colors.textPrimary)
            }
        }
        val providerName = session.provider?.displayName() ?: stringResource(R.string.action_log_in)
        CelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.displayName ?: stringResource(R.string.account_user, providerName),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                PillBadge(providerName, fill = colors.mint)
            }
        }
        CelButton(stringResource(R.string.health_settings), onOpenHealthSettings, fill = colors.surface, textColor = colors.textPrimary)
        CelButton(stringResource(R.string.action_log_out), onLogOut, fill = colors.pink)
    }
}
