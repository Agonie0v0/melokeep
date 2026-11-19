@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.melokeep

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.agonie0v0.melokeep.shared.MeloAgentEvent
import io.github.agonie0v0.melokeep.shared.MeloAgentMessage
import io.github.agonie0v0.melokeep.shared.MeloAgentPlan
import io.github.agonie0v0.melokeep.shared.MeloAgentSession
import io.github.agonie0v0.melokeep.shared.MeloAgentSessionSummary
import io.github.agonie0v0.melokeep.shared.MeloAiController
import io.github.agonie0v0.melokeep.shared.MeloKeepApiClient
import io.github.agonie0v0.melokeep.shared.MeloSearchController
import io.github.agonie0v0.melokeep.shared.MeloSettingsStore
import io.github.agonie0v0.melokeep.shared.MeloTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSnackBarState
import paige.navic.playback.MediaPlayer
import paige.navic.ui.component.common.CoverArt
import paige.navic.ui.component.common.Markdown
import paige.navic.ui.component.common.MarqueeText
import paige.navic.ui.component.common.Waveform
import paige.navic.ui.component.sheet.ModalBottomSheet
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Add
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Close
import paige.navic.ui.icons.outlined.Delete
import paige.navic.ui.icons.outlined.Download
import paige.navic.ui.icons.outlined.Forum
import paige.navic.ui.icons.outlined.KeyboardArrowDown
import paige.navic.ui.icons.outlined.Search
import paige.navic.ui.icons.outlined.Send
import paige.navic.ui.navigation.Screen
import paige.navic.ui.theme.MeloBottomChromeClearance
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.theme.positive
import paige.navic.ui.theme.tabularNums
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

private val AI_QUICK_PROMPTS = listOf(
	"识别截图歌单并全网查找",
	"搜索氛围电子与纯音乐专辑",
	"检查最近下载任务状态",
	"整理并刮削未归档音乐"
)

private val aiJson = Json {
	ignoreUnknownKeys = true
	isLenient = true
	coerceInputValues = true
}

private val prettyAgentJson = Json {
	prettyPrint = true
}

private val AI_TOOL_LABELS = mapOf(
	"search_music" to "全网检索音乐",
	"list_playlists" to "查询本地歌单",
	"read_playlist" to "读取歌单曲目",
	"create_playlist" to "创建本地歌单",
	"add_to_playlist" to "加入本地歌单",
	"download_music" to "提交下载任务",
	"get_status" to "检查队列状态",
	"get_task_status" to "查询任务进度",
	"browse_library" to "检索服务器曲库",
	"preview_organize" to "生成刮削整理预览",
	"list_project_operations" to "查询可用操作",
	"query_project" to "查询系统状态",
	"execute_project" to "执行系统操作",
	"wait_for_tasks" to "等待后台任务",
	"prepare_import" to "整理识别清单",
	"update_plan" to "更新执行计划",
	"request_decision" to "等待用户确认",
	"recover_download" to "恢复下载或整理"
)

private val BOOK_TITLE_REGEX = Regex("《([^》]{1,40})》")
private val DASH_TRACK_REGEX = Regex("(?m)^(?:\\d+[.、]\\s*|[-*•]\\s*)?\\*{0,2}([^\\n\\-—:：]{1,36})\\*{0,2}\\s*[-—]\\s*([^\\n（(]{1,28})")

/**
 * 原生 AI 助手与识图导歌单屏幕（独立路由入口）。
 * 当 [nested] 为 false 时由外层持久化壳层统一承载底栏，不再重复绘制 RootBottomBar。
 */
@Composable
fun MeloAiScreen(
	nested: Boolean = false,
	onOpenSettings: (() -> Unit)? = null
) {
	val controller = koinInject<MeloAiController>()
	val searchController = koinInject<MeloSearchController>()
	val settingsStore = koinInject<MeloSettingsStore>()
	val backStack = LocalNavStack.current
	val openSettings = rememberOpenMeloSettings(onOpenSettings)
	val serverConfig by settingsStore.config.collectAsStateWithLifecycle()
	var sessionSheetOpen by remember { mutableStateOf(false) }

	val popBack = dropUnlessResumed {
		if (backStack.size > 1) {
			backStack.removeLastOrNull()
		}
	}

	Surface(
		modifier = Modifier.fillMaxSize(),
		color = MaterialTheme.colorScheme.background
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			MeloCleanTopBar(
				title = "AI 助手",
				onBack = if (nested) popBack else null,
				trailing = {
					if (serverConfig.isAuthenticated) {
						AiTopBarActionButton(
							icon = Icons.Outlined.Add,
							contentDescription = "新建会话",
							onClick = controller::createSession
						)
						AiTopBarActionButton(
							icon = Icons.Outlined.Forum,
							contentDescription = "切换会话",
							onClick = { sessionSheetOpen = true }
						)
					}
					if (!nested) {
						AiConnectionStatusChip(
							isAuthenticated = serverConfig.isAuthenticated,
							onClick = openSettings
						)
					}
				}
			)

			MeloAiAssistantContent(
				nested = nested,
				bottomClearance = if (nested) 16.dp else MeloBottomChromeClearance,
				onOpenSettings = openSettings,
				sessionSheetOpen = sessionSheetOpen,
				onSessionSheetOpenChange = { sessionSheetOpen = it },
				onSearchTrackInDiscover = { keyword ->
					searchController.updateKeyword(keyword)
					searchController.search(keyword = keyword, refresh = false)
					backStack.add(Screen.MeloDiscover(nested = true))
				},
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
			)
		}
	}
}

@Composable
private fun AiTopBarActionButton(
	icon: ImageVector,
	contentDescription: String,
	onClick: () -> Unit
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
		),
		modifier = Modifier.size(34.dp)
	) {
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center
		) {
			Icon(
				imageVector = icon,
				contentDescription = contentDescription,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(17.dp)
			)
		}
	}
}

