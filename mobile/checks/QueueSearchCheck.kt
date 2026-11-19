package paige.navic.ui.screen.queue

import paige.navic.domain.model.*
import kotlin.time.Duration
import androidx.paging.PagingSource
import kotlinx.coroutines.runBlocking
import paige.navic.database.query.toSqlQuery
import paige.navic.database.paging.createRandomPagingSourceFactory

private fun song(id: String, title: String, artist: String? = null, album: String? = null) = DomainSong(
	id = id, title = title, artistName = artist, artistId = "artist", albumTitle = album,
	albumId = null, parentId = null, comment = null, trackNumber = null, discNumber = null,
	isrc = emptyList(), year = null, genre = null, genres = emptyList(), moods = emptyList(),
	duration = Duration.ZERO, bpm = null, contributors = emptyList(), userRating = null,
	averageRating = null, bitRate = null, bitDepth = null, sampleRate = null, audioChannelCount = null,
	replayGain = null, fileSize = 0, fileExtension = null, mimeType = null, filePath = null,
	starredAt = null, coverArtId = null, musicBrainzId = null,
	explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList()
)

fun main() {
	val repeated = song("same", "星夜序曲", "独立乐手", "清晨钢琴集")
	val queue = listOf(song("other", "Other"), repeated, song("b", "Blue", "Singer"), repeated)
	check(filterQueue(queue, " 星夜序曲 ").map { it.index } == listOf(1, 3))
	check(filterQueue(queue, "独立乐手").map { it.index } == listOf(1, 3))
	check(filterQueue(queue, "钢琴").map { it.index } == listOf(1, 3))
	check(filterQueue(queue, "BLUE").single().index == 2)
	check(filterQueue(queue, "singer").single().index == 2)
	check(filterQueue(queue, " ").map { it.value } == queue)
	check(filterQueue(queue, "missing").isEmpty())
	check(filterQueue(emptyList(), "song").isEmpty())
	val withArtist = song("c", "Track").copy(artists = listOf(DomainSongArtist("a", "Guest")))
	check(filterQueue(listOf(withArtist), "guest").single().index == 0)
	// Removing the second matching occurrence must preserve the first occurrence.
	val secondMatch = filterQueue(queue, "星夜序曲")[1]
	val remaining = queue.toMutableList().apply { removeAt(secondMatch.index) }
	check(remaining.map { it.id } == listOf("other", "same", "b"))
	check(filterQueue(remaining.reversed(), "星夜序曲").single().index == 1)
	check(queue.size == 4)
	println("Queue search checks passed (matching, duplicate occurrences, removal and reordering).")
	val ids = DomainSongListType.Year.toSqlQuery(idsOnly = true).sql
	check(ids.startsWith("SELECT songId FROM SongEntity"))
	check(ids.endsWith("ORDER BY COALESCE(year, 0) DESC, LOWER(title) ASC"))
	check(DomainSongListType.Random.toSqlQuery(idsOnly = true).sql == "SELECT songId FROM SongEntity")
	check(DomainSongListType.Year.toSqlQuery(reversed = true, idsOnly = true).sql
		.endsWith("ORDER BY COALESCE(year, 0) ASC, LOWER(title) DESC"))
	runBlocking {
		var selectionOrder = emptyList<String>()
		val allIds = (0..149).map { it.toString() }
		val factory = createRandomPagingSourceFactory(
			getIds = { allIds }, getId = { it }, fetchByIds = { it.reversed() },
			onOrder = { selectionOrder = it }
		)
		val source = factory()
		check(selectionOrder.toSet() == allIds.toSet())
		val page = source.load(PagingSource.LoadParams.Refresh<Int>(null, 20, false)) as PagingSource.LoadResult.Page<Int, String>
		check(page.data == selectionOrder.take(20))
		val nextPage = source.load(PagingSource.LoadParams.Append(20, 20, false)) as PagingSource.LoadResult.Page<Int, String>
		check(nextPage.data == selectionOrder.drop(20).take(20))
		println("Selection checks passed (sorted IDs, reversed order, random order and unloaded pages).")
	}
}
