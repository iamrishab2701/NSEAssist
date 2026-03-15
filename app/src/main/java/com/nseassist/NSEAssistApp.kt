package com.nseassist

import android.app.Application
import com.nseassist.data.ai.AiAnalysisService
import com.nseassist.data.local.AiSettingsStore
import com.nseassist.data.repository.NSERepository

class NSEAssistApp : Application() {
    val repository: NSERepository by lazy { NSERepository() }
    val aiSettingsStore: AiSettingsStore by lazy { AiSettingsStore(applicationContext) }
    val aiAnalysisService: AiAnalysisService by lazy { AiAnalysisService() }
}
