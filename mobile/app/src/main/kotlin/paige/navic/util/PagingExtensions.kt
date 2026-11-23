/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.util

import androidx.paging.compose.LazyPagingItems

/**
 * Safely peeks at the item at [index] from the current [LazyPagingItems.itemSnapshotList]
 * without triggering paging loads or throwing [IndexOutOfBoundsException] if the snapshot shrunk.
 */
fun <T : Any> LazyPagingItems<T>.peekOrNull(index: Int): T? {
	val snapshot = itemSnapshotList
	return if (index in 0 until snapshot.size) snapshot[index] else null
}

/**
 * Safely gets the item at [index] (triggering paging loads when in bounds), returning `null`
 * if [index] is out of bounds or the snapshot shrinks concurrently.
 */
fun <T : Any> LazyPagingItems<T>.getOrNull(index: Int): T? {
	if (index !in 0 until itemCount) return null
	return try {
		get(index)
	} catch (_: IndexOutOfBoundsException) {
		null
	}
}