@Composable
private fun AiConnectionStatusChip(
	isAuthenticated: Boolean,
	onClick: () -> Unit
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
		)
	) {
		Row(
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(5.dp)
		) {
			Box(
				modifier = Modifier
					.size(6.dp)
					.background(
						color = if (isAuthenticated) {
							MaterialTheme.colorScheme.positive
						} else {
							MaterialTheme.colorScheme.error
						},
						shape = CircleShape
					)
			)
			Text(
				text = if (isAuthenticated) "在线" else "离线",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1
			)
		}
	}
}

/**
 * 可复用的 AI 助手主体内容体，供探索双模融合页与独立 AI 页共同挂载。
 */
@Composable
internal fun MeloAiAssistantContent(
	nested: Boolean,
	bottomClearance: Dp,
	onOpenSettings: () -> Unit,
	sessionSheetOpen: Boolean,
	onSessionSheetOpenChange: (Boolean) -> Unit,
	onSearchTrackInDiscover: (String) -> Unit,
	modifier: Modifier = Modifier
) {
	val controller = koinInject<MeloAiController>()
	val searchController = koinInject<MeloSearchController>()
	val apiClient = koinInject<MeloKeepApiClient>()
	val settingsStore = koinInject<MeloSettingsStore>()
	val player = koinInject<MediaPlayer>()
	val snackBarState = LocalSnackBarState.current
	val context = LocalContext.current
	val focusManager = LocalFocusManager.current
	val density = LocalDensity.current
	val scope = rememberCoroutineScope()

	val state by controller.state.collectAsStateWithLifecycle()
	val searchState by searchController.state.collectAsStateWithLifecycle()
	val serverConfig by settingsStore.config.collectAsStateWithLifecycle()

	// 性能铁律：只订阅当前曲目 ID 与暂停状态，防止进度条跳秒引起对话流大面积重组
	val playbackTrackState by remember(player) {
		player.uiState
			.map { it.currentSong?.id to it.isPaused }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(initialValue = null to true)
	val (currentSongId, isPlayerPaused) = playbackTrackState

	var inputText by rememberSaveable { mutableStateOf("") }
	var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
	var selectedImageDataUrl by remember { mutableStateOf<String?>(null) }
	var encodingImage by remember { mutableStateOf(false) }

	val listState = rememberLazyListState()

	MeloFeedbackObserver(
		errorMessage = state.errorMessage,
		noticeMessage = state.noticeMessage,
		onClearError = controller::clearError,
		onClearNotice = controller::clearNotice
	)

	LaunchedEffect(serverConfig.isAuthenticated) {
		if (serverConfig.isAuthenticated) {
			controller.loadSessions(selectFirstIfEmpty = true)
		}
	}

	DisposableEffect(Unit) {
		onDispose {
			controller.stopPolling()
		}
	}

	if (!serverConfig.isAuthenticated) {
		Box(
			modifier = modifier
				.fillMaxSize()
				.padding(bottom = bottomClearance),
			contentAlignment = Alignment.Center
		) {
			MeloQuietEmptyState(
				message = "未连接 MeloKeep 服务",
				icon = MeloIcons.Cloud,
				actionLabel = "前往设置",
				onAction = onOpenSettings
			)
		}
		return
	}

	val currentSession = state.currentSession
	val turns = remember(currentSession) {
		buildAiTurnBlocks(currentSession)
	}

	LaunchedEffect(
		turns.size,
		currentSession?.partial,
		currentSession?.id,
		state.isRunning
	) {
		val hasStreamingItem = currentSession?.partial?.isNotBlank() == true || state.isRunning
		val lastIdx = (turns.size + if (hasStreamingItem) 1 else 0) - 1
		if (lastIdx >= 0) {
			listState.animateScrollToItem(lastIdx)
		}
	}

	val photoPickerLauncher = rememberLauncherForActivityResult(
		contract = ActivityResultContracts.PickVisualMedia()
	) { uri ->
		if (uri == null) return@rememberLauncherForActivityResult
		selectedImageUri = uri
		encodingImage = true
		scope.launch {
			val encoded = encodeImageUriToDataUrl(context, uri)
			encodingImage = false
			if (encoded != null) {
				selectedImageDataUrl = encoded
			} else {
				selectedImageUri = null
				snackBarState.currentSnackbarData?.dismiss()
				snackBarState.showSnackbar("图片读取或压缩失败，请换一张重试")
			}
		}
	}

	val handleSend: () -> Unit = {
		if (!encodingImage && !state.sending) {
			val img = selectedImageDataUrl
			val msgText = inputText.trim().ifEmpty {
				if (!img.isNullOrBlank()) "请识别这张歌单截图中的歌曲，并帮我查找可下载版本。" else ""
			}
			if (msgText.isNotEmpty() || !img.isNullOrBlank()) {
				focusManager.clearFocus()
				controller.sendMessage(
					message = msgText,
					imageDataUrl = img,
					allowActions = true
				)
				inputText = ""
				selectedImageUri = null
				selectedImageDataUrl = null
			}
		}
	}

	val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
	val navBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
	val inputDockBottomPadding = if (nested) {
		maxOf(imeBottomDp, navBottomDp + 8.dp)
	} else {
		maxOf(imeBottomDp, navBottomDp + bottomClearance + 6.dp)
	}

	Box(modifier = modifier.fillMaxSize()) {
		Column(modifier = Modifier.fillMaxSize()) {
			if (state.sessions.isNotEmpty()) {
				SessionSwitcherStrip(
					sessions = state.sessions,
					currentSessionId = currentSession?.id,
					onSelect = controller::selectSession,
					onNewSession = controller::createSession,
					onOpenManager = { onSessionSheetOpenChange(true) }
				)
			}

			if (currentSession == null || (turns.isEmpty() && currentSession.partial.isBlank() && !state.isRunning)) {
				AiEmptyPromptView(
					modifier = Modifier
						.weight(1f)
						.padding(bottom = inputDockBottomPadding + 64.dp),
					loading = state.loadingSessions,
					onPickPrompt = { prompt ->
						inputText = prompt
					},
					onPickImage = {
						photoPickerLauncher.launch(
							PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
						)
					}
				)
			} else {
				LazyColumn(
					modifier = Modifier.weight(1f),
					state = listState,
					contentPadding = PaddingValues(
						start = 16.dp,
						end = 16.dp,
						top = 8.dp,
						bottom = inputDockBottomPadding + 92.dp
					),
					verticalArrangement = Arrangement.spacedBy(12.dp)
				) {
					items(
						items = turns,
						key = { it.key }
					) { turn ->
						AiTurnSection(
							turn = turn,
							plan = if (turn.isLatest) currentSession.plan else null,
							isRunning = turn.isLatest && state.isRunning,
							apiClient = apiClient,
							recentSearchTracks = searchState.tracks,
							enqueuedTrackIds = searchState.enqueuedTrackIds,
							currentSongId = currentSongId,
							isPlayerPaused = isPlayerPaused,
							onPlayTrack = { track, allTurnTracks ->
								val mapped = MeloTrackMapper.toDomainSongs(allTurnTracks)
								val idx = allTurnTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
								if (mapped.isNotEmpty()) {
									player.playNow(mapped, idx)
								}
							},
							onTogglePlayPause = { player.togglePlay() },
							onDownloadTrack = { track ->
								scope.launch {
									val res = searchController.enqueueDownload(track, null)
									if (res.isSuccess) {
										snackBarState.currentSnackbarData?.dismiss()
										snackBarState.showSnackbar("已加入下载队列：${track.title}")
									}
								}
							},
							onSearchInDiscover = onSearchTrackInDiscover
						)
					}

					if (currentSession.partial.isNotBlank() || state.isRunning) {
						item(key = "streaming-partial") {
							StreamingPartialBubble(
								partial = currentSession.partial,
								detail = currentSession.detail,
								status = currentSession.status,
								onStop = controller::stopCurrentSession
							)
						}
					}

					if (currentSession.status.lowercase() in setOf("stopped", "interrupted")) {
						item(key = "resume-banner") {
							MeloGroupedCard {
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.padding(horizontal = 14.dp, vertical = 10.dp),
									horizontalArrangement = Arrangement.SpaceBetween,
									verticalAlignment = Alignment.CenterVertically
								) {
									Text(
										text = "会话已暂停 · ${formatMeloStatusLabel(currentSession.status)}",
										style = MaterialTheme.typography.bodySmall,
										color = MaterialTheme.colorScheme.onSurfaceVariant
									)
									Text(
										text = "继续生成",
										style = MaterialTheme.typography.labelMedium,
										fontWeight = FontWeight.Medium,
										color = MaterialTheme.colorScheme.primary,
										modifier = Modifier
											.clip(CircleShape)
											.clickable(onClick = controller::resumeCurrentSession)
											.padding(horizontal = 8.dp, vertical = 4.dp)
									)
								}
							}
						}
					}
				}
			}
		}

		// 悬浮于底栏与播放舱之上的多模态输入舱
		Box(
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.fillMaxWidth()
				.padding(bottom = inputDockBottomPadding)
		) {
			AiInputDock(
				inputText = inputText,
				onInputChange = { inputText = it },
				selectedImageUri = selectedImageUri,
				encodingImage = encodingImage,
				sending = state.sending,
				isRunning = state.isRunning,
				onPickImage = {
					photoPickerLauncher.launch(
						PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
					)
				},
				onClearImage = {
					selectedImageUri = null
					selectedImageDataUrl = null
				},
				onSend = handleSend,
				onStop = controller::stopCurrentSession
			)
		}
	}

	if (sessionSheetOpen) {
		SessionManagerBottomSheet(
			sessions = state.sessions,
			currentSessionId = currentSession?.id,
			hostNavBottom = navBottomDp,
			onSelectSession = { id ->
				controller.selectSession(id)
				onSessionSheetOpenChange(false)
			},
			onCreateSession = {
				controller.createSession()
				onSessionSheetOpenChange(false)
			},
			onDeleteSession = controller::deleteSession,
			onDismissRequest = { onSessionSheetOpenChange(false) }
		)
	}
}

