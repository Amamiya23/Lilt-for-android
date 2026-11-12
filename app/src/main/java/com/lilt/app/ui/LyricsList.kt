package com.lilt.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lilt.app.data.LyricLine
import com.lilt.app.ui.theme.Radix

private val LyricStyle = TextStyle(fontSize = 28.sp, lineHeight = 1.3.em, fontWeight = FontWeight.ExtraBold,
    lineBreak = LineBreak.Simple, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))

/** All lyrics stay sharp; only playback decides which line is highlighted. */
@Composable
internal fun LyricsList(
    lines: List<LyricLine>,
    activeIndex: Int,
    position: State<Long>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    songKey: String? = null,
) {
    if (lines.isEmpty()) return
    key(songKey) {
        BoxWithConstraints(modifier) {
            val anchor = maxHeight * .35f
            val anchorPx = with(LocalDensity.current) { anchor.roundToPx() }
            // Opening a song starts at its sung line. A drag yields control until an explicit return or seek.
            // Negative offsets clamp at the list's start, so the first lines never gain empty space above.
            val listState = rememberLazyListState(activeIndex.coerceIn(lines.indices), -anchorPx)
            var following by rememberSaveable { mutableStateOf(true) }
            var seekTarget by remember { mutableStateOf<Int?>(null) }
            val browsing = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (source == NestedScrollSource.UserInput && available.y != 0f) {
                            following = false
                            seekTarget = null
                        }
                        return Offset.Zero
                    }
                }
            }
            // Hold a clicked destination while the playback controller acknowledges the asynchronous seek.
            LaunchedEffect(activeIndex, seekTarget) {
                if (activeIndex == seekTarget) seekTarget = null
            }
            val target = (seekTarget ?: activeIndex).coerceIn(lines.indices)
            LaunchedEffect(target, following, maxHeight) {
                if (following) listState.animateScrollToItem(target, -anchorPx)
            }
            LazyColumn(Modifier.fillMaxSize().testTag("lyrics-list").nestedScroll(browsing), listState,
                contentPadding = PaddingValues(top = 4.dp, bottom = maxHeight - anchor),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                itemsIndexed(lines, key = { index, line -> "$index-${line.timeMs}" }) { index, line ->
                    val active = index == activeIndex
                    val ink by animateFloatAsState(if (active) 1f else .68f, tween(260), label = "lyricInk")
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .quietClickable(role = Role.Button) {
                            seekTarget = index
                            following = true
                            onSeek(line.timeMs)
                        }.semantics { selected = active }
                        .padding(vertical = 4.dp).heightIn(min = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (active && line.words.isNotEmpty()) SungLine(line, position, LyricStyle)
                        else Text(line.text, color = Radix.White.copy(alpha = ink), style = LyricStyle)
                        if (line.translation.isNotBlank()) Text(line.translation,
                            color = Radix.White.copy(alpha = if (active) .8f else .6f),
                            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (!following) Button(onClick = { seekTarget = null; following = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 8.dp).heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(
                    containerColor = Radix.SlateDark3, contentColor = Radix.SlateDark12)) {
                Text("回到当前")
            }
        }
    }
}
