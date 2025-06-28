package wotw.server.util

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import wotw.server.main.WotwBackendServer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Scheduler(private val task: suspend () -> Unit) {
    companion object {
        private val executor = Executors.newSingleThreadScheduledExecutor()
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    fun scheduleExecution(every: Every, fixedRate: Boolean = false) {
        val taskWrapper = Runnable {
            runBlocking(WotwBackendServer.serverCoroutineContext) {
                task()
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
            executor.awaitTermination(1, TimeUnit.MINUTES)
        } catch (_: InterruptedException) { }
    }
}

data class Every(val delay: Long, val unit: TimeUnit, val initialDelay: Long = delay)
