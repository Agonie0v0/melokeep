@file:OptIn(
	ExperimentalMaterial3Api::class,
	ExperimentalMaterial3ExpressiveApi::class,
	ExperimentalLayoutApi::class
)

package paige.navic.melokeep

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.agonie0v0.melokeep.shared.MeloDailyRecommendController
import io.github.agonie0v0.melokeep.shared.MeloDailyRecommendItem
import io.github.agonie0v0.melokeep.shared.MeloDailyRecommendUiState
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloRecommendSeed
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloTrack
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.database.dao.SongDao
import paige.navic.database.entity.DownloadEntity
import paige.navic.database.entity.DownloadStatus
import paige.navic.database.entity.SongEntity
import paige.navic.database.mapper.toDomainModel
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSizeClass
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.DownloadManager
import paige.navic.domain.manager.session.SessionManager
import coil3.compose.LocalPlatformContext as LocalCoilPlatformContext
import paige.navic.domain.model.DomainAlbum
import paige.navic.domain.model.DomainAlbumListType
import paige.navic.domain.model.DomainArtistListType
import paige.navic.domain.model.DomainFilter
import paige.navic.domain.model.DomainPlaylist
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.DomainSongListType
import paige.navic.domain.model.settings.BottomBarVisibilityMode
import paige.navic.domain.repository.SongRepository
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.common.SongQueueSwipeBackground
import paige.navic.ui.component.common.SwipeToDismissBox
import paige.navic.ui.component.snackbar.ErrorSnackBar
import paige.navic.ui.core.UiState
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.filled.Play
import paige.navic.ui.icons.filled.Star
import paige.navic.ui.icons.outlined.Album
import paige.navic.ui.icons.outlined.Artist
import paige.navic.ui.icons.outlined.ArrowBack
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Checklist
import paige.navic.ui.icons.outlined.ChevronForward
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Delete
import paige.navic.ui.icons.outlined.Download
import paige.navic.ui.icons.outlined.Edit
import paige.navic.ui.icons.outlined.LibraryAdd
import paige.navic.ui.icons.outlined.LibraryMusic
import paige.navic.ui.icons.outlined.MoreVert
import paige.navic.ui.icons.outlined.PlaylistAdd
import paige.navic.ui.icons.outlined.PlaylistRemove
import paige.navic.ui.icons.outlined.Queue
import paige.navic.ui.icons.outlined.QueuePlayNext
import paige.navic.ui.icons.outlined.Refresh
import paige.navic.ui.icons.outlined.Shuffle
import paige.navic.ui.icons.outlined.Star
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screen.album.viewmodel.AlbumListViewModel
import paige.navic.ui.screen.artist.viewmodel.ArtistDetailViewModel
import paige.navic.ui.screen.artist.viewmodel.ArtistListViewModel
import paige.navic.ui.screen.collection.viewmodel.CollectionDetailViewModel
import paige.navic.ui.screen.playlist.dialog.PlaylistUpdateDialog
import paige.navic.ui.screen.song.viewmodel.SongListViewModel
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.theme.meloGlassSurface
import paige.navic.ui.theme.meloGoldBorder
import paige.navic.ui.theme.meloGoldContainer
import paige.navic.ui.theme.meloGoldText
import paige.navic.ui.theme.meloSubtleBorder
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import paige.navic.util.getOrNull
import paige.navic.util.peekOrNull
import paige.navic.util.toHoursMinutesSeconds
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* =========================================================================================
 * 1. MeloCollectionDetailScreen (原生 Studio 专辑 / 歌单详情)
 * ========================================================================================= */

