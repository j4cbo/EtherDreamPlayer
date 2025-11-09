/**
 * Ether Dream player - DAC list view and viewmodel
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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel

class DacListViewModel : ViewModel() {
    val dacs = mutableStateOf(emptyMap<String, DiscoveredDac>())
    val selectedDacId = mutableStateOf<String?>(null)
    val connection = mutableStateOf<EtherDream?>(null)

    init {
        EtherDreamListener.listen { dacs.value = it }
    }
}

/**
 * [DacList] is a [Composable] element that renders a list of discovered Ether Dream DACs and provides
 * radio buttons to select up to one. The selected DAC is stored in the [DacListViewModel].
 */
@Composable
fun DacList(
    viewModel: DacListViewModel,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            text = if (viewModel.dacs.value.isNotEmpty()) "Available DACs" else "No Ether Dream DACs found",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .padding(start = 5.dp, end = 5.dp, top = 10.dp)
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        for (dac in viewModel.dacs.value.values.sortedBy { it.id }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .selectable(
                        selected = (dac.id == viewModel.selectedDacId.value),
                        onClick = {
                            if (viewModel.selectedDacId.value != dac.id) {
                                viewModel.selectedDacId.value = dac.id
                                viewModel.connection.value?.shutdown()
                                // TODO: this is opening a new connection on the UI thread :(
                                viewModel.connection.value = EtherDream(dac)
                            }
                        },
                        role = Role.RadioButton
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = (dac.id == viewModel.selectedDacId.value),
                    onClick = null // onclick handled at row level
                )
                Text(
                    text = dac.id,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }
    }
}
