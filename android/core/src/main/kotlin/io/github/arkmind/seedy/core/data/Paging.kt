package io.github.arkmind.seedy.core.data

/** This list followed by [page]'s items it doesn't hold yet: servers repeat an item across pages now and then. */
fun <T, K> List<T>.plusNew(page: List<T>, key: (T) -> K): List<T> {
    val seen = mapTo(HashSet(), key)
    return this + page.filter { key(it) !in seen }
}
