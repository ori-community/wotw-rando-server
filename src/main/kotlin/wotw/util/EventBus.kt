package wotw.util

import kotlin.reflect.KClass

class EventBus {
    private val messages: MutableMap<KClass<*>, MutableList<suspend (Any) -> Unit>> = hashMapOf()
    private val registrations: MutableMap<Any, MutableList<Pair<KClass<*>, suspend (Any) -> Unit>>> = hashMapOf()

    fun <T : Any> register(receiver: Any, messageType: KClass<T>, block: suspend (T) -> Unit) {
        val typeErasedBlock: suspend (Any) -> Unit = { block(it as T) }
        registrations.getOrPut(receiver) { mutableListOf() }.add(messageType to typeErasedBlock)
        messages.getOrPut(messageType) { mutableListOf() }.add(typeErasedBlock)
    }

    fun unregisterAllForReceiver(receiver: Any) {
        registrations.remove(receiver)?.forEach { (type, handler) ->
            messages.getOrPut(type) { mutableListOf() }.remove(handler)
        }
    }

    fun unregisterAll() {
        registrations.clear()
    }

    suspend fun send(message: Any) {
        //this is for js-compatibility - sadly .supertypes doesn't exist on js
        //There's *very easy* optimization possible here, but we'll ignore that until necessary
        messages.filterKeys { it.isInstance(message) }.forEach { (_, list) -> list.forEach { it.invoke(message) } }
    }
}

class EventBusWithMetadata<META_TYPE> {
    private val messages: MutableMap<KClass<*>, MutableList<suspend (Any, META_TYPE) -> Unit>> = hashMapOf()
    private val registrations: MutableMap<Any, MutableList<Pair<KClass<*>, suspend (Any, META_TYPE) -> Unit>>> = hashMapOf()

    fun <T : Any> register(receiver: Any, messageType: KClass<T>, block: suspend (T, META_TYPE) -> Unit) {
        val typeErasedBlock: suspend (Any, META_TYPE) -> Unit = { message, meta ->
            block(message as T, meta)
        }
        registrations.getOrPut(receiver) { mutableListOf() }.add(messageType to typeErasedBlock)
        messages.getOrPut(messageType) { mutableListOf() }.add(typeErasedBlock)
    }

    fun unregisterAllForReceiver(receiver: Any) {
        registrations.remove(receiver)?.forEach { (type, handler) ->
            messages.getOrPut(type) { mutableListOf() }.remove(handler)
        }
    }

    fun unregisterAll() {
        registrations.clear()
    }

    suspend fun send(message: Any, meta: META_TYPE) {
        //this is for js-compatibility - sadly .supertypes doesn't exist on js
        //There's *very easy* optimization possible here, but we'll ignore that until necessary
        messages.filterKeys { it.isInstance(message) }.forEach { (_, list) -> list.forEach { it(message, meta) } }
    }
}