@Composable
fun MeloCollectionDetailScreen(
	collectionId: String,
	tab: String
) {
	val viewModel = koinViewModel<CollectionDetailViewModel> { parametersOf(collectionId) }
	val downloadManager = koinInject<DownloadManager>()
	val player = koinInject<MediaPlayer>()
	val backStack = LocalNavStack.current
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()

	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val collection = uiState.data?.collection
	val songs = collection?.songs.orEmpty()
	val otherAlbums by viewModel.otherAlbums.collectAsStateWithLifecycle()

	val allDownloads by downloadManager.allDownloads.collectAsStateWithLifecycle(emptyList())
	val downloadsMap = remember(allDownloads) { allDownloads.associateBy { it.songId } }

	var isMultiSelecting by rememberSaveable { mutableStateOf(false) }
	val selectedSongIds = remember { mutableStateMapOf<String, Boolean>() }
	val selectedRowIndices = remember { mutableStateMapOf<Int, Boolean>() }
	var activeMenuSong by remember { mutableStateOf<DomainSong?>(null) }
	var activeMenuSongIndex by remember { mutableStateOf<Int?>(null) }
	var playlistDialogSongIds by remember { mutableStateOf<List<String>?>(null) }
	var pendingBatchRemoveIndices by remember { mutableStateOf<List<Int>?>(null) }
	var playlistMenuExpanded by remember { mutableStateOf(false) }
	var renamePlaylistOpen by remember { mutableStateOf(false) }
	var renamePlaylistInput by remember { mutableStateOf("") }
	var deletePlaylistConfirmOpen by remember { mutableStateOf(false) }
	var didNavigateBackAfterDelete by remember { mutableStateOf(false) }

	val isAlbum = collection is DomainAlbum
	val isPlaylist = collection is DomainPlaylist
	val isStarred = if (isAlbum) (collection as DomainAlbum).starredAt != null else false

	val clearAllSelections = {
		selectedSongIds.clear()
		selectedRowIndices.clear()
	}

	LaunchedEffect(songs.size, isPlaylist) {
		if (isPlaylist) {
			val staleKeys = selectedRowIndices.keys.filter { it !in songs.indices }
			staleKeys.forEach { selectedRowIndices.remove(it) }
		} else {
			val validIds = songs.mapTo(HashSet()) { it.id }
			val staleIds = selectedSongIds.keys.filter { it !in validIds }
			staleIds.forEach { selectedSongIds.remove(it) }
		}
	}

	BackHandler(enabled = isMultiSelecting) {
		isMultiSelecting = false
		clearAllSelections()
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
	) {
		if (collection == null && uiState is UiState.Loading) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				CircularProgressIndicator()
			}
		} else if (collection == null) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.statusBarsPadding(),
				contentAlignment = Alignment.Center
			) {
				MeloQuietEmptyState(
					message = "未找到专辑或歌单",
					icon = Icons.Outlined.LibraryMusic,
					actionLabel = "返回",
					onAction = { backStack.removeLastOrNull() }
				)
			}
		} else {
			LazyColumn(
				modifier = Modifier.fillMaxSize(),
				contentPadding = PaddingValues(
					top = 0.dp,
					bottom = MeloBottomChromeClearance + if (isMultiSelecting) 128.dp else 0.dp
				)
			) {
				// 1. 顶部大封面 + 流光毛玻璃头部
				item(key = "collection-header", contentType = "header") {
					val headerTopColor = MaterialTheme.colorScheme.surfaceContainerHigh
					val headerBgColor = MaterialTheme.colorScheme.background
					val headerBrush = remember(headerTopColor, headerBgColor) {
						Brush.verticalGradient(
							colors = listOf(
								headerTopColor.copy(alpha = 0.88f),
								headerBgColor
							)
						)
					}
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.background(headerBrush)
							.statusBarsPadding()
							.padding(horizontal = 16.dp, vertical = 12.dp)
					) {
						Column(
							modifier = Modifier.fillMaxWidth(),
							horizontalAlignment = Alignment.CenterHorizontally,
							verticalArrangement = Arrangement.spacedBy(14.dp)
						) {
							// 顶端导航栏
							Row(
								modifier = Modifier.fillMaxWidth(),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.SpaceBetween
							) {
								Surface(
									onClick = { backStack.removeLastOrNull() },
									shape = CircleShape,
									color = MaterialTheme.colorScheme.surfaceContainer,
									border = libraryHairlineBorder(),
									modifier = Modifier.size(38.dp)
								) {
									Box(contentAlignment = Alignment.Center) {
										Icon(
											imageVector = Icons.Outlined.ArrowBack,
											contentDescription = "返回",
											modifier = Modifier.size(20.dp)
										)
									}
								}

								Text(
									text = if (isAlbum) "专辑" else "歌单",
									style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
									color = MaterialTheme.colorScheme.onSurface
								)

								if (isAlbum) {
									Surface(
										onClick = { viewModel.starAlbum(!isStarred) },
										shape = CircleShape,
										color = if (isStarred) MaterialTheme.colorScheme.meloGoldContainer else MaterialTheme.colorScheme.surfaceContainer,
										border = libraryHairlineBorder(),
										modifier = Modifier.size(38.dp)
									) {
										Box(contentAlignment = Alignment.Center) {
											Icon(
												imageVector = if (isStarred) Icons.Filled.Star else Icons.Outlined.Star,
												contentDescription = "收藏专辑",
												tint = if (isStarred) MaterialTheme.colorScheme.meloGoldText else MaterialTheme.colorScheme.onSurfaceVariant,
												modifier = Modifier.size(20.dp)
											)
										}
									}
								} else if (isPlaylist) {
									Box {
										Surface(
											onClick = { playlistMenuExpanded = true },
											shape = CircleShape,
											color = MaterialTheme.colorScheme.surfaceContainer,
											border = libraryHairlineBorder(),
											modifier = Modifier.size(38.dp)
										) {
											Box(contentAlignment = Alignment.Center) {
												Icon(
													imageVector = Icons.Outlined.MoreVert,
													contentDescription = "歌单管理",
													tint = MaterialTheme.colorScheme.onSurfaceVariant,
													modifier = Modifier.size(20.dp)
												)
											}
										}
										DropdownMenu(
											expanded = playlistMenuExpanded,
											onDismissRequest = { playlistMenuExpanded = false }
										) {
											DropdownMenuItem(
												text = { Text("重命名歌单") },
												leadingIcon = {
													Icon(
														imageVector = Icons.Outlined.Edit,
														contentDescription = null
													)
												},
												onClick = {
													playlistMenuExpanded = false
													renamePlaylistInput = collection.name.orEmpty()
													renamePlaylistOpen = true
												}
											)
											DropdownMenuItem(
												text = {
													Text(
														text = "删除歌单",
														color = MaterialTheme.colorScheme.error
													)
												},
												leadingIcon = {
													Icon(
														imageVector = Icons.Outlined.Delete,
														contentDescription = null,
														tint = MaterialTheme.colorScheme.error
													)
												},
												onClick = {
													playlistMenuExpanded = false
													deletePlaylistConfirmOpen = true
												}
											)
										}
									}
								} else {
									Spacer(Modifier.size(38.dp))
								}
							}

							// 封面卡
							Surface(
								shape = RoundedCornerShape(18.dp),
								color = MaterialTheme.colorScheme.surfaceContainerHighest,
								border = libraryHairlineBorder(),
								shadowElevation = 4.dp,
								modifier = Modifier.size(172.dp)
							) {
								CoverArt(
									coverArtId = collection.coverArtId,
									contentDescription = collection.name,
									modifier = Modifier.fillMaxSize()
								)
							}

							// 标题与副标题信息
							Column(
								horizontalAlignment = Alignment.CenterHorizontally,
								verticalArrangement = Arrangement.spacedBy(4.dp),
								modifier = Modifier.padding(horizontal = 12.dp)
							) {
								Text(
									text = collection.name ?: "未命名",
									style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
									color = MaterialTheme.colorScheme.onSurface,
									textAlign = TextAlign.Center,
									maxLines = 2,
									overflow = TextOverflow.Ellipsis
								)

								if (isAlbum) {
									val album = collection as DomainAlbum
									Row(
										horizontalArrangement = Arrangement.spacedBy(6.dp),
										verticalAlignment = Alignment.CenterVertically
									) {
										Text(
											text = album.artistName ?: "未知艺人",
											style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
											color = MaterialTheme.colorScheme.primary,
											modifier = Modifier.clickable {
												backStack.add(Screen.ArtistDetail(album.artistId))
											}
										)
										if (album.year != null && album.year > 0) {
											Text("·", color = MaterialTheme.colorScheme.outline)
											Text(
												text = "${album.year}",
												style = MaterialTheme.typography.bodyMedium.tabularNums(),
												color = MaterialTheme.colorScheme.onSurfaceVariant
											)
										}
									}
								} else {
									val pl = collection as? DomainPlaylist
									if (!pl?.owner.isNullOrBlank()) {
										Text(
											text = pl?.owner.orEmpty(),
											style = MaterialTheme.typography.bodyMedium,
											color = MaterialTheme.colorScheme.onSurfaceVariant
										)
									}
								}

								// 曲目与时长统计
								val durationStr = collection.duration?.toHoursMinutesSeconds().orEmpty()
								Text(
									text = "共 ${songs.size} 首" + if (durationStr.isNotBlank()) " · $durationStr" else "",
									style = MaterialTheme.typography.labelMedium.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
							}

							// 操作按钮胶囊条（播放全部 + 离线全辑 + 随机播放圆钮 + 多选开关圆钮）
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(8.dp),
								verticalAlignment = Alignment.CenterVertically
							) {
								Button(
									onClick = { if (songs.isNotEmpty()) player.playNow(songs, 0) },
									modifier = Modifier
										.weight(1f)
										.height(42.dp),
									shape = CircleShape,
									contentPadding = PaddingValues(horizontal = 10.dp)
								) {
									Icon(MeloIcons.PlayArrow, null, modifier = Modifier.size(16.dp))
									Spacer(Modifier.width(4.dp))
									Text("播放全部", maxLines = 1, softWrap = false)
								}

								Surface(
									onClick = {
										if (songs.isNotEmpty()) {
											scope.launch {
												queueSongsOffline(downloadManager, songs)
												snackBarState.showSnackbar("已将全部 ${songs.size} 首歌曲加入手机下载队列")
											}
										}
									},
									shape = CircleShape,
									color = MaterialTheme.colorScheme.surfaceContainer,
									border = libraryHairlineBorder(),
									modifier = Modifier
										.weight(1f)
										.height(42.dp)
								) {
									Row(
										modifier = Modifier.padding(horizontal = 10.dp),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.Center
									) {
										Icon(Icons.Outlined.Download, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
										Spacer(Modifier.width(4.dp))
										Text("离线全辑", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
									}
								}

								Surface(
									onClick = { if (songs.isNotEmpty()) player.playNow(songs.shuffled(), 0) },
									shape = CircleShape,
									color = MaterialTheme.colorScheme.surfaceContainer,
									border = libraryHairlineBorder(),
									modifier = Modifier.size(42.dp)
								) {
									Box(contentAlignment = Alignment.Center) {
										Icon(Icons.Outlined.Shuffle, contentDescription = "随机播放", modifier = Modifier.size(18.dp))
									}
								}

								Surface(
									onClick = {
										isMultiSelecting = !isMultiSelecting
										if (!isMultiSelecting) clearAllSelections()
									},
									shape = CircleShape,
									color = if (isMultiSelecting) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
									border = libraryHairlineBorder(),
									modifier = Modifier.size(42.dp)
								) {
									Box(contentAlignment = Alignment.Center) {
										Icon(
											imageVector = if (isMultiSelecting) Icons.Outlined.Close else Icons.Outlined.Checklist,
											contentDescription = "多选",
											modifier = Modifier.size(18.dp)
										)
									}
								}
							}
						}
					}
				}

				// 2. 歌曲列表项
				itemsIndexed(
					items = songs,
					key = { idx, song -> "${song.id}-$idx" },
					contentType = { _, _ -> "track" }
				) { index, song ->
					val isSelected = if (isPlaylist) {
						selectedRowIndices[index] == true
					} else {
						selectedSongIds[song.id] == true
					}
					val dlStatus = downloadsMap[song.id]?.status
					val isFirst = index == 0
					val isLast = index == songs.lastIndex
					val qualityTag = remember(song.id, song.bitDepth, song.fileExtension, song.bitRate) {
						libraryFormatQuietQuality(song.bitDepth, song.fileExtension, song.bitRate)
					}
					val subtitleText = remember(song.id, song.artistName, song.duration, qualityTag) {
						listOfNotNull(
							song.artistName?.takeIf { it.isNotBlank() },
							song.duration.takeIf { it.inWholeSeconds > 0 }?.toHoursMinutesSeconds(),
							qualityTag
						).joinToString(" · ")
					}

					val rowShape = libraryGroupedItemShape(isFirst, isLast)
					val isDownloaded = dlStatus == DownloadStatus.DOWNLOADED
					MeloSwipeableSongRow(
						song = song,
						selectionMode = isMultiSelecting,
						shape = rowShape,
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 16.dp)
					) {
						Surface(
							modifier = Modifier
								.fillMaxWidth()
								.combinedClickable(
									onClick = {
										if (isMultiSelecting) {
											if (isPlaylist) {
												selectedRowIndices[index] = !isSelected
											} else {
												selectedSongIds[song.id] = !isSelected
											}
										} else {
											player.playNow(songs, index)
										}
									},
									onLongClick = {
										isMultiSelecting = true
										if (isPlaylist) {
											selectedRowIndices[index] = true
										} else {
											selectedSongIds[song.id] = true
										}
									}
								),
							shape = rowShape,
							color = if (isMultiSelecting && isSelected) {
								MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
							} else {
								MaterialTheme.colorScheme.surfaceContainer
							}
						) {
							Column {
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.padding(horizontal = 12.dp, vertical = 10.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(10.dp)
								) {
									if (isMultiSelecting) {
										Checkbox(
											checked = isSelected,
											onCheckedChange = { checked ->
												if (isPlaylist) {
													selectedRowIndices[index] = checked
												} else {
													selectedSongIds[song.id] = checked
												}
											}
										)
									} else {
										Text(
											text = "${index + 1}",
											style = MaterialTheme.typography.labelMedium.tabularNums(),
											color = MaterialTheme.colorScheme.onSurfaceVariant,
											modifier = Modifier.width(24.dp),
											textAlign = TextAlign.Center
										)
									}

									Column(
										modifier = Modifier.weight(1f),
										verticalArrangement = Arrangement.spacedBy(2.dp)
									) {
										Text(
											text = song.title.ifBlank { "未知歌曲" },
											style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
											maxLines = 1,
											overflow = TextOverflow.Ellipsis
										)
										if (subtitleText.isNotBlank() || isDownloaded) {
											Row(
												verticalAlignment = Alignment.CenterVertically,
												horizontalArrangement = Arrangement.spacedBy(4.dp)
											) {
												if (isDownloaded) {
													MeloDownloadedTinyBadge()
												}
												if (subtitleText.isNotBlank()) {
													Text(
														text = subtitleText,
														style = MaterialTheme.typography.bodySmall.tabularNums(),
														color = MaterialTheme.colorScheme.onSurfaceVariant,
														maxLines = 1,
														overflow = TextOverflow.Ellipsis
													)
												}
											}
										}
									}

									IconButton(
										onClick = {
											activeMenuSong = song
											activeMenuSongIndex = index
										},
										modifier = Modifier.size(32.dp)
									) {
										Icon(
											imageVector = Icons.Outlined.MoreVert,
											contentDescription = "更多",
											tint = MaterialTheme.colorScheme.onSurfaceVariant,
											modifier = Modifier.size(18.dp)
										)
									}
								}
								if (!isLast) {
									MeloHairlineDivider(startIndent = if (isMultiSelecting) 56.dp else 46.dp)
								}
							}
						}
					}
				}

				// 3. 其它相关专辑 (如果是专辑)
				if (isAlbum && otherAlbums.isNotEmpty()) {
					item(key = "other-albums-section", contentType = "carousel") {
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.padding(top = 24.dp, bottom = 8.dp)
								.padding(horizontal = 16.dp),
							verticalArrangement = Arrangement.spacedBy(12.dp)
						) {
							Text(
								text = "其它专辑",
								style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
							)
							LazyRow(
								horizontalArrangement = Arrangement.spacedBy(12.dp),
								contentPadding = PaddingValues(bottom = 8.dp)
							) {
								items(
									items = otherAlbums,
									key = { it.id },
									contentType = { "album-card" }
								) { itemAlbum ->
									Surface(
										onClick = { backStack.add(Screen.CollectionDetail(itemAlbum.id, "album")) },
										shape = RoundedCornerShape(14.dp),
										color = MaterialTheme.colorScheme.surfaceContainer,
										border = libraryHairlineBorder(),
										modifier = Modifier.width(128.dp)
									) {
										Column(modifier = Modifier.padding(8.dp)) {
											CoverArt(
												coverArtId = itemAlbum.coverArtId,
												contentDescription = itemAlbum.name,
												modifier = Modifier
													.fillMaxWidth()
													.aspectRatio(1f)
													.clip(RoundedCornerShape(10.dp))
											)
											Spacer(Modifier.height(6.dp))
											Text(
												text = itemAlbum.name ?: "专辑",
												style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
												maxLines = 1,
												overflow = TextOverflow.Ellipsis
											)
											Text(
												text = itemAlbum.year?.let { "$it" } ?: "专辑",
												style = MaterialTheme.typography.labelSmall.tabularNums(),
												color = MaterialTheme.colorScheme.onSurfaceVariant
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

		// 多选 Studio 批控悬浮条
		AnimatedVisibility(
			visible = isMultiSelecting && songs.isNotEmpty(),
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.navigationBarsPadding()
				.padding(bottom = rememberBatchDockBottomClearance(), start = 16.dp, end = 16.dp)
		) {
			val pickedIndices by remember(songs, isPlaylist) {
				derivedStateOf {
					if (isPlaylist) {
						songs.indices.filter { selectedRowIndices[it] == true }
					} else {
						emptyList()
					}
				}
			}
			val pickedSongs by remember(songs, isPlaylist) {
				derivedStateOf {
					if (isPlaylist) {
						pickedIndices.map { songs[it] }
					} else {
						songs.filter { selectedSongIds[it.id] == true }
					}
				}
			}
			val hasRemovableOffline = remember(pickedSongs, downloadsMap) {
				pickedSongs.any {
					val st = downloadsMap[it.id]?.status
					st == DownloadStatus.DOWNLOADED || st == DownloadStatus.DOWNLOADING
				}
			}
			StudioBatchSelectionDock(
				selectedCount = pickedSongs.size,
				totalCount = songs.size,
				hasOfflineRemovable = hasRemovableOffline,
				onSelectAll = {
					if (isPlaylist) {
						if (pickedIndices.size == songs.size && songs.isNotEmpty()) {
							selectedRowIndices.clear()
						} else {
							songs.indices.forEach { selectedRowIndices[it] = true }
						}
					} else {
						if (pickedSongs.size == songs.size && songs.isNotEmpty()) {
							selectedSongIds.clear()
						} else {
							songs.forEach { selectedSongIds[it.id] = true }
						}
					}
				},
				onClear = {
					clearAllSelections()
				},
				onExit = {
					isMultiSelecting = false
					clearAllSelections()
				},
				onDownloadSelected = {
					if (pickedSongs.isNotEmpty()) {
						scope.launch {
							queueSongsOffline(downloadManager, pickedSongs)
							snackBarState.showSnackbar("已将 ${pickedSongs.size} 首加入手机离线下载队列")
							isMultiSelecting = false
							clearAllSelections()
						}
					}
				},
				onAddToPlaylist = {
					if (pickedSongs.isNotEmpty()) {
						playlistDialogSongIds = pickedSongs.map { it.id }
					}
				},
				onPlaySelected = {
					if (pickedSongs.isNotEmpty()) {
						player.playNow(pickedSongs, 0)
						isMultiSelecting = false
						clearAllSelections()
					}
				},
				onDeleteOfflineSelected = {
					removeSongsOffline(downloadManager, downloadsMap, pickedSongs)
					scope.launch { snackBarState.showSnackbar("已移除所选曲目的手机离线缓存") }
					isMultiSelecting = false
					clearAllSelections()
				},
				onRemoveFromPlaylistSelected = if (isPlaylist) {
					{
						if (pickedIndices.isNotEmpty()) {
							pendingBatchRemoveIndices = pickedIndices
						}
					}
				} else null
			)
		}

		// 单曲更多菜单 BottomSheet
		activeMenuSong?.let { song ->
			val targetIndex = activeMenuSongIndex
			NativeDetailSongActionSheet(
				song = song,
				downloadStatus = downloadsMap[song.id]?.status,
				onDismiss = {
					activeMenuSong = null
					activeMenuSongIndex = null
				},
				onPlayNext = {
					player.playNextSingle(song)
					activeMenuSong = null
					activeMenuSongIndex = null
					scope.launch { snackBarState.showSnackbar("下一首播放：${song.title}") }
				},
				onAddToQueue = {
					player.addToQueue(listOf(song))
					activeMenuSong = null
					activeMenuSongIndex = null
					scope.launch { snackBarState.showSnackbar("已添加到播放队列尾部") }
				},
				onAddToPlaylist = {
					playlistDialogSongIds = listOf(song.id)
					activeMenuSong = null
					activeMenuSongIndex = null
				},
				onDownload = {
					activeMenuSong = null
					activeMenuSongIndex = null
					scope.launch {
						queueSongsOffline(downloadManager, listOf(song))
						snackBarState.showSnackbar("已加入手机下载：${song.title}")
					}
				},
				onCancelDownload = {
					downloadManager.cancelDownload(song.id)
					activeMenuSong = null
					activeMenuSongIndex = null
					scope.launch { snackBarState.showSnackbar("已取消下载") }
				},
				onDeleteDownload = {
					downloadManager.deleteDownload(song.id)
					activeMenuSong = null
					activeMenuSongIndex = null
					scope.launch { snackBarState.showSnackbar("已从手机删除离线文件") }
				},
				onRemoveFromPlaylist = if (isPlaylist && targetIndex != null) {
					{
						activeMenuSong = null
						activeMenuSongIndex = null
						viewModel.removeSongAtIndex(targetIndex)
					}
				} else null
			)
		}

		playlistDialogSongIds?.let { ids ->
			PlaylistUpdateDialog(
				songIds = ids.toPersistentList(),
				onSuccess = {
					isMultiSelecting = false
					clearAllSelections()
				},
				onDismissRequest = { playlistDialogSongIds = null }
			)
		}

		pendingBatchRemoveIndices?.let { indices ->
			AlertDialog(
				onDismissRequest = { pendingBatchRemoveIndices = null },
				icon = {
					Icon(
						imageVector = Icons.Outlined.PlaylistRemove,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.error
					)
				},
				title = { Text("移出歌单") },
				text = {
					Text("确认从「${collection?.name ?: "当前歌单"}」移除选中的 ${indices.size} 首歌曲吗？仅从歌单移除，不会删除服务器音乐文件。")
				},
				confirmButton = {
					Button(
						onClick = {
							pendingBatchRemoveIndices = null
							isMultiSelecting = false
							clearAllSelections()
							viewModel.removeSongsAtIndices(indices)
						},
						colors = ButtonDefaults.buttonColors(
							containerColor = MaterialTheme.colorScheme.error,
							contentColor = MaterialTheme.colorScheme.onError
						)
					) {
						Text("确认移除")
					}
				},
				dismissButton = {
					TextButton(onClick = { pendingBatchRemoveIndices = null }) {
						Text("取消")
					}
				}
			)
		}

		if (renamePlaylistOpen) {
			AlertDialog(
				onDismissRequest = { renamePlaylistOpen = false },
				icon = {
					Icon(
						imageVector = Icons.Outlined.Edit,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.primary
					)
				},
				title = { Text("重命名歌单") },
				text = {
					OutlinedTextField(
						value = renamePlaylistInput,
						onValueChange = { renamePlaylistInput = it },
						singleLine = true,
						shape = RoundedCornerShape(12.dp),
						label = { Text("歌单名称") },
						modifier = Modifier.fillMaxWidth()
					)
				},
				confirmButton = {
					Button(
						onClick = {
							val nextName = renamePlaylistInput.trim()
							renamePlaylistOpen = false
							viewModel.renamePlaylist(nextName)
						},
						enabled = renamePlaylistInput.isNotBlank() &&
							renamePlaylistInput.trim() != (collection?.name ?: "").trim()
					) {
						Text("保存")
					}
				},
				dismissButton = {
					TextButton(onClick = { renamePlaylistOpen = false }) {
						Text("取消")
					}
				}
			)
		}

		if (deletePlaylistConfirmOpen) {
			AlertDialog(
				onDismissRequest = { deletePlaylistConfirmOpen = false },
				icon = {
					Icon(
						imageVector = Icons.Outlined.Delete,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.error
					)
				},
				title = { Text("删除歌单") },
				text = {
					Text("确认删除歌单「${collection?.name ?: "未命名歌单"}」吗？此操作不可撤销，不会删除音乐文件。")
				},
				confirmButton = {
					Button(
						onClick = {
							deletePlaylistConfirmOpen = false
							viewModel.deletePlaylist {
								if (!didNavigateBackAfterDelete) {
									didNavigateBackAfterDelete = true
									backStack.removeLastOrNull()
								}
							}
						},
						colors = ButtonDefaults.buttonColors(
							containerColor = MaterialTheme.colorScheme.error,
							contentColor = MaterialTheme.colorScheme.onError
						)
					) {
						Text("确认删除")
					}
				},
				dismissButton = {
					TextButton(onClick = { deletePlaylistConfirmOpen = false }) {
						Text("取消")
					}
				}
			)
		}

		ErrorSnackBar(
			error = (uiState as? UiState.Error)?.error,
			onClearError = viewModel::clearError
		)
	}
}

/* =========================================================================================
 * 2. MeloArtistDetailScreen (原生 Studio 歌手详情)
 * ========================================================================================= */

@Composable
fun MeloArtistDetailScreen(artistId: String) {
	val viewModel = koinViewModel<ArtistDetailViewModel> { parametersOf(artistId) }
	val downloadManager = koinInject<DownloadManager>()
	val player = koinInject<MediaPlayer>()
	val backStack = LocalNavStack.current
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()

	val uiState by viewModel.artistState.collectAsStateWithLifecycle()
	val isStarred by viewModel.starred.collectAsStateWithLifecycle()

	val allDownloads by downloadManager.allDownloads.collectAsStateWithLifecycle(emptyList())
	val downloadsMap = remember(allDownloads) { allDownloads.associateBy { it.songId } }

	var isMultiSelecting by rememberSaveable { mutableStateOf(false) }
	val selectedSongIds = remember { mutableStateMapOf<String, Boolean>() }
	var activeMenuSong by remember { mutableStateOf<DomainSong?>(null) }
	var playlistDialogSongIds by remember { mutableStateOf<List<String>?>(null) }

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
	) {
		when (val state = uiState) {
			is UiState.Loading -> {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					CircularProgressIndicator()
				}
			}
			is UiState.Error -> {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.statusBarsPadding(),
					contentAlignment = Alignment.Center
				) {
					MeloQuietEmptyState(
						message = state.error.message ?: "加载歌手失败",
						icon = Icons.Outlined.Artist,
						actionLabel = "返回",
						onAction = { backStack.removeLastOrNull() }
					)
				}
			}
			is UiState.Success -> {
				val artistData = state.data
				val artist = artistData.artist
				val topSongs = artistData.topSongs
				val albums = artistData.albums
				val similarArtists = artistData.similarArtists

				LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = PaddingValues(
						top = 0.dp,
						bottom = MeloBottomChromeClearance + if (isMultiSelecting) 128.dp else 0.dp
					)
				) {
					// 1. 歌手大头像头部
					item(key = "artist-header", contentType = "header") {
						val headerTopColor = MaterialTheme.colorScheme.surfaceContainerHigh
						val headerBgColor = MaterialTheme.colorScheme.background
						val headerBrush = remember(headerTopColor, headerBgColor) {
							Brush.verticalGradient(
								colors = listOf(
									headerTopColor.copy(alpha = 0.88f),
									headerBgColor
								)
							)
						}
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.background(headerBrush)
								.statusBarsPadding()
								.padding(horizontal = 16.dp, vertical = 12.dp)
						) {
							Column(
								modifier = Modifier.fillMaxWidth(),
								horizontalAlignment = Alignment.CenterHorizontally,
								verticalArrangement = Arrangement.spacedBy(14.dp)
							) {
								// 顶栏返回与关注
								Row(
									modifier = Modifier.fillMaxWidth(),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.SpaceBetween
								) {
									Surface(
										onClick = { backStack.removeLastOrNull() },
										shape = CircleShape,
										color = MaterialTheme.colorScheme.surfaceContainer,
										border = libraryHairlineBorder(),
										modifier = Modifier.size(38.dp)
									) {
										Box(contentAlignment = Alignment.Center) {
											Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", modifier = Modifier.size(20.dp))
										}
									}

									Text(
										text = "歌手",
										style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
									)

									Surface(
										onClick = { viewModel.starArtist(!isStarred) },
										shape = CircleShape,
										color = if (isStarred) MaterialTheme.colorScheme.meloGoldContainer else MaterialTheme.colorScheme.surfaceContainer,
										border = libraryHairlineBorder(),
										modifier = Modifier.size(38.dp)
									) {
										Box(contentAlignment = Alignment.Center) {
											Icon(
												imageVector = if (isStarred) Icons.Filled.Star else Icons.Outlined.Star,
												contentDescription = "关注歌手",
												tint = if (isStarred) MaterialTheme.colorScheme.meloGoldText else MaterialTheme.colorScheme.onSurfaceVariant,
												modifier = Modifier.size(20.dp)
											)
										}
									}
								}

								// 圆形大头像
								Surface(
									shape = CircleShape,
									color = MaterialTheme.colorScheme.surfaceContainerHighest,
									border = libraryHairlineBorder(),
									shadowElevation = 4.dp,
									modifier = Modifier.size(144.dp)
								) {
									MeloArtistAvatar(
										artistName = artist.name,
										coverArtId = artist.coverArtId,
										artistImageUrl = artist.artistImageUrl,
										contentDescription = artist.name,
										modifier = Modifier.fillMaxSize(),
										shape = CircleShape
									)
								}

								// 艺人名字与作品概览
								Column(
									horizontalAlignment = Alignment.CenterHorizontally,
									verticalArrangement = Arrangement.spacedBy(4.dp)
								) {
									Text(
										text = artist.name,
										style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
										textAlign = TextAlign.Center
									)
									Text(
										text = "${albums.size} 专辑 · ${topSongs.size} 首热门",
										style = MaterialTheme.typography.labelMedium.tabularNums(),
										color = MaterialTheme.colorScheme.onSurfaceVariant
									)
								}

								// 快捷动作栏：播放热门 + 离线热门
								Row(
									modifier = Modifier.fillMaxWidth(),
									horizontalArrangement = Arrangement.spacedBy(10.dp)
								) {
									Button(
										onClick = { if (topSongs.isNotEmpty()) player.playNow(topSongs, 0) },
										modifier = Modifier
											.weight(1f)
											.height(42.dp),
										shape = CircleShape,
										contentPadding = PaddingValues(horizontal = 12.dp)
									) {
										Icon(MeloIcons.PlayArrow, null, modifier = Modifier.size(16.dp))
										Spacer(Modifier.width(4.dp))
										Text("播放热门", maxLines = 1, softWrap = false)
									}

									FilledTonalButton(
										onClick = {
											if (topSongs.isNotEmpty()) {
												scope.launch {
													queueSongsOffline(downloadManager, topSongs)
													snackBarState.showSnackbar("已将 ${topSongs.size} 首热门曲目加入手机下载队列")
												}
											}
										},
										modifier = Modifier
											.weight(1f)
											.height(42.dp),
										shape = CircleShape,
										contentPadding = PaddingValues(horizontal = 12.dp)
									) {
										Icon(Icons.Outlined.Download, null, modifier = Modifier.size(16.dp))
										Spacer(Modifier.width(4.dp))
										Text("离线热门", maxLines = 1, softWrap = false)
									}
								}
							}
						}
					}

					// 2. 热门单曲榜
					if (topSongs.isNotEmpty()) {
						item(key = "top-songs-header", contentType = "section-header") {
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 16.dp, vertical = 10.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.SpaceBetween
							) {
								Text(
									text = "热门作品 · ${topSongs.size}",
									style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
								)
								Surface(
									onClick = {
										isMultiSelecting = !isMultiSelecting
										if (!isMultiSelecting) selectedSongIds.clear()
									},
									shape = CircleShape,
									color = if (isMultiSelecting) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
									border = libraryHairlineBorder()
								) {
									Row(
										modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.spacedBy(4.dp)
									) {
										Icon(
											imageVector = if (isMultiSelecting) Icons.Outlined.Close else Icons.Outlined.Checklist,
											contentDescription = null,
											modifier = Modifier.size(14.dp)
										)
										Text(
											text = if (isMultiSelecting) "退出" else "多选",
											style = MaterialTheme.typography.labelMedium,
											maxLines = 1,
											softWrap = false
										)
									}
								}
							}
						}

						itemsIndexed(
							items = topSongs,
							key = { _, s -> "top-${s.id}" },
							contentType = { _, _ -> "track" }
						) { idx, song ->
							val isSelected = selectedSongIds[song.id] == true
							val dlStatus = downloadsMap[song.id]?.status
							val isFirst = idx == 0
							val isLast = idx == topSongs.lastIndex
							val qualityTag = remember(song.id, song.bitDepth, song.fileExtension, song.bitRate) {
								libraryFormatQuietQuality(song.bitDepth, song.fileExtension, song.bitRate)
							}
							val topSubText = remember(song.id, song.albumTitle, artist.name, qualityTag) {
								listOfNotNull(
									(song.albumTitle ?: artist.name).takeIf { it.isNotBlank() },
									qualityTag
								).joinToString(" · ")
							}
							val rowShape = libraryGroupedItemShape(isFirst, isLast)
							val isDownloaded = dlStatus == DownloadStatus.DOWNLOADED
							MeloSwipeableSongRow(
								song = song,
								selectionMode = isMultiSelecting,
								shape = rowShape,
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 16.dp)
							) {
								Surface(
									modifier = Modifier
										.fillMaxWidth()
										.combinedClickable(
											onClick = {
												if (isMultiSelecting) {
													selectedSongIds[song.id] = !isSelected
												} else {
													player.playNow(topSongs, idx)
												}
											},
											onLongClick = {
												isMultiSelecting = true
												selectedSongIds[song.id] = true
											}
										),
									shape = rowShape,
									color = if (isMultiSelecting && isSelected) {
										MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
									} else {
										MaterialTheme.colorScheme.surfaceContainer
									}
								) {
									Column {
										Row(
											modifier = Modifier
												.fillMaxWidth()
												.padding(horizontal = 12.dp, vertical = 10.dp),
											verticalAlignment = Alignment.CenterVertically,
											horizontalArrangement = Arrangement.spacedBy(10.dp)
										) {
											if (isMultiSelecting) {
												Checkbox(
													checked = isSelected,
													onCheckedChange = { selectedSongIds[song.id] = it }
												)
											} else {
												Text(
													text = "${idx + 1}",
													style = MaterialTheme.typography.labelMedium.tabularNums(),
													color = if (idx < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
													fontWeight = if (idx < 3) FontWeight.SemiBold else FontWeight.Normal,
													modifier = Modifier.width(22.dp),
													textAlign = TextAlign.Center
												)
											}

											CoverArt(
												coverArtId = song.coverArtId,
												contentDescription = song.title,
												modifier = Modifier
													.size(44.dp)
													.clip(RoundedCornerShape(9.dp))
											)

											Column(
												modifier = Modifier.weight(1f),
												verticalArrangement = Arrangement.spacedBy(2.dp)
											) {
												Text(
													text = song.title.ifBlank { "未知歌曲" },
													style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
													maxLines = 1,
													overflow = TextOverflow.Ellipsis
												)
												Row(
													verticalAlignment = Alignment.CenterVertically,
													horizontalArrangement = Arrangement.spacedBy(4.dp)
												) {
													if (isDownloaded) {
														MeloDownloadedTinyBadge()
													}
													Text(
														text = topSubText,
														style = MaterialTheme.typography.bodySmall,
														color = MaterialTheme.colorScheme.onSurfaceVariant,
														maxLines = 1,
														overflow = TextOverflow.Ellipsis
													)
												}
											}

											IconButton(
												onClick = { activeMenuSong = song },
												modifier = Modifier.size(32.dp)
											) {
												Icon(
													imageVector = Icons.Outlined.MoreVert,
													contentDescription = "更多",
													tint = MaterialTheme.colorScheme.onSurfaceVariant,
													modifier = Modifier.size(18.dp)
												)
											}
										}
										if (!isLast) {
											MeloHairlineDivider(startIndent = if (isMultiSelecting) 108.dp else 94.dp)
										}
									}
								}
							}
						}
					}

					// 3. 全部专辑网格
					if (albums.isNotEmpty()) {
						item(key = "albums-header", contentType = "section-header") {
							Text(
								text = "全部专辑 · ${albums.size}",
								style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
								modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp)
							)
						}

						val rowCount = (albums.size + 1) / 2
						items(
							count = rowCount,
							key = { rowIdx -> "album-row-${albums[rowIdx * 2].id}" },
							contentType = { "album-grid-row" }
						) { rowIdx ->
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 16.dp, vertical = 6.dp),
								horizontalArrangement = Arrangement.spacedBy(12.dp)
							) {
								val first = albums[rowIdx * 2]
								val second = albums.getOrNull(rowIdx * 2 + 1)

								ArtistAlbumGridCard(
									album = first,
									modifier = Modifier.weight(1f),
									onClick = { backStack.add(Screen.CollectionDetail(first.id, "album")) }
								)

								if (second != null) {
									ArtistAlbumGridCard(
										album = second,
										modifier = Modifier.weight(1f),
										onClick = { backStack.add(Screen.CollectionDetail(second.id, "album")) }
									)
								} else {
									Spacer(Modifier.weight(1f))
								}
							}
						}
					}

					// 4. 相似歌手横滑推荐
					if (similarArtists.isNotEmpty()) {
						item(key = "similar-artists-section", contentType = "carousel") {
							Column(
								modifier = Modifier
									.fillMaxWidth()
									.padding(top = 24.dp, bottom = 12.dp)
									.padding(horizontal = 16.dp),
								verticalArrangement = Arrangement.spacedBy(10.dp)
							) {
								Text(
									text = "相似歌手",
									style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
								)
								LazyRow(
									horizontalArrangement = Arrangement.spacedBy(14.dp),
									contentPadding = PaddingValues(vertical = 4.dp)
								) {
									items(
										items = similarArtists,
										key = { it.id },
										contentType = { "artist-card" }
									) { sim ->
										Column(
											horizontalAlignment = Alignment.CenterHorizontally,
											verticalArrangement = Arrangement.spacedBy(6.dp),
											modifier = Modifier
												.width(80.dp)
												.clickable { backStack.add(Screen.ArtistDetail(sim.id)) }
										) {
											Surface(
												shape = CircleShape,
												color = MaterialTheme.colorScheme.surfaceContainer,
												border = libraryHairlineBorder(),
												modifier = Modifier.size(68.dp)
											) {
												MeloArtistAvatar(
													artistName = sim.name,
													coverArtId = sim.coverArtId,
													artistImageUrl = sim.artistImageUrl,
													contentDescription = sim.name,
													modifier = Modifier.fillMaxSize(),
													shape = CircleShape
												)
											}
											Text(
												text = sim.name,
												style = MaterialTheme.typography.labelSmall,
												textAlign = TextAlign.Center,
												maxLines = 1,
												overflow = TextOverflow.Ellipsis
											)
										}
									}
								}
							}
						}
					}
				}

				AnimatedVisibility(
					visible = isMultiSelecting && topSongs.isNotEmpty(),
					enter = slideInVertically { it } + fadeIn(),
					exit = slideOutVertically { it } + fadeOut(),
					modifier = Modifier
						.align(Alignment.BottomCenter)
						.navigationBarsPadding()
						.padding(bottom = rememberBatchDockBottomClearance(), start = 16.dp, end = 16.dp)
				) {
					val pickedSongs by remember(topSongs) {
						derivedStateOf { topSongs.filter { selectedSongIds[it.id] == true } }
					}
					val hasRemovableOffline = remember(pickedSongs, downloadsMap) {
						pickedSongs.any {
							val st = downloadsMap[it.id]?.status
							st == DownloadStatus.DOWNLOADED || st == DownloadStatus.DOWNLOADING
						}
					}
					StudioBatchSelectionDock(
						selectedCount = pickedSongs.size,
						totalCount = topSongs.size,
						hasOfflineRemovable = hasRemovableOffline,
						onSelectAll = {
							if (pickedSongs.size == topSongs.size) {
								selectedSongIds.clear()
							} else {
								topSongs.forEach { selectedSongIds[it.id] = true }
							}
						},
						onClear = { selectedSongIds.clear() },
						onExit = {
							isMultiSelecting = false
							selectedSongIds.clear()
						},
						onDownloadSelected = {
							if (pickedSongs.isNotEmpty()) {
								scope.launch {
									queueSongsOffline(downloadManager, pickedSongs)
									snackBarState.showSnackbar("已将 ${pickedSongs.size} 首加入手机离线下载队列")
									isMultiSelecting = false
									selectedSongIds.clear()
								}
							}
						},
						onAddToPlaylist = {
							if (pickedSongs.isNotEmpty()) {
								playlistDialogSongIds = pickedSongs.map { it.id }
							}
						},
						onPlaySelected = {
							if (pickedSongs.isNotEmpty()) {
								player.playNow(pickedSongs, 0)
								isMultiSelecting = false
								selectedSongIds.clear()
							}
						},
						onDeleteOfflineSelected = {
							removeSongsOffline(downloadManager, downloadsMap, pickedSongs)
							scope.launch { snackBarState.showSnackbar("已移除所选曲目的手机离线缓存") }
							isMultiSelecting = false
							selectedSongIds.clear()
						}
					)
				}
			}
		}

		activeMenuSong?.let { song ->
			NativeDetailSongActionSheet(
				song = song,
				downloadStatus = downloadsMap[song.id]?.status,
				onDismiss = { activeMenuSong = null },
				onPlayNext = {
					player.playNextSingle(song)
					activeMenuSong = null
					scope.launch { snackBarState.showSnackbar("下一首播放：${song.title}") }
				},
				onAddToQueue = {
					player.addToQueue(listOf(song))
					activeMenuSong = null
					scope.launch { snackBarState.showSnackbar("已添加到播放队列尾部") }
				},
				onAddToPlaylist = {
					playlistDialogSongIds = listOf(song.id)
					activeMenuSong = null
				},
				onDownload = {
					activeMenuSong = null
					scope.launch {
						queueSongsOffline(downloadManager, listOf(song))
						snackBarState.showSnackbar("已加入手机下载：${song.title}")
					}
				},
				onCancelDownload = {
					downloadManager.cancelDownload(song.id)
					activeMenuSong = null
					scope.launch { snackBarState.showSnackbar("已取消下载") }
				},
				onDeleteDownload = {
					downloadManager.deleteDownload(song.id)
					activeMenuSong = null
					scope.launch { snackBarState.showSnackbar("已从手机删除离线文件") }
				}
			)
		}

		playlistDialogSongIds?.let { ids ->
			PlaylistUpdateDialog(
				songIds = ids.toPersistentList(),
				onSuccess = {
					isMultiSelecting = false
					selectedSongIds.clear()
				},
				onDismissRequest = { playlistDialogSongIds = null }
			)
		}
	}
}

