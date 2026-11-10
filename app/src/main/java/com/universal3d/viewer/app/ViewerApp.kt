package com.universal3d.viewer.app

import android.app.Application

class ViewerApp : Application() {
    lateinit var loader: ModelLoader
        private set
    lateinit var recent: RecentFiles
        private set

    override fun onCreate() {
        super.onCreate()
        loader = ModelLoader(this)
        recent = RecentFiles(this)
    }
}
