package leakcanary

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass
import shark.SharkLog

class AnalysisJobHandler(

  private val analysisClient: HeapAnalysisClient,
  /**
   * The executor on which the analysis is performed and on which [analysisCallback] is called.
   * This should likely be a single thread executor with a background thread priority.
   */
  analysisExecutor: Executor,

  /**
   * The initial delay (in milliseconds) before the [analysisExecutor] starts
   *
   * If not specified, the default initial delay is set to 100 milliseconds.
   */
  analysisExecutorDelayMillis: Long = INITIAL_EXECUTOR_DELAY_IN_MILLI,

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
) {

  private val delayedScheduledExecutorService: DelayedScheduledExecutorService =
    DelayedScheduledExecutorService(analysisExecutor, analysisExecutorDelayMillis)

  private var currentJob = AtomicReference<HeapAnalysisJob?>()

  internal fun updateJobState(
    jobStarterPoint: KClass<*>,
    jobState: JobState
  ) {
    when (jobState) {
      JobState.STARTED -> executeJobIfNeeded(jobStarterPoint)
      JobState.STOPPED -> cancelExistingJob()
    }
  }

  internal fun shutdown() {
    delayedScheduledExecutorService.shutdown()
  }

  private fun executeJobIfNeeded(jobStarterPoint: KClass<*>) {
    if (currentJob.get() != null) {
      return
    }

    val job =
      analysisClient.newJob(JobContext(jobStarterPoint))
    if (currentJob.compareAndSet(null, job)) {
      delayedScheduledExecutorService.schedule {
        val result = job.execute()
        analysisCallback(result)
      }
    }
  }

  private fun cancelExistingJob() {
    currentJob.getAndUpdate { job ->
      job?.cancel("screen on again")
      null
    }
  }

  private class DelayedScheduledExecutorService(
    private val analysisExecutor: Executor,
    private val analysisExecutorDelayMillis: Long
  ) {

    private val scheduledExecutor: ScheduledExecutorService by lazy {
      Executors.newScheduledThreadPool(1)
    }

    private var scheduledFuture: ScheduledFuture<*>? = null

    /**
     * Runs the specified [action] after an initial [analysisExecutorDelayMillis]
     */
    fun schedule(action: Runnable) {
      scheduledFuture = scheduledExecutor.schedule(
        {
          analysisExecutor.execute(action)
        },
        analysisExecutorDelayMillis,
        TimeUnit.MILLISECONDS
      )
    }

    fun shutdown() {
      scheduledFuture?.cancel(true)
      scheduledExecutor.shutdownNow()
    }
  }

  internal enum class JobState {
    STARTED,
    STOPPED
  }

  companion object {
    const val INITIAL_EXECUTOR_DELAY_IN_MILLI = 100L
  }
}
