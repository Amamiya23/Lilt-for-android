package com.lilt.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherResumeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launcherReusesPlayerActivityAndKeepsEachPanel() {
        val activity = compose.activity
        val app = activity.application
        val vm = ViewModelProvider(activity)[LiltViewModel::class.java]
        val originalPanel = vm.state.value.playerPanel
        var created = 0
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity is MainActivity) created++ }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            for (panel in PlayerPanel.entries) {
                compose.runOnUiThread { vm.enablePreview(); vm.showPlayer(true); vm.setPlayerPanel(panel) }
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                instrumentation.waitForIdleSync()
                instrumentation.context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClass(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                instrumentation.waitForIdleSync()
                compose.runOnIdle {
                    assertEquals(0, created)
                    assertTrue(vm.state.value.playerOpen)
                    assertEquals(panel, vm.state.value.playerPanel)
                }
            }
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
            compose.runOnUiThread { vm.setPlayerPanel(originalPanel) }
        }
    }
}
