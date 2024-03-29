package wotw.server.util

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.StatementInterceptor
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.slf4j.LoggerFactory
import wotw.io.messages.protobuf.PrintTextMessage
import wotw.io.messages.protobuf.Vector2
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

inline fun <reified T : Any> T.logger() = LoggerFactory.getLogger(T::class.java)

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

object CompletableFuture {
    fun <T> supplyAsync(executor: Executor? = null, block: () -> T) =
        if (executor == null) CompletableFuture.supplyAsync { block() } else CompletableFuture.supplyAsync(
            { block() },
            executor
        )
}

fun randomString(length: Int): String {
    val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
    return (1..length)
        .map { allowedChars.random() }
        .joinToString("")
}

fun makeServerTextMessage(text: String, time: Float = 3.0f): PrintTextMessage =
    PrintTextMessage(
        time = time,
        text = text,
        position = Vector2(0f, -2f),
        screenPosition = PrintTextMessage.SCREEN_POSITION_MIDDLE_CENTER,
        queue = "server",
        id = 0,
    )

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

fun assertTransaction() {
    assert(TransactionManager.currentOrNull() != null) {
        "This function has to be called in an active transaction"
    }
}

fun doAfterTransaction(action: suspend () -> Unit) {
    TransactionManager.current().registerInterceptor(object : StatementInterceptor {
        override fun afterCommit(transaction: Transaction) {
            runBlocking {
                launch {
                    action()
                }
            }
        }
    })
}
