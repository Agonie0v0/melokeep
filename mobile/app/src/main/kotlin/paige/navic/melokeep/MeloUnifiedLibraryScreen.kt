@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.melokeep

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloLibraryFile
import io.github.agonie0v0.melokeep.shared.MeloLibraryFolder
import io.github.agonie0v0.melokeep.shared.MeloLocalLibraryController
import io.github.agonie0v0.melokeep.shared.MeloPlaybackInfo
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloStudioController
import io.github.agonie0v0.melokeep.shared.MeloTrack
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.settings.ListViewMode
import paige.navic.playback.MediaPlayer
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Add
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Delete
import paige.navic.ui.icons.outlined.LibraryMusic
import paige.navic.ui.icons.outlined.Search
import paige.navic.ui.navigation.Screen
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.theme.tabularNums

internal enum class LibrarySourceMode(val label: String) {
	NAVIDROME("Navidrome 库"),
	LOCAL("本地库")
}

internal enum class LibraryLocalSubTab(val label: String) {
	OVERVIEW("概览"),
	SONGS("歌曲"),
	ALBUMS("专辑"),
	ARTISTS("歌手"),
	PLAYLISTS("歌单"),
	FOLDERS("文件夹")
}

internal sealed interface LibraryLocalDetailRoute {
	data object Favorites : LibraryLocalDetailRoute
	data object DailyMix : LibraryLocalDetailRoute
	data class Playlist(val id: String) : LibraryLocalDetailRoute
	data class Album(val name: String) : LibraryLocalDetailRoute
	data class Artist(val name: String) : LibraryLocalDetailRoute
}

internal enum class LibraryNavidromeSubTab(val label: String) {
	OVERVIEW("概览"),
	ALBUMS("专辑"),
	PLAYLISTS("歌单"),
	ARTISTS("歌手"),
	SONGS("歌曲")
}

internal data class LibraryFolderChildEntry(
	val root: String,
	val name: String,
	val fullPath: String
)

internal data class LibraryLocalTrackItem(
	val track: MeloTrack,
	val song: DomainSong
)

internal val LibraryLosslessExtensions = setOf("flac", "wav", "alac", "ape", "dsf", "aiff", "aif", "wv")

/**
 * 平等双库「曲库」主页面：Navidrome 库与本地库并列，
 * 保留等宽双库胶囊、无右上角离线按钮与全库多选/离线能力。
 */