@Composable
private fun ArtistAlbumGridCard(
	album: DomainAlbum,
	modifier: Modifier = Modifier,
	onClick: () -> Unit
) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = libraryHairlineBorder(),
		modifier = modifier
	) {
		Column(modifier = Modifier.padding(9.dp)) {
			CoverArt(
				coverArtId = album.coverArtId,
				contentDescription = album.name,
				modifier = Modifier
					.fillMaxWidth()
					.aspectRatio(1f)
					.clip(RoundedCornerShape(10.dp))
			)
			Spacer(Modifier.height(8.dp))
			Text(
				text = album.name ?: "未知专辑",
				style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			val metaText = remember(album.id, album.year, album.songCount) {
				listOfNotNull(album.year?.let { "$it" }, "${album.songCount} 首").joinToString(" · ")
			}
			Text(
				text = metaText,
				style = MaterialTheme.typography.labelSmall.tabularNums(),
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
	}
}

/* =========================================================================================
 * 3. MeloLikedSongsScreen (原生 Studio「我喜欢」主页)
 * ========================================================================================= */

private enum class LikedSubTab(val label: String) {
	SONGS("歌曲"),
	ALBUMS("专辑"),
	ARTISTS("歌手")
}

@Composable
fun MeloLikedSongsScreen() {
	val persistentViewModelStoreOwner = koinInject<PersistentViewModelStoreOwner>()
	val songsVm = koinViewModel<SongListViewModel>(
		key = "meloLikedSongs",
		parameters = { parametersOf(DomainSongListType.FrequentlyPlayed, setOf(DomainFilter.Starred)) },
		viewModelStoreOwner = persistentViewModelStoreOwner
	)
	val albumsVm = koinViewModel<AlbumListViewModel>(
		key = "meloLikedAlbums",
		parameters = { parametersOf(DomainAlbumListType.AlphabeticalByName, setOf(DomainFilter.Starred)) },
		viewModelStoreOwner = persistentViewModelStoreOwner
	)
	val artistsVm = koinViewModel<ArtistListViewModel>(
		key = "meloLikedArtists",
		parameters = { parametersOf(DomainArtistListType.AlphabeticalByName, setOf(DomainFilter.Starred)) },
		viewModelStoreOwner = persistentViewModelStoreOwner
	)

	val downloadManager = koinInject<DownloadManager>()
	val songRepository = koinInject<SongRepository>()
	val player = koinInject<MediaPlayer>()
	val backStack = LocalNavStack.current
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()

	var subTab by rememberSaveable { mutableStateOf(LikedSubTab.SONGS) }

	val songsState by songsVm.uiState.collectAsStateWithLifecycle()
	val songsLazyItems = songsState.data?.items?.collectAsLazyPagingItems()
	val snapshotSongs = remember(songsLazyItems?.itemSnapshotList) {
		songsLazyItems?.itemSnapshotList?.items.orEmpty()
	}
	val likedItemCount = songsLazyItems?.itemCount ?: 0
	val albumsState by albumsVm.uiState.collectAsStateWithLifecycle()
	val albumsLazyItems = albumsState.data?.items?.collectAsLazyPagingItems()
	val artistsState by artistsVm.uiState.collectAsStateWithLifecycle()
	val artistsLazyItems = artistsState.data?.items?.collectAsLazyPagingItems()

	val allDownloads by downloadManager.allDownloads.collectAsStateWithLifecycle(emptyList())
	val downloadsMap = remember(allDownloads) { allDownloads.associateBy { it.songId } }

	var isMultiSelecting by rememberSaveable { mutableStateOf(false) }
	val selectedSongIds = remember { mutableStateMapOf<String, Boolean>() }
	var allLikedSongIds by remember { mutableStateOf<List<String>>(emptyList()) }
	var isLoadingLikedIds by remember { mutableStateOf(false) }
	var activeMenuSong by remember { mutableStateOf<DomainSong?>(null) }
	var playlistDialogSongIds by remember { mutableStateOf<List<String>?>(null) }

	LaunchedEffect(isMultiSelecting, subTab, likedItemCount, songsState) {
		if (!isMultiSelecting || subTab != LikedSubTab.SONGS) {
			allLikedSongIds = emptyList()
			isLoadingLikedIds = false
			return@LaunchedEffect
		}
		isLoadingLikedIds = true
		try {
			val ids = withContext(Dispatchers.IO) { songsVm.getSelectionOrder() }
			allLikedSongIds = ids
			if (ids.isNotEmpty() && selectedSongIds.isNotEmpty()) {
				val validIds = ids.toHashSet()
				val staleIds = selectedSongIds.keys.filter { it !in validIds }
				staleIds.forEach { selectedSongIds.remove(it) }
			}
		} finally {
			isLoadingLikedIds = false
		}
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			// 顶栏与分段胶囊
			Surface(
				color = MaterialTheme.colorScheme.background,
				modifier = Modifier.fillMaxWidth()
			) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.statusBarsPadding()
						.padding(horizontal = 16.dp, vertical = 8.dp),
					verticalArrangement = Arrangement.spacedBy(10.dp)
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween
					) {
						Surface(
							onClick = { backStack.removeLastOrNull() },
							shape = CircleShape,
							color = MaterialTheme.colorScheme.surfaceContainer,
							border = libraryHairlineBorder(),
							modifier = Modifier.size(38.dp)
						) {
							Box(contentAlignment = Alignment.Center) {
								Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", modifier = Modifier.size(20.dp))
							}
						}

						Text(
							text = "我喜欢",
							style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
						)

						Spacer(Modifier.size(38.dp))
					}

					// 三栏切换胶囊
					Surface(
						shape = CircleShape,
						color = MaterialTheme.colorScheme.surfaceContainerHigh,
						border = libraryHairlineBorder(),
						modifier = Modifier.fillMaxWidth()
					) {
						Row(
							modifier = Modifier.padding(3.dp),
							horizontalArrangement = Arrangement.spacedBy(4.dp)
						) {
							LikedSubTab.entries.forEach { entry ->
								val selected = subTab == entry
								Box(
									modifier = Modifier
										.weight(1f)
										.clip(CircleShape)
										.background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
										.clickable {
											subTab = entry
											isMultiSelecting = false
											selectedSongIds.clear()
										}
										.padding(vertical = 8.dp),
									contentAlignment = Alignment.Center
								) {
									Text(
										text = entry.label,
										style = MaterialTheme.typography.labelMedium.copy(
											fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
										),
										color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
									)
								}
							}
						}
					}
				}
			}

			// 内容主体
			when (subTab) {
				LikedSubTab.SONGS -> {
					val itemCount = likedItemCount

					LazyColumn(
						modifier = Modifier.fillMaxSize(),
						contentPadding = PaddingValues(
							start = 16.dp,
							end = 16.dp,
							top = 8.dp,
							bottom = MeloBottomChromeClearance + if (isMultiSelecting) 128.dp else 0.dp
						),
						verticalArrangement = Arrangement.spacedBy(0.dp)
					) {
						item(key = "liked-songs-bar", contentType = "toolbar") {
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(bottom = 10.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.SpaceBetween
							) {
								Text(
									text = when {
										isMultiSelecting && isLoadingLikedIds -> "加载中…"
										isMultiSelecting && allLikedSongIds.isNotEmpty() -> "共 ${allLikedSongIds.size} 首"
										else -> "共 $itemCount 首"
									},
									style = MaterialTheme.typography.labelLarge.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									softWrap = false
								)

								Row(
									horizontalArrangement = Arrangement.spacedBy(6.dp),
									verticalAlignment = Alignment.CenterVertically
								) {
									if (itemCount > 0) {
										Button(
											onClick = {
												scope.launch {
													val allSongs = withContext(Dispatchers.IO) {
														val allIds = songsVm.getSelectionOrder()
														if (allIds.isNotEmpty()) songsVm.getSelectedSongs(allIds) else emptyList()
													}
													if (allSongs.isNotEmpty()) {
														player.playNow(allSongs, 0)
													} else if (snapshotSongs.isNotEmpty()) {
														player.playNow(snapshotSongs, 0)
													}
												}
											},
											shape = CircleShape,
											contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
										) {
											Icon(MeloIcons.PlayArrow, null, modifier = Modifier.size(16.dp))
											Spacer(Modifier.width(4.dp))
											Text("播放全部", maxLines = 1, softWrap = false)
										}

										FilledTonalButton(
											onClick = {
												scope.launch {
													val allIds = withContext(Dispatchers.IO) { songsVm.getSelectionOrder() }
													if (allIds.isNotEmpty()) {
														withContext(Dispatchers.IO) {
															downloadManager.downloadCollection(allIds)
														}
														snackBarState.currentSnackbarData?.dismiss()
														snackBarState.showSnackbar("已将全部 ${allIds.size} 首歌曲加入手机离线队列（可在工坊 → 下载到手机查看）")
													}
												}
											},
											shape = CircleShape,
											contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
										) {
											Icon(Icons.Outlined.Download, null, modifier = Modifier.size(16.dp))
											Spacer(Modifier.width(4.dp))
											Text("全部离线", maxLines = 1, softWrap = false)
										}

										IconButton(
											onClick = {
												isMultiSelecting = !isMultiSelecting
												if (!isMultiSelecting) selectedSongIds.clear()
											}
										) {
											Icon(
												imageVector = if (isMultiSelecting) Icons.Outlined.Close else Icons.Outlined.Checklist,
												contentDescription = "多选"
											)
										}
									}
								}
							}
						}

						if (itemCount == 0) {
							item(key = "empty-liked-songs", contentType = "empty") {
								MeloQuietEmptyState(
									message = "暂无喜欢的歌曲",
									icon = Icons.Outlined.Star
								)
							}
						} else {
							val liveCount = songsLazyItems?.itemCount ?: 0
							items(
								count = liveCount,
								key = { idx ->
									songsLazyItems?.peekOrNull(idx)?.id?.let { "liked-song-$it" }
										?: "liked-song-idx-$idx"
								},
								contentType = { "track" }
							) { idx ->
								val song = songsLazyItems?.getOrNull(idx)
								if (song != null) {
									val isSelected = selectedSongIds[song.id] == true
									val dlStatus = downloadsMap[song.id]?.status
									val isFirst = idx == 0
									val isLast = idx == (songsLazyItems?.itemCount ?: 0) - 1
									val qualityTag = remember(song.id, song.bitDepth, song.fileExtension, song.bitRate) {
										libraryFormatQuietQuality(song.bitDepth, song.fileExtension, song.bitRate)
									}
									val subText = remember(song.id, song.artistName, song.albumTitle, qualityTag) {
										listOfNotNull(
											song.artistName?.takeIf { it.isNotBlank() },
											song.albumTitle?.takeIf { it.isNotBlank() },
											qualityTag
										).joinToString(" · ")
									}

									val rowShape = libraryGroupedItemShape(isFirst, isLast)
									val isDownloaded = dlStatus == DownloadStatus.DOWNLOADED
									MeloSwipeableSongRow(
										song = song,
										selectionMode = isMultiSelecting,
										shape = rowShape,
										modifier = Modifier.fillMaxWidth()
									) {
										Surface(
											shape = rowShape,
											color = if (isMultiSelecting && isSelected) {
												MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
											} else {
												MaterialTheme.colorScheme.surfaceContainer
											},
											modifier = Modifier
												.fillMaxWidth()
												.combinedClickable(
													onClick = {
														if (isMultiSelecting) {
															selectedSongIds[song.id] = !isSelected
														} else {
															val snapIdx = snapshotSongs.indexOfFirst { it.id == song.id }
															if (snapIdx >= 0) {
																player.playNow(snapshotSongs, snapIdx)
															} else {
																player.playNow(song)
															}
														}
													},
													onLongClick = {
														isMultiSelecting = true
														selectedSongIds[song.id] = true
													}
												)
										) {
											Column {
												Row(
													modifier = Modifier
														.fillMaxWidth()
														.padding(horizontal = 12.dp, vertical = 10.dp),
													verticalAlignment = Alignment.CenterVertically,
													horizontalArrangement = Arrangement.spacedBy(10.dp)
												) {
													if (isMultiSelecting) {
														Checkbox(
															checked = isSelected,
															onCheckedChange = { selectedSongIds[song.id] = it }
														)
													} else {
														CoverArt(
															coverArtId = song.coverArtId,
															contentDescription = song.title,
															modifier = Modifier
																.size(44.dp)
																.clip(RoundedCornerShape(9.dp))
														)
													}

													Column(
														modifier = Modifier.weight(1f),
														verticalArrangement = Arrangement.spacedBy(2.dp)
													) {
														Text(
															text = song.title.ifBlank { "未知歌曲" },
															style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
															maxLines = 1,
															overflow = TextOverflow.Ellipsis
														)
														if (subText.isNotBlank() || isDownloaded) {
															Row(
																verticalAlignment = Alignment.CenterVertically,
																horizontalArrangement = Arrangement.spacedBy(4.dp)
															) {
																if (isDownloaded) {
																	MeloDownloadedTinyBadge()
																}
																if (subText.isNotBlank()) {
																	Text(
																		text = subText,
																		style = MaterialTheme.typography.bodySmall,
																		color = MaterialTheme.colorScheme.onSurfaceVariant,
																		maxLines = 1,
																		overflow = TextOverflow.Ellipsis
																	)
																}
															}
														}
													}

													IconButton(
														onClick = { activeMenuSong = song },
														modifier = Modifier.size(32.dp)
													) {
														Icon(
															imageVector = Icons.Outlined.MoreVert,
															contentDescription = "更多",
															tint = MaterialTheme.colorScheme.onSurfaceVariant,
															modifier = Modifier.size(18.dp)
														)
													}
												}
												if (!isLast) {
													MeloHairlineDivider(startIndent = if (isMultiSelecting) 60.dp else 66.dp)
												}
											}
										}
									}
								}
							}
						}
					}
				}

				LikedSubTab.ALBUMS -> {
					val albumCount = albumsLazyItems?.itemCount ?: 0
					if (albumCount == 0) {
						Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
							MeloQuietEmptyState(
								message = "暂无收藏专辑",
								icon = Icons.Outlined.Album
							)
						}
					} else {
						LazyColumn(
							modifier = Modifier.fillMaxSize(),
							contentPadding = PaddingValues(
								start = 16.dp,
								end = 16.dp,
								top = 8.dp,
								bottom = MeloBottomChromeClearance
							)
						) {
							val liveCount = albumsLazyItems?.itemCount ?: 0
							val liveRowCount = (liveCount + 1) / 2
							items(
								count = liveRowCount,
								key = { rowIdx ->
									albumsLazyItems?.peekOrNull(rowIdx * 2)?.id?.let { "liked-album-row-$it" }
										?: "liked-album-row-idx-$rowIdx"
								},
								contentType = { "album-grid-row" }
							) { rowIdx ->
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.padding(vertical = 6.dp),
									horizontalArrangement = Arrangement.spacedBy(12.dp)
								) {
									val first = albumsLazyItems?.getOrNull(rowIdx * 2)
									val second = albumsLazyItems?.getOrNull(rowIdx * 2 + 1)
									if (first != null) {
										ArtistAlbumGridCard(
											album = first,
											modifier = Modifier.weight(1f),
											onClick = { backStack.add(Screen.CollectionDetail(first.id, "album")) }
										)
									} else {
										Spacer(Modifier.weight(1f))
									}
									if (second != null) {
										ArtistAlbumGridCard(
											album = second,
											modifier = Modifier.weight(1f),
											onClick = { backStack.add(Screen.CollectionDetail(second.id, "album")) }
										)
									} else {
										Spacer(Modifier.weight(1f))
									}
								}
							}
						}
					}
				}

				LikedSubTab.ARTISTS -> {
					val artistCount = artistsLazyItems?.itemCount ?: 0
					if (artistCount == 0) {
						Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
							MeloQuietEmptyState(
								message = "暂无关注歌手",
								icon = Icons.Outlined.Artist
							)
						}
					} else {
						LazyColumn(
							modifier = Modifier.fillMaxSize(),
							contentPadding = PaddingValues(
								start = 16.dp,
								end = 16.dp,
								top = 8.dp,
								bottom = MeloBottomChromeClearance
							),
							verticalArrangement = Arrangement.spacedBy(0.dp)
						) {
							val liveCount = artistsLazyItems?.itemCount ?: 0
							items(
								count = liveCount,
								key = { idx ->
									artistsLazyItems?.peekOrNull(idx)?.id?.let { "liked-artist-$it" }
										?: "liked-artist-idx-$idx"
								},
								contentType = { "artist" }
							) { idx ->
								val artist = artistsLazyItems?.getOrNull(idx)
								if (artist != null) {
									val isFirst = idx == 0
									val isLast = idx == (artistsLazyItems?.itemCount ?: 0) - 1
									Surface(
										onClick = { backStack.add(Screen.ArtistDetail(artist.id)) },
										shape = libraryGroupedItemShape(isFirst, isLast),
										color = MaterialTheme.colorScheme.surfaceContainer,
										modifier = Modifier.fillMaxWidth()
									) {
										Column {
											Row(
												modifier = Modifier
													.fillMaxWidth()
													.padding(horizontal = 12.dp, vertical = 10.dp),
												verticalAlignment = Alignment.CenterVertically,
												horizontalArrangement = Arrangement.spacedBy(12.dp)
											) {
												Surface(
													shape = CircleShape,
													color = MaterialTheme.colorScheme.surfaceContainerHighest,
													modifier = Modifier.size(46.dp)
												) {
													MeloArtistAvatar(
														artistName = artist.name,
														coverArtId = artist.coverArtId,
														artistImageUrl = artist.artistImageUrl,
														contentDescription = artist.name,
														modifier = Modifier.fillMaxSize(),
														shape = CircleShape
													)
												}
												Column(modifier = Modifier.weight(1f)) {
													Text(
														text = artist.name,
														style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium)
													)
													Text(
														text = "${artist.albumCount} 张专辑",
														style = MaterialTheme.typography.bodySmall.tabularNums(),
														color = MaterialTheme.colorScheme.onSurfaceVariant
													)
												}
												Icon(
													imageVector = Icons.Outlined.ChevronForward,
													contentDescription = null,
													tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
													modifier = Modifier.size(16.dp)
												)
											}
											if (!isLast) {
												MeloHairlineDivider(startIndent = 70.dp)
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}

		// 多选批处理 Studio 悬浮坞
		AnimatedVisibility(
			visible = isMultiSelecting && subTab == LikedSubTab.SONGS && (allLikedSongIds.isNotEmpty() || likedItemCount > 0),
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.navigationBarsPadding()
				.padding(bottom = rememberBatchDockBottomClearance(), start = 16.dp, end = 16.dp)
		) {
			val selectedOrderedIds by remember(allLikedSongIds) {
				derivedStateOf {
					if (allLikedSongIds.isNotEmpty()) {
						allLikedSongIds.filter { selectedSongIds[it] == true }
					} else {
						selectedSongIds.entries.filter { it.value }.map { it.key }
					}
				}
			}
			val totalCount = if (allLikedSongIds.isNotEmpty()) allLikedSongIds.size else likedItemCount
			val hasRemovableOffline = remember(selectedOrderedIds, downloadsMap) {
				selectedOrderedIds.any { id ->
					val st = downloadsMap[id]?.status
					st == DownloadStatus.DOWNLOADED || st == DownloadStatus.DOWNLOADING
				}
			}
			StudioBatchSelectionDock(
				selectedCount = selectedOrderedIds.size,
				totalCount = totalCount,
				hasOfflineRemovable = hasRemovableOffline,
				onSelectAll = {
					when {
						allLikedSongIds.isNotEmpty() && selectedOrderedIds.size >= allLikedSongIds.size -> {
							selectedSongIds.clear()
						}
						allLikedSongIds.isNotEmpty() -> {
							selectedSongIds.putAll(allLikedSongIds.associateWith { true })
						}
						else -> {
							scope.launch {
								isLoadingLikedIds = true
								try {
									val ids = withContext(Dispatchers.IO) { songsVm.getSelectionOrder() }
									allLikedSongIds = ids
									if (ids.isNotEmpty()) {
										val allSelected = ids.all { selectedSongIds[it] == true }
										if (allSelected) {
											selectedSongIds.clear()
										} else {
											selectedSongIds.putAll(ids.associateWith { true })
										}
									}
								} finally {
									isLoadingLikedIds = false
								}
							}
						}
					}
				},
				onClear = { selectedSongIds.clear() },
				onExit = {
					isMultiSelecting = false
					selectedSongIds.clear()
				},
				onDownloadSelected = {
					val pickedIds = selectedOrderedIds
					if (pickedIds.isNotEmpty()) {
						scope.launch {
							withContext(Dispatchers.IO) {
								downloadManager.downloadCollection(pickedIds)
							}
							snackBarState.currentSnackbarData?.dismiss()
							snackBarState.showSnackbar("已将 ${pickedIds.size} 首歌曲加入手机离线队列（可在工坊 → 下载到手机查看）")
						}
						isMultiSelecting = false
						selectedSongIds.clear()
					}
				},
				onAddToPlaylist = {
					val ids = selectedOrderedIds
					if (ids.isNotEmpty()) {
						playlistDialogSongIds = ids
					}
				},
				onPlaySelected = {
					val pickedIds = selectedOrderedIds
					if (pickedIds.isNotEmpty()) {
						scope.launch {
							val picked = withContext(Dispatchers.IO) {
								songsVm.getSelectedSongs(pickedIds)
							}
							if (picked.isNotEmpty()) {
								player.playNow(picked, 0)
							}
						}
						isMultiSelecting = false
						selectedSongIds.clear()
					}
				},
				onDeleteOfflineSelected = {
					val pickedIds = selectedOrderedIds
					removeSongIdsOffline(downloadManager, downloadsMap, pickedIds)
					if (pickedIds.isNotEmpty()) {
						scope.launch {
							snackBarState.currentSnackbarData?.dismiss()
							snackBarState.showSnackbar("已移除所选曲目的手机离线缓存")
						}
					}
					isMultiSelecting = false
					selectedSongIds.clear()
				}
			)
		}

		activeMenuSong?.let { song ->
			NativeDetailSongActionSheet(
				song = song,
				downloadStatus = downloadsMap[song.id]?.status,
				extraActionLabel = "取消喜欢",
				onExtraAction = {
					activeMenuSong = null
					scope.launch {
						runCatching { songRepository.unstarSong(song.id) }
							.onSuccess {
								songsVm.refreshSongs(false)
								snackBarState.showSnackbar("已移出我喜欢：${song.title}")
							}
					}
				},
				onDismiss = { activeMenuSong = null },
				onPlayNext = {
					player.playNextSingle(song)
					activeMenuSong = null
					scope.launch { snackBarState.showSnackbar("下一首播放：${song.title}") }
				},
				onAddToQueue = {
					player.addToQueue(listOf(song))
					activeMenuSong = null
					scope.launch { snackBarState.showSnackbar("已添加到播放队列尾部") }
				},
				onAddToPlaylist = {
					playlistDialogSongIds = listOf(song.id)
					activeMenuSong = null
				},
				onDownload = {
					activeMenuSong = null
					scope.launch {
						queueSongsOffline(downloadManager, listOf(song))
						snackBarState.showSnackbar("已加入手机下载：${song.title}")
					}
				},
				onCancelDownload = {
					downloadManager.cancelDownload(song.id)
					activeMenuSong = null
					scope.launch { snackBarState.showSnackbar("已取消下载") }
				},
				onDeleteDownload = {
					downloadManager.deleteDownload(song.id)
					activeMenuSong = null
					scope.launch { snackBarState.showSnackbar("已从手机删除离线文件") }
				}
			)
		}

		playlistDialogSongIds?.let { ids ->
			PlaylistUpdateDialog(
				songIds = ids.toPersistentList(),
				onSuccess = {
					isMultiSelecting = false
					selectedSongIds.clear()
				},
				onDismissRequest = { playlistDialogSongIds = null }
			)
		}
	}
}

/* =========================================================================================
 * 4. MeloDailyMixScreen & MeloDailyMixSheet (「猜你喜欢」每日私人歌单 · AI 口味策展)
 * ========================================================================================= */

@Composable
fun MeloDailyMixScreen() {
	val backStack = LocalNavStack.current
	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
	) {
		MeloDailyMixContent(
			onBack = { backStack.removeLastOrNull() },
			nestedInSheet = false
		)
	}
}

@Composable
fun MeloDailyMixSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return

	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = sheetState,
		shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
		containerColor = MaterialTheme.colorScheme.surfaceContainer
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.navigationBarsPadding()
				.imePadding()
		) {
			MeloDailyMixContent(
				onBack = onDismissRequest,
				nestedInSheet = true
			)
		}
	}
}

