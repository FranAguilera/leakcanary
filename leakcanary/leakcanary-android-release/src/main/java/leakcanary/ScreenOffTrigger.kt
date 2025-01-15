package leakcanary

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SCREEN_OFF
import android.content.Intent.ACTION_SCREEN_ON
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import leakcanary.internal.friendly.checkMainThread
import shark.SharkLog

class ScreenOffTrigger(
  private val application: Application,
  private val analysisClient: HeapAnalysisClient,
  /**
   * The executor on which the analysis is performed and on which [analysisCallback] is called.
   * This should likely be a single thread executor with a background thread priority.
   */
  analysisExecutor: Executor,

  /**
   * Called back with a [HeapAnalysisJob.Result] after the screen went off and a
   * heap analysis was attempted. This is called on the same thread that the analysis was
   * performed on.
   *
   * Defaults to logging to [SharkLog] (don't forget to set [SharkLog.logger] if you do want to see
   * logs).
   */
  private val analysisCallback: (HeapAnalysisJob.Result) -> Unit = { result ->
    SharkLog.d { "$result" }
  },

  /**
   * Initial delay to wait for analysisExecutor to start analysis
   *
   * If not provided, initial delay is 500ms
   */
  private val analysisExecutorDelayMillis: Long = INITIAL_EXECUTOR_DELAY_IN_MILLI
) {

  private val currentJob = AtomicReference<HeapAnalysisJob?>()

  private val delayedScheduledExecutorService = DelayedScheduledExecutorService()
  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(
      context: Context,
      intent: Intent
    ) {
      if (intent.shouldStartAnalysis()) {
          val job =
            analysisClient.newJob(JobContext(ScreenOffTrigger::class))
          if(currentJob.compareAndSet(null, job)){
            delayedScheduledExecutorService.schedule {
              val result = job.execute()
              analysisCallback(result)
            }
          }
      } else {
        delayedScheduledExecutorService.cancel()
        currentJob.getAndUpdate { job ->
          job?.cancel("screen on again")
          null
        }
      }
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
  }

  private fun Intent.shouldStartAnalysis():Boolean{
    return this.action == ACTION_SCREEN_OFF && currentJob.get() == null
  }

  private class DelayedScheduledExecutorService(
    private val analysisExecutorDelayMillis:Long,
    private val analysisExecutor: Executor){

    private var scheduledFuture:ScheduledFuture<*>?=null

    private val scheduledExecutorService:ScheduledExecutorService by lazy {
      Executors.newScheduledThreadPool(1)
    }

    /** Runs the specified [action] after an initial [analysisExecutorDelayMillis] */
    fun schedule(action:Runnable){
      scheduledFuture =
        scheduledExecutorService.schedule(
          { analysisExecutor.execute(action)},
          analysisExecutorDelayMillis,
          TimeUnit.MILLISECONDS
        )
    }

    /** Cancels prior ScheduledExecutorService */
    fun cancel(){
      scheduledFuture?.cancel(true)
    }
  }

  companion object{
    const val INITIAL_EXECUTOR_DELAY_IN_MILLI = 500L
  }
}
