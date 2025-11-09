/**
 * Ether Dream player - top level Compose app
 *
 * Copyright 2025 Jacob Potter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.j4cbo.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Top-level application main window.
 *
 * This contains a [PlayerUi] on the left and a [DacList] on the right. When a file is selected in the player
 * UI and played, frames are sent up to the dacCallback function, which sends the data over to the selected
 * Ether Dream DAC (if any).
 */
@Composable
@Preview
fun App(
    dacListViewModel: DacListViewModel = DacListViewModel(),
    errorString: MutableState<String?> = mutableStateOf(null)
) {
    MaterialTheme(
        colorScheme = com.j4cbo.player.theme.lightScheme
    ) {
        // If there's been an error, pop up an alert modal
        errorString.value?.let { showErrorString ->
            AlertDialog(
                onDismissRequest = { errorString.value = null },
                text = { Text(showErrorString) },
                confirmButton = { TextButton(onClick = { errorString.value = null }) { Text("OK") } }
            )
        }

        Row {
            PlayerUi(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .safeContentPadding()
                    .fillMaxHeight()
                    .weight(0.75f),
                errorString = errorString,
                dacCallback = {
                    dacListViewModel.connection.value?.addFrame(it)
                }
            )
            VerticalDivider()
            DacList(
                viewModel = dacListViewModel,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .safeContentPadding()
                    .fillMaxHeight()
                    .weight(0.25f)
                    .selectableGroup()
            )
        }
    }
}