@Composable
internal fun MeloDailyMixContent(
	onBack: () -> Unit,
	nestedInSheet: Boolean
) {
	val settingsStore = koinInject<MeloSettingsStore>()
	val serverConfig by settingsStore.config.collectAsStateWithLifecycle()

	if (serverConfig.isAuthenticated) {
		MeloOnlineDailyRecommendContent(
			isAuthenticated = serverConfig.isAuthenticated,
			onBack = onBack,
			nestedInSheet = nestedInSheet
		)
	} else {
		MeloOfflineLocalDailyMixContent(
			onBack = onBack,
			nestedInSheet = nestedInSheet
		)
	}
}

private data class DailyCalendarInfo(
	val dayOfMonth: String,
	val monthLabel: String,
	val weekdayLabel: String
)

private fun resolveDailyCalendarInfo(dateText: String): DailyCalendarInfo {
	val parsedDate = dateText.trim().takeIf { it.isNotEmpty() }?.let { raw ->
		runCatching {
			SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(raw)
		}.getOrNull()
	} ?: Date()
	val cal = Calendar.getInstance().apply { time = parsedDate }
	val day = String.format(Locale.US, "%02d", cal.get(Calendar.DAY_OF_MONTH))
	val month = "${cal.get(Calendar.MONTH) + 1}月"
	val weekday = when (cal.get(Calendar.DAY_OF_WEEK)) {
		Calendar.SUNDAY -> "周日"
		Calendar.MONDAY -> "周一"
		Calendar.TUESDAY -> "周二"
		Calendar.WEDNESDAY -> "周三"
		Calendar.THURSDAY -> "周四"
		Calendar.FRIDAY -> "周五"
		Calendar.SATURDAY -> "周六"
		else -> ""
	}
	return DailyCalendarInfo(dayOfMonth = day, monthLabel = month, weekdayLabel = weekday)
}

private fun isTrackEnqueueableToLibrary(
	item: MeloDailyRecommendItem,
	enqueuedTrackIds: Set<String>
): Boolean {
	val rawId = item.track.id.trim()
	val source = item.track.source.trim().lowercase()
	return !item.inLibrary &&
		rawId.isNotEmpty() &&
		rawId !in enqueuedTrackIds &&
		source !in setOf("local", "navidrome", "device")
}

