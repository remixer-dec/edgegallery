package com.google.ai.edge.gallery.ui.server

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.server.LlmServerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class ServerViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _isRunning = MutableStateFlow(LlmServerService.isRunning)
    val isRunning: StateFlow<Boolean> = _isRunning

    fun toggleServer(
        ip: String, 
        port: Int, 
        model: Model, 
        useTools: Boolean, 
        enableVision: Boolean,
        maxTokens: Int, 
        accelerator: String, 
        topK: Int, 
        topP: Float, 
        temperature: Float
    ) {
        val intent = Intent(context, LlmServerService::class.java)
        if (_isRunning.value) {
            intent.action = LlmServerService.ACTION_STOP
            context.startService(intent)
            _isRunning.update { false }
        } else {
            intent.action = LlmServerService.ACTION_START
            intent.putExtra(LlmServerService.EXTRA_IP, ip)
            intent.putExtra(LlmServerService.EXTRA_PORT, port)
            intent.putExtra(LlmServerService.EXTRA_MODEL_NAME, model.name)
            intent.putExtra(LlmServerService.EXTRA_MODEL_PATH, model.getPath(context))
            intent.putExtra(LlmServerService.EXTRA_ENABLE_TOOLS, useTools)
            intent.putExtra(LlmServerService.EXTRA_MAX_TOKENS, maxTokens)
            intent.putExtra(LlmServerService.EXTRA_ACCELERATOR, accelerator)
            intent.putExtra(LlmServerService.EXTRA_TOP_K, topK)
            intent.putExtra(LlmServerService.EXTRA_TOP_P, topP)
            intent.putExtra(LlmServerService.EXTRA_TEMPERATURE, temperature)
            intent.putExtra(LlmServerService.EXTRA_ENABLE_VISION, enableVision)
            
            context.startService(intent)
            _isRunning.update { true }
        }
    }
}