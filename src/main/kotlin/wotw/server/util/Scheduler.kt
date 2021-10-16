package wotw.server.util

import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Scheduler(private val task: suspend () -> Unit) {
    private val executor = Executors.newScheduledThreadPool(1)
    fun scheduleExecution(every: Every) {
        val taskWrapper = Runnable {
            runBlocking {
                task.invoke()
            }
        }
        executor.scheduleWithFixedDelay(taskWrapper, every.n, every.n, every.unit)
    }

    fun stop() {
        executor.shutdown()
        try {
            executor.awaitTermination(1, TimeUnit.HOURS)
        } catch (e: InterruptedException) {
        }

    }
}

data class Every(val n: Long, val unit: TimeUnit)