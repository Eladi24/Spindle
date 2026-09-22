package io.github.eladimany.spindle.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eladimany.spindle.R

/**
 * Wordmark lockup — the same "Spindle Signal" mark as the launcher icon (a tightly
 * cropped copy of it, `res/drawable/ic_spindle_signal.xml` — the launcher's own
 * drawable is padded for the adaptive-icon safe zone and would look small and
 * off-center rendered directly here) next to the app name. Fixed brand-indigo
 * background regardless of light/dark theme, same reasoning as Now Playing's hero
 * backdrop: a launch/identity moment, not a themed content screen.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF4B3FD1)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_spindle_signal),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Text(
                "Spindle",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                ),
                color = Color.White,
            )
        }
    }
}