private data class AiTurnBlock(
	val key: String,
	val turnId: String,
	val userMessage: MeloAgentMessage?,
	val assistantMessages: List<MeloAgentMessage>,
	val events: List<MeloAgentEvent>,
	val isLatest: Boolean
)

private fun buildAiTurnBlocks(session: MeloAgentSession?): List<AiTurnBlock> {
	if (session == null) return emptyList()
	val turnMeta = mutableListOf<Pair<String, MeloAgentMessage?>>()
	val turnAssistants = mutableListOf<MutableList<MeloAgentMessage>>()
	val turnEvents = mutableListOf<MutableList<MeloAgentEvent>>()

	session.messages.forEachIndexed { idx, msg ->
		if (msg.role.lowercase() == "user") {
			val tid = msg.turnId.ifBlank { "turn-$idx" }
			turnMeta.add(tid to msg)
			turnAssistants.add(mutableListOf())
			turnEvents.add(mutableListOf())
		} else {
			val lastIdx = turnMeta.lastIndex
			if (lastIdx >= 0 && (msg.turnId.isBlank() || turnMeta[lastIdx].first == msg.turnId)) {
				turnAssistants[lastIdx].add(msg)
			} else {
				val tid = msg.turnId.ifBlank { "assistant-$idx" }
				turnMeta.add(tid to null)
				turnAssistants.add(mutableListOf(msg))
				turnEvents.add(mutableListOf())
			}
		}
	}

	val indexByTurnId = mutableMapOf<String, Int>()
	turnMeta.forEachIndexed { index, (tid, _) ->
		if (tid.isNotBlank() && tid !in indexByTurnId) {
			indexByTurnId[tid] = index
		}
	}

	val unassignedEvents = mutableListOf<MeloAgentEvent>()
	session.events.forEach { ev ->
		val targetIdx = if (ev.turnId.isNotBlank()) indexByTurnId[ev.turnId] else null
		if (targetIdx != null) {
			turnEvents[targetIdx].add(ev)
		} else if (turnMeta.isNotEmpty() && turnMeta.count { it.second != null } <= 1) {
			turnEvents.last().add(ev)
		} else {
			unassignedEvents.add(ev)
		}
	}

	if (unassignedEvents.isNotEmpty()) {
		turnMeta.add("tail-events-${unassignedEvents.first().id}" to null)
		turnAssistants.add(mutableListOf())
		turnEvents.add(unassignedEvents)
	}

	return turnMeta.indices.map { idx ->
		val (tid, userMsg) = turnMeta[idx]
		AiTurnBlock(
			key = "turn-$idx-$tid",
			turnId = tid,
			userMessage = userMsg,
			assistantMessages = turnAssistants[idx],
			events = turnEvents[idx],
			isLatest = idx == turnMeta.lastIndex
		)
	}
}

