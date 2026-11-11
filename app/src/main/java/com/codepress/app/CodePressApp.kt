package com.codepress.app

import android.app.Application
import com.codepress.app.data.ProjectStore

class CodePressApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First launch: create the CodePress folder (and projects.json) on the phone.
        ProjectStore(this)
    }
}
