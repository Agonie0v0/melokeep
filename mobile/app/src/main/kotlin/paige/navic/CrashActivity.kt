/*
 * Navic / MeloKeep, a Subsonic music streaming & studio app for Android
 * Copyright (c) 2026 paige & MeloKeep Swarm
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.SoundEffectConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import paige.navic.ui.icons.Icons
import paige.navic.ui.icons.outlined.Check
import paige.navic.ui.icons.outlined.Copy
import paige.navic.ui.icons.outlined.Error
import paige.navic.ui.icons.outlined.Refresh
import kotlin.system.exitProcess

private val MeloCrashDarkColorScheme = darkColorScheme(
	primary = Color(0xFF60A5FA),
	onPrimary = Color(0xFF06162B),
	primaryContainer = Color(0xFF1C3554),
	onPrimaryContainer = Color(0xFFDCEBFF),
	background = Color(0xFF090B10),
	onBackground = Color(0xFFF8FAFC),
	surface = Color(0xFF090B10),
	onSurface = Color(0xFFF8FAFC),
	surfaceVariant = Color(0xFF1A1F2D),
	onSurfaceVariant = Color(0xFF94A3B8),
	surfaceContainer = Color(0xFF12151E),
	surfaceContainerHigh = Color(0xFF1A1F2D),
	outline = Color(0xFF334155),
	outlineVariant = Color(0xFF232A3C),
	error = Color(0xFFF87171),
	onError = Color(0xFF3B0808),
	errorContainer = Color(0xFF451214),
	onErrorContainer = Color(0xFFFEE2E2)
)

private val MeloCrashLightColorScheme = lightColorScheme(
	primary = Color(0xFF2563EB),
	onPrimary = Color(0xFFFFFFFF),
	primaryContainer = Color(0xFFDBEAFE),
	onPrimaryContainer = Color(0xFF1E3A8A),
	background = Color(0xFFF4F6FA),
	onBackground = Color(0xFF0F172A),
	surface = Color(0xFFF4F6FA),
	onSurface = Color(0xFF0F172A),
	surfaceVariant = Color(0xFFEBEFF6),
	onSurfaceVariant = Color(0xFF52637A),
	surfaceContainer = Color(0xFFFFFFFF),
	surfaceContainerHigh = Color(0xFFEBEFF6),
	outline = Color(0xFFC5D0E0),
	outlineVariant = Color(0xFFDBE3EE),
	error = Color(0xFFDC2626),
	onError = Color(0xFFFFFFFF),
	errorContainer = Color(0xFFFEE2E2),
	onErrorContainer = Color(0xFF7F1D1D)
)

class CrashActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()

		val stackTrace = intent.getStringExtra("stacktrace")
			?.takeIf { it.isNotBlank() }
			?: "暂无可用堆栈日志"

		setContent {
			val view = LocalView.current
			val context = LocalContext.current
			val isDark = isSystemInDarkTheme()
			val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
			} else {
				if (isDark) MeloCrashDarkColorScheme else MeloCrashLightColorScheme
			}

			val exceptionSummary = remember(stackTrace) {
				stackTrace.lineSequence()
					.map { it.trim() }
					.firstOrNull { it.isNotEmpty() }
					?: "未捕获异常"
			}

			var copied by remember { mutableStateOf(false) }
			LaunchedEffect(copied) {
				if (copied) {
					delay(2000L)
					copied = false
				}
			}

			MaterialTheme(colorScheme = colorScheme) {
				Surface(
					modifier = Modifier.fillMaxSize(),
					color = MaterialTheme.colorScheme.background
				) {
					Column(
						modifier = Modifier
							.fillMaxSize()
							.statusBarsPadding()
							.padding(horizontal = 16.dp)
							.padding(top = 12.dp, bottom = 8.dp),
						verticalArrangement = Arrangement.spacedBy(12.dp)
					) {
						Surface(
							modifier = Modifier.fillMaxWidth(),
							shape = RoundedCornerShape(16.dp),
							color = MaterialTheme.colorScheme.surfaceContainer,
							border = BorderStroke(
								width = 0.5.dp,
								color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
							)
						) {
							Column(
								modifier = Modifier
									.fillMaxWidth()
									.padding(16.dp),
								verticalArrangement = Arrangement.spacedBy(10.dp)
							) {
								Row(
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(12.dp)
								) {
									Box(
										modifier = Modifier
											.size(40.dp)
											.background(
												color = MaterialTheme.colorScheme.errorContainer,
												shape = CircleShape
											),
										contentAlignment = Alignment.Center
									) {
										Icon(
											imageVector = Icons.Outlined.Error,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.onErrorContainer,
											modifier = Modifier.size(22.dp)
										)
									}
									Text(
										text = "MeloKeep 遇到了意外错误",
										style = MaterialTheme.typography.titleMedium,
										fontWeight = FontWeight.SemiBold,
										color = MaterialTheme.colorScheme.onSurface
									)
								}
								Text(
									text = "应用发生未处理异常并已安全中止。您可以复制诊断日志反馈问题，或尝试重新启动。",
									style = MaterialTheme.typography.bodySmall,
									lineHeight = 19.sp,
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
						}

						Surface(
							modifier = Modifier
								.weight(1f)
								.fillMaxWidth(),
							shape = RoundedCornerShape(14.dp),
							color = MaterialTheme.colorScheme.surfaceContainer,
							border = BorderStroke(
								width = 0.5.dp,
								color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
							)
						) {
							Column(modifier = Modifier.fillMaxSize()) {
								Column(
									modifier = Modifier
										.fillMaxWidth()
										.padding(horizontal = 14.dp, vertical = 12.dp),
									verticalArrangement = Arrangement.spacedBy(4.dp)
								) {
									Text(
										text = "诊断堆栈日志",
										style = MaterialTheme.typography.labelLarge,
										fontWeight = FontWeight.SemiBold,
										color = MaterialTheme.colorScheme.onSurface
									)
									Text(
										text = exceptionSummary,
										style = MaterialTheme.typography.bodySmall,
										fontFamily = FontFamily.Monospace,
										color = MaterialTheme.colorScheme.error,
										maxLines = 2,
										overflow = TextOverflow.Ellipsis
									)
								}
								HorizontalDivider(
									thickness = 0.5.dp,
									color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
								)
								Box(
									modifier = Modifier
										.weight(1f)
										.fillMaxWidth()
										.verticalScroll(rememberScrollState())
								) {
									SelectionContainer(
										modifier = Modifier
											.fillMaxWidth()
											.horizontalScroll(rememberScrollState())
											.padding(14.dp)
									) {
										Text(
											text = stackTrace,
											fontFamily = FontFamily.Monospace,
											fontSize = 11.sp,
											lineHeight = 16.sp,
											color = MaterialTheme.colorScheme.onSurface
										)
									}
								}
							}
						}

						Column(
							modifier = Modifier
								.fillMaxWidth()
								.navigationBarsPadding(),
							verticalArrangement = Arrangement.spacedBy(4.dp)
						) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(10.dp)
							) {
								OutlinedButton(
									modifier = Modifier
										.weight(1f)
										.heightIn(min = 46.dp),
									shape = RoundedCornerShape(12.dp),
									onClick = {
										view.playSoundEffect(SoundEffectConstants.CLICK)
										val clipboard = getSystemService(ClipboardManager::class.java)
										clipboard?.setPrimaryClip(
											ClipData.newPlainText("MeloKeep Crash Log", stackTrace)
										)
										copied = true
									}
								) {
									Icon(
										imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.Copy,
										contentDescription = null,
										modifier = Modifier.size(16.dp)
									)
									Spacer(modifier = Modifier.width(6.dp))
									Text(
										text = if (copied) "已复制" else "复制日志",
										maxLines = 1
									)
								}

								Button(
									modifier = Modifier
										.weight(1f)
										.heightIn(min = 46.dp),
									shape = RoundedCornerShape(12.dp),
									onClick = {
										view.playSoundEffect(SoundEffectConstants.CLICK)
										val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
										if (launchIntent != null) {
											launchIntent.addFlags(
												Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
											)
											startActivity(launchIntent)
										}
										finishAndRemoveTask()
										exitProcess(0)
									}
								) {
									Icon(
										imageVector = Icons.Outlined.Refresh,
										contentDescription = null,
										modifier = Modifier.size(16.dp)
									)
									Spacer(modifier = Modifier.width(6.dp))
									Text(
										text = "重新启动",
										maxLines = 1
									)
								}
							}

							TextButton(
								modifier = Modifier.fillMaxWidth(),
								shape = RoundedCornerShape(12.dp),
								onClick = {
									view.playSoundEffect(SoundEffectConstants.CLICK)
									finishAndRemoveTask()
								}
							) {
								Text(
									text = "关闭应用",
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
