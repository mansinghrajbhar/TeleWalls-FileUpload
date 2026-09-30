package me.jaival.telewalls.ui.screens.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.jaival.telewalls.ui.components.GeminiApiKeyDialog
import me.jaival.telewalls.viewmodel.SettingsViewModel

@Composable
fun AiScreen(settingsViewModel: SettingsViewModel) {
    val configured by settingsViewModel.hasGeminiApiKey.collectAsState()
    var showKeyDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("AI", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black))
        Text("Gemini-powered file intelligence", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.padding(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (configured) "Gemini AI is ready" else "Gemini AI is not configured", fontWeight = FontWeight.Bold)
                    Text(if (configured) "AI runs only after you approve processing." else "Add your own Gemini API key to enable AI.")
                }
            }
        }

        AiFeatureRow(Icons.Outlined.Category, "Smart categorization", "Suggest categories for uploaded files.")
        AiFeatureRow(Icons.Outlined.Label, "AI descriptions & tags", "Generate useful descriptions and searchable tags.")
        AiFeatureRow(Icons.Outlined.ImageSearch, "Image analysis", "Analyze images and generate visual labels.")
        AiFeatureRow(Icons.Outlined.Security, "Ask before processing", "No file is sent to Gemini without your confirmation.")

        Spacer(Modifier.height(4.dp))
        Button(onClick = { showKeyDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (configured) "Manage Gemini API Key" else "Add Gemini API Key")
        }
        if (configured) {
            OutlinedButton(
                onClick = { settingsViewModel.clearGeminiApiKey() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Remove Gemini API Key") }
        }
    }

    if (showKeyDialog) {
        GeminiApiKeyDialog(settingsViewModel = settingsViewModel, onDismiss = { showKeyDialog = false })
    }
}

@Composable
private fun AiFeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.padding(6.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}