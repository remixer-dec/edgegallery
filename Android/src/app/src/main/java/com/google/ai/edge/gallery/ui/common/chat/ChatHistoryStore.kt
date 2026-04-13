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

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS = "chat_history"
private const val KEY = "saved_chats"

data class SavedChatMessage(val content: String, val isUser: Boolean)

data class SavedChat(
  val id: Long,
  val preview: String,
  val messages: List<SavedChatMessage>,
)

object ChatHistoryStore {
  fun load(context: Context): List<SavedChat> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .getString(KEY, null) ?: return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        val msgs = o.getJSONArray("m")
        SavedChat(
          id = o.getLong("id"),
          preview = o.getString("p"),
          messages = List(msgs.length()) { j ->
            val mo = msgs.getJSONObject(j)
            SavedChatMessage(mo.getString("c"), mo.getBoolean("u"))
          },
        )
      }
    }.getOrElse { emptyList() }
  }

  private fun persist(context: Context, chats: List<SavedChat>) {
    val arr = JSONArray()
    for (c in chats) {
      val msgs = JSONArray()
      for (m in c.messages) {
        msgs.put(JSONObject().put("c", m.content).put("u", m.isUser))
      }
      arr.put(JSONObject().put("id", c.id).put("p", c.preview).put("m", msgs))
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit().putString(KEY, arr.toString()).apply()
  }
  
  fun rename(context: Context, id: Long, newPreview: String): List<SavedChat> {
    val updated = load(context).map {
      if (it.id == id) it.copy(preview = newPreview) else it
    }
    persist(context, updated)
    return updated
  }
  
  fun saveCurrent(context: Context, messages: List<ChatMessage>): List<SavedChat> {
    val textMessages = messages
      .filterIsInstance<ChatMessageText>()
      .map { SavedChatMessage(it.content, it.side == ChatSide.USER) }
    if (textMessages.isEmpty()) return load(context)
    val preview = textMessages.firstOrNull { it.isUser }?.content?.take(60) ?: "Chat"
    val updated = listOf(
      SavedChat(System.currentTimeMillis(), preview, textMessages)
    ) + load(context)
    persist(context, updated)
    return updated
  }

  fun delete(context: Context, id: Long): List<SavedChat> {
    val updated = load(context).filterNot { it.id == id }
    persist(context, updated)
    return updated
  }
}