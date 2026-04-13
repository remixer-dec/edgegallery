/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

@Composable
fun ChatHistoryDialog(
    onDismiss: () -> Unit,
    onSaveCurrent: () -> Unit,
    onLoadChat: (SavedChat) -> Unit,
) {
    val context = LocalContext.current
    var chats by remember { mutableStateOf(ChatHistoryStore.load(context)) }
    var pendingDelete by remember { mutableStateOf<SavedChat?>(null) }
    var pendingRename by remember { mutableStateOf<SavedChat?>(null) }
    var renameText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conversation_history)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                item {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Save, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.chat_history_save_current)) },
                        modifier = Modifier.clickable {
                            onSaveCurrent()
                            chats = ChatHistoryStore.load(context)
                        },
                    )
                    HorizontalDivider()
                }
                items(chats, key = { it.id }) { chat ->
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Chat, contentDescription = null) },
                        headlineContent = {
                            Text(chat.preview, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            IconButton(onClick = { pendingDelete = chat }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.cd_chat_history_delete_icon))
                            }
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                onLoadChat(chat)
                                onDismiss()
                            },
                            onLongClick = {
                                pendingRename = chat
                                renameText = chat.preview
                            }
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )

    pendingDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chat_history_delete_dialog_title)) },
            text = { Text(chat.preview) },
            confirmButton = {
                TextButton(onClick = {
                    chats = ChatHistoryStore.delete(context, chat.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    
    pendingRename?.let { chat ->
        AlertDialog(
          onDismissRequest = { pendingRename = null },
          title = { Text(stringResource(R.string.chat_history_rename_dialog_title)) },
          text = {
            OutlinedTextField(
              value = renameText,
              onValueChange = { renameText = it },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
            )
          },
          confirmButton = {
            TextButton(onClick = {
              if (renameText.isNotBlank()) {
                chats = ChatHistoryStore.rename(context, chat.id, renameText.trim())
              }
              pendingRename = null
            }) { Text(stringResource(R.string.save)) }
           },
           dismissButton = {
             TextButton(onClick = { pendingRename = null }) { Text(stringResource(R.string.cancel)) }
           },
        )
      }
}
