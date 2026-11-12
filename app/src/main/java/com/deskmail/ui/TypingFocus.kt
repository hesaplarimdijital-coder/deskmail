package com.deskmail.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * Whether text is being typed in the window whose views hang under [root]: the single keys (J, K,
 * S, …) belong to the text then, not to the shortcut table.
 *
 * Two answers, either of which is enough:
 * - the window's focused view says it is a text editor (View.onCheckIsTextEditor): Android's own
 *   contract, which also covers a text field inside a message's WebView;
 * - a text field of the Compose content has the focus, read from Compose's own description of
 *   the screen (the semantics tree that accessibility services and tests read). This does not
 *   depend on which view the window believes is focused.
 */
fun isTyping(root: View, focused: View?): Boolean =
    focused?.onCheckIsTextEditor() == true || composeTextFieldFocused(root)

/** Whether a text field of the Compose content under [root] has the focus. */
fun composeTextFieldFocused(root: View): Boolean {
    if (root is ViewRootForTest) return focusedTextField(root.semanticsOwner.unmergedRootSemanticsNode)
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            if (composeTextFieldFocused(root.getChildAt(i))) return true
        }
    }
    return false
}

private fun focusedTextField(node: SemanticsNode): Boolean {
    val config = node.config
    if (config.getOrNull(SemanticsProperties.Focused) == true && config.contains(SemanticsActions.SetText)) return true
    return node.children.any { focusedTextField(it) }
}
