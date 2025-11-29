/*
 * Ether Dream player - WAV handling, audio output, and playback loop
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

import androidx.compose.ui.graphics.Color
import java.io.File
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

// Number of samples in a "frame", as used in both the output audio buffer and the DAC logic.
private const val FRAME_SAMPLES = 1600

// Expected channels of an ILDA WAV file
private const val ILDA_WAV_CHANNELS = 8

// Audio sample start position
private const val ILDA_WAV_AUDIO_CHANNEL = 6

// 2 channels of audio output
private const val STEREO = 2

private val AudioFormat.sampleSizeInBytes get() = sampleSizeInBits / 8

class DisplayFrame(
    val xBuffer: Array<Int> = Array(FRAME_SAMPLES) { 0 },
    val yBuffer: Array<Int> = Array(FRAME_SAMPLES) { 0 },
    val colorBuffer: Array<Color> = Array(FRAME_SAMPLES) { Color.Black },
) {
    fun copy() = DisplayFrame(xBuffer.copyOf(), yBuffer.copyOf(), colorBuffer.copyOf())
}

/**
 * A [WavPlayer] loads a WAV file from [file], plays the audio content to the system output device, and
 * passes frame data to [displayCallback] and [dacCallback].
 */
class WavPlayer(
    val file: File,
    private val displayCallback: (Float, DisplayFrame, Boolean) -> Unit,
    private val dacCallback: (EtherDreamPoints) -> Unit,
) {
    /** Cached format from [file] */
    val format: AudioFormat

    /** Cached length from [file] */
    val lengthFrames: Long

    private val lock: ReentrantLock = ReentrantLock()
    private val cond: Condition = lock.newCondition()

    private var seekRequest: Float? = null

    fun seek(position: Float) =
        lock.withLock {
            seekRequest = position
            cond.signal()
        }

    /**
     * This is @Volatile to allow unlocked reads by [isPlaybackRequested] for the purpose of UI updates. All
     * writes, and reads from the playback thread, are done with the lock held.
     */
    @Volatile
    private var playRequest: Boolean = false

    /** Request that the player start (if [state] is true) or pause (if [state] is false) playback */
    fun requestPlayback(state: Boolean) =
        lock.withLock {
            playRequest = state
            cond.signal()
        }

    /** Get the current requested playback state (true for play, false for pause) */
    fun isPlaybackRequested() = playRequest

    private var shutdownRequest: Boolean = false

    fun shutdown() =
        lock.withLock {
            shutdownRequest = true
            cond.signal()
        }

    private val thread =
        Thread {
            try {
                playFile()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

    init {
        val stream = AudioSystem.getAudioInputStream(file)
        if (stream.format.channels != ILDA_WAV_CHANNELS) {
            throw Exception("An 8-channel WAV file is required")
        }

        format = stream.format
        lengthFrames = stream.frameLength

        thread.start()
    }

    private fun playFile() {
        var stream = AudioSystem.getAudioInputStream(file)

        val bytesPerSampleIn = stream.format.sampleSizeInBytes * stream.format.channels
        val inBuffer = ByteArray(FRAME_SAMPLES * bytesPerSampleIn)
        val bytesPerSampleOut = stream.format.sampleSizeInBytes * STEREO
        val outBuffer = ByteArray(FRAME_SAMPLES * bytesPerSampleOut)

        // The input data is little-endian, so if we're reading from a 24-bit WAV we need to offset by one byte.
        val int16Offset =
            when (stream.format.sampleSizeInBytes) {
                2 -> 0
                3 -> 1
                else -> throw IllegalStateException("only 16- and 24-bit WAVs are supported")
            }

        val audioFormat = AudioFormat(stream.format.sampleRate, stream.format.sampleSizeInBits, STEREO, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, audioFormat, outBuffer.size * STEREO)
        val soundLine = AudioSystem.getLine(info) as SourceDataLine

        soundLine.open(audioFormat, outBuffer.size * STEREO)
        soundLine.start()

        val displayFrame = DisplayFrame()

        var positionSamples = 0L
        while (true) {
            val (seekRequest, playRequest) =
                lock.withLock {
                    while (!(playRequest || shutdownRequest || seekRequest != null)) {
                        cond.await()
                    }
                    if (shutdownRequest) {
                        break
                    }
                    Pair(seekRequest, playRequest).also { seekRequest = null }
                }

            // At this point, either seekRequest or playRequest is true. Either way, we should read
            // one frame to render it...

            if (seekRequest != null) {
                stream = AudioSystem.getAudioInputStream(file)
                positionSamples = (stream.frameLength * seekRequest).toLong()
                stream.skip(positionSamples * stream.format.frameSize)
            }

            val bytesRead = stream.read(inBuffer)
            val samplesRead = bytesRead / bytesPerSampleIn

            val frame = EtherDreamPoints.allocate(samplesRead, stream.format.sampleRate.toInt())

            for (i in 0..<samplesRead) {
                fun channel(n: Int) = inBuffer.int16At(i * bytesPerSampleIn + int16Offset + (n * stream.format.sampleSizeInBytes))
                // Copy only channels 6 and 7 into the output audio buffer
                inBuffer.copyInto(
                    destination = outBuffer,
                    destinationOffset = i * bytesPerSampleOut,
                    startIndex = i * bytesPerSampleIn + (ILDA_WAV_AUDIO_CHANNEL * stream.format.sampleSizeInBytes),
                    endIndex = i * bytesPerSampleIn + ((ILDA_WAV_AUDIO_CHANNEL + STEREO) * stream.format.sampleSizeInBytes),
                )

                val x = -channel(0)
                val y = -channel(1)
                val r = -channel(2) * 2
                val g = -channel(3) * 2
                val b = -channel(4) * 2

                frame.setPoint(i, x, y, r, g, b)

                displayFrame.colorBuffer[i] =
                    Color(
                        red = max(0, min(r shr 8, 255)),
                        green = max(0, min(g shr 8, 255)),
                        blue = max(0, min(b shr 8, 255)),
                    )
                displayFrame.xBuffer[i] = x
                displayFrame.yBuffer[i] = y
            }

            if (seekRequest != null) {
                displayCallback(seekRequest, displayFrame.copy(), true)
            } else {
                displayCallback(positionSamples.toFloat() / stream.frameLength.toFloat(), displayFrame.copy(), false)
            }

            if (playRequest) {
                soundLine.write(outBuffer, 0, samplesRead * bytesPerSampleOut)
                dacCallback(frame)
            }

            positionSamples += samplesRead
        }
    }
}
