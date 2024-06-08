package wotw.server.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Scheduler(private val task: suspend () -> Unit) {
    companion object {
        private val executor = Executors.newScheduledThreadPool(1)
    }

    fun scheduleExecution(every: Every, fixedRate: Boolean = false) {
        val taskWrapper = Runnable {
            runBlocking(Dispatchers.Default) {
                task.invoke()
            }
        }

        if (fixedRate) {
            executor.scheduleAtFixedRate(taskWrapper, every.initialDelay, every.delay, every.unit)
        } else {
            executor.scheduleWithFixedDelay(taskWrapper, every.initialDelay, every.delay, every.unit)
        }
    }

    fun stop() {
        executor.shutdown()
        try {
            executor.awaitTermination(1, TimeUnit.HOURS)
        } catch (_: InterruptedException) { }
    }
}

data class Every(val delay: Long, val unit: TimeUnit, val initialDelay: Long = delay)
