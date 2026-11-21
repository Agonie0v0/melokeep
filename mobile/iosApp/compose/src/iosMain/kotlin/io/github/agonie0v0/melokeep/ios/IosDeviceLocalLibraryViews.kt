@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.agonie0v0.melokeep.ios

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class IosDeviceSubTab(val label: String) {
	FOLDERS("文件夹"),
	SONGS("全部歌曲"),
}

@Composable
internal fun IosLibDeviceLocalSection(
	deviceState: IosDeviceMusicState,
	searchQuery: String,
	audioPlayer: IosAudioPlayer,
	currentTrackId: String?,
	bottomPadding: PaddingValues,
) {
	var subTabName by rememberSaveable { mutableStateOf(IosDeviceSubTab.FOLDERS.name) }
	val subTab = remember(subTabName) {
		IosDeviceSubTab.entries.firstOrNull { it.name == subTabName } ?: IosDeviceSubTab.FOLDERS
	}
	var openedFolderPath by rememberSaveable { mutableStateOf<String?>(null) }

	val normalizedQuery = searchQuery.trim()
	val filteredFolders = remember(deviceState.folders, normalizedQuery) {
		if (normalizedQuery.isBlank()) {
			deviceState.folders
		} else {
			deviceState.folders.filter { folder ->
				folder.name.contains(normalizedQuery, ignoreCase = true) ||
					folder.path.contains(normalizedQuery, ignoreCase = true) ||
					folder.tracks.any { t ->
						t.title.contains(normalizedQuery, ignoreCase = true) ||
							t.artist.contains(normalizedQuery, ignoreCase = true)
					}
			}
		}
	}
	val filteredSongs = remember(deviceState.allTracks, normalizedQuery) {
		if (normalizedQuery.isBlank()) {
			deviceState.allTracks
		} else {
			deviceState.allTracks.filter { t ->
				t.title.contains(normalizedQuery, ignoreCase = true) ||
					t.artist.contains(normalizedQuery, ignoreCase = true) ||
					t.album.contains(normalizedQuery, ignoreCase = true)
			}
		}
	}
	val activeFolder = remember(deviceState.folders, openedFolderPath) {
		openedFolderPath?.let { path -> deviceState.folders.firstOrNull { it.path == path } }
	}
	val activeFolderTracks = remember(activeFolder, normalizedQuery) {
		val base = activeFolder?.tracks.orEmpty()
		if (normalizedQuery.isBlank()) {
			base
		} else {
			base.filter { t ->
				t.title.contains(normalizedQuery, ignoreCase = true) ||
					t.artist.contains(normalizedQuery, ignoreCase = true)
			}
		}
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = 8.dp,
			bottom = bottomPadding.calculateBottomPadding() + 16.dp,
		),
		verticalArrangement = Arrangement.spacedBy(0.dp),
	) {
		if (deviceState.scanning) {
			item(key = "ios-dev-scanning") {
				LinearProgressIndicator(
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 8.dp),
				)
			}
		}

		item(key = "ios-dev-controls") {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(bottom = 10.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						IosDeviceSubTab.entries.forEach { tab ->
							val selected = subTab == tab
							Surface(
								onClick = {
									subTabName = tab.name
									if (tab == IosDeviceSubTab.SONGS) openedFolderPath = null
								},
								shape = CircleShape,
								color = if (selected) {
									MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
								} else {
									MaterialTheme.colorScheme.surfaceContainer
								},
								border = BorderStroke(
									IosHairlineWidth,
									if (selected) {
										MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
									} else {
										iosLibHairlineBorderColor()
									},
								),
							) {
								Text(
									text = tab.label,
									style = MaterialTheme.typography.labelMedium.copy(
										fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
									),
									color = if (selected) {
										MaterialTheme.colorScheme.primary
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant
									},
									modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
								)
							}
						}
					}

					Text(
						text = "${deviceState.folders.size} 目录 · ${deviceState.allTracks.size} 首",
						style = MaterialTheme.typography.labelMedium.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}

				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					FilledTonalButton(
						onClick = { IosDeviceMusicManager.presentAudioPicker() },
						modifier = Modifier.weight(1f),
						shape = RoundedCornerShape(12.dp),
					) {
						Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(4.dp))
						Text("导入音频")
					}
					OutlinedButton(
						onClick = { IosDeviceMusicManager.presentFolderPicker() },
						modifier = Modifier.weight(1f),
						shape = RoundedCornerShape(12.dp),
					) {
						Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
						Spacer(Modifier.width(4.dp))
						Text("导入文件夹")
					}
				}
			}
		}

		if (subTab == IosDeviceSubTab.FOLDERS && activeFolder == null) {
			if (filteredFolders.isEmpty() && !deviceState.scanning) {
				item(key = "ios-dev-empty") {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 36.dp),
						horizontalAlignment = Alignment.CenterHorizontally,
						verticalArrangement = Arrangement.spacedBy(12.dp),
					) {
						IosQuietEmptyState(
							message = if (normalizedQuery.isBlank()) {
								"暂无手机本地音乐，点击上方导入音频/文件夹，或通过 iOS「文件」App 将音乐放入 MeloKeep 目录"
							} else {
								"没有匹配的手机本地文件夹"
							},
							icon = Icons.Rounded.Folder,
						)
						if (normalizedQuery.isBlank()) {
							Button(
								onClick = { IosDeviceMusicManager.presentFolderPicker() },
								shape = RoundedCornerShape(12.dp),
							) {
								Text("选择音乐文件夹")
							}
						}
					}
				}
			} else {
				itemsIndexed(
					items = filteredFolders,
					key = { _, folder -> "ios_dev_folder_${folder.path}" },
				) { idx, folder ->
					Surface(
						shape = iosLibGroupedItemShape(
							isFirst = idx == 0,
							isLast = idx == filteredFolders.lastIndex,
						),
						color = MaterialTheme.colorScheme.surfaceContainer,
						modifier = Modifier
							.fillMaxWidth()
							.clickable { openedFolderPath = folder.path },
					) {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 12.dp, vertical = 10.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp),
						) {
							Box(
								modifier = Modifier
									.size(40.dp)
									.clip(RoundedCornerShape(10.dp))
									.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
								contentAlignment = Alignment.Center,
							) {
								Icon(
									imageVector = Icons.Rounded.Folder,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.primary,
									modifier = Modifier.size(20.dp),
								)
							}
							Column(modifier = Modifier.weight(1f)) {
								Text(
									text = folder.name,
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = FontWeight.Medium,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
								Text(
									text = "${folder.path} · ${folder.tracks.size} 首",
									style = MaterialTheme.typography.bodySmall.tabularNums(),
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
							IconButton(
								onClick = {
									if (folder.tracks.isNotEmpty()) {
										audioPlayer.playQueue(folder.tracks, 0)
									}
								},
								modifier = Modifier.size(32.dp),
							) {
								Icon(
									imageVector = Icons.Rounded.PlaylistPlay,
									contentDescription = "播放此文件夹",
									tint = MaterialTheme.colorScheme.primary,
									modifier = Modifier.size(18.dp),
								)
							}
						}
					}
					if (idx < filteredFolders.lastIndex) {
						IosHairlineDivider(startIndent = 58.dp)
					}
				}
			}
		} else {
			val displayedTracks = if (subTab == IosDeviceSubTab.FOLDERS) {
				activeFolderTracks
			} else {
				filteredSongs
			}

			if (activeFolder != null && subTab == IosDeviceSubTab.FOLDERS) {
				item(key = "ios-dev-breadcrumb") {
					Surface(
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = 10.dp),
						shape = RoundedCornerShape(14.dp),
						color = MaterialTheme.colorScheme.surfaceContainer,
						border = iosLibHairlineBorder(),
					) {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 8.dp, vertical = 6.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(8.dp),
						) {
							IconButton(
								onClick = { openedFolderPath = null },
								modifier = Modifier.size(34.dp),
							) {
								Icon(
									imageVector = Icons.Rounded.ArrowBack,
									contentDescription = "返回目录列表",
									modifier = Modifier.size(18.dp),
								)
							}
							Column(modifier = Modifier.weight(1f)) {
								Text(
									text = activeFolder.name,
									style = MaterialTheme.typography.titleSmall,
									fontWeight = FontWeight.SemiBold,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
								Text(
									text = activeFolder.path,
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
						}
					}
				}
			}

			if (displayedTracks.isNotEmpty()) {
				item(key = "ios-dev-play-actions") {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = 10.dp),
						horizontalArrangement = Arrangement.spacedBy(8.dp),
					) {
						FilledTonalButton(
							onClick = { audioPlayer.playQueue(displayedTracks, 0) },
							modifier = Modifier.weight(1f),
							shape = RoundedCornerShape(12.dp),
						) {
							Icon(Icons.Rounded.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
							Spacer(Modifier.width(6.dp))
							Text("播放全部 (${displayedTracks.size})")
						}
						OutlinedButton(
							onClick = { audioPlayer.playQueue(displayedTracks.shuffled(), 0) },
							modifier = Modifier.weight(1f),
							shape = RoundedCornerShape(12.dp),
						) {
							Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(17.dp))
							Spacer(Modifier.width(6.dp))
							Text("随机播放")
						}
					}
				}
			}

			if (displayedTracks.isEmpty() && !deviceState.scanning) {
				item(key = "ios-dev-tracks-empty") {
					IosQuietEmptyState(
						message = if (normalizedQuery.isBlank()) "暂无可播放的手机本地曲目" else "没有匹配的曲目",
						icon = Icons.Rounded.LibraryMusic,
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 40.dp),
					)
				}
			} else {
				itemsIndexed(
					items = displayedTracks,
					key = { _, track -> track.id },
				) { idx, track ->
					val isPlaying = currentTrackId == track.id
					var menuOpen by remember { mutableStateOf(false) }
					Surface(
						shape = iosLibGroupedItemShape(
							isFirst = idx == 0,
							isLast = idx == displayedTracks.lastIndex,
						),
						color = if (isPlaying) {
							MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
						} else {
							MaterialTheme.colorScheme.surfaceContainer
						},
						modifier = Modifier
							.fillMaxWidth()
							.clickable { audioPlayer.playQueue(displayedTracks, idx) },
					) {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 12.dp, vertical = 9.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp),
						) {
							Box(
								modifier = Modifier
									.size(42.dp)
									.clip(RoundedCornerShape(9.dp))
									.background(MaterialTheme.colorScheme.surfaceContainerHigh),
								contentAlignment = Alignment.Center,
							) {
								Icon(
									imageVector = Icons.Rounded.MusicNote,
									contentDescription = null,
									tint = if (isPlaying) {
										MaterialTheme.colorScheme.primary
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
									},
									modifier = Modifier.size(18.dp),
								)
							}
							Column(modifier = Modifier.weight(1f)) {
								Text(
									text = track.title,
									style = MaterialTheme.typography.bodyMedium,
									fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium,
									color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Unspecified,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
								val parts = buildList {
									if (track.artist.isNotBlank()) add(track.artist)
									track.fileExtension?.uppercase()?.let(::add)
									if (track.sizeBytes > 0L) add(formatIosDeviceSize(track.sizeBytes))
								}
								Text(
									text = parts.joinToString(" · "),
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
							Box {
								IconButton(
									onClick = { menuOpen = true },
									modifier = Modifier.size(32.dp),
								) {
									Icon(
										imageVector = Icons.Rounded.MoreVert,
										contentDescription = "更多",
										tint = MaterialTheme.colorScheme.onSurfaceVariant,
										modifier = Modifier.size(18.dp),
									)
								}
								DropdownMenu(
									expanded = menuOpen,
									onDismissRequest = { menuOpen = false },
								) {
									DropdownMenuItem(
										text = { Text("立即播放") },
										leadingIcon = { Icon(Icons.Rounded.PlaylistPlay, contentDescription = null) },
										onClick = {
											menuOpen = false
											audioPlayer.playQueue(displayedTracks, idx)
										},
									)
									DropdownMenuItem(
										text = { Text("下一首播放") },
										leadingIcon = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
										onClick = {
											menuOpen = false
											audioPlayer.playNext(track)
										},
									)
									DropdownMenuItem(
										text = { Text("加入队列") },
										leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null) },
										onClick = {
											menuOpen = false
											audioPlayer.appendToQueue(listOf(track))
										},
									)
									DropdownMenuItem(
										text = { Text("删除文件") },
										leadingIcon = {
											Icon(
												Icons.Rounded.Delete,
												contentDescription = null,
												tint = MaterialTheme.colorScheme.error,
											)
										},
										onClick = {
											menuOpen = false
											IosDeviceMusicManager.deleteTrack(track)
										},
									)
								}
							}
						}
					}
					if (idx < displayedTracks.lastIndex) {
						IosHairlineDivider(startIndent = 64.dp)
					}
				}
			}
		}
	}
}

private fun formatIosDeviceSize(bytes: Long): String {
	if (bytes <= 0L) return "0 B"
	val kb = bytes / 1024.0
	if (kb < 1024.0) return "${kb.toInt()} KB"
	val mb = kb / 1024.0
	val whole = mb.toInt()
	val tenths = ((mb - whole) * 10.0).toInt().coerceIn(0, 9)
	return "$whole.$tenths MB"
}
