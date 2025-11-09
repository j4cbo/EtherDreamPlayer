/**
 * Ether Dream - TCP driver
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

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.StandardSocketOptions
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.concurrent.Volatile
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds

/** TCP port for Ether Dream communication. */
private const val PORT = 7765

/** sizeof(dac_point_t) */
private const val POINT_SIZE = 18

/** Preferred minimum points per data block. Fewer points may be sent e.g. at the end of a frame. */
const val MIN_POINTS_PER_SEND = 40

/** Maximum points per data block. Chosen to fit within a typical Ethernet / TCP MTU. */
const val MAX_POINTS_PER_SEND = 80

/** Target buffer fullness. At 48kpps this is 75 milliseconds. */
const val TARGET_FULLNESS = 3600

/**
 * Timeout for all socket communications. If the DAC hasn't responded in this amount of time, it's
 * time to try reconnecting.
 */
private val COMM_TIMEOUT = 500.milliseconds

enum class DacState {
    IDLE, PREPARED, PLAYING, INVALID
}

/** Buffer fullness level at which we should tell the DAC to start playing */
const val START_THRESHOLD = 3000

/** Size of response to 'v' command */
const val VERSION_STRING_SIZE = 32

object Commands {
    const val BEGIN = 'b'.code.toByte()
    const val DATA = 'd'.code.toByte()
    const val PREPARE = 'p'.code.toByte()
    const val QUEUE = 'q'.code.toByte()
    const val VERSION = 'v'.code.toByte()
}

const val RESPONSE_ACK = 'a'.code.toByte()
const val RESPONSE_NAK_INVALID = 'I'.code.toByte()

/**
 * Byte-level mask to set at [RATE_CHANGE_OFFSET] within a point if a rate change should be applied.
 *
 * The Ether Dream protocol.h defines DAC_CTRL_RATE_CHANGE as 0x8000 to be applied to the little-endian
 * uint16_t control field at the beginning of the struct. We're operating with [ByteArray]s here instead,
 * so [RATE_CHANGE_OFFSET] points to the high-order byte of that field.
 */
private const val RATE_CHANGE_FLAG = 0x80

/**
 * Location within a point to set [RATE_CHANGE_FLAG].
 */
private const val RATE_CHANGE_OFFSET = 1

private val start = System.nanoTime()
private fun trace(message: String) {
    val deltaT = (System.nanoTime() - start) / 1000000000.0
    println(String.format("[%12.6f] %s", deltaT, message))
}

/** Represents a parsed dac_status */
class DacStatus(buffer: ByteArray, offset: Int = 0) {
    val state = when (buffer[offset + 2]) {
        0.toByte() -> DacState.IDLE
        1.toByte() -> DacState.PREPARED
        2.toByte() -> DacState.PLAYING
        else -> DacState.INVALID
    }

    val fullness = buffer.uint16At(offset + 10)
    val rate = buffer.uint32At(offset + 12)
    val pointsPlayed = buffer.uint32At(offset + 16)

    companion object {
        const val SIZE = 20
    }
}

/** Represents a parsed dac_response */
class DacResponse(buffer: ByteArray, offset: Int = 0) {
    val response = buffer[offset]
    val command = buffer[offset + 1]
    val status = DacStatus(buffer, offset + 2)
    companion object {
        const val SIZE = 2 + DacStatus.SIZE
    }
}

/**
 * Represents a block of points to be sent to the Ether Dream.
 *
 * This might also be called a "frame", but there's no output gap between [EtherDreamPoints] objects
 * that are played in sequence, so the division can be arbitrary, e.g. when reading from a recorded
 * stream like a WAV file.
 */
class EtherDreamPoints private constructor(
    val data: ByteArray,
    val rate: Int
) {
    internal val points get() = data.size / POINT_SIZE

    /** Fill in an x, y, r, g, b value at index [i]. */
    fun setPoint(i: Int, x: Int, y: Int, r: Int, g: Int, b: Int) {
        val x1 = max(Short.MIN_VALUE.toInt(), min(x, Short.MAX_VALUE.toInt()))
        val y1 = max(Short.MIN_VALUE.toInt(), min(y, Short.MAX_VALUE.toInt()))
        val r1 = max(0, min(r, UShort.MAX_VALUE.toInt()))
        val g1 = max(0, min(g, UShort.MAX_VALUE.toInt()))
        val b1 = max(0, min(b, UShort.MAX_VALUE.toInt()))
        data[i * POINT_SIZE + 2] = x1.toByte()
        data[i * POINT_SIZE + 3] = (x1 shr 8).toByte()
        data[i * POINT_SIZE + 4] = y1.toByte()
        data[i * POINT_SIZE + 5] = (y1 shr 8).toByte()
        data[i * POINT_SIZE + 6] = r1.toByte()
        data[i * POINT_SIZE + 7] = (r1 shr 8).toByte()
        data[i * POINT_SIZE + 8] = g1.toByte()
        data[i * POINT_SIZE + 9] = (g1 shr 8).toByte()
        data[i * POINT_SIZE + 10] = b1.toByte()
        data[i * POINT_SIZE + 11] = (b1 shr 8).toByte()
    }

    companion object {
        fun allocate(points: Int, rate: Int) = EtherDreamPoints(ByteArray(points * POINT_SIZE), rate)
    }
}

