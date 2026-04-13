/*
 * Copyright 2025 Google LLC
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
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.tool
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "AGMATask"

/**
 * A custom task that demonstrates how to use function calling to control various device
 * functionalities.
 */
class MobileActionsTask @Inject constructor(
  private val context: Context
) : CustomTask {
  private var curActions = mutableStateListOf<Action>()
  private val tools = listOf(tool(MobileActionsTools(onFunctionCalled = { curActions.add(it) })))

  override val task =
    Task(
      id = BuiltInTaskId.LLM_MOBILE_ACTIONS,
      label = context.getString(R.string.task_label_mobile_actions),
      labelResId = R.string.task_label_mobile_actions,
      description = context.getString(R.string.task_desc_mobile_actions),
      descriptionResId = R.string.task_desc_mobile_actions,
      shortDescription = context.getString(R.string.task_short_desc_mobile_actions),
      shortDescriptionResId = R.string.task_short_desc_mobile_actions,
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions",
      category = Category.LLM,
      icon = Icons.Outlined.Functions,
      agentNameRes = R.string.chat_agent_agent_name,
      models = mutableListOf(),
      experimental = true,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    curActions.clear()

    // Expected to get the current time on user's device.
    LlmChatModelHelper.initialize(
      context = context,
      model = model,
      supportImage = false,
      supportAudio = false,
      onDone = onDone,
      systemInstruction = getSystemPrompt(),
      tools = tools,
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    curActions.clear()
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val customTaskData = data as CustomTaskData
    MobileActionsScreen(
      task = task,
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      bottomPadding = customTaskData.bottomPadding,
      setAppBarControlsDisabled = customTaskData.setAppBarControlsDisabled,
      curActions = curActions,
      tools = tools,
      onProcessingStarted = { curActions.clear() },
    )
  }
}

fun getSystemPrompt(): Contents {
  val now = Date()
  val curDateTimeString = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(now)
  val dayOfWeekString = SimpleDateFormat("EEEE", Locale.getDefault()).format(now)
    return Contents.of(
    listOf(
        "You are a model that can do function calling with the following functions",
        "Current date and time given in YYYY-MM-DDTHH:MM:SS format: ${curDateTimeString}\nDay of week is $dayOfWeekString",
      )
      .map { Content.Text(it) }
  )
}
