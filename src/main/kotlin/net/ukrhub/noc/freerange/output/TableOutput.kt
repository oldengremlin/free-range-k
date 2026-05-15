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
 * Outputs VLAN distribution as an ASCII table (41 rows × 100 columns).
 *
 * Example:
 * ```
 * VLAN Distribution for router (xe-0/0/2)
 *      0         1         2    ...
 *    0 uuuuuuuuuuuuuuuuuuuuuuuu...
 *  100 fffffffffffffffffffbbbbbb...
 * ...
 * Legend: f=free, b=busy, e=error, c=configured, a=another, u=unused
 * Total: f=1000, b=50, e=3, c=20, a=5, u=3016
 * ```
 */
object TableOutput {

    fun print(
        statuses: Map<Int, VlanStatus>,
        counts: Map<VlanStatus, Int>,
        useColor: Boolean,
        target: String,
        interfaceName: String?
    ) {
        val header = buildString {
            append("VLAN Distribution for $target")
            if (interfaceName != null) append(" ($interfaceName)")
        }
        println(header)

        // Column header: groups of 10
        println("     0         1         2         3         4         5         6         7         8         9         ")

        for (row in 0..40) {
            val startVlan = row * 100
            val endVlan = minOf(startVlan + 99, 4094)
            val rowStr = (startVlan..endVlan).joinToString("") { vlan ->
                val status = statuses[vlan] ?: return@joinToString " "
                formatChar(status, useColor)
            }
            println("%4d %s".format(startVlan, rowStr))
        }

        // Legend
        val legend = buildLegend(useColor)
        println()
        println(legend)

        // Summary
        val summary = buildSummary(counts, useColor)
        println(summary)
    }

    private fun buildLegend(useColor: Boolean): String = buildString {
        append("Legend: ")
        append(coloredChar('f', VlanStatus.FREE, useColor))
        append("=free, ")
        append(coloredChar('b', VlanStatus.BUSY, useColor))
        append("=busy, ")
        append(coloredChar('e', VlanStatus.ERROR, useColor))
        append("=error, ")
        append(coloredChar('c', VlanStatus.CONFIGURED, useColor))
        append("=configured, ")
        append(coloredChar('a', VlanStatus.ANOTHER, useColor))
        append("=another, ")
        append(coloredChar('u', VlanStatus.UNUSED, useColor))
        append("=unused")
    }

    private fun buildSummary(counts: Map<VlanStatus, Int>, useColor: Boolean): String = buildString {
        append("Total: ")
        append(coloredChar('f', VlanStatus.FREE, useColor))
        append("=${counts[VlanStatus.FREE] ?: 0}, ")
        append(coloredChar('b', VlanStatus.BUSY, useColor))
        append("=${counts[VlanStatus.BUSY] ?: 0}, ")
        append(coloredChar('e', VlanStatus.ERROR, useColor))
        append("=${counts[VlanStatus.ERROR] ?: 0}, ")
        append(coloredChar('c', VlanStatus.CONFIGURED, useColor))
        append("=${counts[VlanStatus.CONFIGURED] ?: 0}, ")
        append(coloredChar('a', VlanStatus.ANOTHER, useColor))
        append("=${counts[VlanStatus.ANOTHER] ?: 0}, ")
        append(coloredChar('u', VlanStatus.UNUSED, useColor))
        append("=${counts[VlanStatus.UNUSED] ?: 0}")
    }

    private fun coloredChar(c: Char, status: VlanStatus, useColor: Boolean): String {
        return if (useColor) formatChar(status, true) else c.toString()
    }

    private const val ESC = ""

    private fun formatChar(status: VlanStatus, useColor: Boolean): String {
        val c = status.code
        if (!useColor) return c.toString()
        val bg = bgAnsiForStatus(status)
        return "${bg}${ESC}[30m$c${ESC}[0m"
    }

    private fun bgAnsiForStatus(status: VlanStatus): String = when (status) {
        VlanStatus.FREE       -> "${ESC}[48;5;2m"   // green background
        VlanStatus.BUSY       -> "${ESC}[48;5;3m"   // yellow background
        VlanStatus.ERROR      -> "${ESC}[48;5;1m"   // red background
        VlanStatus.CONFIGURED -> "${ESC}[48;5;5m"   // magenta background
        VlanStatus.ANOTHER    -> "${ESC}[48;5;4m"   // blue background
        VlanStatus.UNUSED     -> "${ESC}[48;5;8m"   // dark grey background
    }
}