/**
 * Send a data block ('d' command) with an optional point rate change ('q' command) to a socket.
 *
 * This accesses no internal [EtherDreamConnection] state, so it's written as a helper on [OutputStream] instead
 * of a member of [EtherDreamConnection]; it therefore also doesn't need to be called with the connection's lock held.
 */
private fun OutputStream.sendDataBlock(data: ByteArray, inputOffsetPoints: Int, inputLengthPoints: Int, rate: Int?) {
    val inputLengthBytes = inputLengthPoints * POINT_SIZE
    val outputBuffer: ByteArray
    val outputOffsetBytes: Int
    if (rate != null) {
        outputBuffer = ByteArray(inputLengthBytes + 8)
        outputBuffer[0] = Commands.QUEUE
        outputBuffer.setInt32(1, rate)
        outputOffsetBytes = 5
    } else {
        outputBuffer = ByteArray(inputLengthBytes + 3)
        outputOffsetBytes = 0
    }

    outputBuffer[outputOffsetBytes] = Commands.DATA
    outputBuffer.setInt16(outputOffsetBytes + 1, inputLengthPoints)
    val dataOffsetBytes = outputOffsetBytes + 3
    data.copyInto(
        destination = outputBuffer,
        destinationOffset = dataOffsetBytes,
        startIndex = inputOffsetPoints * POINT_SIZE,
        endIndex = inputOffsetPoints * POINT_SIZE + inputLengthBytes
    )
    if (rate != null) {
        outputBuffer[dataOffsetBytes + RATE_CHANGE_OFFSET] =
            outputBuffer[dataOffsetBytes + RATE_CHANGE_OFFSET].toInt().or(RATE_CHANGE_FLAG).toByte()
    }

    write(outputBuffer)
}

/**
 * A single instance of a TCP connection to an Ether Dream DAC. This is used internally by [EtherDream]. If
 * the TCP connection fails, such as due to a significant network error or the DAC rebooting, then the
 * [EtherDreamConnection] will be discarded and replaced allowing the same [EtherDream] to continue to work.
 */
