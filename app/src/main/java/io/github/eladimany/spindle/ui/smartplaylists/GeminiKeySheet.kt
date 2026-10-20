package io.github.eladimany.spindle.ui.smartplaylists

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eladimany.spindle.data.smartplaylists.GeminiApi
import io.github.eladimany.spindle.ui.components.glow

/**
 * The free Gemini key setup, mockup F2–F3: the three steps, a wait while AI Studio is
 * open, then the key found on the clipboard (or a paste field) and Google's check.
 */
@Composable
fun KeySetupSheet(
    setup: KeySetup,
    onOpenKeyPage: () -> Unit,
    onLeftApp: () -> Unit,
    onReturned: (CharSequence?) -> Unit,
    onEdit: (String) -> Unit,
    onCheck: () -> Unit,
    onUseKey: () -> Unit,
    onPasteDifferent: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Back from AI Studio: read what the user copied. Android only lets the focused
        // window read the clipboard, so wait for focus — and it must be the sheet's own
        // window: the sheet is a separate window, the screen behind it never regains focus.
        val context = LocalContext.current
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(focused, setup) {
            val waiting = setup as? KeySetup.WaitingForCopy ?: return@LaunchedEffect
            when {
                !focused -> onLeftApp()
                waiting.leftApp -> {
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    onReturned(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context))
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when (setup) {
                KeySetup.Guide -> GuideContent(onOpenKeyPage)
                is KeySetup.WaitingForCopy -> WaitingContent(onOpenKeyPage, onPasteDifferent)
                is KeySetup.Entry -> EntryContent(setup, onEdit, onCheck, onUseKey, onPasteDifferent)
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, letterSpacing = (-0.3).sp)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** F2. */
@Composable
private fun GuideContent(onOpenKeyPage: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    SheetTitle("Three quick steps", "Google's site opens. Then come right back.")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Step(1, "Google AI Studio opens", "Already signed in with this phone's Google account. If asked, accept Google's terms.")
        Step(
            2,
            "Tap “Create API key”",
            buildAnnotatedString {
                append("If it asks which project, choose a ")
                withStyle(SpanStyle(color = colors.onSurface, fontWeight = FontWeight.Medium)) { append("new project") }
                append(" — that keeps it on the free plan.")
            },
        )
        Step(3, "Tap Copy, then come back", "Spindle picks the key up by itself.")
    }
    FreePlanNote()
    PrimaryButton("Open Google AI Studio", onClick = onOpenKeyPage) {
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Step(number: Int, title: String, body: String) = Step(number, title, AnnotatedString(body))

@Composable
private fun Step(number: Int, title: String, body: AnnotatedString) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Text("$number", style = MaterialTheme.typography.labelLarge, color = colors.primary, fontWeight = FontWeight.Bold) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun FreePlanNote() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.primary.copy(alpha = 0.06f))
            .border(1.dp, colors.primary.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            Text("Can this ever cost me money?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            "No. A new key starts on Google's free plan with no card attached, so there's nothing to charge. " +
                "If the daily free limit runs out, Google just says “try later”.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Text(
            "Spindle never asks you to turn on billing in Google — leave it off.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

/** AI Studio is open; shown if the user comes back to the sheet before copying. */
@Composable
private fun WaitingContent(onOpenKeyPage: () -> Unit, onPaste: () -> Unit) {
    SheetTitle("Waiting for your key", "Copy the key in Google AI Studio, then come back here.")
    PrimaryButton("Open Google AI Studio again", onClick = onOpenKeyPage) {
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    TextButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) { Text("Paste it myself") }
}

/** F3. */
@Composable
private fun EntryContent(
    entry: KeySetup.Entry,
    onEdit: (String) -> Unit,
    onCheck: () -> Unit,
    onUseKey: () -> Unit,
    onPasteDifferent: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (entry.fromClipboard) {
        SheetTitle("Found your key", "You copied it from Google AI Studio.")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, colors.primary.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Key, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            Text(GeminiApi.mask(entry.text), fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        }
    } else {
        SheetTitle("Paste your key", "The key you copied in Google AI Studio.")
        entry.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        OutlinedTextField(
            value = entry.text,
            onValueChange = onEdit,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Gemini API key") },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onCheck() }),
        )
    }

    when (val check = entry.check) {
        CheckState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Checking with Google…", style = MaterialTheme.typography.bodyMedium, color = colors.primary)
        }
        CheckState.Works -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            Text("Key works", style = MaterialTheme.typography.bodyMedium, color = colors.primary)
        }
        is CheckState.Failed -> Text(
            check.message,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.tertiary.copy(alpha = 0.06f))
                .border(1.dp, colors.tertiary.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        null -> Unit
    }

    if (entry.check == CheckState.Works) {
        PrimaryButton("Use this key", onClick = onUseKey)
    } else {
        PrimaryButton(
            if (entry.check is CheckState.Failed) "Check again" else "Check key",
            onClick = onCheck,
            enabled = entry.text.isNotBlank() && entry.check != CheckState.Checking,
        )
    }
    if (entry.fromClipboard) {
        TextButton(onClick = onPasteDifferent, modifier = Modifier.fillMaxWidth()) { Text("Paste a different key") }
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(if (enabled) Modifier.glow(colors.primary.copy(alpha = 0.35f), radius = 16.dp, cornerRadius = 28.dp) else Modifier),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (trailing != null) {
            Box(Modifier.padding(start = 8.dp)) { trailing() }
        }
    }
}