private suspend fun buildSeedsFromSongDao(songDao: SongDao): List<MeloRecommendSeed> =
	withContext(Dispatchers.IO) {
		val starred = runCatching { songDao.getStarredSongs().firstOrNull().orEmpty() }.getOrDefault(emptyList())
		val top = runCatching { songDao.getTopSongs(24).firstOrNull().orEmpty() }.getOrDefault(emptyList())
		val seen = HashSet<String>()
		val seeds = mutableListOf<MeloRecommendSeed>()

		fun addEntity(entity: SongEntity, weight: Double) {
			val title = entity.title.trim()
			if (title.isEmpty()) return
			val artist = entity.artistName?.trim().orEmpty()
			val key = "${title.lowercase()}|${artist.lowercase()}"
			if (!seen.add(key)) return
			seeds += MeloRecommendSeed(
				title = title,
				artist = artist,
				album = entity.albumTitle?.trim().orEmpty(),
				genre = entity.genre?.trim().orEmpty().ifBlank {
					entity.genres.firstOrNull()?.trim().orEmpty()
				},
				weight = weight
			)
		}

		starred.take(16).forEach { addEntity(it, 1.8) }
		top.forEach { addEntity(it, 1.2) }
		seeds.take(30)
	}

@Composable
private fun MeloOnlineDailyRecommendContent(
	isAuthenticated: Boolean,
	onBack: () -> Unit,
	nestedInSheet: Boolean
) {
	val songDao = koinInject<SongDao>()
	val downloadManager = koinInject<DownloadManager>()
	val player = koinInject<MediaPlayer>()
	val dailyController = koinInject<MeloDailyRecommendController>()
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()

	val dailyState by dailyController.state.collectAsStateWithLifecycle()
	val allDownloads by downloadManager.allDownloads.collectAsStateWithLifecycle(emptyList())
	val downloadsMap = remember(allDownloads) { allDownloads.associateBy { it.songId } }
	val currentPlayingSongId by remember(player) {
		player.uiState
			.map { it.currentSong?.id }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(initialValue = player.uiState.value.currentSong?.id)

	var isMultiSelecting by rememberSaveable { mutableStateOf(false) }
	val selectedSongIds = remember { mutableStateMapOf<String, Boolean>() }
	var activeMenuEntry by remember { mutableStateOf<Pair<MeloDailyRecommendItem, DomainSong>?>(null) }
	var playlistDialogSongIds by remember { mutableStateOf<List<String>?>(null) }

	val mappedSongs = remember(dailyState.items) {
		dailyState.items.map { MeloTrackMapper.toDomainSong(it.track) }
	}
	val enqueueableTracks = remember(dailyState.items, dailyState.enqueuedTrackIds) {
		dailyState.items
			.filter { isTrackEnqueueableToLibrary(it, dailyState.enqueuedTrackIds) }
			.map { it.track }
	}

	MeloFeedbackObserver(
		errorMessage = dailyState.errorMessage,
		noticeMessage = dailyState.noticeMessage,
		onClearError = dailyController::clearError,
		onClearNotice = dailyController::clearNotice
	)

	LaunchedEffect(isAuthenticated) {
		if (isAuthenticated) {
			val cur = dailyController.state.value
			if (cur.items.isEmpty() && !cur.loading && !cur.streaming) {
				dailyController.loadOrGenerate(
					force = false,
					seedsProvider = { buildSeedsFromSongDao(songDao) }
				)
			}
		}
	}

	LaunchedEffect(mappedSongs) {
		val validIds = mappedSongs.mapTo(HashSet()) { it.id }
		val staleIds = selectedSongIds.keys.filter { it !in validIds }
		staleIds.forEach { selectedSongIds.remove(it) }
	}

	BackHandler(enabled = isMultiSelecting) {
		isMultiSelecting = false
		selectedSongIds.clear()
	}

	val triggerRefresh: () -> Unit = {
		isMultiSelecting = false
		selectedSongIds.clear()
		dailyController.loadOrGenerate(
			force = true,
			seedsProvider = { buildSeedsFromSongDao(songDao) }
		)
	}

	Box(
		modifier = if (nestedInSheet) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
	) {
		LazyColumn(
			modifier = if (nestedInSheet) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				top = 8.dp,
				bottom = (if (nestedInSheet) 28.dp else MeloBottomChromeClearance) +
					if (isMultiSelecting) 128.dp else 0.dp
			)
		) {
			item(key = "daily-mix-header", contentType = "header") {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.then(if (!nestedInSheet) Modifier.statusBarsPadding() else Modifier)
						.padding(horizontal = 16.dp, vertical = 6.dp),
					verticalArrangement = Arrangement.spacedBy(12.dp)
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween
					) {
						Surface(
							onClick = onBack,
							shape = CircleShape,
							color = if (nestedInSheet) {
								MaterialTheme.colorScheme.surfaceContainerHigh
							} else {
								MaterialTheme.colorScheme.surfaceContainer
							},
							border = libraryHairlineBorder(),
							modifier = Modifier.size(36.dp)
						) {
							Box(contentAlignment = Alignment.Center) {
								Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", modifier = Modifier.size(18.dp))
							}
						}

						Text(
							text = "每日推荐",
							style = MaterialTheme.typography.titleMedium.copy(
								fontFamily = defaultFont(grade = 100),
								fontWeight = FontWeight.Bold
							)
						)

						Row(
							horizontalArrangement = Arrangement.spacedBy(2.dp),
							verticalAlignment = Alignment.CenterVertically
						) {
							if (dailyState.streaming) {
								IconButton(onClick = dailyController::stopGeneration) {
									Icon(
										imageVector = MeloIcons.Stop,
										contentDescription = "停止生成",
										tint = MaterialTheme.colorScheme.error,
										modifier = Modifier.size(18.dp)
									)
								}
							}
							if (mappedSongs.isNotEmpty()) {
								IconButton(
									onClick = {
										isMultiSelecting = !isMultiSelecting
										if (!isMultiSelecting) selectedSongIds.clear()
									}
								) {
									Icon(
										imageVector = if (isMultiSelecting) Icons.Outlined.Close else Icons.Outlined.Checklist,
										contentDescription = if (isMultiSelecting) "退出多选" else "多选"
									)
								}
							}
							IconButton(
								onClick = triggerRefresh,
								enabled = !dailyState.loading
							) {
								Icon(Icons.Outlined.Refresh, contentDescription = "换一批")
							}
						}
					}

					MeloDailyCalendarHeroCard(
						state = dailyState,
						nestedInSheet = nestedInSheet
					)

					MeloDailyActionBar(
						hasSongs = mappedSongs.isNotEmpty(),
						isLoading = dailyState.loading,
						isMultiSelecting = isMultiSelecting,
						enqueueableCount = enqueueableTracks.size,
						nestedInSheet = nestedInSheet,
						onPlayAll = { if (mappedSongs.isNotEmpty()) player.playNow(mappedSongs, 0) },
						onRefresh = triggerRefresh,
						onBatchEnqueueToLibrary = {
							dailyController.enqueueDownloadToLibrary(enqueueableTracks)
						},
						onOfflineAll = {
							if (mappedSongs.isNotEmpty()) {
								scope.launch {
									queueSongsOffline(downloadManager, mappedSongs)
									snackBarState.currentSnackbarData?.dismiss()
									snackBarState.showSnackbar("已将今日 ${mappedSongs.size} 首全部加入手机离线缓存")
								}
							}
						},
						onToggleMultiSelect = {
							isMultiSelecting = !isMultiSelecting
							if (!isMultiSelecting) selectedSongIds.clear()
						}
					)
				}
			}

			if (dailyState.loading && dailyState.items.isEmpty()) {
				item(key = "loading-daily-songs", contentType = "loading") {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 48.dp),
						horizontalAlignment = Alignment.CenterHorizontally,
						verticalArrangement = Arrangement.spacedBy(12.dp)
					) {
						CircularProgressIndicator()
						Text(
							text = dailyState.stageMessage.ifBlank { "正在为你生成今日推荐歌单…" },
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}
			} else if (dailyState.items.isEmpty() && !dailyState.streaming) {
				item(key = "empty-daily-songs", contentType = "empty") {
					MeloQuietEmptyState(
						message = "暂无推荐曲目",
						icon = MeloIcons.Sparkles,
						actionLabel = "换一批试试",
						onAction = triggerRefresh
					)
				}
			} else {
				itemsIndexed(
					items = dailyState.items,
					key = { idx, item -> "daily-online-$idx-${item.track.id}" },
					contentType = { _, _ -> "track" }
				) { idx, item ->
					val song = mappedSongs[idx]
					val isSelected = selectedSongIds[song.id] == true
					val dlStatus = downloadsMap[song.id]?.status
					val isFirst = idx == 0
					val isLast = idx == dailyState.items.lastIndex
					val isPlaying = currentPlayingSongId == song.id
					val rowShape = libraryGroupedItemShape(isFirst, isLast)

					MeloDailyRecommendTrackRow(
						index = idx,
						item = item,
						song = song,
						downloadStatus = dlStatus,
						isPlaying = isPlaying,
						isMultiSelecting = isMultiSelecting,
						isSelected = isSelected,
						isEnqueued = item.track.id in dailyState.enqueuedTrackIds,
						canEnqueueToLibrary = isTrackEnqueueableToLibrary(item, dailyState.enqueuedTrackIds),
						isLast = isLast,
						rowShape = rowShape,
						nestedInSheet = nestedInSheet,
						onClick = {
							if (isMultiSelecting) {
								selectedSongIds[song.id] = !isSelected
							} else {
								player.playNow(mappedSongs, idx)
							}
						},
						onLongClick = {
							isMultiSelecting = true
							selectedSongIds[song.id] = true
						},
						onCheckedChange = { checked ->
							selectedSongIds[song.id] = checked
						},
						onEnqueueToLibrary = {
							dailyController.enqueueDownloadToLibrary(listOf(item.track))
						},
						onOpenMenu = {
							activeMenuEntry = item to song
						}
					)
				}
			}
		}

		AnimatedVisibility(
			visible = isMultiSelecting && mappedSongs.isNotEmpty(),
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.then(if (!nestedInSheet) Modifier.navigationBarsPadding() else Modifier)
				.padding(
					bottom = rememberBatchDockBottomClearance(nestedInSheet = nestedInSheet),
					start = 16.dp,
					end = 16.dp
				)
		) {
			val pickedEntries by remember(dailyState.items, mappedSongs) {
				derivedStateOf {
					dailyState.items.zip(mappedSongs).filter { (_, song) ->
						selectedSongIds[song.id] == true
					}
				}
			}
			val pickedSongs = remember(pickedEntries) { pickedEntries.map { it.second } }
			val pickedEnqueueableTracks = remember(pickedEntries, dailyState.enqueuedTrackIds) {
				pickedEntries
					.map { it.first }
					.filter { isTrackEnqueueableToLibrary(it, dailyState.enqueuedTrackIds) }
					.map { it.track }
			}
			val hasRemovableOffline = remember(pickedSongs, downloadsMap) {
				pickedSongs.any {
					val st = downloadsMap[it.id]?.status
					st == DownloadStatus.DOWNLOADED || st == DownloadStatus.DOWNLOADING
				}
			}
			StudioBatchSelectionDock(
				selectedCount = pickedSongs.size,
				totalCount = mappedSongs.size,
				hasOfflineRemovable = hasRemovableOffline,
				onSelectAll = {
					if (pickedSongs.size == mappedSongs.size) {
						selectedSongIds.clear()
					} else {
						mappedSongs.forEach { selectedSongIds[it.id] = true }
					}
				},
				onClear = { selectedSongIds.clear() },
				onExit = {
					isMultiSelecting = false
					selectedSongIds.clear()
				},
				onDownloadSelected = {
					if (pickedSongs.isNotEmpty()) {
						scope.launch {
							queueSongsOffline(downloadManager, pickedSongs)
							snackBarState.currentSnackbarData?.dismiss()
							snackBarState.showSnackbar("已将 ${pickedSongs.size} 首加入手机离线下载队列")
							isMultiSelecting = false
							selectedSongIds.clear()
						}
					}
				},
				onDownloadToLibrarySelected = {
					if (pickedEnqueueableTracks.isNotEmpty()) {
						dailyController.enqueueDownloadToLibrary(pickedEnqueueableTracks)
						isMultiSelecting = false
						selectedSongIds.clear()
					}
				},
				downloadToLibraryLabel = if (pickedEnqueueableTracks.isNotEmpty()) {
					"入库 (${pickedEnqueueableTracks.size})"
				} else {
					"下载入库"
				},
				downloadToLibraryEnabled = pickedEnqueueableTracks.isNotEmpty(),
				onPlayNextSelected = {
					if (pickedSongs.isNotEmpty()) {
						pickedSongs.asReversed().forEach { player.playNextSingle(it) }
						isMultiSelecting = false
						selectedSongIds.clear()
					}
				},
				onAddToPlaylist = {
					val ids = pickedSongs.map { it.id }.filter { it.isNotBlank() }
					if (ids.isNotEmpty()) playlistDialogSongIds = ids
				},
				onPlaySelected = {
					if (pickedSongs.isNotEmpty()) {
						player.playNow(pickedSongs, 0)
						isMultiSelecting = false
						selectedSongIds.clear()
					}
				},
				onDeleteOfflineSelected = {
					removeSongsOffline(downloadManager, downloadsMap, pickedSongs)
					scope.launch { snackBarState.showSnackbar("已移除所选曲目的手机离线缓存") }
					isMultiSelecting = false
					selectedSongIds.clear()
				}
			)
		}

		activeMenuEntry?.let { (item, song) ->
			val canEnqueue = isTrackEnqueueableToLibrary(item, dailyState.enqueuedTrackIds)
			NativeDetailSongActionSheet(
				song = song,
				downloadStatus = downloadsMap[song.id]?.status,
				extraActionLabel = if (canEnqueue) "下载到 MeloKeep 曲库" else null,
				extraActionIcon = Icons.Outlined.LibraryAdd,
				extraActionTint = MaterialTheme.colorScheme.primary,
				onExtraAction = if (canEnqueue) {
					{
						dailyController.enqueueDownloadToLibrary(listOf(item.track))
						activeMenuEntry = null
					}
				} else null,
				onDismiss = { activeMenuEntry = null },
				onPlayNext = {
					player.playNextSingle(song)
					activeMenuEntry = null
					scope.launch { snackBarState.showSnackbar("下一首播放：${song.title}") }
				},
				onAddToQueue = {
					player.addToQueue(listOf(song))
					activeMenuEntry = null
					scope.launch { snackBarState.showSnackbar("已添加到播放队列尾部") }
				},
				onAddToPlaylist = {
					playlistDialogSongIds = listOf(song.id)
					activeMenuEntry = null
				},
				onDownload = {
					activeMenuEntry = null
					scope.launch {
						queueSongsOffline(downloadManager, listOf(song))
						snackBarState.showSnackbar("已加入手机下载：${song.title}")
					}
				},
				onCancelDownload = {
					downloadManager.cancelDownload(song.id)
					activeMenuEntry = null
					scope.launch { snackBarState.showSnackbar("已取消下载") }
				},
				onDeleteDownload = {
					downloadManager.deleteDownload(song.id)
					activeMenuEntry = null
					scope.launch { snackBarState.showSnackbar("已从手机删除离线文件") }
				}
			)
		}

		playlistDialogSongIds?.let { ids ->
			PlaylistUpdateDialog(
				songIds = ids.toPersistentList(),
				onSuccess = {
					isMultiSelecting = false
					selectedSongIds.clear()
				},
				onDismissRequest = { playlistDialogSongIds = null }
			)
		}
	}
}

@Composable
private fun MeloDailyCalendarHeroCard(
	state: MeloDailyRecommendUiState,
	nestedInSheet: Boolean
) {
	val calInfo = remember(state.date) { resolveDailyCalendarInfo(state.date) }
	val targetCount = state.targetCount.takeIf { it > 0 } ?: 30
	val isAiMode = state.mode.equals("ai", ignoreCase = true)
	val modeLabel = when {
		isAiMode -> "AI 推荐"
		state.mode.isNotBlank() -> "规则推荐"
		else -> ""
	}
	val cardColor = if (nestedInSheet) {
		MaterialTheme.colorScheme.surfaceContainerLow
	} else {
		MaterialTheme.colorScheme.surfaceContainer
	}

	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(16.dp),
		color = cardColor,
		border = libraryHairlineBorder()
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(14.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(14.dp)
			) {
				// 网易云风格大日历徽章
				Surface(
					shape = RoundedCornerShape(14.dp),
					color = MaterialTheme.colorScheme.surfaceContainerHigh,
					border = libraryHairlineBorder(),
					modifier = Modifier.width(64.dp)
				) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.background(MaterialTheme.colorScheme.primary)
								.padding(vertical = 3.dp),
							contentAlignment = Alignment.Center
						) {
							Text(
								text = "${calInfo.monthLabel}·${calInfo.weekdayLabel}",
								style = MaterialTheme.typography.labelSmall.copy(
									fontSize = 10.sp,
									fontWeight = FontWeight.SemiBold
								),
								color = MaterialTheme.colorScheme.onPrimary,
								maxLines = 1
							)
						}
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.padding(vertical = 6.dp),
							contentAlignment = Alignment.Center
						) {
							Text(
								text = calInfo.dayOfMonth,
								style = MaterialTheme.typography.headlineMedium.tabularNums().copy(
									fontFamily = defaultFont(grade = 100),
									fontWeight = FontWeight.ExtraBold
								),
								color = MaterialTheme.colorScheme.onSurface
							)
						}
					}
				}

				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(5.dp)
				) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(8.dp)
					) {
						Text(
							text = "每日推荐",
							style = MaterialTheme.typography.titleLarge.copy(
								fontFamily = defaultFont(grade = 100),
								fontWeight = FontWeight.Bold
							),
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
						if (modeLabel.isNotBlank()) {
							Surface(
								shape = CircleShape,
								color = if (isAiMode) {
									MaterialTheme.colorScheme.meloGoldContainer
								} else {
									MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
								},
								border = BorderStroke(
									MeloHairlineWidth,
									if (isAiMode) {
										MaterialTheme.colorScheme.meloGoldBorder
									} else {
										MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
									}
								)
							) {
								Row(
									modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(4.dp)
								) {
									if (isAiMode) {
										Icon(
											imageVector = MeloIcons.Sparkles,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.meloGoldText,
											modifier = Modifier.size(12.dp)
										)
									}
									Text(
										text = modeLabel,
										style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
										color = if (isAiMode) {
											MaterialTheme.colorScheme.meloGoldText
										} else {
											MaterialTheme.colorScheme.primary
										},
										maxLines = 1
									)
								}
							}
						}
					}

					Text(
						text = buildString {
							append("猜你喜欢 · 全网 $targetCount 首")
							if (state.items.isNotEmpty() && state.items.size != targetCount) {
								append("（已加载 ${state.items.size} 首）")
							}
						},
						style = MaterialTheme.typography.labelMedium.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}
			}

			if (state.styleTags.isNotEmpty()) {
				FlowRow(
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalArrangement = Arrangement.spacedBy(6.dp)
				) {
					state.styleTags.take(6).forEach { rawTag ->
						val cleanTag = rawTag.trim().removePrefix("#")
						if (cleanTag.isNotEmpty()) {
							Surface(
								shape = CircleShape,
								color = MaterialTheme.colorScheme.surfaceContainerHigh,
								border = libraryHairlineBorder()
							) {
								Text(
									text = "#$cleanTag",
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
									maxLines = 1
								)
							}
						}
					}
				}
			}

			if (state.curatorNote.isNotBlank()) {
				Text(
					text = state.curatorNote,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
				)
			}

			if (state.streaming) {
				val progressText = state.stageMessage.ifBlank {
					"正在全网匹配曲目 ${state.matchedCount}/$targetCount…"
				}
				Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween
					) {
						Text(
							text = progressText,
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.primary,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f)
						)
						Spacer(Modifier.width(8.dp))
						Text(
							text = "${state.matchedCount}/$targetCount",
							style = MaterialTheme.typography.labelSmall.tabularNums(),
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
					if (targetCount > 0 && state.matchedCount > 0) {
						LinearProgressIndicator(
							progress = { (state.matchedCount.toFloat() / targetCount.toFloat()).coerceIn(0f, 1f) },
							modifier = Modifier
								.fillMaxWidth()
								.height(3.dp)
								.clip(CircleShape),
							color = MaterialTheme.colorScheme.primary,
							trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
						)
					} else {
						LinearProgressIndicator(
							modifier = Modifier
								.fillMaxWidth()
								.height(3.dp)
								.clip(CircleShape),
							color = MaterialTheme.colorScheme.primary,
							trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
						)
					}
				}
			}
		}
	}
}