@Composable
fun MeloUnifiedLibraryScreen(
	onOpenMeloSettings: () -> Unit = {},
	onNavigateToStudioScrape: () -> Unit = {}
) {
	val sessionManager = koinInject<SessionManager>()
	val settingsStore = koinInject<MeloSettingsStore>()
	val studioController = koinInject<MeloStudioController>()
	val localController = koinInject<MeloLocalLibraryController>()
	val apiClient = koinInject<MeloKeepApiClient>()
	val player = koinInject<MediaPlayer>()
	val downloadManager = koinInject<DownloadManager>()
	val preferenceManager = koinInject<PreferenceManager>()
	val snackBarState = LocalSnackBarState.current
	val backStack = LocalNavStack.current
	val scope = rememberCoroutineScope()

	val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
	val meloConfig by settingsStore.config.collectAsStateWithLifecycle()
	val librarySearchHistory by settingsStore.librarySearchHistory.collectAsStateWithLifecycle()
	val studioState by studioController.state.collectAsStateWithLifecycle()
	val localState by localController.state.collectAsStateWithLifecycle()
	val allDownloads by downloadManager.allDownloads.collectAsStateWithLifecycle(initialValue = emptyList())
	val downloadsMap = remember(allDownloads) { allDownloads.associateBy { it.songId } }

	var navidromeConnectionSheetOpen by rememberSaveable { mutableStateOf(false) }
	var dailyMixSheetOpen by rememberSaveable { mutableStateOf(false) }

	val isNavidromeLoggedIn = sessionState !is SessionState.Unauthenticated
	val isMeloConnected = meloConfig.isAuthenticated

	val currentPlayingPair by remember(player) {
		player.uiState
			.map { it.currentSong?.id to it.isPaused }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(initialValue = null to true)

	var sourceMode by rememberSaveable {
		mutableStateOf(
			if (sessionManager.sessionState.value is SessionState.Unauthenticated &&
				settingsStore.currentConfig().isAuthenticated
			) {
				LibrarySourceMode.LOCAL
			} else {
				LibrarySourceMode.NAVIDROME
			}
		)
	}
	var navSubTab by rememberSaveable { mutableStateOf(LibraryNavidromeSubTab.OVERVIEW) }
	var localSubTab by rememberSaveable { mutableStateOf(LibraryLocalSubTab.OVERVIEW) }
	var localDetailRoute by remember { mutableStateOf<LibraryLocalDetailRoute?>(null) }
	var localPlaylistPickerIds by remember { mutableStateOf<List<String>?>(null) }
	var albumCardsMode by rememberSaveable {
		mutableStateOf(preferenceManager.albumListViewMode != ListViewMode.List)
	}
	var songCardsMode by rememberSaveable { mutableStateOf(false) }

	var fileSearchOpen by rememberSaveable { mutableStateOf(false) }
	var fileSearchInput by rememberSaveable { mutableStateOf(studioState.fileQuery) }
	var mkdirDialogOpen by rememberSaveable { mutableStateOf(false) }
	var renameFolderTarget by remember { mutableStateOf<LibraryFolderChildEntry?>(null) }
	var editingFilePath by rememberSaveable { mutableStateOf<String?>(null) }
	var moveDialogOpen by rememberSaveable { mutableStateOf(false) }
	var deleteFilesConfirmOpen by rememberSaveable { mutableStateOf(false) }

	MeloFeedbackObserver(
		errorMessage = studioState.errorMessage,
		noticeMessage = studioState.noticeMessage,
		onClearError = studioController::clearError,
		onClearNotice = studioController::clearNotice
	)
	MeloFeedbackObserver(
		errorMessage = localState.errorMessage,
		noticeMessage = localState.noticeMessage,
		onClearError = localController::clearError,
		onClearNotice = localController::clearNotice
	)

	// 仅「文件夹」子栏目沿用目录过滤，其余子栏目始终基于根目录全量文件聚合
	val folderArg = if (localSubTab == LibraryLocalSubTab.FOLDERS) studioState.currentFolder else null
	val reloadLocalFiles: () -> Unit = {
		studioController.refreshLibraryAndReload(
			query = studioState.fileQuery,
			folder = folderArg
		) {
			localController.refreshAll()
		}
	}

	LaunchedEffect(isNavidromeLoggedIn, isMeloConnected) {
		if (isNavidromeLoggedIn && isMeloConnected) {
			studioController.checkNavidromeCapabilities()
		}
	}

	LaunchedEffect(sourceMode, isMeloConnected) {
		if (sourceMode == LibrarySourceMode.LOCAL && isMeloConnected) {
			localController.refreshAll()
		}
	}

	LaunchedEffect(sourceMode, isMeloConnected, localSubTab) {
		if (sourceMode != LibrarySourceMode.LOCAL || !isMeloConnected) return@LaunchedEffect
		val needsRootReload = localSubTab != LibraryLocalSubTab.FOLDERS &&
			!studioState.currentFolder.isNullOrBlank()
		if (needsRootReload || (studioState.libraryFiles.isEmpty() && !studioState.filesLoading)) {
			studioController.loadLibraryFiles(
				query = studioState.fileQuery,
				folder = if (localSubTab == LibraryLocalSubTab.FOLDERS) studioState.currentFolder else null,
				reset = true
			)
		}
	}

	LaunchedEffect(studioState.fileQuery) {
		if (fileSearchInput != studioState.fileQuery) {
			fileSearchInput = studioState.fileQuery
		}
	}

	val activeLocalDetail = if (sourceMode == LibrarySourceMode.LOCAL && isMeloConnected) {
		localDetailRoute
	} else {
		null
	}

	LaunchedEffect(activeLocalDetail) {
		when (activeLocalDetail) {
			is LibraryLocalDetailRoute.Playlist -> localController.loadPlaylistDetail(activeLocalDetail.id)
			LibraryLocalDetailRoute.Favorites -> localController.refreshFavorites()
			else -> localController.clearActivePlaylist()
		}
	}

	BackHandler(enabled = activeLocalDetail != null) {
		localDetailRoute = null
	}

	val hostNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

	val selectedFiles = remember(studioState.libraryFiles, studioState.selectedFilePaths) {
		studioState.libraryFiles.filter { it.path in studioState.selectedFilePaths }
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
	) {
		AnimatedContent(
			targetState = activeLocalDetail,
			transitionSpec = {
				when {
					initialState == null && targetState != null -> {
						(slideInHorizontally(tween(180)) { it / 5 } + fadeIn(tween(180))) togetherWith
							(slideOutHorizontally(tween(180)) { -it / 8 } + fadeOut(tween(140)))
					}
					initialState != null && targetState == null -> {
						(slideInHorizontally(tween(180)) { -it / 8 } + fadeIn(tween(180))) togetherWith
							(slideOutHorizontally(tween(180)) { it / 5 } + fadeOut(tween(140)))
					}
					else -> fadeIn(tween(180)) togetherWith fadeOut(tween(140))
				}
			},
			modifier = Modifier.fillMaxSize(),
			label = "UnifiedLibraryLocalDetailSwitch"
		) { detailRoute ->
			if (detailRoute != null) {
				LibraryLocalDetailView(
					route = detailRoute,
					onBack = { localDetailRoute = null },
					studioState = studioState,
					localState = localState,
					localController = localController,
					downloadsMap = downloadsMap,
					currentPlayingPair = currentPlayingPair,
					hostNavBottom = hostNavBottom,
					onRequestAddToLocalPlaylist = { localPlaylistPickerIds = it }
				)
			} else {
				Column(modifier = Modifier.fillMaxSize()) {
					UnifiedLibraryTopHeader(
						sourceMode = sourceMode,
						onSelectSourceMode = { sourceMode = it },
						isNavidromeLoggedIn = isNavidromeLoggedIn,
						isMeloConnected = isMeloConnected,
						scanningNavidrome = studioState.scanningNavidrome,
						scanTrackCount = studioState.scanStatus?.count,
						onTriggerScan = {
							if (isMeloConnected) {
								studioController.triggerNavidromeScan()
							} else {
								scope.launch {
									snackBarState.currentSnackbarData?.dismiss()
									snackBarState.showSnackbar("请先连接 MeloKeep 服务以触发扫库")
								}
								onOpenMeloSettings()
							}
						},
						onOpenNavidromeSearch = dropUnlessResumed {
							backStack.add(Screen.Search(nested = true))
						},
						onOpenCreateFolder = { mkdirDialogOpen = true },
						fileSearchOpen = fileSearchOpen,
						onToggleFileSearch = {
							val next = !fileSearchOpen
							fileSearchOpen = next
							if (!next && studioState.fileQuery.isNotBlank()) {
								fileSearchInput = ""
								studioController.loadLibraryFiles(
									query = "",
									folder = folderArg,
									reset = true
								)
							}
						},
						navSubTab = navSubTab,
						onSelectNavSubTab = { navSubTab = it },
						localSubTab = localSubTab,
						onSelectLocalSubTab = {
							localSubTab = it
							localDetailRoute = null
						},
						showLocalSubTabs = true,
						fileSearchInput = fileSearchInput,
						onFileSearchInputChange = { fileSearchInput = it },
						onFileSearchSubmit = {
							settingsStore.recordLibrarySearchHistory(fileSearchInput)
							studioController.loadLibraryFiles(
								query = fileSearchInput,
								folder = folderArg,
								reset = true
							)
						},
						onFileSearchClear = {
							fileSearchInput = ""
							studioController.loadLibraryFiles(
								query = "",
								folder = folderArg,
								reset = true
							)
						},
						librarySearchHistory = librarySearchHistory,
						onSelectSearchHistory = { kw ->
							fileSearchInput = kw
							settingsStore.recordLibrarySearchHistory(kw)
							studioController.loadLibraryFiles(
								query = kw,
								folder = folderArg,
								reset = true
							)
						},
						onRemoveSearchHistory = settingsStore::removeLibrarySearchHistory,
						onClearSearchHistory = settingsStore::clearLibrarySearchHistory
					)

					AnimatedContent(
						targetState = sourceMode,
						transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
						modifier = Modifier.weight(1f),
						label = "UnifiedLibrarySourceSwitch"
					) { activeSource ->
						when (activeSource) {
							LibrarySourceMode.NAVIDROME -> {
								if (!isNavidromeLoggedIn) {
									NavidromeUnauthenticatedCard(
										onConfigureNavidrome = { navidromeConnectionSheetOpen = true },
										onSwitchToLocal = { sourceMode = LibrarySourceMode.LOCAL }
									)
								} else {
									LibraryNavidromeStreamingSection(
										subTab = navSubTab,
										albumCardsMode = albumCardsMode,
										songCardsMode = songCardsMode,
										isMeloConnected = isMeloConnected,
										studioState = studioState,
										studioController = studioController,
										downloadsMap = downloadsMap,
										currentPlayingPair = currentPlayingPair,
										hostNavBottom = hostNavBottom,
										onSelectSubTab = { navSubTab = it },
										onToggleAlbumCardsMode = {
											val next = !albumCardsMode
											albumCardsMode = next
											preferenceManager.albumListViewMode = if (next) ListViewMode.Grid else ListViewMode.List
										},
										onToggleSongCardsMode = { songCardsMode = !songCardsMode },
										onOpenDailyMix = { dailyMixSheetOpen = true }
									)
								}
							}

							LibrarySourceMode.LOCAL -> {
								if (!isMeloConnected) {
									MeloLocalUnconfiguredCard(
										onOpenMeloSettings = onOpenMeloSettings
									)
								} else {
									LibraryLocalSection(
										subTab = localSubTab,
										onOpenRoute = { localDetailRoute = it },
										onSelectSubTab = {
											localSubTab = it
											localDetailRoute = null
										},
										studioState = studioState,
										studioController = studioController,
										localState = localState,
										localController = localController,
										downloadsMap = downloadsMap,
										currentPlayingPair = currentPlayingPair,
										hostNavBottom = hostNavBottom,
										onRequestAddToLocalPlaylist = { localPlaylistPickerIds = it },
										onRenameFolder = { renameFolderTarget = it },
										onEditTags = { file -> editingFilePath = file.path },
										onRefreshFiles = reloadLocalFiles
									)
								}
							}
						}
					}
				}
			}
		}

		// 本地库「文件夹」多选批处理悬浮坞（含离线到手机 / 加歌单）
		AnimatedVisibility(
			visible = sourceMode == LibrarySourceMode.LOCAL &&
				isMeloConnected &&
				localSubTab == LibraryLocalSubTab.FOLDERS &&
				localDetailRoute == null &&
				studioState.selectedFilePaths.isNotEmpty(),
			enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 2 },
			exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 2 },
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.windowInsetsPadding(WindowInsets.navigationBars)
				.padding(horizontal = 16.dp)
				.padding(bottom = rememberBatchDockBottomClearance())
		) {
			LibraryBatchSelectionDock(
				selectedCount = studioState.selectedFilePaths.size,
				busy = studioState.jobsLoading || studioState.filesLoading,
				onClear = studioController::clearFileSelection,
				onScrape = {
					studioController.createScrapeJob(
						paths = studioState.selectedFilePaths.toList(),
						action = "both",
						autoApply = false
					)
					onNavigateToStudioScrape()
				},
				onMove = { moveDialogOpen = true },
				onDelete = { deleteFilesConfirmOpen = true },
				onOffline = {
					val songs = selectedFiles.map {
						MeloTrackMapper.toDomainSong(it.track ?: libraryBuildFallbackMeloTrack(it, apiClient))
					}
					if (songs.isNotEmpty()) {
						scope.launch {
							queueSongsOffline(downloadManager, songs)
							snackBarState.currentSnackbarData?.dismiss()
							snackBarState.showSnackbar("已将 ${songs.size} 首歌曲加入手机离线队列（可在工坊 → 下载到手机查看）")
						}
						studioController.clearFileSelection()
					}
				},
				onAddToPlaylist = {
					val ids = selectedFiles.map { (it.track ?: libraryBuildFallbackMeloTrack(it, apiClient)).id }
					if (ids.isNotEmpty()) localPlaylistPickerIds = ids
				},
				onSelectAll = {
					val allLoadedSelected = studioState.libraryFiles.isNotEmpty() &&
						studioState.libraryFiles.all { it.path in studioState.selectedFilePaths }
					if (allLoadedSelected) {
						studioController.clearFileSelection()
					} else {
						studioController.selectAllFiles()
					}
				}
			)
		}
	}

	localPlaylistPickerIds?.let { ids ->
		LibraryLocalPlaylistPickerSheet(
			trackCount = ids.size,
			playlists = localState.playlists,
			hostNavBottom = hostNavBottom,
			onDismiss = { localPlaylistPickerIds = null },
			onPick = { playlistId ->
				localController.addTracksToPlaylist(playlistId, ids)
				localPlaylistPickerIds = null
			},
			onCreate = { name ->
				localController.createPlaylist(name, ids)
				localPlaylistPickerIds = null
			}
		)
	}

	if (mkdirDialogOpen) {
		val currentClean = remember(studioState.currentFolder) {
			libraryNormalizeFolder(studioState.currentFolder)
		}
		val defaultRoot = remember(studioState.libraryFolders, studioState.libraryFiles, currentClean) {
			libraryResolveRootForFolder(
				folders = studioState.libraryFolders,
				files = studioState.libraryFiles,
				folderPath = currentClean
			)
		}
		LibraryCreateFolderDialog(
			parentFolder = currentClean,
			onDismiss = { mkdirDialogOpen = false },
			onConfirm = { newName ->
				mkdirDialogOpen = false
				studioController.createSubFolder(
					root = defaultRoot,
					parent = currentClean,
					name = newName
				)
			}
		)
	}

	renameFolderTarget?.let { target ->
		LibraryRenameFolderDialog(
			folder = target,
			onDismiss = { renameFolderTarget = null },
			onConfirm = { newName ->
				renameFolderTarget = null
				studioController.renameFolder(
					root = target.root,
					folder = target.fullPath,
					newName = newName
				)
			}
		)
	}

	editingFilePath?.let { path ->
		val liveFile = remember(studioState.libraryFiles, path) {
			studioState.libraryFiles.firstOrNull { it.path == path }
		}
		if (liveFile == null) {
			editingFilePath = null
		} else {
			LibraryFileTagEditorSheet(
				file = liveFile,
				hostNavBottom = hostNavBottom,
				onDismiss = { editingFilePath = null },
				onSave = { changedMap ->
					if (changedMap.isNotEmpty()) {
						studioController.updateFileTags(liveFile.path, changedMap)
					}
					editingFilePath = null
				},
				onUnlockAll = {
					studioController.unlockFileTags(liveFile.path)
				}
			)
		}
	}

	if (moveDialogOpen) {
		val currentClean = remember(studioState.currentFolder) {
			libraryNormalizeFolder(studioState.currentFolder)
		}
		val defaultRoot = remember(studioState.libraryFolders, studioState.libraryFiles, currentClean) {
			libraryResolveRootForFolder(
				folders = studioState.libraryFolders,
				files = studioState.libraryFiles,
				folderPath = currentClean
			)
		}
		LibraryMoveTargetFolderSheet(
			folders = studioState.libraryFolders,
			defaultRoot = defaultRoot,
			currentFolder = currentClean,
			selectedCount = studioState.selectedFilePaths.size,
			hostNavBottom = hostNavBottom,
			onDismiss = { moveDialogOpen = false },
			onConfirmMove = { targetRoot, targetFolder ->
				moveDialogOpen = false
				studioController.moveSelectedFiles(
					targetRoot = targetRoot,
					targetFolder = targetFolder
				)
			}
		)
	}

	if (deleteFilesConfirmOpen) {
		AlertDialog(
			onDismissRequest = { deleteFilesConfirmOpen = false },
			icon = {
				Icon(
					imageVector = Icons.Outlined.Delete,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.error
				)
			},
			title = { Text("确认删除所选文件") },
			text = {
				Text(
					"将永久删除选中的 ${studioState.selectedFilePaths.size} 个本地音频文件，删除后不可恢复。"
				)
			},
			confirmButton = {
				Button(
					onClick = {
						deleteFilesConfirmOpen = false
						studioController.deleteSelectedFiles()
					},
					colors = ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.error,
						contentColor = MaterialTheme.colorScheme.onError
					)
				) {
					Text("永久删除")
				}
			},
			dismissButton = {
				TextButton(onClick = { deleteFilesConfirmOpen = false }) {
					Text("取消")
				}
			}
		)
	}

	if (navidromeConnectionSheetOpen) {
		MeloNavidromeConnectionSheet(
			presented = navidromeConnectionSheetOpen,
			onDismissRequest = { navidromeConnectionSheetOpen = false }
		)
	}

	if (dailyMixSheetOpen) {
		MeloDailyMixSheet(
			presented = dailyMixSheetOpen,
			onDismissRequest = { dailyMixSheetOpen = false }
		)
	}
}

