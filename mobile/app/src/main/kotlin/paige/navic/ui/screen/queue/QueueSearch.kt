package paige.navic.ui.screen.queue

import paige.navic.domain.model.DomainSong

/** Preserve occurrences: a queue can contain the same song more than once. */
internal fun filterQueue(queue: List<DomainSong>, query: String): List<IndexedValue<DomainSong>> {
	val term = query.trim()
	return queue.withIndex().filter { (_, song) ->
		term.isEmpty() || song.title.contains(term, ignoreCase = true) ||
			song.artistName?.contains(term, ignoreCase = true) == true ||
			song.albumTitle?.contains(term, ignoreCase = true) == true ||
			song.artists.any { it.name.contains(term, ignoreCase = true) }
	}
}
