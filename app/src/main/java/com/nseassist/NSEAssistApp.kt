package com.nseassist

import android.app.Application
import com.nseassist.data.repository.NSERepository

class NSEAssistApp : Application() {
    val repository: NSERepository by lazy { NSERepository() }
}
