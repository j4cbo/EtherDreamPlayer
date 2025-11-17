/*
 * Ether Dream - UDP broadcast listener
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

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.seconds

private const val BROADCAST_PORT = 7654

/**
 * Information about a DAC seen on the network.
 */
data class DiscoveredDac(
    val id: String,
    val ipAddr: InetAddress,
    val hardwareRev: Int,
    val softwareRev: Int,
    val bufferCapacity: Int,
)

/** Time after which a DAC should be removed from the list if no packets have been received. */
private val EXPIRY_THRESHOLD = 3.seconds

/** Length (in bytes) of Ether Dream broadcast packets. */
private const val EXPECTED_BROADCAST_LENGTH = 36

/**
 * Try receiving a packet of up to [size] bytes, and if so, return it. If a [SocketTimeoutException] is caught,
 * return null instead.
 */
fun DatagramSocket.tryReceive(size: Int): DatagramPacket? {
    val buffer = ByteArray(size)
    val packet = DatagramPacket(buffer, buffer.size)

    try {
        receive(packet)
        return packet
    } catch (_: SocketTimeoutException) {
        return null
    }
}

/**
 * Listener for Ether Dream broadcast packets.
 */
object EtherDreamListener {
    private val listeners: MutableList<(Map<String, DiscoveredDac>) -> Unit> = mutableListOf()

    private val discoveryThread by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Thread {
            try {
                discoveryLoop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun discoveryLoop() {
        val discoveredEtherDreams: MutableMap<String, Pair<DiscoveredDac, Long>> = mutableMapOf()

        val socket =
            DatagramSocket(null).apply {
                reuseAddress = true // must be set before calling bind()
                soTimeout = 1200 // milliseconds - this needs to be significantly less than EXPIRY_THRESHOLD
                bind(InetSocketAddress(BROADCAST_PORT))
            }

        while (true) {
            val packet = socket.tryReceive(256)

            // If we found a DAC, add it to the list
            val dacAdded: Boolean
            if (packet != null && packet.length == EXPECTED_BROADCAST_LENGTH) {
                val dac =
                    DiscoveredDac(
                        id = packet.data.sliceArray(3..5).toHexString(),
                        ipAddr = packet.address,
                        hardwareRev = packet.data.uint16At(6),
                        softwareRev = packet.data.uint16At(8),
                        bufferCapacity = packet.data.uint16At(10),
                    )

                dacAdded = !discoveredEtherDreams.contains(dac.id)
                discoveredEtherDreams[dac.id] = Pair(dac, System.nanoTime())
            } else {
                dacAdded = false
            }

            // Remove any DAC that hasn't been seen in a while
            val now = System.nanoTime()
            val anyRemoved =
                discoveredEtherDreams.entries.removeIf {
                    now - it.value.second > EXPIRY_THRESHOLD.inWholeNanoseconds
                }

            if (dacAdded || anyRemoved) {
                // Update listeners
                val output = discoveredEtherDreams.mapValues { it.value.first }
                synchronized(this) {
                    for (listener in listeners) {
                        listener(output)
                    }
                }
            }
        }
    }

    /**
     * Call listener whenever the DAC list changes.
     */
    fun listen(listener: (Map<String, DiscoveredDac>) -> Unit) {
        // Start the discovery thread if it's not already running
        discoveryThread
        synchronized(this) {
            listeners.add(listener)
        }
    }
}