@Composable
private fun AiTurnSection(
	turn: AiTurnBlock,
	plan: MeloAgentPlan?,
	isRunning: Boolean,
	apiClient: MeloKeepApiClient,
	recentSearchTracks: List<MeloTrack>,
	enqueuedTrackIds: Set<String>,
	currentSongId: String?,
	isPlayerPaused: Boolean,
	onPlayTrack: (MeloTrack, List<MeloTrack>) -> Unit,
	onTogglePlayPause: () -> Unit,
	onDownloadTrack: (MeloTrack) -> Unit,
	onSearchInDiscover: (String) -> Unit
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		turn.userMessage?.let { userMsg ->
			AiMessageBubble(message = userMsg)
		}

		// 可折叠轨迹胶囊：收纳 Agent 的 events 与 plan，默认收起
		if (turn.events.isNotEmpty() || (plan != null && plan.steps.isNotEmpty())) {
			AiTrajectoryCapsule(
				turnKey = turn.key,
				events = turn.events,
				plan = plan,
				isRunning = isRunning
			)
		}

		turn.assistantMessages.forEach { assistantMsg ->
			AiMessageBubble(message = assistantMsg)
		}

		// 音乐微卡片化：提取工具返回曲目 + 消息关联曲目推荐
		val extractedTracks = remember(turn.events, turn.assistantMessages, recentSearchTracks) {
			resolveTurnMusicMatches(
				events = turn.events,
				assistantMessages = turn.assistantMessages,
				recentSearchTracks = recentSearchTracks,
				apiClient = apiClient
			)
		}
		val textSuggestions = remember(turn.assistantMessages, extractedTracks) {
			if (extractedTracks.isNotEmpty()) {
				emptyList()
			} else {
				extractSongSuggestionsFromMessages(turn.assistantMessages)
			}
		}

		if (extractedTracks.isNotEmpty()) {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				MeloSectionLabel(title = "关联曲目 · ${extractedTracks.size}")
				LazyRow(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					contentPadding = PaddingValues(horizontal = 2.dp)
				) {
					items(extractedTracks.take(12), key = { "ai-track-${turn.key}-${it.id}" }) { track ->
						val songId = "${MeloTrackMapper.ID_PREFIX}${track.id}"
						val isCurrent = currentSongId == songId
						AiMusicMicroCard(
							track = track,
							isCurrent = isCurrent,
							isPlaying = isCurrent && !isPlayerPaused,
							isEnqueued = track.id in enqueuedTrackIds,
							onPlayClick = {
								if (isCurrent) onTogglePlayPause() else onPlayTrack(track, extractedTracks)
							},
							onDownloadClick = { onDownloadTrack(track) }
						)
					}
				}
			}
		} else if (textSuggestions.isNotEmpty()) {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				MeloSectionLabel(title = "推荐搜索")
				LazyRow(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					contentPadding = PaddingValues(horizontal = 2.dp)
				) {
					items(textSuggestions, key = { "ai-suggest-${turn.key}-$it" }) { query ->
						AiSuggestedSearchChip(
							query = query,
							onClick = { onSearchInDiscover(query) }
						)
					}
				}
			}
		}
	}
}

@Composable
private fun AiTrajectoryCapsule(
	turnKey: String,
	events: List<MeloAgentEvent>,
	plan: MeloAgentPlan?,
	isRunning: Boolean
) {
	var expanded by rememberSaveable(turnKey) { mutableStateOf(false) }
	val arrowScale by animateFloatAsState(
		targetValue = if (expanded) -1f else 1f,
		animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
	)

	val stepCount = maxOf(events.size, plan?.steps?.size ?: 0)
	val runningEvent = events.lastOrNull { it.status.lowercase() in setOf("running", "waiting_tasks") }
	val hasFailure = events.any { it.status.lowercase() in setOf("failed", "error", "operation_unknown") }
	val headerLabel = when {
		runningEvent != null -> "执行中 · ${AI_TOOL_LABELS[runningEvent.tool] ?: runningEvent.tool.ifBlank { "处理中" }}"
		isRunning -> "分析中…"
		events.isNotEmpty() -> "已执行 ${events.size} 步"
		plan != null -> "计划 · ${plan.steps.size} 步"
		else -> "执行轨迹"
	}

	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(12.dp))
			.clickable { expanded = !expanded },
		shape = RoundedCornerShape(12.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = if (hasFailure) {
				MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
			}
		)
	) {
		Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				when {
					runningEvent != null || isRunning -> {
						CircularProgressIndicator(
							modifier = Modifier.size(13.dp),
							strokeWidth = 1.8.dp
						)
					}
					hasFailure -> {
						Box(
							modifier = Modifier
								.size(6.dp)
								.background(MaterialTheme.colorScheme.error, CircleShape)
						)
					}
					else -> {
						Icon(
							imageVector = MeloIcons.Sparkles,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.primary,
							modifier = Modifier.size(14.dp)
						)
					}
				}
				Text(
					text = headerLabel,
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f)
				)
				if (stepCount > 0) {
					Text(
						text = "$stepCount 步",
						style = MaterialTheme.typography.labelSmall.tabularNums(),
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
				Icon(
					imageVector = Icons.Outlined.KeyboardArrowDown,
					contentDescription = "展开执行轨迹",
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.size(16.dp)
						.scale(scaleX = 1f, scaleY = arrowScale)
				)
			}

			AnimatedVisibility(visible = expanded) {
				Column(
					modifier = Modifier.padding(top = 8.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp)
				) {
					MeloHairlineDivider()

					if (plan != null && plan.steps.isNotEmpty()) {
						Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
							if (plan.goal.isNotBlank()) {
								Text(
									text = "目标：${plan.goal}",
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.primary
								)
							}
							plan.steps.forEach { step ->
								val done = step.status.lowercase() in setOf("complete", "completed", "done")
								val running = step.status.lowercase() == "running"
								Row(
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(6.dp)
								) {
									when {
										running -> CircularProgressIndicator(
											modifier = Modifier.size(11.dp),
											strokeWidth = 1.6.dp
										)
										done -> Icon(
											imageVector = Icons.Outlined.Check,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.positive,
											modifier = Modifier.size(13.dp)
										)
										else -> Box(
											modifier = Modifier
												.size(6.dp)
												.background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
										)
									}
									Text(
										text = step.title,
										style = MaterialTheme.typography.bodySmall,
										color = if (done) {
											MaterialTheme.colorScheme.onSurfaceVariant
										} else {
											MaterialTheme.colorScheme.onSurface
										}
									)
								}
							}
						}
					}

					events.forEachIndexed { idx, event ->
						if (idx > 0) {
							MeloHairlineDivider()
						}
						TrajectoryEventStepRow(
							index = idx + 1,
							event = event
						)
					}
				}
			}
		}
	}
}

