package com.deskmail.ui.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.deskmail.R
import desktopkit.components.DeskButton
import desktopkit.components.DeskDialog
import desktopkit.components.DeskTextField

/**
 * A kept text's name and words, to be written or changed: "Keep as a template" while writing (the
 * words are what was written; only the name is asked), and the templates in Settings.
 *
 * @param onDelete offered for a text that is kept already.
 */
@Composable
fun TemplateDialog(
    title: String,
    initialName: String,
    initialBody: String,
    onDismiss: () -> Unit,
    onSave: (name: String, body: String) -> Unit,
    editBody: Boolean = true,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var body by remember { mutableStateOf(initialBody) }
    val canSave = name.isNotBlank() && body.isNotBlank()
    DeskDialog(
        onDismiss = onDismiss,
        title = title,
        actions = {
            if (onDelete != null) DeskButton(stringResource(R.string.template_delete), onDelete)
            DeskButton(stringResource(R.string.template_save), { onSave(name.trim(), body) }, primary = true, enabled = canSave)
        },
    ) {
        DeskTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth().testTag(TemplateTags.NAME),
            label = stringResource(R.string.template_name),
            hint = stringResource(R.string.template_name_hint),
        )
        if (editBody) {
            DeskTextField(
                value = body,
                onValueChange = { body = it },
                modifier = Modifier.fillMaxWidth().testTag(TemplateTags.BODY),
                label = stringResource(R.string.template_body),
                singleLine = false,
                minLines = BODY_LINES,
                maxLines = Int.MAX_VALUE,
            )
        }
    }
}

/** Names the tests find the dialog's fields by. */
object TemplateTags {
    const val NAME = "template-name"
    const val BODY = "template-body"
}

/** The text field is this many lines tall before anything is written. */
private const val BODY_LINES = 5
