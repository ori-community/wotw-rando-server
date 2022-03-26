package wotw.server.util

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.routing.*
import io.ktor.util.pipeline.*
import org.slf4j.LoggerFactory
import wotw.io.messages.protobuf.PrintTextMessage
import wotw.io.messages.protobuf.Vector2
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

inline fun <reified T : Any> T.logger() = LoggerFactory.getLogger(T::class.java)

@ContextDsl
@JvmName("putTyped")
inline fun <reified R : Any> Route.put(
    path: String,
    crossinline body: suspend PipelineContext<Unit, ApplicationCall>.(R) -> Unit
): Route {
    return route(path, HttpMethod.Put) {
        handle {
            body(call.receive())
        }
    }
}
object CompletableFuture{
    fun <T> supplyAsync(executor: Executor? = null, block: () -> T) =
        if (executor == null) CompletableFuture.supplyAsync { block() } else CompletableFuture.supplyAsync(
            { block() },
            executor
        )
}

fun rezero(n: Int) = if (n == -1) 0 else n
fun rezero(n: Double) = if (n == -1.0) 0.0 else n
fun zerore(n: Int) = if (n == 0) -1 else n
fun zerore(n: Double) = if (n == 0.0) -1.0 else n

fun randomString(length: Int) : String {
    val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
    return (1..length)
        .map { allowedChars.random() }
        .joinToString("")
}

fun makeServerMessage(text: String, time: Float = 3.0f): PrintTextMessage = PrintTextMessage(
    time = time,
    text = text,
    position = Vector2(0f, -2f),
    screenPosition = PrintTextMessage.SCREEN_POSITION_MIDDLE_CENTER,
    queue = "server",
)