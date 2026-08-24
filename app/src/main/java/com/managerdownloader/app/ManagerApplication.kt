package com.managerdownloader.app

import android.app.Application
import com.managerdownloader.app.browser.ContentBlocker
import com.managerdownloader.app.data.DownloadRepository
import com.managerdownloader.app.data.SettingsRepository

class ManagerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsRepository.initialize(this)
        DownloadRepository.initialize(this)
        ContentBlocker.initialize(this)
    }
}