@Composable
private fun HeaderCircleButton(
	icon: ImageVector,
	contentDescription: String,
	onClick: () -> Unit,
	active: Boolean = false
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = if (active) {
			MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
		} else {
			MaterialTheme.colorScheme.surfaceContainer
		},
		border = BorderStroke(
			MeloHairlineWidth,
			if (active) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
			} else {
				libraryHairlineBorderColor()
			}
		),
		modifier = Modifier.size(34.dp)
	) {
		Box(contentAlignment = Alignment.Center) {
			Icon(
				imageVector = icon,
				contentDescription = contentDescription,
				tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.size(17.dp)
			)
		}
	}
}

@Composable
private fun <T> HeaderSubTabRow(
	tabs: List<T>,
	selected: T,
	label: (T) -> String,
	onSelect: (T) -> Unit,
	modifier: Modifier = Modifier
) {
	LazyRow(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		items(
			items = tabs,
			key = { label(it) },
			contentType = { "subtab" }
		) { tab ->
			val isSelected = tab == selected
			Surface(
				onClick = { onSelect(tab) },
				shape = CircleShape,
				color = if (isSelected) {
					MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
				} else {
					Color.Transparent
				},
				border = if (isSelected) {
					BorderStroke(MeloHairlineWidth, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
				} else {
					null
				}
			) {
				Text(
					text = label(tab),
					style = MaterialTheme.typography.labelMedium.copy(
						fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
					),
					color = if (isSelected) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
					maxLines = 1,
					softWrap = false
				)
			}
		}
	}
}

@Composable
private fun UnifiedLibraryTopHeader(
	sourceMode: LibrarySourceMode,
	onSelectSourceMode: (LibrarySourceMode) -> Unit,
	isNavidromeLoggedIn: Boolean,
	isMeloConnected: Boolean,
	scanningNavidrome: Boolean,
	scanTrackCount: Long?,
	onTriggerScan: () -> Unit,
	onOpenNavidromeSearch: () -> Unit,
	onOpenCreateFolder: () -> Unit,
	fileSearchOpen: Boolean,
	onToggleFileSearch: () -> Unit,
	navSubTab: LibraryNavidromeSubTab,
	onSelectNavSubTab: (LibraryNavidromeSubTab) -> Unit,
	localSubTab: LibraryLocalSubTab,
	onSelectLocalSubTab: (LibraryLocalSubTab) -> Unit,
	showLocalSubTabs: Boolean,
	fileSearchInput: String,
	onFileSearchInputChange: (String) -> Unit,
	onFileSearchSubmit: () -> Unit,
	onFileSearchClear: () -> Unit,
	librarySearchHistory: List<String>,
	onSelectSearchHistory: (String) -> Unit,
	onRemoveSearchHistory: (String) -> Unit,
	onClearSearchHistory: () -> Unit
) {
	val focusManager = LocalFocusManager.current
	var manageHistory by remember { mutableStateOf(false) }
	var clearHistoryConfirmOpen by remember { mutableStateOf(false) }
	LaunchedEffect(librarySearchHistory.isEmpty()) {
		if (librarySearchHistory.isEmpty()) {
			manageHistory = false
		}
	}
	val localSearchable = localSubTab == LibraryLocalSubTab.FOLDERS ||
		localSubTab == LibraryLocalSubTab.SONGS ||
		localSubTab == LibraryLocalSubTab.ALBUMS ||
		localSubTab == LibraryLocalSubTab.ARTISTS

	Surface(
		color = MaterialTheme.colorScheme.background,
		modifier = Modifier.fillMaxWidth()
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.statusBarsPadding()
				.padding(top = 8.dp, bottom = 6.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			// 第一行：等宽 216.dp 双库切换胶囊 + 右侧快捷动作（无右上角离线按钮）
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Surface(
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainerHigh,
					border = libraryHairlineBorder(),
					modifier = Modifier.width(216.dp)
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(3.dp),
						horizontalArrangement = Arrangement.spacedBy(2.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						LibrarySourceMode.entries.forEach { mode ->
							val selected = sourceMode == mode
							val pillBg = if (selected) {
								MaterialTheme.colorScheme.primary
							} else {
								Color.Transparent
							}
							val pillFg = if (selected) {
								MaterialTheme.colorScheme.onPrimary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							}
							Box(
								modifier = Modifier
									.weight(1f)
									.clip(CircleShape)
									.background(pillBg)
									.clickable { onSelectSourceMode(mode) }
									.padding(horizontal = 8.dp, vertical = 6.dp),
								contentAlignment = Alignment.Center
							) {
								Text(
									text = mode.label,
									style = MaterialTheme.typography.labelMedium.copy(
										fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
									),
									color = pillFg,
									textAlign = TextAlign.Center,
									maxLines = 1,
									softWrap = false,
									overflow = TextOverflow.Ellipsis
								)
							}
						}
					}
				}

				Spacer(Modifier.width(8.dp))

				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp)
				) {
					if (sourceMode == LibrarySourceMode.NAVIDROME) {
						Surface(
							onClick = onTriggerScan,
							enabled = !scanningNavidrome,
							shape = CircleShape,
							color = MaterialTheme.colorScheme.surfaceContainer,
							border = libraryHairlineBorder()
						) {
							Row(
								modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(4.dp)
							) {
								if (scanningNavidrome) {
									CircularProgressIndicator(
										modifier = Modifier.size(12.dp),
										strokeWidth = 1.8.dp,
										color = MaterialTheme.colorScheme.primary
									)
								} else {
									Text(
										text = "↻",
										style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
										color = MaterialTheme.colorScheme.primary
									)
								}
								val countSuffix = scanTrackCount?.takeIf { it > 0L }?.let { " $it" }.orEmpty()
								Text(
									text = if (scanningNavidrome) "扫库中" else "扫库$countSuffix",
									style = MaterialTheme.typography.labelMedium.tabularNums(),
									color = MaterialTheme.colorScheme.onSurface,
									maxLines = 1,
									softWrap = false
								)
							}
						}

						if (isNavidromeLoggedIn) {
							HeaderCircleButton(
								icon = Icons.Outlined.Search,
								contentDescription = "快搜 Navidrome 库",
								onClick = onOpenNavidromeSearch
							)
						}
					} else if (isMeloConnected) {
						if (localSubTab == LibraryLocalSubTab.FOLDERS && showLocalSubTabs) {
							HeaderCircleButton(
								icon = Icons.Outlined.Add,
								contentDescription = "新建目录",
								onClick = onOpenCreateFolder
							)
						}
						if (localSearchable && showLocalSubTabs) {
							HeaderCircleButton(
								icon = if (fileSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
								contentDescription = "搜索本地库",
								onClick = onToggleFileSearch,
								active = fileSearchOpen
							)
						}
					}
				}
			}

			// 第二行：Navidrome 库子栏目
			AnimatedVisibility(
				visible = sourceMode == LibrarySourceMode.NAVIDROME && isNavidromeLoggedIn
			) {
				HeaderSubTabRow(
					tabs = LibraryNavidromeSubTab.entries,
					selected = navSubTab,
					label = { it.label },
					onSelect = onSelectNavSubTab,
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp)
				)
			}

			// 第二行：本地库子栏目
			AnimatedVisibility(
				visible = sourceMode == LibrarySourceMode.LOCAL && isMeloConnected && showLocalSubTabs
			) {
				HeaderSubTabRow(
					tabs = LibraryLocalSubTab.entries,
					selected = localSubTab,
					label = { it.label },
					onSelect = onSelectLocalSubTab,
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp)
				)
			}

			// 第三行：本地库搜索输入框
			AnimatedVisibility(
				visible = sourceMode == LibrarySourceMode.LOCAL &&
					isMeloConnected &&
					fileSearchOpen &&
					localSearchable &&
					showLocalSubTabs
			) {
				Surface(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp),
					shape = CircleShape,
					color = MaterialTheme.colorScheme.surfaceContainer,
					border = libraryHairlineBorder()
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.height(40.dp)
							.padding(start = 14.dp, end = 6.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						Icon(
							imageVector = Icons.Outlined.Search,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(16.dp)
						)
						Spacer(Modifier.width(8.dp))
						Box(
							modifier = Modifier.weight(1f),
							contentAlignment = Alignment.CenterStart
						) {
							if (fileSearchInput.isEmpty()) {
								Text(
									text = "搜索歌名、歌手、专辑或路径…",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
							}
							BasicTextField(
								value = fileSearchInput,
								onValueChange = onFileSearchInputChange,
								singleLine = true,
								textStyle = TextStyle(
									color = MaterialTheme.colorScheme.onSurface,
									fontFamily = defaultFont(),
									fontSize = 14.sp
								),
								cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
								keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
								keyboardActions = KeyboardActions(
									onSearch = {
										focusManager.clearFocus()
										onFileSearchSubmit()
									}
								),
								modifier = Modifier.fillMaxWidth()
							)
						}
						if (fileSearchInput.isNotEmpty()) {
							IconButton(
								onClick = onFileSearchClear,
								modifier = Modifier.size(30.dp)
							) {
								Icon(
									imageVector = Icons.Outlined.Close,
									contentDescription = "清除",
									modifier = Modifier.size(15.dp)
								)
							}
						}
						FilledTonalButton(
							onClick = {
								focusManager.clearFocus()
								onFileSearchSubmit()
							},
							contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
							modifier = Modifier.height(28.dp)
						) {
							Text("搜索", style = MaterialTheme.typography.labelMedium)
						}
					}
				}
			}

			AnimatedVisibility(
				visible = sourceMode == LibrarySourceMode.LOCAL &&
					isMeloConnected &&
					fileSearchOpen &&
					localSearchable &&
					librarySearchHistory.isNotEmpty()
			) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp),
					verticalArrangement = Arrangement.spacedBy(6.dp)
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically
					) {
						Text(
							text = "最近搜索",
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
						Row(
							horizontalArrangement = Arrangement.spacedBy(8.dp),
							verticalAlignment = Alignment.CenterVertically
						) {
							Text(
								text = if (manageHistory) "完成" else "管理",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.primary,
								modifier = Modifier
									.clip(CircleShape)
									.clickable { manageHistory = !manageHistory }
									.padding(horizontal = 6.dp, vertical = 2.dp)
							)
							Text(
								text = "清空",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.error,
								modifier = Modifier
									.clip(CircleShape)
									.clickable { clearHistoryConfirmOpen = true }
									.padding(horizontal = 6.dp, vertical = 2.dp)
							)
						}
					}

					LazyRow(
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						contentPadding = PaddingValues(bottom = 2.dp)
					) {
						items(librarySearchHistory, key = { it }) { item ->
							Surface(
								onClick = {
									if (manageHistory) {
										onRemoveSearchHistory(item)
									} else {
										focusManager.clearFocus()
										onSelectSearchHistory(item)
									}
								},
								shape = CircleShape,
								color = MaterialTheme.colorScheme.surfaceContainer,
								border = libraryHairlineBorder()
							) {
								Row(
									modifier = Modifier.padding(
										start = 11.dp,
										end = if (manageHistory) 8.dp else 11.dp,
										top = 5.dp,
										bottom = 5.dp
									),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(5.dp)
								) {
									Text(
										text = item,
										style = MaterialTheme.typography.labelMedium,
										color = MaterialTheme.colorScheme.onSurface,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis
									)
									if (manageHistory) {
										Icon(
											imageVector = Icons.Outlined.Close,
											contentDescription = "删除",
											tint = MaterialTheme.colorScheme.onSurfaceVariant,
											modifier = Modifier.size(13.dp)
										)
									}
								}
							}
						}
					}
				}
			}
		}
	}

	if (clearHistoryConfirmOpen) {
		AlertDialog(
			onDismissRequest = { clearHistoryConfirmOpen = false },
			title = { Text("清空搜索历史") },
			text = { Text("确认清空本地曲库的所有最近搜索记录吗？") },
			confirmButton = {
				Button(
					onClick = {
						clearHistoryConfirmOpen = false
						manageHistory = false
						onClearSearchHistory()
					},
					colors = ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.error,
						contentColor = MaterialTheme.colorScheme.onError
					)
				) {
					Text("清空")
				}
			},
			dismissButton = {
				TextButton(onClick = { clearHistoryConfirmOpen = false }) {
					Text("取消")
				}
			}
		)
	}
}

