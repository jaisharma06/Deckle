package com.avyaan.deckle.designsystem.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.avyaan.deckle.R

/**
 * A single-field naming dialog. The current name arrives fully selected so typing replaces it; [hint]
 * names what an empty field falls back to (e.g. "Page 3"), so clearing the field is a valid answer.
 */
@Composable
fun RenameDialog(
    title: String,
    initial: String,
    hint: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    maxLength: Int = 120,
) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOfField(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { if (it.text.length <= maxLength) field = it },
                singleLine = true,
                placeholder = { Text(hint) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(field.text) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(field.text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun mutableStateOfField(initial: String) =
    androidx.compose.runtime.mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
