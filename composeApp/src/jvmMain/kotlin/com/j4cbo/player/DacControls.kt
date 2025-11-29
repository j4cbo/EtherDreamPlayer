/*
 * Ether Dream player - laser output controls
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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel

class DacControlsViewModel : ViewModel() {
    val brightness = mutableStateOf(1.0f)
    val size = mutableStateOf(1.0f)
    val delay = mutableStateOf(0.0f)
}

// Minimum scale = 25%, to prevent collapsing the image down to a hot beam
const val MINIMUM_SIZE = 0.25

@Composable
fun DacControls(viewModel: DacControlsViewModel) {
    Column(
        verticalArrangement = Arrangement.Bottom,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp),
    ) {
        Text("Brightness")
        Slider(
            modifier = Modifier.height(40.dp),
            value = viewModel.brightness.value,
            onValueChange = { viewModel.brightness.value = it },
        )
        Text("Size")
        Slider(
            modifier = Modifier.height(40.dp),
            value = viewModel.size.value,
            onValueChange = { viewModel.size.value = it },
        )
        Text("Delay")
        Slider(
            modifier = Modifier.height(50.dp),
            value = viewModel.delay.value,
            onValueChange = { viewModel.delay.value = it },
        )
    }
}