@Composable
private fun TrajectoryEventStepRow(
	index: Int,
	event: MeloAgentEvent
) {
	var showRawJson by rememberSaveable(event.id, event.turnId, index) { mutableStateOf(false) }
	val statusLower = event.status.lowercase()
	val statusColor = when (statusLower) {
		"complete", "completed" -> MaterialTheme.colorScheme.positive
		"failed", "error", "operation_unknown" -> MaterialTheme.colorScheme.error
		else -> MaterialTheme.colorScheme.primary
	}
	val toolTitle = AI_TOOL_LABELS[event.tool]
		?: listOfNotNull(
			event.tool.takeIf { it.isNotBlank() },
			event.operation.takeIf { it.isNotBlank() }
		).joinToString(" · ").ifBlank { "工具执行" }

	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Box(
				modifier = Modifier
					.size(6.dp)
					.background(statusColor, CircleShape)
			)
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = "$index. $toolTitle",
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				if (event.detail.isNotBlank()) {
					Text(
						text = event.detail,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis
					)
				}
			}
			Text(
				text = formatMeloStatusLabel(event.status),
				style = MaterialTheme.typography.labelSmall,
				color = statusColor
			)
		}

		if (event.result != null) {
			Text(
				text = if (showRawJson) "收起输出" else "原始输出",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.primary,
				modifier = Modifier
					.padding(start = 14.dp)
					.clip(CircleShape)
					.clickable { showRawJson = !showRawJson }
					.padding(horizontal = 4.dp, vertical = 2.dp)
			)
			AnimatedVisibility(visible = showRawJson) {
				val pretty = remember(event.result) {
					runCatching {
						prettyAgentJson.encodeToString(JsonObject.serializer(), event.result!!)
					}.getOrDefault(event.result.toString())
				}
				Surface(
					shape = RoundedCornerShape(10.dp),
					color = MaterialTheme.colorScheme.surfaceContainerLow,
					border = BorderStroke(
						width = MeloHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
					)
				) {
					Text(
						text = pretty,
						style = MaterialTheme.typography.bodySmall.copy(
							fontFamily = FontFamily.Monospace
						),
						modifier = Modifier
							.fillMaxWidth()
							.padding(10.dp)
					)
				}
			}
		}
	}
}

@Composable
private fun AiMusicMicroCard(
	track: MeloTrack,
	isCurrent: Boolean,
	isPlaying: Boolean,
	isEnqueued: Boolean,
	onPlayClick: () -> Unit,
	onDownloadClick: () -> Unit
) {
	val qualityBadge = remember(track.id, track.qualities, track.playback) {
		extractTrackQualityBadge(track)
	}
	val metaLine = remember(track.id, track.artist, track.source, qualityBadge) {
		listOfNotNull(
			track.artist.takeIf { it.isNotBlank() },
			track.source.takeIf { it.isNotBlank() }?.let(::translateMeloSourceName),
			qualityBadge?.first?.takeIf { it.isNotBlank() }
		).joinToString(" · ").ifBlank { "全网音源" }
	}

	Surface(
		modifier = Modifier.width(264.dp),
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = if (isCurrent) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
			}
		)
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable(onClick = onPlayClick)
				.padding(10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp)
		) {
			Box(contentAlignment = Alignment.Center) {
				CoverArt(
					coverArtId = track.cover,
					modifier = Modifier.size(42.dp),
					shape = RoundedCornerShape(9.dp)
				)
				if (isCurrent) {
					Box(
						modifier = Modifier
							.size(42.dp)
							.clip(RoundedCornerShape(9.dp))
							.background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
						contentAlignment = Alignment.Center
					) {
						Waveform(isPlaying = isPlaying)
					}
				}
			}

			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp)
			) {
				MarqueeText(
					text = track.title.ifBlank { "未知曲目" },
					style = MaterialTheme.typography.labelLarge.copy(
						fontWeight = FontWeight.Medium,
						color = if (isCurrent) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.onSurface
						}
					)
				)
				Text(
					text = metaLine,
					style = MaterialTheme.typography.bodySmall.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}

			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(2.dp)
			) {
				IconButton(
					onClick = onPlayClick,
					modifier = Modifier.size(32.dp)
				) {
					Icon(
						imageVector = if (isPlaying) MeloIcons.Pause else MeloIcons.PlayArrow,
						contentDescription = if (isPlaying) "暂停试听" else "试听",
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(18.dp)
					)
				}
				IconButton(
					onClick = onDownloadClick,
					modifier = Modifier.size(32.dp)
				) {
					Icon(
						imageVector = if (isEnqueued) Icons.Outlined.Check else Icons.Outlined.Download,
						contentDescription = if (isEnqueued) "已入库" else "下载入库",
						tint = if (isEnqueued) {
							MaterialTheme.colorScheme.positive
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						modifier = Modifier.size(17.dp)
					)
				}
			}
		}
	}
}

