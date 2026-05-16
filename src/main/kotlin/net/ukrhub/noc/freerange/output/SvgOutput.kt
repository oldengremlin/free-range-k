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
import org.apache.logging.log4j.LogManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object SvgOutput {

    private val logger = LogManager.getLogger(SvgOutput::class.java)

    private val STATUS_COLORS = mapOf(
        VlanStatus.FREE       to "#00ff00",
        VlanStatus.BUSY       to "#ffff00",
        VlanStatus.ERROR      to "#ff0000",
        VlanStatus.CONFIGURED to "#ff00ff",
        VlanStatus.ANOTHER    to "#0000ff",
        VlanStatus.UNUSED     to "#555555",
        VlanStatus.SHARED     to "#ff8800"
    )

    private val STATUS_NAMES = mapOf(
        VlanStatus.FREE       to "free",
        VlanStatus.BUSY       to "busy",
        VlanStatus.ERROR      to "error",
        VlanStatus.CONFIGURED to "configured",
        VlanStatus.ANOTHER    to "another",
        VlanStatus.UNUSED     to "unused",
        VlanStatus.SHARED     to "shared"
    )

    private const val CELL_W  = 13
    private const val CELL_H  = 22
    private const val ROWS    = 41
    private const val COLS    = 100
    private const val HEADER_H = 60
    private const val LABEL_W  = 50
    private const val FONT     = 14  // for titles, labels, legend
    private const val CELL_FONT = 7  // small letter inside each cell
    private const val CHAR_W   = 8

    fun save(
        statuses: Map<Int, VlanStatus>,
        counts: Map<VlanStatus, Int>,
        outputPath: String,
        target: String,
        interfaceName: String?,
        isGlobal: Boolean = false
    ): String {
        val dir = File(outputPath)
        if (!dir.exists()) dir.mkdirs()
        val safeName = interfaceName?.replace('/', '-')
        val filename = "free-range-$target${if (safeName != null) "-$safeName" else ""}.svg"
        val targetFile = File(dir, filename)
        val tmp = File.createTempFile("free-range-", ".svg.tmp", dir)
        try {
            tmp.writeText(buildSvg(statuses, counts, target, interfaceName, isGlobal))
            Files.move(tmp.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        logger.info("SVG saved: ${targetFile.absolutePath}")
        return filename
    }

    fun buildSvg(
        statuses: Map<Int, VlanStatus>,
        counts: Map<VlanStatus, Int>,
        target: String,
        interfaceName: String?,
        isGlobal: Boolean = false
    ): String {
        val w = LABEL_W + COLS * CELL_W + 10
        val h = HEADER_H + ROWS * CELL_H + 20 + 50
        val title = "VLAN Distribution for $target${if (interfaceName != null) " ($interfaceName)" else ""}"

        return buildString {
            appendLine("""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h">""")
            appendLine("""<rect width="$w" height="$h" fill="white"/>""")

            // Title
            appendLine("""<text x="10" y="25" font-family="monospace" font-size="18" font-weight="bold">${xml(title)}</text>""")

            // Column group headers (0..9)
            for (i in 0..9) {
                val x = LABEL_W + i * 10 * CELL_W + 4
                appendLine("""<text x="$x" y="${HEADER_H - 5}" font-family="monospace" font-size="$FONT">$i</text>""")
            }

            // Grid: each cell = <g><title>…</title><rect/><text/></g>
            // so the tooltip fires whether the cursor is on the letter or the background
            for (row in 0..40) {
                val startVlan = row * 100
                val endVlan   = minOf(startVlan + 99, 4094)
                val y         = HEADER_H + row * CELL_H

                appendLine("""<text x="5" y="${y + FONT}" font-family="monospace" font-size="$FONT">${"%4d".format(startVlan)}</text>""")

                for ((col, vlan) in (startVlan..endVlan).withIndex()) {
                    val status = statuses[vlan] ?: continue
                    val x      = LABEL_W + col * CELL_W
                    val fill   = STATUS_COLORS[status]!!
                    val stroke = darken(fill)
                    val name   = STATUS_NAMES[status]!!

                    appendLine("<g>")
                    appendLine("""  <title>VLAN $vlan, $name</title>""")
                    appendLine("""  <rect x="$x" y="$y" width="$CELL_W" height="$CELL_H" fill="$fill" stroke="$stroke" stroke-width="1"/>""")
                    // small letter in top-left corner of the cell
                    appendLine("""  <text x="${x + 2}" y="${y + CELL_FONT + 1}" font-family="monospace" font-size="$CELL_FONT">${status.code}</text>""")
                    appendLine("</g>")
                }
            }

            // Legend
            val legendY = h - 50
            var x = 10
            appendLine("""<text x="$x" y="$legendY" font-family="monospace" font-size="$FONT">Legend: </text>""")
            x += "Legend: ".length * CHAR_W
            val legendEntries = VlanStatus.entries.filter { isGlobal || it != VlanStatus.SHARED }.map { it to "=${STATUS_NAMES[it]}" }
            for ((idx, entry) in legendEntries.withIndex()) {
                val (status, label) = entry
                val fill = STATUS_COLORS[status]!!
                appendLine("""<rect x="$x" y="${legendY - FONT + 2}" width="10" height="12" fill="$fill" stroke="${darken(fill)}" stroke-width="1"/>""")
                appendLine("""<text x="${x + 2}" y="$legendY" font-family="monospace" font-size="$FONT">${status.code}</text>""")
                x += 12
                val sep = if (idx < legendEntries.size - 1) ", " else ""
                appendLine("""<text x="$x" y="$legendY" font-family="monospace" font-size="$FONT">${xml(label)}$sep</text>""")
                x += (label.length + sep.length) * CHAR_W
            }

            // Summary
            val summaryY = h - 30
            x = 10
            appendLine("""<text x="$x" y="$summaryY" font-family="monospace" font-size="$FONT">Total: </text>""")
            x += "Total: ".length * CHAR_W
            val summaryEntries = VlanStatus.entries.filter { isGlobal || it != VlanStatus.SHARED }
            for ((idx, status) in summaryEntries.withIndex()) {
                val fill  = STATUS_COLORS[status]!!
                val count = counts[status] ?: 0
                appendLine("""<rect x="$x" y="${summaryY - FONT + 2}" width="10" height="12" fill="$fill" stroke="${darken(fill)}" stroke-width="1"/>""")
                appendLine("""<text x="${x + 2}" y="$summaryY" font-family="monospace" font-size="$FONT">${status.code}</text>""")
                x += 12
                val sep      = if (idx < summaryEntries.size - 1) ", " else ""
                val countStr = "=$count$sep"
                appendLine("""<text x="$x" y="$summaryY" font-family="monospace" font-size="$FONT">$countStr</text>""")
                x += countStr.length * CHAR_W
            }

            append("</svg>")
        }
    }

    /** Returns a darkened version of a #rrggbb color (factor < 1.0 = darker). */
    private fun darken(hex: String, factor: Double = 0.6): String {
        val v = hex.trimStart('#').toLong(16)
        val r = ((v shr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = ((v shr 8  and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((v         and 0xFF) * factor).toInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(r, g, b)
    }

    private fun xml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
