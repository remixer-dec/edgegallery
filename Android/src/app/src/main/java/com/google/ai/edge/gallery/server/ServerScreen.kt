package com.google.ai.edge.gallery.ui.server

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.ai.edge.gallery.proto.AcceleratorOverride
import com.google.ai.edge.gallery.ui.benchmark.BenchmarkModelPicker
import com.google.ai.edge.gallery.ui.common.ConfigDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.AcceleratorSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    modelManagerViewModel: ModelManagerViewModel,
    navigateUp: () -> Unit,
    viewModel: ServerViewModel = hiltViewModel()
) {
    val isRunning by viewModel.isRunning.collectAsState()
    var ipAddress by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("8080") }
    var enableTools by remember { mutableStateOf(false) }
    var enableVision by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    
    val downloadedModels = remember { 
        modelManagerViewModel.getAllDownloadedModels().filter { it.isLlm } 
    }
    var selectedModelName by remember { 
        mutableStateOf(downloadedModels.firstOrNull()?.name ?: "") 
    }
    val selectedModel = remember(selectedModelName) { 
        downloadedModels.find { it.name == selectedModelName } 
    }

    Scaffold(
        topBar = {
            GalleryTopAppBar(
                title = "Local API Server",
                leftAction = AppBarAction(AppBarActionType.NAVIGATE_UP, navigateUp),
                rightAction = AppBarAction(AppBarActionType.APP_SETTING) { showConfigDialog = true }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.server_screen_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.server_screen_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = ipAddress,
                onValueChange = { ipAddress = it },
                label = { Text(stringResource(R.string.server_ip_address_label)) },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text(stringResource(R.string.server_port_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.server_enable_tools_label),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.server_enable_tools_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = enableTools,
                    onCheckedChange = { enableTools = it },
                    enabled = !isRunning
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Vision", style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.server_enable_vision),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = enableVision,
                    onCheckedChange = { enableVision = it },
                    enabled = !isRunning && (selectedModel?.llmSupportImage == true)
                )
            }

            Text(stringResource(R.string.server_select_model_label), style = MaterialTheme.typography.titleSmall)
            if (downloadedModels.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BenchmarkModelPicker(
                        selectedModelName = selectedModelName,
                        modelNames = downloadedModels.map { it.name },
                        titleResId = com.google.ai.edge.gallery.R.string.select_model,
                        onSelected = { selectedModelName = it }
                    )
                }
            } else {
                Text(stringResource(R.string.server_no_llm_models_downloaded), color = MaterialTheme.colorScheme.error)
            }
            

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { 
                    selectedModel?.let { model ->
                        val maxTokens = model.getIntConfigValue(ConfigKeys.MAX_TOKENS, 1024)
                        val topK = model.getIntConfigValue(ConfigKeys.TOPK, 40)
                        val topP = model.getFloatConfigValue(ConfigKeys.TOPP, 0.95f)
                        val temp = model.getFloatConfigValue(ConfigKeys.TEMPERATURE, 1.0f)
                        
                        val accelOverride = AcceleratorSettings.acceleratorOverride.value
                        val accelerator = if (accelOverride != AcceleratorOverride.ACCELERATOR_OVERRIDE_AUTO) {
                            when (accelOverride) {
                                AcceleratorOverride.ACCELERATOR_OVERRIDE_CPU -> "CPU"
                                AcceleratorOverride.ACCELERATOR_OVERRIDE_NPU -> "NPU"
                                else -> "GPU"
                            }
                        } else {
                            model.getStringConfigValue(ConfigKeys.ACCELERATOR, "GPU")
                        }

                        viewModel.toggleServer(
                            ipAddress, port.toInt(), model, enableTools, enableVision,
                            maxTokens, accelerator, topK, topP, temp
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = selectedModel != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(stringResource(if (isRunning) R.string.server_button_stop else R.string.server_button_start))
            }
        }

        if (showConfigDialog && selectedModel != null) {
            val acceleratorOverride = AcceleratorSettings.acceleratorOverride.value
            val effectiveInitialValues = if (acceleratorOverride != AcceleratorOverride.ACCELERATOR_OVERRIDE_AUTO) {
                selectedModel.configValues.toMutableMap().apply {
                    this[ConfigKeys.ACCELERATOR.label] = when (acceleratorOverride) {
                        AcceleratorOverride.ACCELERATOR_OVERRIDE_CPU -> "CPU"
                        AcceleratorOverride.ACCELERATOR_OVERRIDE_GPU -> "GPU"
                        AcceleratorOverride.ACCELERATOR_OVERRIDE_NPU -> "NPU"
                        else -> this[ConfigKeys.ACCELERATOR.label] ?: "GPU"
                    }
                }
            } else {
                selectedModel.configValues
            }

            ConfigDialog(
                title = stringResource(R.string.server_config_dialog_title),
                configs = selectedModel.configs,
                initialValues = effectiveInitialValues,
                onDismissed = { showConfigDialog = false },
                onOk = { curConfigValues, _, _ ->
                    showConfigDialog = false
                    val newAccel = curConfigValues[ConfigKeys.ACCELERATOR.label] as? String
                    val oldAccel = effectiveInitialValues[ConfigKeys.ACCELERATOR.label] as? String
                    if (newAccel != oldAccel) {
                        AcceleratorSettings.acceleratorOverride.value = AcceleratorOverride.ACCELERATOR_OVERRIDE_AUTO
                        modelManagerViewModel.saveAcceleratorOverride(AcceleratorOverride.ACCELERATOR_OVERRIDE_AUTO)
                    }
                    selectedModel.configValues = curConfigValues
                }
            )
        }
    }
}