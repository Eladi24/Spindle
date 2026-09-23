package io.github.eladimany.spindle.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/** Where letter [letter]'s rows start — [index] is a position into the name-sorted list
 * the anchors were built from, which must be the same order the screen's list shows. */
data class SectionAnchor(val letter: Char, val index: Int)

/**
 * A-Z + '#' anchors over an already-sorted list: one per change of first letter.
 * Anything not starting with A-Z (digits, symbols, non-Latin scripts) files under '#'.
 */
fun <T> sectionAnchors(sorted: List<T>, name: (T) -> String): List<SectionAnchor> {
    val anchors = mutableListOf<SectionAnchor>()
    var lastLetter: Char? = null
    sorted.forEachIndexed { index, item ->
        val first = name(item).firstOrNull()?.uppercaseChar()
        val letter = if (first != null && first in 'A'..'Z') first else '#'
        if (letter != lastLetter) {
            anchors += SectionAnchor(letter, index)
            lastLetter = letter
        }
    }
    return anchors
}

/**
 * A-Z (+ "#") fast-scroll rail. A single unified gesture handles both a tap (jumps once)
 * and a drag (scrubs continuously) — [awaitFirstDown] fires on first touch with no slop,
 * then [drag] tracks the same pointer for as long as it's down.
 */
@Composable
fun AlphabetIndexBar(
    sections: List<SectionAnchor>,
    onScrub: (SectionAnchor, active: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var heightPx by remember { mutableStateOf(0f) }

    fun sectionForY(y: Float): SectionAnchor {
        val fraction = if (heightPx > 0f) (y / heightPx).coerceIn(0f, 1f) else 0f
        val index = (fraction * (sections.size - 1)).toInt().coerceIn(0, sections.lastIndex)
        return sections[index]
    }

    Column(
        modifier = modifier
            .width(24.dp)
            .onGloballyPositioned { heightPx = it.size.height.toFloat() }
            .pointerInput(sections) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onScrub(sectionForY(down.position.y), true)
                    drag(down.id) { change ->
                        onScrub(sectionForY(change.position.y), true)
                        change.consume()
                    }
                    onScrub(sections.first(), false)
                }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        sections.forEach { section ->
            Text(
                text = section.letter.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The large centered letter shown while scrubbing [AlphabetIndexBar]. */
@Composable
fun ScrubLetterBubble(letter: Char, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
