package me.jaival.telewalls.ui.components
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun GeminiProcessingDialog(
    onAnalyze: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use Gemini AI?") },
        text = {
            Text("Gemini will analyze this file before upload to suggest a category, description and tags. For images, it will also generate visual tags. The file/image is sent to Google Gemini only after you choose Analyze.")
        },
        confirmButton = { TextButton(onClick = onAnalyze) { Text("Analyze & Upload") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Upload Without AI") } }
    )
}