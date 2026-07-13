// 130726 Initial implementation
package com.motionamp.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ProcessingScreen(progress: Float, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Amplifying motion…")
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        )
        Text("${(progress * 100).toInt()}%")
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