private class EtherDreamConnection(
    dac: DiscoveredDac,
    private val lock: ReentrantLock,
    private val condition: Condition
) {
    private val socket: Socket
    private val outputStream: OutputStream
    private val inputStream: InputStream

    val firmwareVersion: String

    private var status: DacStatus
    private var statusReceivedNanoTime: Long

    /**
     * Not-yet-ACKed data commands
     */
    private val unackedBlocks = ArrayDeque<Int>()

    private val frames = ArrayDeque<EtherDreamPoints>()

    /**
     * Number of ACKs for non-data commands that we're waiting for
     */
    private var pendingMetaAcks: Int = 0

    private var beginSent = false

    var shuttingDown = false

    // This condition is never signalled; it's used as a convenient "unlock the lock and sleep" helper.
    private val sleeper: Condition = lock.newCondition()

    private fun readResponse() = DacResponse(inputStream.readNBytes(DacResponse.SIZE))

    private val receiverThread: Thread

    init {
        trace("Connecting to ${dac.ipAddr}")
        socket = Socket()
        socket.connect(InetSocketAddress(dac.ipAddr, PORT), 1000)

        trace("Connected to ${dac.ipAddr}")

        socket.soTimeout = COMM_TIMEOUT.inWholeMilliseconds.toInt()
        outputStream = socket.getOutputStream()
        inputStream = socket.getInputStream()

        socket.setOption(StandardSocketOptions.TCP_NODELAY, true)
        // throw away the first response
        status = readResponse().status
        statusReceivedNanoTime = System.nanoTime()

        if (dac.softwareRev >= 2) {
            outputStream.write(byteArrayOf(Commands.VERSION))
            firmwareVersion = inputStream.readNBytes(VERSION_STRING_SIZE).decodeToString().trimEnd { it == ' ' || it.code == 0 }
        } else {
            firmwareVersion = "[old]"
        }

        trace("Connected to DAC, version $firmwareVersion")

        receiverThread = Thread(this::receiveLoop)
        receiverThread.start()
    }

    private fun receiveLoop() {
        try {
            while (true) {
                // Read a response from the DAC
                val response = try {
                    readResponse()
                } catch (_: SocketTimeoutException) {
                    // If we're not currently playing a frame, this is expected
                    lock.withLock {
                        if (frames.isEmpty()) {
                           continue
                        } else {
                            trace("Receiver thread timeout")
                            shuttingDown = true
                            condition.signalAll()
                            return
                        }
                    }
                }

                lock.withLock {
                    status = response.status
                    statusReceivedNanoTime = System.nanoTime()

                    if (shuttingDown) {
                        return
                    }

                    if (response.status.state == DacState.IDLE) {
                        beginSent = false
                    }

                    if (response.command == Commands.DATA) {
                        if (unackedBlocks.isEmpty()) {
                            throw IllegalStateException("unexpected data ack")
                        }
                        unackedBlocks.removeFirst()
                    } else {
                        if (pendingMetaAcks == 0) {
                            throw IllegalStateException(
                                "unexpected meta ack for ${
                                    response.command.toInt().toChar()
                                } (${response.command.toInt()}"
                            )
                        }
                        pendingMetaAcks -= 1
                    }

                    if (response.response != RESPONSE_ACK && response.response != RESPONSE_NAK_INVALID) {
                        // This indicates a major desynchronization - we should close and reopen the connection
                        throw IllegalStateException("unexpected response ${response.response}")
                    }

                    condition.signalAll()
                }
            }
        } catch (e: Exception) {
            lock.withLock {
                shuttingDown = true
                condition.signalAll()
            }
            trace("Receiver thread failure")
            e.printStackTrace()
        }
    }

    private fun sendLoop() {
        var currentFrameOffset = 0
        var lastRate = 0

        while (true) {
            val frame: EtherDreamPoints
            val sendPoints: Int
            val sendOffset: Int
            val sendRate: Int?

            lock.withLock {
                // Wait for a frame to be available
                while (frames.isEmpty() && !shuttingDown) {
                    condition.await()
                }

                if (shuttingDown) {
                    return
                }

                frame = frames.first()

                // Start the data that's already in the buffer if it's full enough
                if (status.fullness >= START_THRESHOLD && !beginSent) {
                    pendingMetaAcks += 1
                    beginSent = true
                    trace("sending begin")
                    val beginCommand = ByteArray(7)
                    beginCommand[0] = Commands.BEGIN
                    beginCommand.setInt32(3, frame.rate)
                    outputStream.write(beginCommand)
                }

                // How many points do we expect to have been consumed by playback since we last received an ack?
                val expectedUsed = if (status.state == DacState.PLAYING) {
                    val timeDiff = System.nanoTime() - statusReceivedNanoTime
                    require(timeDiff >= 0) { "nanoTime can't go backwards" }
                    timeDiff * frame.rate / 1000000000
                } else {
                    0
                }

                // Based on that, how many points do we expect to be in the buffer?
                val expectedFullness = status.fullness + unackedBlocks.sum() - expectedUsed

                val capacity = TARGET_FULLNESS - expectedFullness

                if (status.state != DacState.PLAYING ||
                    status.fullness < 1000 ||
                    unackedBlocks.size > 30 ||
                    pendingMetaAcks > 4 ||
                    expectedUsed > 1000) {
                    trace("TX: ${status.fullness} + ${unackedBlocks.sum()} (${unackedBlocks.size} d $pendingMetaAcks m) - $expectedUsed = expected $expectedFullness, capacity $capacity")
                }

                if (capacity < MIN_POINTS_PER_SEND) {
                    // How long will it be until we have MAX_POINTS_PER_SEND space available?
                    val pointsNeeded = MAX_POINTS_PER_SEND - capacity
                    val timeNeeded = pointsNeeded * 1000000000 / frame.rate
                    sleeper.await(timeNeeded, TimeUnit.NANOSECONDS)
                    continue
                }

                val pointsRemainingInFrame = frame.points - currentFrameOffset

                // Save off the number of points to send and offset before updating internal state
                sendPoints = min(capacity.toInt(), min(pointsRemainingInFrame, MAX_POINTS_PER_SEND))
                sendOffset = currentFrameOffset

                currentFrameOffset += sendPoints
                if (currentFrameOffset == frame.points) {
                    frames.removeFirst()
                    currentFrameOffset = 0
                }

                // If we're idle, prepare the stream
                if (status.state == DacState.IDLE) {
                    trace("TX: preparing...")
                    outputStream.write(byteArrayOf(Commands.PREPARE))
                    val preparedAt = System.nanoTime()

                    // Block here until the prepare command is acknowledged and the reader thread is fully caught up
                    pendingMetaAcks += 1
                    while (pendingMetaAcks > 0) {
                        condition.await()
                        if (System.nanoTime() - preparedAt > COMM_TIMEOUT.inWholeNanoseconds) {
                            throw Exception("sender prepare timeout")
                        }
                    }
                    trace("TX: ... prepare ACKed")
                }

                // Check whether there's a rate change; if so, we'll send 2 commands instead of 1
                sendRate = if (frame.rate != lastRate) {
                    pendingMetaAcks += 1
                    lastRate = frame.rate
                    frame.rate
                } else {
                    null
                }

                unackedBlocks.add(sendPoints)
            }

            outputStream.sendDataBlock(frame.data, sendOffset, sendPoints, sendRate)
        }
    }

    /**
     * This is the primary worker function. It handles sending data to the DAC as long as data is available. If it
     * ever returns, something has gone wrong with the DAC (connection in a bad state, DAC disappeared, etc.) and
     * the connection should be discarded and a new one created.
     */
    fun runSender() {
        try {
            sendLoop()
        } catch (e: Exception) {
            trace("Sender thread failure")
            e.printStackTrace()
        }

        // Shut down the receiver thread
        lock.withLock {
            shuttingDown = true
            condition.signalAll()
        }

        receiverThread.join()
        outputStream.close()
        inputStream.close()
        socket.close()
    }

    fun addFrame(frame: EtherDreamPoints) {
        lock.withLock {
            if (frames.size > 2) {
                trace("dropping frame")
                return
            }
            frames.addLast(frame)
            condition.signalAll()
        }
    }

    fun isReadyUnlocked() = frames.size <= 1
}

