package leakcanary

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SCREEN_OFF
import android.content.Intent.ACTION_SCREEN_ON
import android.content.IntentFilter
import android.os.Build
import leakcanary.internal.friendly.checkMainThread
import leakcanary.AnalysisJobHandler.JobState.STARTED
import leakcanary.AnalysisJobHandler.JobState.STOPPED

class ScreenOffTrigger(
  private val application: Application,
  private val analysisJobHandler: AnalysisJobHandler
) {

  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(
      context: Context,
      intent: Intent
    ) {
      val jobState = if (intent.action == ACTION_SCREEN_OFF)
        STARTED
      else {
        STOPPED
      }
      analysisJobHandler.updateJobState(ScreenOffTrigger::class, jobState)
    }
  }

  fun start() {
    checkMainThread()
    val intentFilter = IntentFilter().apply {
      addAction(ACTION_SCREEN_ON)
      addAction(ACTION_SCREEN_OFF)
    }
    if (Build.VERSION.SDK_INT >= 33) {
      val flags = Context.RECEIVER_EXPORTED
      application.registerReceiver(screenReceiver, intentFilter, flags)
    } else {
      application.registerReceiver(screenReceiver, intentFilter)
    }
  }

  fun stop() {
    checkMainThread()
    application.unregisterReceiver(screenReceiver)
    analysisJobHandler.shutdown()
  }
}
