package wotw.util

class MultiMap<K, V>(private val backingMap: MutableMap<K, MutableSet<V>>): Map<K, MutableSet<V>> by backingMap{
    constructor(): this(hashMapOf())

    override operator fun get(key: K): MutableSet<V>{
        return backingMap.getOrPut(key){ mutableSetOf()}
    }

    fun add(key: K, value: V){
        get(key) += value
    }

    fun addAll(pairs: Collection<Pair<K,V>>){
        pairs.forEach{
            add(it.first, it.second)
        }
    }

    fun add(key: K, values: Collection<V>){
        values.forEach{
            add(key, it)
        }
    }

    fun remove(key: K, value: V){
        if(containsKey(key))
            get(key) -= value
    }

    operator fun plus(other: MultiMap<K, V>): MultiMap<K,V>{
        val result = MultiMap<K,V>()
        this.entries.forEach {
            result.add(it.key, it.value)
        }
        other.entries.forEach {
            result.add(it.key, it.value)
        }
        return result
    }
}