@Composable
private fun NavidromeUnauthenticatedCard(
	onConfigureNavidrome: () -> Unit,
	onSwitchToLocal: () -> Unit
) {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 24.dp)
			.padding(bottom = MeloBottomChromeClearance),
		contentAlignment = Alignment.Center
	) {
		Column(
			modifier = Modifier.widthIn(max = 360.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			MeloQuietEmptyState(
				message = "未连接 Navidrome 服务",
				icon = Icons.Outlined.LibraryMusic
			)
			Button(
				onClick = onConfigureNavidrome,
				modifier = Modifier
					.fillMaxWidth()
					.height(44.dp),
				shape = RoundedCornerShape(12.dp)
			) {
				Text("配置 Navidrome")
			}
			OutlinedButton(
				onClick = onSwitchToLocal,
				modifier = Modifier
					.fillMaxWidth()
					.height(42.dp),
				shape = RoundedCornerShape(12.dp)
			) {
				Text("切换至本地库")
			}
		}
	}
}

@Composable
private fun MeloLocalUnconfiguredCard(
	onOpenMeloSettings: () -> Unit
) {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 24.dp)
			.padding(bottom = MeloBottomChromeClearance),
		contentAlignment = Alignment.Center
	) {
		MeloQuietEmptyState(
			message = "未连接 MeloKeep 本地服务",
			icon = MeloIcons.Folder,
			actionLabel = "前往配置",
			onAction = onOpenMeloSettings
		)
	}
}

