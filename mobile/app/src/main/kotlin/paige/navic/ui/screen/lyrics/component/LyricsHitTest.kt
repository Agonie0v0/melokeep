package paige.navic.ui.screen.lyrics.component

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Only fires [onTap] when the pointer tap lands inside actual laid-out glyph line bounds
 * (plus a small [slop] tolerance), ignoring empty horizontal/vertical margin taps.
 */
internal fun Modifier.detectLyricTextTap(
	textLayoutResultProvider: () -> TextLayoutResult?,
	slop: Dp = 8.dp,
	onTap: () -> Unit
): Modifier = composed {
	val latestOnTap by rememberUpdatedState(onTap)
	val latestLayoutProvider by rememberUpdatedState(textLayoutResultProvider)
	pointerInput(slop) {
		val slopPx = slop.toPx()
		detectTapGestures { offset ->
			val layout = latestLayoutProvider() ?: return@detectTapGestures
			for (lineIndex in 0 until layout.lineCount) {
				val left = layout.getLineLeft(lineIndex) - slopPx
				val right = layout.getLineRight(lineIndex) + slopPx
				val top = layout.getLineTop(lineIndex) - slopPx
				val bottom = layout.getLineBottom(lineIndex) + slopPx
				if (offset.x in left..right && offset.y in top..bottom) {
					latestOnTap()
					break
				}
			}
		}
	}
}
