package leakcanary

import android.app.Application
import leakcanary.internal.BackgroundListener
import leakcanary.internal.friendly.checkMainThread

import leakcanary.AnalysisJobHandler.JobState.STARTED
import leakcanary.AnalysisJobHandler.JobState.STOPPED

class BackgroundTrigger(
  private val application: Application,
  private val analysisJobHandler: AnalysisJobHandler,
  processInfo: ProcessInfo = ProcessInfo.Real,
) {

  private val backgroundListener = BackgroundListener(processInfo) { appInBackgroundNow ->
    val jobState = if (appInBackgroundNow) {
      STARTED
    } else {
      STOPPED
    }
    analysisJobHandler.updateJobState(BackgroundTrigger::class, jobState)
  }

  fun start() {
    checkMainThread()
    backgroundListener.install(application)
  }

  fun stop() {
    checkMainThread()
    backgroundListener.uninstall(application)
  }
}