@Composable
private fun MeloDailyActionBar(
	hasSongs: Boolean,
	isLoading: Boolean,
	isMultiSelecting: Boolean,
	enqueueableCount: Int,
	nestedInSheet: Boolean,
	onPlayAll: () -> Unit,
	onRefresh: () -> Unit,
	onBatchEnqueueToLibrary: () -> Unit,
	onOfflineAll: () -> Unit,
	onToggleMultiSelect: () -> Unit
) {
	val pillSurfaceColor = if (nestedInSheet) {
		MaterialTheme.colorScheme.surfaceContainerLow
	} else {
		MaterialTheme.colorScheme.surfaceContainer
	}

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState()),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Button(
			onClick = onPlayAll,
			enabled = hasSongs,
			modifier = Modifier.height(38.dp),
			shape = CircleShape,
			contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
		) {
			Icon(MeloIcons.PlayArrow, null, modifier = Modifier.size(16.dp))
			Spacer(Modifier.width(4.dp))
			Text("播放全部", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
		}

		FilledTonalButton(
			onClick = onRefresh,
			enabled = !isLoading,
			modifier = Modifier.height(38.dp),
			shape = CircleShape,
			contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
		) {
			Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(15.dp))
			Spacer(Modifier.width(4.dp))
			Text("换一批", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
		}

		Surface(
			onClick = onBatchEnqueueToLibrary,
			enabled = enqueueableCount > 0,
			shape = CircleShape,
			color = pillSurfaceColor,
			border = libraryHairlineBorder(),
			modifier = Modifier.height(38.dp)
		) {
			Row(
				modifier = Modifier.padding(horizontal = 12.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp)
			) {
				Icon(
					imageVector = if (enqueueableCount > 0) Icons.Outlined.LibraryAdd else Icons.Outlined.Check,
					contentDescription = null,
					modifier = Modifier.size(15.dp),
					tint = when {
						enqueueableCount > 0 -> MaterialTheme.colorScheme.primary
						hasSongs -> MaterialTheme.colorScheme.positive
						else -> MaterialTheme.colorScheme.outline
					}
				)
				Text(
					text = if (enqueueableCount > 0) "批量入库 ($enqueueableCount)" else "已全入库",
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = if (enqueueableCount > 0) {
						MaterialTheme.colorScheme.onSurface
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					maxLines = 1,
					softWrap = false
				)
			}
		}

		Surface(
			onClick = onOfflineAll,
			enabled = hasSongs,
			shape = CircleShape,
			color = pillSurfaceColor,
			border = libraryHairlineBorder(),
			modifier = Modifier.height(38.dp)
		) {
			Row(
				modifier = Modifier.padding(horizontal = 12.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp)
			) {
				Icon(
					Icons.Outlined.Download,
					null,
					modifier = Modifier.size(15.dp),
					tint = if (hasSongs) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
				)
				Text(
					text = "整单离线",
					style = MaterialTheme.typography.labelMedium,
					color = if (hasSongs) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
					maxLines = 1,
					softWrap = false
				)
			}
		}

		Surface(
			onClick = onToggleMultiSelect,
			enabled = hasSongs || isMultiSelecting,
			shape = CircleShape,
			color = if (isMultiSelecting) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
			} else {
				pillSurfaceColor
			},
			border = BorderStroke(
				MeloHairlineWidth,
				if (isMultiSelecting) {
					MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
				} else {
					libraryHairlineBorderColor()
				}
			),
			modifier = Modifier.height(38.dp)
		) {
			Row(
				modifier = Modifier.padding(horizontal = 12.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(4.dp)
			) {
				Icon(
					imageVector = if (isMultiSelecting) Icons.Outlined.Close else Icons.Outlined.Checklist,
					contentDescription = null,
					modifier = Modifier.size(15.dp),
					tint = if (isMultiSelecting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
				)
				Text(
					text = if (isMultiSelecting) "退出多选" else "多选",
					style = MaterialTheme.typography.labelMedium,
					color = if (isMultiSelecting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					softWrap = false
				)
			}
		}
	}
}

@Composable
private fun MeloDailyRecommendTrackRow(
	index: Int,
	item: MeloDailyRecommendItem,
	song: DomainSong,
	downloadStatus: DownloadStatus?,
	isPlaying: Boolean,
	isMultiSelecting: Boolean,
	isSelected: Boolean,
	isEnqueued: Boolean,
	canEnqueueToLibrary: Boolean,
	isLast: Boolean,
	rowShape: Shape,
	nestedInSheet: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onCheckedChange: (Boolean) -> Unit,
	onEnqueueToLibrary: () -> Unit,
	onOpenMenu: () -> Unit
) {
	val track = item.track
	val indexLabel = remember(index) { String.format(Locale.US, "%02d", index + 1) }
	val qualityBadge = remember(track.id, track.qualities, track.playback) {
		extractTrackQualityBadge(track)?.first
	}
	val sourceLabel = remember(track.source) {
		track.source.trim().takeIf { it.isNotEmpty() }?.let(::translateMeloSourceName)
	}
	val subText = remember(track.id, song.artistName, sourceLabel, qualityBadge) {
		listOfNotNull(
			song.artistName?.takeIf { it.isNotBlank() },
			sourceLabel,
			qualityBadge
		).joinToString(" · ")
	}
	val isDownloaded = downloadStatus == DownloadStatus.DOWNLOADED
	val inLibraryOrEnqueued = item.inLibrary || isEnqueued
	val baseContainerColor = if (nestedInSheet) {
		MaterialTheme.colorScheme.surfaceContainerLow
	} else {
		MaterialTheme.colorScheme.surfaceContainer
	}
	val rowBgColor = when {
		isMultiSelecting && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
		isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
		else -> baseContainerColor
	}

	MeloSwipeableSongRow(
		song = song,
		selectionMode = isMultiSelecting,
		shape = rowShape,
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp)
	) {
		Surface(
			modifier = Modifier
				.fillMaxWidth()
				.combinedClickable(
					onClick = onClick,
					onLongClick = onLongClick
				),
			shape = rowShape,
			color = rowBgColor
		) {
			Column {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 12.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(10.dp)
				) {
					if (isMultiSelecting) {
						Checkbox(
							checked = isSelected,
							onCheckedChange = onCheckedChange
						)
					} else {
						Text(
							text = indexLabel,
							style = MaterialTheme.typography.labelMedium.tabularNums().copy(
								fontWeight = if (isPlaying || index < 3) FontWeight.Bold else FontWeight.Medium
							),
							color = if (isPlaying) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							},
							modifier = Modifier.width(24.dp),
							textAlign = TextAlign.Center
						)
					}

					CoverArt(
						coverArtId = song.coverArtId,
						contentDescription = song.title,
						modifier = Modifier
							.size(46.dp)
							.clip(RoundedCornerShape(9.dp))
					)

					Column(
						modifier = Modifier.weight(1f),
						verticalArrangement = Arrangement.spacedBy(3.dp)
					) {
						Text(
							text = song.title.ifBlank { "未知曲目" },
							style = MaterialTheme.typography.titleSmall.copy(
								fontWeight = FontWeight.Medium
							),
							color = if (isPlaying) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurface
							},
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)

						if (subText.isNotBlank() || isDownloaded) {
							Row(
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(4.dp)
							) {
								if (isDownloaded) {
									MeloDownloadedTinyBadge()
								}
								if (subText.isNotBlank()) {
									Text(
										text = subText,
										style = MaterialTheme.typography.bodySmall,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis
									)
								}
							}
						}

						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(5.dp)
						) {
							Surface(
								shape = RoundedCornerShape(5.dp),
								color = if (inLibraryOrEnqueued) {
									MaterialTheme.colorScheme.positive.copy(alpha = 0.14f)
								} else {
									MaterialTheme.colorScheme.surfaceContainerHigh
								},
								border = BorderStroke(
									MeloHairlineWidth,
									if (inLibraryOrEnqueued) {
										MaterialTheme.colorScheme.positive.copy(alpha = 0.35f)
									} else {
										MaterialTheme.colorScheme.meloSubtleBorder
									}
								)
							) {
								Text(
									text = when {
										item.inLibrary -> "已在曲库"
										isEnqueued -> "已提交入库"
										else -> "全网发现"
									},
									style = MaterialTheme.typography.labelSmall.copy(
										fontSize = 10.sp,
										fontWeight = FontWeight.Medium
									),
									color = if (inLibraryOrEnqueued) {
										MaterialTheme.colorScheme.positive
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant
									},
									maxLines = 1,
									modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
								)
							}

							if (item.reason.isNotBlank()) {
								Surface(
									shape = RoundedCornerShape(5.dp),
									color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
								) {
									Text(
										text = item.reason,
										style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
										color = MaterialTheme.colorScheme.onPrimaryContainer,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
										modifier = Modifier
											.widthIn(max = 170.dp)
											.padding(horizontal = 6.dp, vertical = 1.5.dp)
									)
								}
							}
						}
					}

					if (!isMultiSelecting) {
						if (inLibraryOrEnqueued) {
							Box(
								modifier = Modifier.size(32.dp),
								contentAlignment = Alignment.Center
							) {
								Icon(
									imageVector = Icons.Outlined.Check,
									contentDescription = if (item.inLibrary) "已在曲库" else "已提交入库",
									tint = MaterialTheme.colorScheme.positive,
									modifier = Modifier.size(16.dp)
								)
							}
						} else if (canEnqueueToLibrary) {
							IconButton(
								onClick = onEnqueueToLibrary,
								modifier = Modifier.size(32.dp)
							) {
								Icon(
									imageVector = Icons.Outlined.LibraryAdd,
									contentDescription = "下载到曲库",
									tint = MaterialTheme.colorScheme.primary,
									modifier = Modifier.size(18.dp)
								)
							}
						}

						IconButton(
							onClick = onOpenMenu,
							modifier = Modifier.size(32.dp)
						) {
							Icon(
								imageVector = Icons.Outlined.MoreVert,
								contentDescription = "更多",
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.size(18.dp)
							)
						}
					}
				}

				if (!isLast) {
					MeloHairlineDivider(startIndent = if (isMultiSelecting) 108.dp else 98.dp)
				}
			}
		}
	}
}

