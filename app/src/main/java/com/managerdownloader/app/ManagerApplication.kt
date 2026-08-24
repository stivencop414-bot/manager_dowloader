package com.managerdownloader.app

import android.app.Application
import com.managerdownloader.app.browser.ContentBlocker
import com.managerdownloader.app.data.DownloadRepository
import com.managerdownloader.app.data.SettingsRepository
import com.managerdownloader.app.data.StorageRepository

class ManagerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsRepository.initialize(this)
        StorageRepository.initialize(this)
        DownloadRepository.initialize(this)
        ContentBlocker.initialize(this)
    }
}