// =========================================================================
// 曲库拆分模块间共享原语与纯工具函数（一律以 Library / library 前缀命名）
// =========================================================================

@Composable
internal fun libraryHairlineBorderColor(): Color =
	MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)

@Composable
internal fun libraryHairlineBorder(): BorderStroke =
	BorderStroke(MeloHairlineWidth, libraryHairlineBorderColor())

internal fun libraryGroupedItemShape(isFirst: Boolean, isLast: Boolean): Shape = when {
	isFirst && isLast -> RoundedCornerShape(14.dp)
	isFirst -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
	isLast -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
	else -> RectangleShape
}

internal fun libraryFormatQuietQuality(
	bitDepth: Int?,
	fileExtension: String?,
	bitRate: Int?
): String? {
	val ext = fileExtension?.lowercase().orEmpty()
	return when {
		(bitDepth ?: 0) >= 24 -> "HI-RES"
		ext in LibraryLosslessExtensions -> ext.uppercase()
		(bitRate ?: 0) >= 700 -> "LOSSLESS"
		else -> null
	}
}

internal fun libraryFormatLocalQuietQuality(track: MeloTrack): String? {
	val hasHiRes = track.qualities.any { q ->
		q.label.contains("hi-res", ignoreCase = true) ||
			q.label.contains("24", ignoreCase = true) ||
			q.label.contains("母带") ||
			q.codec.contains("24", ignoreCase = true)
	}
	if (hasHiRes) return "HI-RES"
	if (track.hasLossless) {
		return track.codecLabel?.uppercase()?.takeIf { it.isNotBlank() } ?: "FLAC"
	}
	return null
}

