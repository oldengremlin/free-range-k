/*
 * Copyright 2026 olden.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ukrhub.noc.freerange.output

import net.ukrhub.noc.freerange.vlan.VlanStatus

/**
 * Outputs VLAN distribution as a compact combined-ranges string.
 *
 * Without color: 100-200(f),201(b),202-210(u)
 * With color:    ANSI-colored range numbers (no status suffix in color mode, matching Ruby)
 */
object TextOutput {

    /**
     * Builds and prints the combined ranges line.
     */
    fun print(
        statuses: Map<Int, VlanStatus>,
        useColor: Boolean,
        target: String,
        interfaceName: String?
    ) {
        val result = buildCombinedRanges(statuses, useColor)
        println(result)
    }

    fun buildCombinedRanges(statuses: Map<Int, VlanStatus>, useColor: Boolean): String {
        if (statuses.isEmpty()) return ""

        val sortedVlans = statuses.keys.sorted()
        val parts = mutableListOf<String>()

        var rangeStart = sortedVlans[0]
        var rangeEnd = rangeStart
        var currentStatus = statuses[rangeStart]!!

        for (idx in 1 until sortedVlans.size) {
            val vlan = sortedVlans[idx]
            val status = statuses[vlan]!!
            if (vlan == rangeEnd + 1 && status == currentStatus) {
                rangeEnd = vlan
            } else {
                parts.add(formatRange(rangeStart, rangeEnd, currentStatus, useColor))
                rangeStart = vlan
                rangeEnd = vlan
                currentStatus = status
            }
        }
        parts.add(formatRange(rangeStart, rangeEnd, currentStatus, useColor))

        return parts.joinToString(",")
    }

    private val ESC: String = 0x1B.toChar().toString()

    private fun formatRange(start: Int, end: Int, status: VlanStatus, useColor: Boolean): String {
        val rangeText = if (start == end) "$start" else "$start-$end"
        return if (useColor) {
            val ansi = ansiForStatus(status)
            "$ansi$rangeText${ESC}[0m"
        } else {
            "$rangeText(${status.code})"
        }
    }

    private fun ansiForStatus(status: VlanStatus): String = when (status) {
        VlanStatus.FREE       -> "${ESC}[32m"   // green
        VlanStatus.BUSY       -> "${ESC}[33m"   // yellow
        VlanStatus.ERROR      -> "${ESC}[31m"   // red
        VlanStatus.CONFIGURED -> "${ESC}[35m"   // magenta
        VlanStatus.ANOTHER    -> "${ESC}[34m"   // blue
        VlanStatus.UNUSED     -> "${ESC}[90m"   // dark grey
        VlanStatus.SHARED     -> "${ESC}[38;5;208m" // orange
    }
}