@Composable
private fun AiSuggestedSearchChip(
	query: String,
	onClick: () -> Unit
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
		)
	) {
		Row(
			modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(5.dp)
		) {
			Icon(
				imageVector = Icons.Outlined.Search,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(14.dp)
			)
			Text(
				text = query,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.widthIn(max = 180.dp)
			)
		}
	}
}

private fun resolveTurnMusicMatches(
	events: List<MeloAgentEvent>,
	assistantMessages: List<MeloAgentMessage>,
	recentSearchTracks: List<MeloTrack>,
	apiClient: MeloKeepApiClient
): List<MeloTrack> {
	val collected = LinkedHashMap<String, MeloTrack>()

	fun addTrack(raw: MeloTrack) {
		if (raw.id.isBlank() || raw.title.isBlank()) return
		val resolved = raw.copy(
			cover = apiClient.resolveMediaUrl(raw.cover),
			playback = raw.playback.copy(url = apiClient.resolveMediaUrl(raw.playback.url))
		)
		collected.putIfAbsent(resolved.id, resolved)
	}

	events.forEach { ev ->
		val rootObj = ev.result ?: return@forEach
		val dataObj = (rootObj["data"] as? JsonObject) ?: rootObj
		val trackArrays = listOfNotNull(
			dataObj["tracks"] as? JsonArray,
			rootObj["tracks"] as? JsonArray
		)
		trackArrays.forEach { arr ->
			arr.forEach { el ->
				val obj = el as? JsonObject ?: return@forEach
				runCatching { aiJson.decodeFromJsonElement(MeloTrack.serializer(), obj) }
					.getOrNull()
					?.let(::addTrack)
			}
		}
		val downloadsArr = (dataObj["downloads"] as? JsonArray) ?: (rootObj["downloads"] as? JsonArray)
		downloadsArr?.forEach { el ->
			val trackObj = (el as? JsonObject)?.get("track") as? JsonObject ?: return@forEach
			runCatching { aiJson.decodeFromJsonElement(MeloTrack.serializer(), trackObj) }
				.getOrNull()
				?.let(::addTrack)
		}
	}

	if (collected.isEmpty() && recentSearchTracks.isNotEmpty() && assistantMessages.isNotEmpty()) {
		val fullText = assistantMessages.joinToString("\n") { it.content }
		if (fullText.isNotBlank()) {
			val lowerText = fullText.lowercase()
			recentSearchTracks.forEach { track ->
				val t = track.title.trim().lowercase()
				if (t.length >= 2 && t in lowerText) {
					addTrack(track)
				}
			}
		}
	}

	return collected.values.toList()
}

private fun extractSongSuggestionsFromMessages(messages: List<MeloAgentMessage>): List<String> {
	if (messages.isEmpty()) return emptyList()
	val text = messages.joinToString("\n") { it.content }
	val results = LinkedHashSet<String>()
	BOOK_TITLE_REGEX.findAll(text).forEach { match ->
		val title = match.groupValues[1].trim()
		if (title.length in 2..30) results.add(title)
	}
	if (results.isEmpty()) {
		DASH_TRACK_REGEX.findAll(text).forEach { match ->
			val title = match.groupValues[1].trim()
			val artist = match.groupValues[2].trim()
			if (title.length in 2..28 && artist.isNotBlank() && !title.startsWith("#")) {
				results.add("$title $artist")
			}
		}
	}
	return results.take(4)
}

@Composable
private fun SessionSwitcherStrip(
	sessions: List<MeloAgentSessionSummary>,
	currentSessionId: String?,
	onSelect: (String) -> Unit,
	onNewSession: () -> Unit,
	onOpenManager: () -> Unit
) {
	LazyRow(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 2.dp, bottom = 6.dp),
		contentPadding = PaddingValues(horizontal = 16.dp),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		items(sessions, key = { it.id }) { item ->
			val selected = item.id == currentSessionId
			AiSessionPill(
				label = item.title.ifBlank { "新对话" },
				selected = selected,
				onClick = { onSelect(item.id) }
			)
		}
		item(key = "new-session-chip") {
			AiSessionPill(
				label = "新会话",
				selected = false,
				leadingIcon = Icons.Outlined.Add,
				onClick = onNewSession
			)
		}
		item(key = "manage-sessions-chip") {
			AiSessionPill(
				label = "全部",
				selected = false,
				leadingIcon = Icons.Outlined.Forum,
				onClick = onOpenManager
			)
		}
	}
}

@Composable
private fun AiSessionPill(
	label: String,
	selected: Boolean,
	leadingIcon: ImageVector? = null,
	onClick: () -> Unit
) {
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = if (selected) {
			MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
		} else {
			MaterialTheme.colorScheme.surfaceContainer
		},
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = if (selected) {
				MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
			} else {
				MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
			}
		)
	) {
		Row(
			modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp)
		) {
			if (leadingIcon != null) {
				Icon(
					imageVector = leadingIcon,
					contentDescription = null,
					tint = if (selected) {
						MaterialTheme.colorScheme.primary
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					modifier = Modifier.size(13.dp)
				)
			}
			Text(
				text = label,
				style = MaterialTheme.typography.labelMedium.copy(
					fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
				),
				color = if (selected) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.widthIn(max = 136.dp)
			)
		}
	}
}