class EtherDream(val dac: DiscoveredDac) {

    /** Shared Lock across the parent [EtherDream] and child [EtherDreamConnection], to handle reconnects. */
    private val lock = ReentrantLock()

    /** Shared [Condition] across the parent [EtherDream] and child [EtherDreamConnection], to handle reconnects. */
    private val condition: Condition = lock.newCondition()

    /** Signals the sender/receiver threads to shut down */
    private var shutdown: Boolean = false

    /** Volatile to allow unlocked reads */
    @Volatile
    private var connection: EtherDreamConnection = EtherDreamConnection(dac, lock, condition)

    val senderThread: Thread

    init {
        senderThread = Thread(this::runSender)
        senderThread.start()
    }

    private fun reconnectWithTimeout(): EtherDreamConnection {
        while (true) {
            try {
                return EtherDreamConnection(dac, lock, condition)
            } catch (e: Exception) {
                e.printStackTrace()
                Thread.sleep(COMM_TIMEOUT.inWholeMilliseconds)
            }
        }
    }

    private fun runSender() {
        while (true) {
            connection.runSender()

            if (shutdown) {
                return
            }

            trace("Reconnecting to ${dac.ipAddr}...")
            val newConnection = reconnectWithTimeout()
            lock.withLock {
                connection = newConnection
                condition.signalAll()
            }
        }
    }

    fun waitForReady() {
        lock.withLock {
            while (true) {
                if (shutdown) {
                    throw IllegalStateException("EtherDream instance has been shut down")
                }
                if (connection.isReadyUnlocked()) {
                    return
                }
                condition.await()
            }
        }
    }

    fun addFrame(frame: EtherDreamPoints) {
        if (shutdown) {
            throw IllegalStateException("EtherDream instance has been shut down")
        }
        connection.addFrame(frame)
    }

    fun shutdown() {
        lock.withLock {
            shutdown = true
            connection.shuttingDown = true
            condition.signalAll()
        }
    }
}

/**
 * Test helper
 */
private fun EtherDream.drawCircle() {
    val nPoints = 480
    while (true) {
        repeat(1000) {
            val frame = EtherDreamPoints.allocate(nPoints, 48000)
            for (i in 0..<nPoints) {
                val radians = (i.toFloat() / nPoints.toFloat()) * 2.0 * Math.PI
                val x = (cos(radians) * 10000).toInt()
                val y = (sin(radians) * 10000).toInt()
                val color = if (radians < Math.PI) 65535 else 30000
                frame.setPoint(i, x, y, color, color, color)
            }

            waitForReady()
            addFrame(frame)
        }
    }
}