@Composable
private fun MeloOfflineLocalDailyMixContent(
	onBack: () -> Unit,
	nestedInSheet: Boolean
) {
	val songDao = koinInject<SongDao>()
	val downloadManager = koinInject<DownloadManager>()
	val player = koinInject<MediaPlayer>()
	val preferenceManager = koinInject<PreferenceManager>()
	val settingsStore = koinInject<MeloSettingsStore>()
	val apiClient = koinInject<MeloKeepApiClient>()
	val snackBarState = LocalSnackBarState.current
	val scope = rememberCoroutineScope()

	var dailySongs by remember { mutableStateOf<List<DomainSong>>(emptyList()) }
	var curatorNote by remember { mutableStateOf("") }
	var isLoading by remember { mutableStateOf(true) }
	var isAiCurating by remember { mutableStateOf(false) }

	var isMultiSelecting by rememberSaveable { mutableStateOf(false) }
	val selectedSongIds = remember { mutableStateMapOf<String, Boolean>() }
	var activeMenuSong by remember { mutableStateOf<DomainSong?>(null) }
	var playlistDialogSongIds by remember { mutableStateOf<List<String>?>(null) }

	val allDownloads by downloadManager.allDownloads.collectAsStateWithLifecycle(emptyList())
	val downloadsMap = remember(allDownloads) { allDownloads.associateBy { it.songId } }

	fun loadDailyMix(forceRegenerate: Boolean = false) {
		scope.launch {
			isLoading = true
			val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
			val cachedDate = preferenceManager.dailyMixDateKey
			val cachedIds = preferenceManager.dailyMixSongIds
			val cachedNote = preferenceManager.dailyMixCuratorNote

			if (!forceRegenerate && cachedDate == todayKey && cachedIds.isNotEmpty()) {
				val entities = withContext(Dispatchers.IO) { songDao.getSongsByIds(cachedIds) }
				if (entities.isNotEmpty()) {
					dailySongs = entities.map { it.toDomainModel() }
					curatorNote = cachedNote.takeUnless {
						it.isBlank() ||
							"今日专属" in it ||
							"今日私享" in it ||
							"温情守候" in it ||
							it.startsWith("基于你的")
					}.orEmpty()
					isLoading = false
					return@launch
				}
			}

			// 零延迟多源算法混排生成
			withContext(Dispatchers.IO) {
				val starred = songDao.getStarredSongs().firstOrNull().orEmpty()
				val topSongs = songDao.getTopSongs(40).firstOrNull().orEmpty()
				val randomSongs = songDao.getRandomSongsList(30)

				val pool = mutableListOf<SongEntity>()
				pool.addAll(starred.shuffled().take(8))
				pool.addAll(topSongs.shuffled().take(8))
				pool.addAll(randomSongs)

				val chosenEntities = pool.distinctBy { it.songId }.shuffled().take(25)
				val chosenSongs = chosenEntities.map { it.toDomainModel() }
				val songIds = chosenSongs.map { it.id }

				preferenceManager.dailyMixDateKey = todayKey
				preferenceManager.dailyMixSongIds = songIds
				preferenceManager.dailyMixCuratorNote = ""

				withContext(Dispatchers.Main) {
					dailySongs = chosenSongs
					curatorNote = ""
					isLoading = false
				}
			}
		}
	}

	fun triggerAiCurate() {
		val isAuth = settingsStore.currentConfig().isAuthenticated
		if (!isAuth) {
			scope.launch { snackBarState.showSnackbar("请先连接 MeloKeep 服务") }
			return
		}
		scope.launch {
			isAiCurating = true
			try {
				val sampleTracks = dailySongs.take(8).joinToString("; ") { "${it.title} - ${it.artistName}" }
				val prompt = "请分析以下听歌曲目，生成一句简短凝练的音乐导语（不超过28字）及3个风格标签：\n$sampleTracks"
				val reply = withContext(Dispatchers.IO) {
					val created = apiClient.createAgentSession()
					var currentSession = apiClient.sendAgentMessage(sessionId = created.id, message = prompt)
					val startTime = System.currentTimeMillis()
					while (currentSession.status in setOf("running", "waiting_tasks", "stopping") &&
						System.currentTimeMillis() - startTime < 20_000L
					) {
						delay(1000)
						currentSession = apiClient.getAgentSession(created.id)
					}
					currentSession.messages
						.lastOrNull { it.role.lowercase() != "user" && it.content.isNotBlank() }
						?.content
				}
				if (!reply.isNullOrBlank()) {
					val clean = reply.lines().filter { it.isNotBlank() }.joinToString(" ").trim()
					curatorNote = clean
					preferenceManager.dailyMixCuratorNote = clean
					snackBarState.showSnackbar("AI 策展已完成")
				}
			} catch (_: Exception) {
				snackBarState.showSnackbar("AI 策展暂不可用")
			} finally {
				isAiCurating = false
			}
		}
	}

	LaunchedEffect(Unit) {
		loadDailyMix(forceRegenerate = false)
	}

	Box(
		modifier = if (nestedInSheet) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
	) {
		LazyColumn(
			modifier = if (nestedInSheet) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				top = 8.dp,
				bottom = (if (nestedInSheet) 28.dp else MeloBottomChromeClearance) +
					if (isMultiSelecting) 120.dp else 0.dp
			)
		) {
			item(key = "daily-mix-header", contentType = "header") {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.then(if (!nestedInSheet) Modifier.statusBarsPadding() else Modifier)
						.padding(horizontal = 16.dp, vertical = 6.dp),
					verticalArrangement = Arrangement.spacedBy(14.dp)
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween
					) {
						Surface(
							onClick = onBack,
							shape = CircleShape,
							color = MaterialTheme.colorScheme.surfaceContainer,
							border = libraryHairlineBorder(),
							modifier = Modifier.size(36.dp)
						) {
							Box(contentAlignment = Alignment.Center) {
								Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", modifier = Modifier.size(18.dp))
							}
						}

						Text(
							text = "猜你喜欢",
							style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
						)

						Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
							if (dailySongs.isNotEmpty()) {
								IconButton(
									onClick = {
										isMultiSelecting = !isMultiSelecting
										if (!isMultiSelecting) selectedSongIds.clear()
									}
								) {
									Icon(
										imageVector = if (isMultiSelecting) Icons.Outlined.Close else Icons.Outlined.Checklist,
										contentDescription = "多选"
									)
								}
							}
							IconButton(onClick = { loadDailyMix(forceRegenerate = true) }) {
								Icon(Icons.Outlined.Refresh, contentDescription = "换一批")
							}
						}
					}

					// 简洁摘要与 AI 策展入口
					MeloGroupedCard {
						Column(
							modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
							verticalArrangement = Arrangement.spacedBy(8.dp)
						) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.SpaceBetween
							) {
								Text(
									text = "共 ${dailySongs.size} 首",
									style = MaterialTheme.typography.labelLarge.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)

								FilledTonalButton(
									onClick = ::triggerAiCurate,
									enabled = !isAiCurating,
									shape = CircleShape,
									contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
									modifier = Modifier.height(30.dp)
								) {
									if (isAiCurating) {
										CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.6.dp)
										Spacer(Modifier.width(4.dp))
										Text("策展中…", fontSize = 11.sp)
									} else {
										Icon(MeloIcons.Sparkles, null, modifier = Modifier.size(13.dp))
										Spacer(Modifier.width(4.dp))
										Text("AI 策展", fontSize = 11.sp)
									}
								}
							}

							if (curatorNote.isNotBlank()) {
								Text(
									text = curatorNote,
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurface
								)
							}
						}
					}

					// 快捷动作条：播放全部 + 换一批 + 整单离线
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalAlignment = Alignment.CenterVertically
					) {
						Button(
							onClick = { if (dailySongs.isNotEmpty()) player.playNow(dailySongs, 0) },
							modifier = Modifier
								.weight(1.1f)
								.height(42.dp),
							shape = CircleShape,
							contentPadding = PaddingValues(horizontal = 10.dp)
						) {
							Icon(MeloIcons.PlayArrow, null, modifier = Modifier.size(16.dp))
							Spacer(Modifier.width(4.dp))
							Text("播放全部", maxLines = 1, softWrap = false)
						}

						FilledTonalButton(
							onClick = { loadDailyMix(forceRegenerate = true) },
							modifier = Modifier
								.weight(0.9f)
								.height(42.dp),
							shape = CircleShape,
							contentPadding = PaddingValues(horizontal = 10.dp)
						) {
							Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(15.dp))
							Spacer(Modifier.width(4.dp))
							Text("换一批", maxLines = 1, softWrap = false)
						}

						Surface(
							onClick = {
								if (dailySongs.isNotEmpty()) {
									scope.launch {
										queueSongsOffline(downloadManager, dailySongs)
										snackBarState.showSnackbar("已将今日 ${dailySongs.size} 首全部加入手机离线缓存")
									}
								}
							},
							shape = CircleShape,
							color = MaterialTheme.colorScheme.surfaceContainer,
							border = libraryHairlineBorder(),
							modifier = Modifier.height(42.dp)
						) {
							Row(
								modifier = Modifier.padding(horizontal = 12.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(4.dp)
							) {
								Icon(Icons.Outlined.Download, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
								Text("整单离线", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
							}
						}
					}
				}
			}

			if (isLoading) {
				item(key = "loading-daily-songs", contentType = "loading") {
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 48.dp),
						contentAlignment = Alignment.Center
					) {
						CircularProgressIndicator()
					}
				}
			} else if (dailySongs.isEmpty()) {
				item(key = "empty-daily-songs", contentType = "empty") {
					MeloQuietEmptyState(
						message = "暂无推荐曲目",
						icon = MeloIcons.Sparkles
					)
				}
			} else {
				itemsIndexed(
					items = dailySongs,
					key = { _, s -> "daily-${s.id}" },
					contentType = { _, _ -> "track" }
				) { idx, song ->
					val isSelected = selectedSongIds[song.id] == true
					val dlStatus = downloadsMap[song.id]?.status
					val isFirst = idx == 0
					val isLast = idx == dailySongs.lastIndex
					val qualityTag = remember(song.id, song.bitDepth, song.fileExtension, song.bitRate) {
						libraryFormatQuietQuality(song.bitDepth, song.fileExtension, song.bitRate)
					}
					val dailySubText = remember(song.id, song.artistName, song.albumTitle, qualityTag) {
						listOfNotNull(
							song.artistName?.takeIf { it.isNotBlank() },
							song.albumTitle?.takeIf { it.isNotBlank() },
							qualityTag
						).joinToString(" · ")
					}
					val rowShape = libraryGroupedItemShape(isFirst, isLast)
					val isDownloaded = dlStatus == DownloadStatus.DOWNLOADED
					MeloSwipeableSongRow(
						song = song,
						selectionMode = isMultiSelecting,
						shape = rowShape,
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 16.dp)
					) {
						Surface(
							modifier = Modifier
								.fillMaxWidth()
								.combinedClickable(
									onClick = {
										if (isMultiSelecting) {
											selectedSongIds[song.id] = !isSelected
										} else {
											player.playNow(dailySongs, idx)
										}
									},
									onLongClick = {
										isMultiSelecting = true
										selectedSongIds[song.id] = true
									}
								),
							shape = rowShape,
							color = if (isMultiSelecting && isSelected) {
								MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
							} else {
								MaterialTheme.colorScheme.surfaceContainer
							}
						) {
							Column {
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.padding(horizontal = 12.dp, vertical = 10.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(10.dp)
								) {
									if (isMultiSelecting) {
										Checkbox(
											checked = isSelected,
											onCheckedChange = { selectedSongIds[song.id] = it }
										)
									} else {
										Text(
											text = "${idx + 1}",
											style = MaterialTheme.typography.labelMedium.tabularNums(),
											color = MaterialTheme.colorScheme.onSurfaceVariant,
											modifier = Modifier.width(22.dp),
											textAlign = TextAlign.Center
										)
									}

									CoverArt(
										coverArtId = song.coverArtId,
										contentDescription = song.title,
										modifier = Modifier
											.size(44.dp)
											.clip(RoundedCornerShape(9.dp))
									)

									Column(
										modifier = Modifier.weight(1f),
										verticalArrangement = Arrangement.spacedBy(2.dp)
									) {
										Text(
											text = song.title.ifBlank { "未知歌曲" },
											style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
											maxLines = 1,
											overflow = TextOverflow.Ellipsis
										)
										if (dailySubText.isNotBlank() || isDownloaded) {
											Row(
												verticalAlignment = Alignment.CenterVertically,
												horizontalArrangement = Arrangement.spacedBy(4.dp)
											) {
												if (isDownloaded) {
													MeloDownloadedTinyBadge()
												}
												if (dailySubText.isNotBlank()) {
													Text(
														text = dailySubText,
														style = MaterialTheme.typography.bodySmall,
														color = MaterialTheme.colorScheme.onSurfaceVariant,
														maxLines = 1,
														overflow = TextOverflow.Ellipsis
													)
												}
											}
										}
									}

									IconButton(
										onClick = { activeMenuSong = song },
										modifier = Modifier.size(32.dp)
									) {
										Icon(
											imageVector = Icons.Outlined.MoreVert,
											contentDescription = "更多",
											tint = MaterialTheme.colorScheme.onSurfaceVariant,
											modifier = Modifier.size(18.dp)
										)
									}
								}
								if (!isLast) {
									MeloHairlineDivider(startIndent = if (isMultiSelecting) 108.dp else 94.dp)
								}
							}
						}
					}
				}
			}
		}

		// 多选批控悬浮条
		AnimatedVisibility(
			visible = isMultiSelecting && dailySongs.isNotEmpty(),
			enter = slideInVertically { it } + fadeIn(),
			exit = slideOutVertically { it } + fadeOut(),
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.then(if (!nestedInSheet) Modifier.navigationBarsPadding() else Modifier)
				.padding(
					bottom = rememberBatchDockBottomClearance(nestedInSheet = nestedInSheet),
					start = 16.dp,
					end = 16.dp
				)
		) {
			val pickedSongs by remember(dailySongs) {
				derivedStateOf { dailySongs.filter { selectedSongIds[it.id] == true } }
			}
			val hasRemovableOffline = remember(pickedSongs, downloadsMap) {
				pickedSongs.any {
					val st = downloadsMap[it.id]?.status
					st == DownloadStatus.DOWNLOADED || st == DownloadStatus.DOWNLOADING
				}
			}
			val hasNonMelo = remember(pickedSongs) {
				pickedSongs.any { !MeloTrackMapper.isMeloSongId(it.id) }
			}
			StudioBatchSelectionDock(
				selectedCount = pickedSongs.size,
				totalCount = dailySongs.size,
				hasOfflineRemovable = hasRemovableOffline,
				onSelectAll = {
					if (pickedSongs.size == dailySongs.size) {
						selectedSongIds.clear()
					} else {
						dailySongs.forEach { selectedSongIds[it.id] = true }
					}
				},
				onClear = { selectedSongIds.clear() },
				onExit = {
					isMultiSelecting = false
					selectedSongIds.clear()
				},
				onDownloadSelected = {
					if (pickedSongs.isNotEmpty()) {
						scope.launch {
							queueSongsOffline(downloadManager, pickedSongs)
							snackBarState.showSnackbar("已将 ${pickedSongs.size} 首加入手机离线下载队列")
							isMultiSelecting = false
							selectedSongIds.clear()
						}
					}
				},
				onAddToPlaylist = if (hasNonMelo) {
					{
						val navIds = pickedSongs.filter { !MeloTrackMapper.isMeloSongId(it.id) }.map { it.id }
						if (navIds.isNotEmpty()) playlistDialogSongIds = navIds
					}
				} else null,
				onPlaySelected = {
					if (pickedSongs.isNotEmpty()) {
						player.playNow(pickedSongs, 0)
						isMultiSelecting = false
						selectedSongIds.clear()
					}
				},
				onDeleteOfflineSelected = {
					removeSongsOffline(downloadManager, downloadsMap, pickedSongs)
					scope.launch { snackBarState.showSnackbar("已移除所选曲目的手机离线缓存") }
					isMultiSelecting = false
					selectedSongIds.clear()
				}
			)
		}

		activeMenuSong?.let { song ->
			val allowNavPlaylist = !MeloTrackMapper.isMeloSongId(song.id)
			NativeDetailSongActionSheet(
				song = song,
				downloadStatus = downloadsMap[song.id]?.status,
				onDismiss = { activeMenuSong = null },
				onPlayNext = {
					player.playNextSingle(song)
					activeMenuSong = null
					scope.launch { snackBarState.showSnackbar("下一首播放：${song.title}") }
				},
				onAddToQueue = {
					player.addToQueue(listOf(song))
					activeMenuSong = null
					scope.launch { snackBarState.showSnackbar("已添加到播放队列尾部") }
				},
				onAddToPlaylist = if (allowNavPlaylist) {
					{
						playlistDialogSongIds = listOf(song.id)
						activeMenuSong = null
					}
				} else null,
				onDownload = {
					activeMenuSong = null
					scope.launch {
						queueSongsOffline(downloadManager, listOf(song))
						snackBarState.showSnackbar("已加入手机下载：${song.title}")
					}
				},
				onCancelDownload = {
					downloadManager.cancelDownload(song.id)
					activeMenuSong = null
					scope.launch { snackBarState.showSnackbar("已取消下载") }
				},
				onDeleteDownload = {
					downloadManager.deleteDownload(song.id)
					activeMenuSong = null
					scope.launch { snackBarState.showSnackbar("已从手机删除离线文件") }
				}
			)
		}

		playlistDialogSongIds?.let { ids ->
			PlaylistUpdateDialog(
				songIds = ids.toPersistentList(),
				onSuccess = {
					isMultiSelecting = false
					selectedSongIds.clear()
				},
				onDismissRequest = { playlistDialogSongIds = null }
			)
		}
	}
}

/* =========================================================================================
 * 5. 通用离线状态指示图标、Studio 批控悬浮条 & 单曲菜单组件
 * ========================================================================================= */

internal suspend fun queueSongsOffline(
	downloadManager: DownloadManager,
	songs: List<DomainSong>
) {
	if (songs.isEmpty()) return
	val external = songs.filter { it.isExternal || MeloTrackMapper.isMeloSongId(it.id) }
	val internalIds = songs.filterNot { it.isExternal || MeloTrackMapper.isMeloSongId(it.id) }.map { it.id }
	if (external.isNotEmpty()) {
		downloadManager.downloadExternalSongs(external)
	}
	if (internalIds.isNotEmpty()) {
		downloadManager.downloadCollection(internalIds)
	}
}

internal fun removeSongsOffline(
	downloadManager: DownloadManager,
	downloadsMap: Map<String, DownloadEntity>,
	songs: List<DomainSong>
) {
	removeSongIdsOffline(downloadManager, downloadsMap, songs.map { it.id })
}

internal fun removeSongIdsOffline(
	downloadManager: DownloadManager,
	downloadsMap: Map<String, DownloadEntity>,
	songIds: List<String>
) {
	val toCancel = songIds.filter { downloadsMap[it]?.status == DownloadStatus.DOWNLOADING }
	val toDelete = songIds.filter { downloadsMap[it]?.status == DownloadStatus.DOWNLOADED }
	if (toCancel.isNotEmpty()) downloadManager.cancelCollectionDownload(toCancel)
	if (toDelete.isNotEmpty()) downloadManager.deleteDownloadedCollection(toDelete)
}