@Composable
internal fun LibrarySectionHeader(
	title: String,
	modifier: Modifier = Modifier,
	count: String? = null,
	actionLabel: String? = null,
	onActionClick: (() -> Unit)? = null
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp)
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleSmall.copy(
					fontFamily = defaultFont(grade = 100),
					fontWeight = FontWeight.SemiBold
				),
				color = MaterialTheme.colorScheme.onSurface
			)
			if (!count.isNullOrBlank()) {
				Text(
					text = count,
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
		if (actionLabel != null && onActionClick != null) {
			Text(
				text = actionLabel,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier
					.clip(CircleShape)
					.clickable(onClick = onActionClick)
					.padding(horizontal = 6.dp, vertical = 3.dp)
			)
		}
	}
}

@Composable
internal fun LibraryDockPill(
	label: String,
	icon: ImageVector,
	onClick: () -> Unit,
	emphasized: Boolean = false
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
		contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
		border = libraryHairlineBorder()
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp)
		) {
			Icon(icon, null, modifier = Modifier.size(14.dp))
			Text(
				text = label,
				style = MaterialTheme.typography.labelMedium,
				maxLines = 1,
				softWrap = false
			)
		}
	}
}

@Composable
internal fun LibrarySheetActionRow(
	icon: ImageVector,
	label: String,
	onClick: () -> Unit,
	tint: Color = MaterialTheme.colorScheme.onSurface
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 20.dp, vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(16.dp)
	) {
		Icon(icon, contentDescription = null, tint = tint.copy(alpha = 0.86f), modifier = Modifier.size(20.dp))
		Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
	}
}

