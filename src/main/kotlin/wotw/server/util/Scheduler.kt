package wotw.server.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Scheduler(private val task: suspend () -> Unit) {
    private val executor = Executors.newScheduledThreadPool(1)

    fun scheduleExecution(every: Every, fixedRate: Boolean = false) {
        val taskWrapper = Runnable {
            runBlocking(Dispatchers.Default) {
                task.invoke()
            }
        }

        if (fixedRate) {
            executor.scheduleAtFixedRate(taskWrapper, every.n, every.n, every.unit)
        } else {
            executor.scheduleWithFixedDelay(taskWrapper, every.n, every.n, every.unit)
        }
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