@Composable
internal fun rememberBatchDockBottomClearance(
	nestedInSheet: Boolean = false
): Dp {
	if (nestedInSheet) return 14.dp
	val backStack = LocalNavStack.current
	val player = koinInject<MediaPlayer>()
	val preferenceManager = koinInject<PreferenceManager>()
	val sizeClass = LocalSizeClass.current
	val isPlaybackActive by remember(player) {
		player.uiState
			.map { it.currentSong != null && !it.isPaused }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(
		initialValue = player.uiState.value.let { it.currentSong != null && !it.isPaused }
	)

	val topEntry = backStack.lastOrNull()
	val isAtRootShell = topEntry is Screen.Library
	val isCompact = sizeClass.widthSizeClass < WindowWidthSizeClass.Medium
	val isFullscreenNoPlayer = topEntry is Screen.DailyMix || topEntry is Screen.PhoneDownloads
	val allowPlayerOnScreen = isAtRootShell ||
		preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
	val hasPlayerDeck = !isFullscreenNoPlayer &&
		allowPlayerOnScreen &&
		(isPlaybackActive || !preferenceManager.hideIfIdle)
	val hasNavDock = isAtRootShell && isCompact

	return when {
		hasNavDock && hasPlayerDeck -> MeloBottomChromeClearance + 8.dp
		hasNavDock -> 88.dp
		hasPlayerDeck -> 92.dp
		else -> 20.dp
	}
}

@Composable
internal fun StudioBatchSelectionDock(
	selectedCount: Int,
	totalCount: Int,
	hasOfflineRemovable: Boolean,
	onSelectAll: () -> Unit,
	onClear: () -> Unit,
	onExit: () -> Unit,
	onDownloadSelected: () -> Unit,
	onAddToPlaylist: (() -> Unit)? = null,
	onPlaySelected: (() -> Unit)? = null,
	onDeleteOfflineSelected: (() -> Unit)? = null,
	playlistButtonLabel: String = "加入歌单",
	modifier: Modifier = Modifier,
	onRemoveFromPlaylistSelected: (() -> Unit)? = null,
	onPlayNextSelected: (() -> Unit)? = null,
	onDownloadToLibrarySelected: (() -> Unit)? = null,
	downloadToLibraryLabel: String = "下载入库",
	downloadToLibraryEnabled: Boolean = selectedCount > 0
) {
	Surface(
		shape = RoundedCornerShape(20.dp),
		color = MaterialTheme.colorScheme.meloGlassSurface,
		border = libraryHairlineBorder(),
		shadowElevation = 10.dp,
		modifier = modifier.fillMaxWidth()
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 10.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp)
				) {
					IconButton(
						onClick = onExit,
						modifier = Modifier.size(28.dp)
					) {
						Icon(Icons.Outlined.Close, contentDescription = "退出多选", modifier = Modifier.size(16.dp))
					}
					Text(
						text = "已选 $selectedCount / $totalCount 首",
						style = MaterialTheme.typography.labelLarge.tabularNums(),
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface
					)
				}

				Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					TextButton(
						onClick = onSelectAll,
						contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
						modifier = Modifier.height(30.dp)
					) {
						Text(
							text = if (selectedCount == totalCount && totalCount > 0) "全不选" else "全选",
							style = MaterialTheme.typography.labelMedium
						)
					}
					if (selectedCount > 0) {
						TextButton(
							onClick = onClear,
							contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
							modifier = Modifier.height(30.dp)
						) {
							Text(
								text = "清空",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
					}
				}
			}

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.horizontalScroll(rememberScrollState()),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Button(
					onClick = onDownloadSelected,
					enabled = selectedCount > 0,
					shape = CircleShape,
					contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
					modifier = Modifier.height(36.dp)
				) {
					Icon(Icons.Outlined.Download, null, modifier = Modifier.size(15.dp))
					Spacer(Modifier.width(4.dp))
					Text("离线到手机 ($selectedCount)", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
				}

				if (onDownloadToLibrarySelected != null) {
					FilledTonalButton(
						onClick = onDownloadToLibrarySelected,
						enabled = downloadToLibraryEnabled,
						shape = CircleShape,
						contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
						modifier = Modifier.height(36.dp)
					) {
						Icon(Icons.Outlined.LibraryAdd, null, modifier = Modifier.size(15.dp))
						Spacer(Modifier.width(4.dp))
						Text(downloadToLibraryLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
					}
				}

				if (onPlayNextSelected != null) {
					Surface(
						onClick = onPlayNextSelected,
						enabled = selectedCount > 0,
						shape = CircleShape,
						color = MaterialTheme.colorScheme.surfaceContainerHigh,
						border = libraryHairlineBorder(),
						modifier = Modifier.height(36.dp)
					) {
						Row(
							modifier = Modifier.padding(horizontal = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp)
						) {
							Icon(
								imageVector = Icons.Outlined.QueuePlayNext,
								contentDescription = null,
								modifier = Modifier.size(15.dp),
								tint = if (selectedCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
							)
							Text(
								text = "下一首播放",
								style = MaterialTheme.typography.labelMedium,
								color = if (selectedCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
								maxLines = 1,
								softWrap = false
							)
						}
					}
				}

				if (onAddToPlaylist != null) {
					FilledTonalButton(
						onClick = onAddToPlaylist,
						enabled = selectedCount > 0,
						shape = CircleShape,
						contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
						modifier = Modifier.height(36.dp)
					) {
						Icon(Icons.Outlined.PlaylistAdd, null, modifier = Modifier.size(15.dp))
						Spacer(Modifier.width(4.dp))
						Text(playlistButtonLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
					}
				}

				if (onPlaySelected != null) {
					Surface(
						onClick = onPlaySelected,
						enabled = selectedCount > 0,
						shape = CircleShape,
						color = MaterialTheme.colorScheme.surfaceContainerHigh,
						border = libraryHairlineBorder(),
						modifier = Modifier.height(36.dp)
					) {
						Row(
							modifier = Modifier.padding(horizontal = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp)
						) {
							Icon(
								imageVector = Icons.Filled.Play,
								contentDescription = null,
								modifier = Modifier.size(15.dp),
								tint = if (selectedCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
							)
							Text(
								text = "播放选中",
								style = MaterialTheme.typography.labelMedium,
								color = if (selectedCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
								maxLines = 1,
								softWrap = false
							)
						}
					}
				}

				if (onDeleteOfflineSelected != null && hasOfflineRemovable) {
					Surface(
						onClick = onDeleteOfflineSelected,
						shape = CircleShape,
						color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
						contentColor = MaterialTheme.colorScheme.onErrorContainer,
						modifier = Modifier.height(36.dp)
					) {
						Row(
							modifier = Modifier.padding(horizontal = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp)
						) {
							Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(15.dp))
							Text("删除离线", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
						}
					}
				}

				if (onRemoveFromPlaylistSelected != null) {
					val removeEnabled = selectedCount > 0
					Surface(
						onClick = onRemoveFromPlaylistSelected,
						enabled = removeEnabled,
						shape = CircleShape,
						color = if (removeEnabled) {
							MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.82f)
						} else {
							MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
						},
						contentColor = if (removeEnabled) {
							MaterialTheme.colorScheme.onErrorContainer
						} else {
							MaterialTheme.colorScheme.outline
						},
						modifier = Modifier.height(36.dp)
					) {
						Row(
							modifier = Modifier.padding(horizontal = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp)
						) {
							Icon(Icons.Outlined.PlaylistRemove, null, modifier = Modifier.size(15.dp))
							Text("移出歌单", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun NativeDetailSongActionSheet(
	song: DomainSong,
	downloadStatus: DownloadStatus?,
	onDismiss: () -> Unit,
	onPlayNext: () -> Unit,
	onAddToQueue: () -> Unit,
	onAddToPlaylist: (() -> Unit)? = null,
	onDownload: () -> Unit,
	onCancelDownload: () -> Unit,
	onDeleteDownload: () -> Unit,
	extraActionLabel: String? = null,
	onExtraAction: (() -> Unit)? = null,
	onRemoveFromPlaylist: (() -> Unit)? = null,
	extraActionIcon: ImageVector = Icons.Outlined.Star,
	extraActionTint: Color = MaterialTheme.colorScheme.meloGoldText
) {
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.navigationBarsPadding()
				.padding(horizontal = 20.dp, vertical = 12.dp),
			verticalArrangement = Arrangement.spacedBy(6.dp)
		) {
			Text(
				text = song.title.ifBlank { "歌曲操作" },
				style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			Text(
				text = song.artistName ?: "未知艺人",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			MeloHairlineDivider(modifier = Modifier.padding(vertical = 4.dp))

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clip(RoundedCornerShape(12.dp))
					.clickable(onClick = onPlayNext)
					.padding(vertical = 11.dp, horizontal = 4.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp)
			) {
				Icon(Icons.Outlined.QueuePlayNext, null)
				Text("下一首播放")
			}

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clip(RoundedCornerShape(12.dp))
					.clickable(onClick = onAddToQueue)
					.padding(vertical = 11.dp, horizontal = 4.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp)
			) {
				Icon(Icons.Outlined.Queue, null)
				Text("加入播放队列")
			}

			if (onAddToPlaylist != null) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clip(RoundedCornerShape(12.dp))
						.clickable(onClick = onAddToPlaylist)
						.padding(vertical = 11.dp, horizontal = 4.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(12.dp)
				) {
					Icon(Icons.Outlined.PlaylistAdd, null)
					Text("加入歌单")
				}
			}

			when (downloadStatus) {
				DownloadStatus.DOWNLOADED -> {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clip(RoundedCornerShape(12.dp))
							.clickable(onClick = onDeleteDownload)
							.padding(vertical = 11.dp, horizontal = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(12.dp)
					) {
						Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
						Text("从手机删除离线文件", color = MaterialTheme.colorScheme.error)
					}
				}
				DownloadStatus.DOWNLOADING -> {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clip(RoundedCornerShape(12.dp))
							.clickable(onClick = onCancelDownload)
							.padding(vertical = 11.dp, horizontal = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(12.dp)
					) {
						Icon(Icons.Outlined.Close, null, tint = MaterialTheme.colorScheme.error)
						Text("取消离线缓存", color = MaterialTheme.colorScheme.error)
					}
				}
				else -> {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clip(RoundedCornerShape(12.dp))
							.clickable(onClick = onDownload)
							.padding(vertical = 11.dp, horizontal = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(12.dp)
					) {
						Icon(Icons.Outlined.Download, null, tint = MaterialTheme.colorScheme.primary)
						Text("离线到手机")
					}
				}
			}

			if (extraActionLabel != null && onExtraAction != null) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clip(RoundedCornerShape(12.dp))
						.clickable(onClick = onExtraAction)
						.padding(vertical = 11.dp, horizontal = 4.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(12.dp)
				) {
					Icon(extraActionIcon, null, tint = extraActionTint)
					Text(extraActionLabel)
				}
			}

			if (onRemoveFromPlaylist != null) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clip(RoundedCornerShape(12.dp))
						.clickable(onClick = onRemoveFromPlaylist)
						.padding(vertical = 11.dp, horizontal = 4.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(12.dp)
				) {
					Icon(Icons.Outlined.PlaylistRemove, null, tint = MaterialTheme.colorScheme.error)
					Text("从歌单移除", color = MaterialTheme.colorScheme.error)
				}
			}
		}
	}
}

@Composable
internal fun OfflineSongStatusIcon(
	status: DownloadStatus?,
	onDownloadClick: () -> Unit,
	onDeleteClick: () -> Unit,
	onCancelDownloadingClick: (() -> Unit)? = null,
	modifier: Modifier = Modifier
) {
	when (status) {
		DownloadStatus.DOWNLOADED -> {
			Surface(
				onClick = onDeleteClick,
				shape = CircleShape,
				color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
				modifier = modifier.size(28.dp)
			) {
				Box(contentAlignment = Alignment.Center) {
					Icon(
						imageVector = Icons.Outlined.Check,
						contentDescription = "已离线至手机（点击移除）",
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(16.dp)
					)
				}
			}
		}
		DownloadStatus.DOWNLOADING -> {
			Surface(
				onClick = { onCancelDownloadingClick?.invoke() },
				enabled = onCancelDownloadingClick != null,
				shape = CircleShape,
				color = Color.Transparent,
				modifier = modifier.size(28.dp)
			) {
				Box(contentAlignment = Alignment.Center) {
					CircularProgressIndicator(
						modifier = Modifier.size(18.dp),
						strokeWidth = 2.dp,
						color = MaterialTheme.colorScheme.primary
					)
					if (onCancelDownloadingClick != null) {
						Icon(
							imageVector = Icons.Outlined.Close,
							contentDescription = "取消下载",
							tint = MaterialTheme.colorScheme.primary,
							modifier = Modifier.size(10.dp)
						)
					}
				}
			}
		}
		DownloadStatus.FAILED -> {
			Surface(
				onClick = onDownloadClick,
				shape = CircleShape,
				color = MaterialTheme.colorScheme.errorContainer,
				modifier = modifier.size(28.dp)
			) {
				Box(contentAlignment = Alignment.Center) {
					Icon(
						imageVector = Icons.Outlined.Refresh,
						contentDescription = "下载失败，点击重试",
						tint = MaterialTheme.colorScheme.error,
						modifier = Modifier.size(16.dp)
					)
				}
			}
		}
		DownloadStatus.NOT_DOWNLOADED, null -> {
			IconButton(
				onClick = onDownloadClick,
				modifier = modifier.size(28.dp)
			) {
				Icon(
					imageVector = Icons.Outlined.Download,
					contentDescription = "下载到手机",
					tint = MaterialTheme.colorScheme.outline,
					modifier = Modifier.size(18.dp)
				)
			}
		}
	}
}

/**
 * 极小的非交互“已离线”副标题微标（12.dp 圆形打勾）。
 */
@Composable
internal fun MeloDownloadedTinyBadge(
	modifier: Modifier = Modifier
) {
	Box(
		modifier = modifier
			.size(12.dp)
			.clip(CircleShape)
			.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
		contentAlignment = Alignment.Center
	) {
		Icon(
			imageVector = Icons.Outlined.Check,
			contentDescription = "已离线",
			tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
			modifier = Modifier.size(9.dp)
		)
	}
}

/**
 * 通用歌曲行左右滑入队容器：
 * - 右滑（StartToEnd）：加入待播队尾（addToQueueSingle）并回弹
 * - 左滑（EndToStart）：设为下一首播放（playNextSingle）并回弹
 * - 多选模式（selectionMode == true）时自动禁用手势避免误触
 */
@Composable
internal fun MeloSwipeableSongRow(
	song: DomainSong,
	modifier: Modifier = Modifier,
	selectionMode: Boolean = false,
	shape: Shape = RoundedCornerShape(14.dp),
	content: @Composable () -> Unit
) {
	val player = koinInject<MediaPlayer>()
	val snackBarState = LocalSnackBarState.current
	val dismissState = rememberSwipeToDismissBoxState()
	val scope = rememberCoroutineScope()

	SwipeToDismissBox(
		modifier = modifier,
		state = dismissState,
		gesturesEnabled = !selectionMode,
		onDismiss = { direction ->
			when (direction) {
				SwipeToDismissBoxValue.StartToEnd -> {
					player.addToQueueSingle(song, notify = false)
					scope.launch {
						snackBarState.currentSnackbarData?.dismiss()
						snackBarState.showSnackbar("已加入待播队尾")
					}
				}
				SwipeToDismissBoxValue.EndToStart -> {
					player.playNextSingle(song)
					scope.launch {
						snackBarState.currentSnackbarData?.dismiss()
						snackBarState.showSnackbar("已设为下一首播放")
					}
				}
				SwipeToDismissBoxValue.Settled -> {}
			}
			scope.launch { dismissState.reset() }
		},
		backgroundContent = {
			SongQueueSwipeBackground(
				dismissDirection = dismissState.dismissDirection,
				shape = shape
			)
		}
	) {
		Box(modifier = Modifier.fillMaxWidth()) {
			content()
		}
	}
}

private const val LASTFM_DEFAULT_STAR_HASH = "2a96cbd8b46e442fc41c2b86b821562f"

private val ARTIST_PLACEHOLDER_NAMES = setOf(
	"未知歌手",
	"unknown artist",
	"various artists",
	"群星"
)

private fun String?.cleanUsableArtistImageUrl(): String? =
	this?.trim()?.takeIf { url ->
		url.isNotEmpty() && !url.contains(LASTFM_DEFAULT_STAR_HASH, ignoreCase = true)
	}

private data class ArtistAvatarCandidate(
	val url: String,
	val cacheKey: String?,
	val attachMeloHeaders: Boolean,
	val attachSubsonicHeaders: Boolean
)

/**
 * 统一歌手头像组件：
 * 1. 优先非空且非 Last.fm 默认星标的 [artistImageUrl] 或 Navidrome/本地 [coverArtId]（当 [preferMeloAvatar] 为 true 时优先 MeloKeep `/api/artists/avatar`）；
 * 2. 主源缺失或 Coil 加载失败时，自动降级至 MeloKeep `/api/artists/avatar?name=...`（附带 Bearer / media_token 鉴权）；
 * 3. 所有网络源均不可用或加载中时显示和谐主题色首字或歌手图标占位，绝不留空白框。
 */
@Composable
internal fun MeloArtistAvatar(
	artistName: String,
	coverArtId: String? = null,
	artistImageUrl: String? = null,
	modifier: Modifier = Modifier,
	shape: Shape = CircleShape,
	contentDescription: String? = artistName,
	preferMeloAvatar: Boolean = false
) {
	val imageLoader = koinInject<ImageLoader>()
	val sessionManager = koinInject<SessionManager>()
	val preferenceManager = koinInject<PreferenceManager>()
	val meloSettingsStore = koinInject<MeloSettingsStore>()
	val meloApiClient = koinInject<MeloKeepApiClient>()
	val coilPlatformContext = LocalCoilPlatformContext.current

	val meloConfig by meloSettingsStore.config.collectAsStateWithLifecycle()
	val trimmedName = remember(artistName) { artistName.trim() }

	val candidates = remember(
		trimmedName,
		coverArtId,
		artistImageUrl,
		preferMeloAvatar,
		meloConfig,
		sessionManager.instanceUrl
	) {
		val cleanBase = meloConfig.baseUrl.trim().trimEnd('/')
		val meloReady = meloConfig.isAuthenticated ||
			(meloConfig.isConfigured && meloConfig.mediaToken.isNotBlank())

		val externalCandidate = artistImageUrl.cleanUsableArtistImageUrl()?.let { url ->
			val isOwnMelo = cleanBase.isNotEmpty() && url.startsWith(cleanBase, ignoreCase = true)
			val resolvedUrl = if (isOwnMelo) {
				meloApiClient.resolveMediaUrl(url) ?: url
			} else {
				url
			}
			ArtistAvatarCandidate(
				url = resolvedUrl,
				cacheKey = "artist_img:${url.substringBefore("?media_token=")}",
				attachMeloHeaders = isOwnMelo,
				attachSubsonicHeaders = false
			)
		}

		val coverCandidate = coverArtId.cleanUsableArtistImageUrl()?.let { rawCover ->
			val hasDirectScheme = rawCover.startsWith("http://", ignoreCase = true) ||
				rawCover.startsWith("https://", ignoreCase = true) ||
				rawCover.startsWith("content://", ignoreCase = true) ||
				rawCover.startsWith("file://", ignoreCase = true)
			val isRelativeApi = rawCover.startsWith("/api/", ignoreCase = true) ||
				rawCover.startsWith("/downloads/", ignoreCase = true)

			when {
				hasDirectScheme -> {
					val isOwnMelo = cleanBase.isNotEmpty() && rawCover.startsWith(cleanBase, ignoreCase = true)
					val resolvedUrl = if (isOwnMelo) {
						meloApiClient.resolveMediaUrl(rawCover) ?: rawCover
					} else {
						rawCover
					}
					ArtistAvatarCandidate(
						url = resolvedUrl,
						cacheKey = rawCover.substringBefore("?media_token="),
						attachMeloHeaders = isOwnMelo,
						attachSubsonicHeaders = false
					)
				}
				isRelativeApi && meloReady -> {
					val resolvedUrl = meloApiClient.resolveMediaUrl(rawCover)
					resolvedUrl?.let {
						ArtistAvatarCandidate(
							url = it,
							cacheKey = "melo_cover:${rawCover.substringBefore("?media_token=")}",
							attachMeloHeaders = true,
							attachSubsonicHeaders = false
						)
					}
				}
				!isRelativeApi && sessionManager.instanceUrl.isNotBlank() -> {
					ArtistAvatarCandidate(
						url = sessionManager.getCoverArtUrl(rawCover),
						cacheKey = rawCover,
						attachMeloHeaders = false,
						attachSubsonicHeaders = true
					)
				}
				else -> null
			}
		}

		val meloAvatarCandidate = if (
			meloReady &&
			trimmedName.isNotEmpty() &&
			trimmedName.lowercase() !in ARTIST_PLACEHOLDER_NAMES
		) {
			val path = "/api/artists/avatar?name=${Uri.encode(trimmedName)}"
			meloApiClient.resolveMediaUrl(path)?.let { resolvedUrl ->
				ArtistAvatarCandidate(
					url = resolvedUrl,
					cacheKey = "melo_artist_avatar:${trimmedName.lowercase()}",
					attachMeloHeaders = true,
					attachSubsonicHeaders = false
				)
			}
		} else {
			null
		}

		val ordered = if (preferMeloAvatar) {
			listOfNotNull(externalCandidate, meloAvatarCandidate, coverCandidate)
		} else {
			listOfNotNull(externalCandidate, coverCandidate, meloAvatarCandidate)
		}
		ordered.distinctBy { it.url }
	}

	var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
	var allFailed by remember(candidates) { mutableStateOf(candidates.isEmpty()) }
	var imageLoaded by remember(candidates) { mutableStateOf(false) }

	val activeCandidate = if (!allFailed) candidates.getOrNull(candidateIndex) else null

	val request = remember(
		activeCandidate,
		meloConfig.bearerToken,
		preferenceManager.customHeaders,
		coilPlatformContext
	) {
		activeCandidate?.let { candidate ->
			val headersBuilder = NetworkHeaders.Builder()
			var hasHeaders = false
			if (candidate.attachMeloHeaders) {
				headersBuilder.add("X-MeloKeep-Request", "1")
				if (meloConfig.bearerToken.isNotBlank()) {
					headersBuilder.add("Authorization", "Bearer ${meloConfig.bearerToken}")
				}
				hasHeaders = true
			}
			if (candidate.attachSubsonicHeaders) {
				preferenceManager.customHeadersMap().forEach { (key, value) ->
					headersBuilder.add(key, value)
					hasHeaders = true
				}
			}
			ImageRequest.Builder(coilPlatformContext)
				.data(candidate.url)
				.apply {
					candidate.cacheKey?.let { key ->
						memoryCacheKey(key)
						diskCacheKey(key)
					}
					if (hasHeaders) {
						httpHeaders(headersBuilder.build())
					}
				}
				.diskCachePolicy(CachePolicy.ENABLED)
				.memoryCachePolicy(CachePolicy.ENABLED)
				.crossfade(120)
				.build()
		}
	}

	val paletteIndex = remember(trimmedName) {
		(trimmedName.lowercase().hashCode() and 0x7fffffff) % 4
	}
	val (fallbackBgColor, fallbackFgColor) = when (paletteIndex) {
		0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
		1 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
		2 -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
		else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.primary
	}
	val initialGlyph = remember(trimmedName) {
		trimmedName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString()
	}

	BoxWithConstraints(
		modifier = modifier
			.clip(shape)
			.background(fallbackBgColor),
		contentAlignment = Alignment.Center
	) {
		if (!imageLoaded) {
			val resolvedDp = if (maxWidth.value.isFinite() && maxHeight.value.isFinite()) {
				minOf(maxWidth, maxHeight)
			} else {
				48.dp
			}
			if (initialGlyph != null) {
				Text(
					text = initialGlyph,
					style = MaterialTheme.typography.titleMedium.copy(
						fontSize = (resolvedDp.value * 0.40f).coerceIn(14f, 52f).sp,
						fontWeight = FontWeight.Bold
					),
					color = fallbackFgColor,
					maxLines = 1
				)
			} else {
				Icon(
					imageVector = Icons.Outlined.Artist,
					contentDescription = null,
					tint = fallbackFgColor,
					modifier = Modifier.size((resolvedDp * 0.46f).coerceIn(18.dp, 56.dp))
				)
			}
		}

		if (request != null) {
			AsyncImage(
				model = request,
				imageLoader = imageLoader,
				contentDescription = contentDescription,
				contentScale = ContentScale.Crop,
				onSuccess = { imageLoaded = true },
				onError = {
					imageLoaded = false
					if (candidateIndex + 1 < candidates.size) {
						candidateIndex += 1
					} else {
						allFailed = true
					}
				},
				modifier = Modifier.fillMaxSize()
			)
		}
	}
}