@Composable
private fun AiEmptyPromptView(
	modifier: Modifier = Modifier,
	loading: Boolean,
	onPickPrompt: (String) -> Unit,
	onPickImage: () -> Unit
) {
	Box(
		modifier = modifier.fillMaxWidth(),
		contentAlignment = Alignment.Center
	) {
		if (loading) {
			CircularProgressIndicator(
				modifier = Modifier.size(24.dp),
				strokeWidth = 2.dp
			)
		} else {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp, vertical = 12.dp),
				verticalArrangement = Arrangement.spacedBy(16.dp)
			) {
				MeloQuietEmptyState(
					message = "文字指令或传图识别歌单",
					icon = MeloIcons.Sparkles,
					actionLabel = "相册选图",
					onAction = onPickImage
				)

				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					MeloSectionLabel(title = "快捷提问")
					MeloGroupedCard {
						AI_QUICK_PROMPTS.forEachIndexed { index, prompt ->
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.clickable { onPickPrompt(prompt) }
									.padding(horizontal = 14.dp, vertical = 12.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(10.dp)
							) {
								Icon(
									imageVector = MeloIcons.Sparkles,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(15.dp)
								)
								Text(
									text = prompt,
									style = MaterialTheme.typography.bodyMedium,
									color = MaterialTheme.colorScheme.onSurface,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
									modifier = Modifier.weight(1f)
								)
							}
							if (index < AI_QUICK_PROMPTS.lastIndex) {
								MeloHairlineDivider(startIndent = 39.dp)
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun AiMessageBubble(message: MeloAgentMessage) {
	val isUser = message.role.lowercase() == "user"
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
	) {
		Surface(
			modifier = Modifier.widthIn(max = 324.dp),
			shape = if (isUser) {
				RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
			} else {
				RoundedCornerShape(14.dp)
			},
			color = if (isUser) {
				MaterialTheme.colorScheme.primaryContainer
			} else {
				MaterialTheme.colorScheme.surfaceContainer
			},
			contentColor = if (isUser) {
				MaterialTheme.colorScheme.onPrimaryContainer
			} else {
				MaterialTheme.colorScheme.onSurface
			},
			border = if (isUser) {
				null
			} else {
				BorderStroke(
					width = MeloHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
				)
			}
		) {
			Column(
				modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp)
			) {
				if (message.hasImage) {
					Surface(
						shape = CircleShape,
						color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
						border = BorderStroke(
							width = MeloHairlineWidth,
							color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
						)
					) {
						Row(
							modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp)
						) {
							Icon(MeloIcons.Photo, null, modifier = Modifier.size(12.dp))
							Text("歌单图片", style = MaterialTheme.typography.labelSmall)
						}
					}
				}
				if (message.content.isNotBlank()) {
					if (isUser) {
						Text(
							text = message.content,
							style = MaterialTheme.typography.bodyMedium
						)
					} else {
						Markdown(text = message.content)
					}
				}
			}
		}
	}
}

@Composable
private fun StreamingPartialBubble(
	partial: String,
	detail: String,
	status: String,
	onStop: () -> Unit
) {
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		border = BorderStroke(
			width = MeloHairlineWidth,
			color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
		)
	) {
		Column(
			modifier = Modifier.padding(13.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.8.dp)
					Text(
						text = detail.ifBlank { formatMeloStatusLabel(status) },
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.primary
					)
				}
				Row(
					modifier = Modifier
						.clip(CircleShape)
						.clickable(onClick = onStop)
						.padding(horizontal = 6.dp, vertical = 3.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(3.dp)
				) {
					Icon(
						imageVector = MeloIcons.Stop,
						contentDescription = null,
						modifier = Modifier.size(14.dp),
						tint = MaterialTheme.colorScheme.error
					)
					Text(
						text = "停止",
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.error
					)
				}
			}
			if (partial.isNotBlank()) {
				Markdown(text = partial)
			}
		}
	}
}

@Composable
private fun AiInputDock(
	inputText: String,
	onInputChange: (String) -> Unit,
	selectedImageUri: Uri?,
	encodingImage: Boolean,
	sending: Boolean,
	isRunning: Boolean,
	onPickImage: () -> Unit,
	onClearImage: () -> Unit,
	onSend: () -> Unit,
	onStop: () -> Unit
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 14.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp)
	) {
		AnimatedVisibility(visible = selectedImageUri != null) {
			Surface(
				shape = RoundedCornerShape(12.dp),
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = MeloHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
				)
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(8.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(10.dp)
				) {
					val imageLoader = koinInject<ImageLoader>()
					AsyncImage(
						model = selectedImageUri,
						imageLoader = imageLoader,
						contentDescription = "已选歌单图片",
						contentScale = ContentScale.Crop,
						modifier = Modifier
							.size(40.dp)
							.clip(RoundedCornerShape(8.dp))
					)
					Text(
						text = if (encodingImage) "正在处理图片…" else "已附加歌单图片",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.weight(1f)
					)
					IconButton(
						onClick = onClearImage,
						modifier = Modifier.size(28.dp)
					) {
						Icon(
							imageVector = Icons.Outlined.Close,
							contentDescription = "移除图片",
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(16.dp)
						)
					}
				}
			}
		}

		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.Bottom,
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Surface(
				modifier = Modifier.weight(1f),
				shape = RoundedCornerShape(22.dp),
				color = MaterialTheme.colorScheme.surfaceContainer,
				border = BorderStroke(
					width = MeloHairlineWidth,
					color = MaterialTheme.colorScheme.outline.copy(alpha = 0.40f)
				),
				tonalElevation = 0.dp
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 4.dp, end = 14.dp, top = 3.dp, bottom = 3.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					IconButton(
						onClick = onPickImage,
						enabled = !encodingImage && !sending,
						modifier = Modifier.size(38.dp)
					) {
						if (encodingImage) {
							CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.8.dp)
						} else {
							Icon(
								imageVector = MeloIcons.Photo,
								contentDescription = "从相册选择歌单截图",
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.size(20.dp)
							)
						}
					}
					Box(
						modifier = Modifier
							.weight(1f)
							.heightIn(min = 38.dp, max = 104.dp)
							.padding(vertical = 6.dp),
						contentAlignment = Alignment.CenterStart
					) {
						if (inputText.isEmpty()) {
							Text(
								text = "输入指令或附歌单截图…",
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
						}
						BasicTextField(
							value = inputText,
							onValueChange = onInputChange,
							maxLines = 4,
							textStyle = TextStyle(
								color = MaterialTheme.colorScheme.onSurface,
								fontFamily = defaultFont(),
								fontSize = MaterialTheme.typography.bodyMedium.fontSize
							),
							cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
							keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
							keyboardActions = KeyboardActions(onSend = { onSend() }),
							modifier = Modifier.fillMaxWidth()
						)
					}
				}
			}

			if (isRunning) {
				FilledTonalIconButton(
					onClick = onStop,
					colors = IconButtonDefaults.filledTonalIconButtonColors(
						containerColor = MaterialTheme.colorScheme.errorContainer,
						contentColor = MaterialTheme.colorScheme.onErrorContainer
					),
					modifier = Modifier.size(44.dp)
				) {
					Icon(
						imageVector = MeloIcons.Stop,
						contentDescription = "停止生成",
						modifier = Modifier.size(20.dp)
					)
				}
			} else {
				val canSend = !encodingImage && !sending &&
					(inputText.isNotBlank() || selectedImageUri != null)
				FilledTonalIconButton(
					onClick = onSend,
					enabled = canSend,
					colors = IconButtonDefaults.filledTonalIconButtonColors(
						containerColor = MaterialTheme.colorScheme.primary,
						contentColor = MaterialTheme.colorScheme.onPrimary,
						disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
						disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
					),
					modifier = Modifier.size(44.dp)
				) {
					if (sending) {
						CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
					} else {
						Icon(
							imageVector = Icons.Outlined.Send,
							contentDescription = "发送",
							modifier = Modifier.size(19.dp)
						)
					}
				}
			}
		}
	}
}

