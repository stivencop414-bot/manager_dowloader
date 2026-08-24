package com.managerdownloader.app

import android.app.Application
import com.managerdownloader.app.data.DownloadRepository

class ManagerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadRepository.initialize(this)
    }
}
