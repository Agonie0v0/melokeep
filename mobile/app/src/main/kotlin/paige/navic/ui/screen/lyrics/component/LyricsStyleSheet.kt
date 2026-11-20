package paige.navic.ui.screen.lyrics.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.theme.tabularNums
import kotlin.math.roundToInt

private data class LyricsColorPreset(
	val label: String,
	val hex: String,
	val previewColor: Color
)

private val LyricsActiveColorPresets = listOf(
	LyricsColorPreset("默认白", "", Color(0xFFFFFFFF)),
	LyricsColorPreset("暖金", "#FFE082", Color(0xFFFFE082)),
	LyricsColorPreset("青蓝", "#80DEEA", Color(0xFF80DEEA)),
	LyricsColorPreset("樱粉", "#F48FB1", Color(0xFFF48FB1)),
	LyricsColorPreset("薄荷", "#A5D6A7", Color(0xFFA5D6A7)),
	LyricsColorPreset("浅紫", "#CE93D8", Color(0xFFCE93D8))
)

internal const val DEFAULT_LYRICS_FONT_SIZE_SP = 28f
internal const val DEFAULT_LYRICS_ACTIVE_COLOR_HEX = ""
internal const val DEFAULT_LYRICS_INACTIVE_ALPHA = 0.45f
internal const val DEFAULT_LYRICS_TEXT_ALIGN = "start"

fun parseLyricsHexColor(rawHex: String): Color? {
	val cleaned = rawHex.trim().removePrefix("#")
	if (cleaned.isEmpty()) return null
	val argb = when (cleaned.length) {
		6 -> {
			val rgb = cleaned.toLongOrNull(16) ?: return null
			0xFF000000L or rgb
		}
		8 -> cleaned.toLongOrNull(16) ?: return null
		else -> return null
	}
	return Color(argb.toInt())
}

fun normalizeLyricsHexInput(raw: String): String? {
	val cleaned = raw.trim().removePrefix("#").uppercase()
	if (cleaned.isEmpty()) return ""
	if ((cleaned.length == 6 || cleaned.length == 8) && cleaned.all { it in '0'..'9' || it in 'A'..'F' }) {
		return "#$cleaned"
	}
	return null
}

fun resetLyricsStylePreferences(preferenceManager: PreferenceManager) {
	preferenceManager.lyricsFontSizeSp = DEFAULT_LYRICS_FONT_SIZE_SP
	preferenceManager.lyricsActiveColorHex = DEFAULT_LYRICS_ACTIVE_COLOR_HEX
	preferenceManager.lyricsInactiveAlpha = DEFAULT_LYRICS_INACTIVE_ALPHA
	preferenceManager.lyricsTextAlign = DEFAULT_LYRICS_TEXT_ALIGN
}

