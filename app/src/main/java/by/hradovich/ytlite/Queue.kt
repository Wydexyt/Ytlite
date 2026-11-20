package by.hradovich.ytlite

/** Элемент очереди: видео или музыкальный трек. */
data class QItem(
    val id: String,
    val title: String,
    val channel: String,
    val thumb: String?,
    val music: Boolean = false,
) {
    companion object {
        fun of(v: Video, music: Boolean = false) = QItem(v.id, v.title, v.channel, v.thumb, music)
    }
}

/**
 * Общая очередь воспроизведения для видео и музыки. Одна на всё приложение (экраны и сервис в одном процессе),
 * все вызовы — из главного потока.
 */
object Queue {
    val items = ArrayList<QItem>()

    /** Сыгранное в этой сессии — для «Назад» и чтобы автовоспроизведение не повторялось. */
    val history = ArrayList<QItem>()

    /** Исходный список (альбом, плейлист) — для «повторять всё» и отмены перемешивания. */
    var source: List<QItem> = emptyList()

    var shuffled = false
        private set

    fun setShuffle(on: Boolean) {
        shuffled = on
        if (on) items.shuffle()
        else {
            val order = source.withIndex().associate { it.value.id to it.index }
            items.sortBy { order[it.id] ?: Int.MAX_VALUE }
        }
        changed()
    }

    private val listeners = ArrayList<() -> Unit>()

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    fun addLast(q: QItem) {
        items.removeAll { it.id == q.id }
        items += q
        changed()
    }

    fun addNext(q: QItem) {
        items.removeAll { it.id == q.id }
        items.add(0, q)
        changed()
    }

    fun addAll(list: List<QItem>) {
        val ids = list.map { it.id }.toSet()
        items.removeAll { it.id in ids }
        items += list
        changed()
    }

    fun replace(list: List<QItem>) {
        items.clear()
        items += list
        changed()
    }

    fun removeAt(i: Int) {
        if (i in items.indices) items.removeAt(i)
        changed()
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        changed()
    }

    fun clear() {
        items.clear()
        changed()
    }

    fun poll(): QItem? = items.removeFirstOrNull()?.also { changed() }

    fun played(q: QItem) {
        if (history.lastOrNull()?.id == q.id) return
        history.removeAll { it.id == q.id }
        history += q
        if (history.size > 100) history.removeAt(0)
    }
}