@Composable
internal fun SessionManagerBottomSheet(
	sessions: List<MeloAgentSessionSummary>,
	currentSessionId: String?,
	hostNavBottom: Dp = 16.dp,
	onSelectSession: (String) -> Unit,
	onCreateSession: () -> Unit,
	onDeleteSession: (String) -> Unit,
	onDismissRequest: () -> Unit
) {
	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = rememberBottomSheetState(
			initialValue = SheetValue.Hidden,
			enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
		),
		sheetTitle = "AI 对话会话"
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp)
				.padding(bottom = maxOf(hostNavBottom, 16.dp) + 16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp)
		) {
			MeloSectionLabel(
				title = "共 ${sessions.size} 个会话",
				trailing = {
					Surface(
						onClick = onCreateSession,
						shape = CircleShape,
						color = MaterialTheme.colorScheme.primary
					) {
						Row(
							modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(4.dp)
						) {
							Icon(
								imageVector = Icons.Outlined.Add,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.onPrimary,
								modifier = Modifier.size(14.dp)
							)
							Text(
								text = "新建会话",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.onPrimary
							)
						}
					}
				}
			)

			if (sessions.isEmpty()) {
				MeloQuietEmptyState(
					message = "暂无对话记录",
					icon = Icons.Outlined.Forum
				)
			} else {
				Surface(
					modifier = Modifier.fillMaxWidth(),
					shape = RoundedCornerShape(14.dp),
					color = MaterialTheme.colorScheme.surfaceContainer,
					border = BorderStroke(
						width = MeloHairlineWidth,
						color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
					)
				) {
					LazyColumn(
						modifier = Modifier
							.fillMaxWidth()
							.heightIn(max = 360.dp)
					) {
						itemsIndexed(sessions, key = { _, s -> s.id }) { idx, session ->
							val isCurrent = session.id == currentSessionId
							Column(modifier = Modifier.fillMaxWidth()) {
								Row(
									modifier = Modifier
										.fillMaxWidth()
										.then(
											if (isCurrent) {
												Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
											} else {
												Modifier
											}
										)
										.clickable { onSelectSession(session.id) }
										.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(10.dp)
								) {
									if (isCurrent) {
										Box(
											modifier = Modifier
												.size(6.dp)
												.background(MaterialTheme.colorScheme.primary, CircleShape)
										)
									}
									Column(
										modifier = Modifier.weight(1f),
										verticalArrangement = Arrangement.spacedBy(2.dp)
									) {
										Text(
											text = session.title.ifBlank { "未命名对话" },
											style = MaterialTheme.typography.bodyMedium,
											fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
											color = if (isCurrent) {
												MaterialTheme.colorScheme.primary
											} else {
												MaterialTheme.colorScheme.onSurface
											},
											maxLines = 1,
											overflow = TextOverflow.Ellipsis
										)
										Text(
											text = formatMeloStatusLabel(session.status),
											style = MaterialTheme.typography.bodySmall,
											color = MaterialTheme.colorScheme.onSurfaceVariant,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis
										)
									}
									IconButton(
										onClick = { onDeleteSession(session.id) },
										modifier = Modifier.size(34.dp)
									) {
										Icon(
											imageVector = Icons.Outlined.Delete,
											contentDescription = "删除会话",
											tint = MaterialTheme.colorScheme.error,
											modifier = Modifier.size(18.dp)
										)
									}
								}
								if (idx < sessions.lastIndex) {
									MeloHairlineDivider(startIndent = 14.dp)
								}
							}
						}
					}
				}
			}
		}
	}
}

private suspend fun encodeImageUriToDataUrl(
	context: Context,
	uri: Uri,
	maxDimension: Int = 1600
): String? = withContext(Dispatchers.IO) {
	runCatching {
		val resolver = context.contentResolver
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		resolver.openInputStream(uri)?.use { input ->
			BitmapFactory.decodeStream(input, null, bounds)
		}
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

		val maxSide = max(bounds.outWidth, bounds.outHeight)
		var sampleSize = 1
		while (maxSide / sampleSize > maxDimension * 2) {
			sampleSize *= 2
		}

		val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
		val decoded = resolver.openInputStream(uri)?.use { input ->
			BitmapFactory.decodeStream(input, null, decodeOpts)
		} ?: return@runCatching null

		val scaled = if (max(decoded.width, decoded.height) > maxDimension) {
			val ratio = maxDimension.toFloat() / max(decoded.width, decoded.height).toFloat()
			val targetW = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
			val targetH = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
			Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
				if (it !== decoded) decoded.recycle()
			}
		} else {
			decoded
		}

		val bytes = ByteArrayOutputStream().use { out ->
			scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
			var data = out.toByteArray()
			if (data.size > 1_500_000) {
				out.reset()
				scaled.compress(Bitmap.CompressFormat.JPEG, 68, out)
				data = out.toByteArray()
			}
			data
		}
		scaled.recycle()

		val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
		"data:image/jpeg;base64,$b64"
	}.getOrNull()
}