@Composable
fun LyricsStyleCustomizationControls(
	preferenceManager: PreferenceManager,
	showResetButton: Boolean = true,
	modifier: Modifier = Modifier
) {
	val fontSizeSp = preferenceManager.lyricsFontSizeSp.coerceIn(18f, 40f)
	val activeColorHex = preferenceManager.lyricsActiveColorHex
	val inactiveAlpha = preferenceManager.lyricsInactiveAlpha.coerceIn(0.2f, 0.85f)
	val textAlign = if (preferenceManager.lyricsTextAlign == "center") "center" else "start"

	var hexInput by remember { mutableStateOf(activeColorHex) }
	LaunchedEffect(activeColorHex) {
		if (hexInput.trim().uppercase() != activeColorHex.trim().uppercase()) {
			hexInput = activeColorHex
		}
	}

	val customParsedColor = remember(hexInput) { parseLyricsHexColor(hexInput) }
	val isHexInvalid = hexInput.isNotBlank() && customParsedColor == null

	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(14.dp)
	) {
		// 1. 字号大小 (18..40 sp)
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = "歌词字号",
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium
				)
				Text(
					text = "${fontSizeSp.roundToInt()} sp",
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = MaterialTheme.colorScheme.primary
				)
			}
			Slider(
				value = fontSizeSp,
				onValueChange = { value ->
					preferenceManager.lyricsFontSizeSp = value.coerceIn(18f, 40f)
				},
				valueRange = 18f..40f
			)
		}

		// 2. 非高亮行透明度 (0.20..0.85)
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = "非高亮透明度",
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium
				)
				Text(
					text = "${(inactiveAlpha * 100).roundToInt()}%",
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = MaterialTheme.colorScheme.primary
				)
			}
			Slider(
				value = inactiveAlpha,
				onValueChange = { value ->
					preferenceManager.lyricsInactiveAlpha = value.coerceIn(0.2f, 0.85f)
				},
				valueRange = 0.2f..0.85f
			)
		}

		// 3. 对齐方式
		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(
				text = "对齐方式",
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium
			)
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				listOf("start" to "左对齐", "center" to "居中对齐").forEach { (key, label) ->
					val selected = textAlign == key
					Surface(
						onClick = { preferenceManager.lyricsTextAlign = key },
						shape = RoundedCornerShape(10.dp),
						color = if (selected) {
							MaterialTheme.colorScheme.primaryContainer
						} else {
							MaterialTheme.colorScheme.surfaceContainerHigh
						},
						border = if (selected) {
							BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
						} else null,
						modifier = Modifier.weight(1f)
					) {
						Box(
							modifier = Modifier.padding(vertical = 9.dp),
							contentAlignment = Alignment.Center
						) {
							Text(
								text = label,
								style = MaterialTheme.typography.labelMedium,
								fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
								color = if (selected) {
									MaterialTheme.colorScheme.onPrimaryContainer
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								}
							)
						}
					}
				}
			}
		}

		// 4. 高亮歌词颜色预设 + 自定义 Hex
		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = "高亮歌词颜色",
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium
				)
				Text(
					text = activeColorHex.ifBlank { "默认纯白" },
					style = MaterialTheme.typography.labelMedium.tabularNums(),
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(10.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				LyricsActiveColorPresets.forEach { preset ->
					val selected = activeColorHex.equals(preset.hex, ignoreCase = true)
					Column(
						horizontalAlignment = Alignment.CenterHorizontally,
						verticalArrangement = Arrangement.spacedBy(4.dp),
						modifier = Modifier.weight(1f)
					) {
						Box(
							modifier = Modifier
								.size(34.dp)
								.clip(CircleShape)
								.background(preset.previewColor)
								.border(
									width = if (selected) 2.5.dp else 1.dp,
									color = if (selected) {
										MaterialTheme.colorScheme.primary
									} else {
										MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
									},
									shape = CircleShape
								)
								.clickable {
									preferenceManager.lyricsActiveColorHex = preset.hex
									hexInput = preset.hex
								},
							contentAlignment = Alignment.Center
						) {
							if (selected) {
								Icon(
									imageVector = Icons.Outlined.Check,
									contentDescription = preset.label,
									tint = Color(0xFF0F172A),
									modifier = Modifier.size(16.dp)
								)
							}
						}
						Text(
							text = preset.label,
							style = MaterialTheme.typography.labelSmall,
							color = if (selected) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							},
							maxLines = 1
						)
					}
				}
			}

			OutlinedTextField(
				value = hexInput,
				onValueChange = { raw ->
					val filtered = raw.filter { it == '#' || it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
						.take(9)
					hexInput = filtered
					normalizeLyricsHexInput(filtered)?.let { normalized ->
						preferenceManager.lyricsActiveColorHex = normalized
					}
				},
				singleLine = true,
				label = { Text("自定义 Hex（留空为默认）") },
				placeholder = { Text("例如 #FFE082 或 #FF80DEEA") },
				leadingIcon = {
					Box(
						modifier = Modifier
							.size(20.dp)
							.clip(CircleShape)
							.background(parseLyricsHexColor(activeColorHex) ?: Color.White)
							.border(
								width = 1.dp,
								color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
								shape = CircleShape
							)
					)
				},
				isError = isHexInvalid,
				supportingText = if (isHexInvalid) {
					{ Text("请输入 6 位或 8 位十六进制颜色，如 #FFE082") }
				} else null,
				modifier = Modifier.fillMaxWidth()
			)
		}

		if (showResetButton) {
			OutlinedButton(
				onClick = {
					resetLyricsStylePreferences(preferenceManager)
					hexInput = DEFAULT_LYRICS_ACTIVE_COLOR_HEX
				},
				modifier = Modifier.fillMaxWidth()
			) {
				Text("恢复默认歌词样式")
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsStyleBottomSheet(
	presented: Boolean,
	onDismissRequest: () -> Unit
) {
	if (!presented) return
	val preferenceManager = koinInject<PreferenceManager>()
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.navigationBarsPadding()
				.padding(horizontal = 20.dp)
				.padding(bottom = 20.dp)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = "歌词样式",
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold
				)
				TextButton(
					onClick = { resetLyricsStylePreferences(preferenceManager) }
				) {
					Text("恢复默认")
				}
			}

			Spacer(Modifier.height(6.dp))

			LyricsStyleCustomizationControls(
				preferenceManager = preferenceManager,
				showResetButton = false,
				modifier = Modifier.fillMaxWidth()
			)
		}
	}
}
