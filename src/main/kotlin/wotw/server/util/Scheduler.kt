package wotw.server.util

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import wotw.server.main.WotwBackendServer
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

internal class SchedulerThreadFactory(private val name: String) : ThreadFactory {
    override fun newThread(r: Runnable): Thread {
        return Thread(r, name)
    }
}

class Scheduler(private val name: String, private val task: suspend () -> Unit) {
    private val executor = Executors.newSingleThreadScheduledExecutor(SchedulerThreadFactory(name))

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    fun scheduleExecution(every: Every, fixedRate: Boolean = false) {
        val taskWrapper = Runnable {
            try {
                runBlocking(WotwBackendServer.serverCoroutineContext) {
                    task()
                }
            } catch (e: Throwable) {
                logger().error("Encountered exception in scheduler '$name': ${e.message}", e)
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
