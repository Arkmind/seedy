package io.github.arkmind.seedy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** Stands in for a screen that isn't built yet. */
@Composable
fun NotYet(title: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(SeedyColors.Bg), contentAlignment = Alignment.Center) {
        SText(title, size = 22.sp, weight = 900, color = SeedyColors.White)
    }
}
