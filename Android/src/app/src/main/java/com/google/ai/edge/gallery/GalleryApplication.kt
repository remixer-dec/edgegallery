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

package com.google.ai.edge.gallery

import android.app.Application
import android.util.Log
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.ui.theme.AcceleratorSettings
import com.google.ai.edge.gallery.ui.theme.SystemPromptSettings
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.firebase.FirebaseApp
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository

  override fun onCreate() {
    super.onCreate()

    // Pre-load custom LiteRT lib BEFORE any litertlm class is accessed.
    val overridePath = dataStoreRepository.readLiteRtLibOverridePath()
    if (overridePath.isNotEmpty() && java.io.File(overridePath).exists()) {
      try {
        System.load(overridePath)
        Log.i(TAG, "Loaded custom LiteRT lib: $overridePath")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load custom LiteRT lib", e)
      }
    }

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()
    AcceleratorSettings.acceleratorOverride.value = dataStoreRepository.readAcceleratorOverride()
    SystemPromptSettings.systemPrompt.value = dataStoreRepository.readSystemPrompt()

    FirebaseApp.initializeApp(this)
    try {
      val isAnalyticsDisabled = dataStoreRepository.isAnalyticsDisabled()
            
      com.google.ai.edge.gallery.firebaseAnalytics?.setAnalyticsCollectionEnabled(!isAnalyticsDisabled)
    } catch (e: Exception) {
      android.util.Log.w("GalleryApplication", "Firebase Analytics not configured.", e)
    }
  }

  companion object {
    private const val TAG = "GalleryApplication"
  }
}
