package me.jaival.telewalls.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.jaival.telewalls.viewmodel.SettingsViewModel

@Composable
fun GeminiApiKeyDialog(settingsViewModel: SettingsViewModel, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gemini AI") },
        text = {
            Column {
                Text("Add your Gemini API key to enable AI categorization, descriptions and image tags. The key is stored locally using Android encrypted storage.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Gemini API key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("AI is never called automatically. TeleFiles will ask before sending a file to Gemini.")
            }
        },
        confirmButton = {
            Button(onClick = { if (key.isNotBlank()) settingsViewModel.saveGeminiApiKey(key); onDismiss() }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = { settingsViewModel.clearGeminiApiKey(); onDismiss() }) {
                Text("Remove key")
            }
        }
    )
}