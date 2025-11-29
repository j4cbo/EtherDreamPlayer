/*
 * Ether Dream player - playback UI
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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

@Composable
fun PlayPauseButton(
    modifier: Modifier = Modifier,
    isPlaying: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
        )
    }
}

/**
 * Format a number of seconds as mm:ss
 */
private fun Int.formatMinutesSeconds() = "${this / 60}:${(this % 60).toString().padStart(2, '0')}"

/**
 * Main playback user interface: file open button, position slider and indicators, preview window.
 */
@Composable
fun PlayerUi(
    errorString: MutableState<String?>,
    dacCallback: (EtherDreamPoints) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Internal state
    var player by remember { mutableStateOf<WavPlayer?>(null) }
    var sliderPosition by remember { mutableStateOf(0f) }
    var currentFrame by remember { mutableStateOf<DisplayFrame?>(null) }
    var previousFrame by remember { mutableStateOf<DisplayFrame?>(null) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val filePickerLauncher =
            rememberFilePickerLauncher(
                mode = FileKitMode.Single,
                type = FileKitType.File(extension = "wav"),
            ) { openedFile ->
                if (openedFile != null) {
                    try {
                        // Reset the player whenever a new file is opened
                        player?.shutdown()
                        player =
                            WavPlayer(
                                openedFile.file,
                                displayCallback = { position, frame, isSeek ->
                                    sliderPosition = position
                                    previousFrame = if (isSeek) null else currentFrame
                                    currentFrame = frame
                                },
                                dacCallback = dacCallback,
                            )
                        currentFrame = null
                        previousFrame = null
                        sliderPosition = 0f
                    } catch (e: Exception) {
                        println(e.message)
                        errorString.value = e.message
                    }
                }
            }

        Button(onClick = { filePickerLauncher.launch() }) {
            Text("Open File")
        }

        player?.let { player ->
            Text("${player.file.name}")

            Slider(
                colors =
                    SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.secondary,
                        inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                value = sliderPosition,
                onValueChange = {
                    sliderPosition = it
                    player.seek(it)
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().safeContentPadding(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val seconds = (sliderPosition * player.lengthFrames / player.format.sampleRate).toInt()
                val secondsRemaining = (player.lengthFrames / player.format.sampleRate).toInt() - seconds

                Row(modifier = Modifier.weight(1f)) {
                    Text(seconds.formatMinutesSeconds(), Modifier.padding(start = 8.dp))
                }

                PlayPauseButton(
                    isPlaying = player.isPlaybackRequested(),
                    enabled = true,
                    onClick = {
                        player.requestPlayback(!player.isPlaybackRequested())
                    },
                )

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(secondsRemaining.formatMinutesSeconds(), Modifier.padding(end = 8.dp))
                }
            }

            Canvas(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .padding(10.dp),
            ) {
                drawRect(
                    color = Color.Black,
                    size = size,
                )

                val width = size.width

                /** Calculate the UI [Offset] corresponding to the point at index [i] in the current frame */
                fun DisplayFrame.offset(i: Int) =
                    Offset(
                        (0.5f + (xBuffer[i].toFloat() / (Short.MAX_VALUE * 2))) * width,
                        (0.5f - (yBuffer[i].toFloat() / (Short.MAX_VALUE * 2))) * width,
                    )

                fun DisplayFrame.draw() {
                    var offset = offset(0)
                    for (i in 1..<colorBuffer.size - 6) {
                        val nextOffset = offset(i)
                        drawLine(
                            colorBuffer[i + 5],
                            start = offset,
                            end = nextOffset,
                            cap = StrokeCap.Round,
                            strokeWidth = width / 500f,
                        )
                        offset = nextOffset
                    }
                }

                // Draw both the previous and current frames, to better simulate persistence-of-vision effects
                previousFrame?.draw()
                currentFrame?.draw()
            }
        }
    }
}