internal fun libraryBuildFallbackMeloTrack(
	file: MeloLibraryFile,
	apiClient: MeloKeepApiClient
): MeloTrack {
	val encodedPath = Uri.encode(file.path)
	val audioUrl = apiClient.resolveMediaUrl("/api/files/audio?path=$encodedPath")
	val ext = file.name.substringAfterLast('.', "").lowercase()
	return MeloTrack(
		id = file.path,
		source = "local",
		sourceId = file.path.removePrefix("asset:"),
		title = file.metadata["title"]?.takeIf { it.isNotBlank() }
			?: file.name.substringBeforeLast('.').ifBlank { file.name },
		artist = file.metadata["artist"].orEmpty(),
		album = file.metadata["album"].orEmpty(),
		sizeBytes = file.size,
		playback = MeloPlaybackInfo(
			state = "ready",
			url = audioUrl,
			codec = ext.takeIf { it.isNotBlank() }
		)
	)
}

internal fun libraryNormalizeFolder(raw: String?): String {
	val cleaned = raw.orEmpty().replace('\\', '/').trim().trim('/')
	return if (cleaned == ".") "" else cleaned
}

internal fun libraryExtractImmediateChildFolders(
	folders: List<MeloLibraryFolder>,
	currentFolder: String
): List<LibraryFolderChildEntry> {
	val seen = linkedMapOf<String, LibraryFolderChildEntry>()
	folders.forEach { folderObj ->
		val norm = libraryNormalizeFolder(folderObj.folder)
		if (norm.isEmpty()) return@forEach
		val childName = when {
			currentFolder.isEmpty() -> norm.substringBefore('/')
			norm.startsWith("$currentFolder/") -> norm.removePrefix("$currentFolder/").substringBefore('/')
			else -> return@forEach
		}
		if (childName.isBlank()) return@forEach
		val childFullPath = if (currentFolder.isEmpty()) childName else "$currentFolder/$childName"
		if (childFullPath !in seen) {
			seen[childFullPath] = LibraryFolderChildEntry(
				root = folderObj.root,
				name = childName,
				fullPath = childFullPath
			)
		}
	}
	return seen.values.sortedBy { it.name.lowercase() }
}

internal fun libraryResolveRootForFolder(
	folders: List<MeloLibraryFolder>,
	files: List<MeloLibraryFile>,
	folderPath: String
): String {
	if (folderPath.isNotEmpty()) {
		folders.firstOrNull {
			val norm = libraryNormalizeFolder(it.folder)
			norm == folderPath || norm.startsWith("$folderPath/")
		}?.root?.takeIf { it.isNotBlank() }?.let { return it }
	}
	return files.firstOrNull { it.root.isNotBlank() }?.root
		?: folders.firstOrNull { it.root.isNotBlank() }?.root
		?: ""
}
