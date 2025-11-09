/**
 * Ether Dream player - general utilities
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

fun ByteArray.uint16At(offset: Int) =
    ((get(offset + 1).toInt() and 0xff) shl 8) or (get(offset).toInt() and 0xff)

fun ByteArray.int16At(offset: Int) =
    (get(offset + 1).toInt() shl 8) or (get(offset).toInt() and 0xff)

fun ByteArray.uint32At(offset: Int) =
    (get(offset + 3).toUByte().toUInt() shl 24) or (get(offset + 2).toUByte().toUInt() shl 16) or (get(offset + 1).toUByte().toUInt() shl 8) or get(offset).toUByte().toUInt()

fun ByteArray.setInt16(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value shr 8).toByte()
}

fun ByteArray.setInt32(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value shr 8).toByte()
    this[offset + 2] = (value shr 16).toByte()
    this[offset + 3] = (value shr 24).toByte()
}
