/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.ui.viewmodel

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import paige.navic.ui.core.UiState


data class Selectable<T : Any>(
	val items: Flow<PagingData<T>>?,
	val selected: T?
)

data class SelectableList<T>(
	val items: List<T>?,
	val selected: T?
)

abstract class SelectableViewModel<T : Any> : AsyncViewModel<Selectable<T>>() {
	var items
		get() = data?.items
		set(value) {
			execute {
				Selectable(
					items = value,
					selected = selected
				)
			}
		}

	var selected
		get() = data?.selected
		set(value) {
			updateState { state ->
				val updated = Selectable(
					items = state.data?.items,
					selected = value
				)
				when (state) {
					is UiState.Loading -> UiState.Loading(updated)
					is UiState.Error -> UiState.Error(state.error, updated)
					is UiState.Success -> UiState.Success(updated)
				}
			}
		}

	fun clear() {
		selected = null
	}
}

abstract class SelectableListViewModel<T : Any> : AsyncViewModel<SelectableList<T>>() {
	var items
		get() = data?.items
		set(value) {
			execute {
				SelectableList(
					items = value,
					selected = selected
				)
			}
		}

	var selected
		get() = data?.selected
		set(value) {
			execute {
				SelectableList(
					items = items,
					selected = value
				)
			}
		}

	fun clear() {
		selected = null
	}
}
