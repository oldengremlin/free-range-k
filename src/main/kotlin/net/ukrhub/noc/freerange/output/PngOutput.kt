package net.ukrhub.noc.freerange.output

import net.ukrhub.noc.freerange.vlan.VlanStatus
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders VLAN distribution as a PNG image using Java AWT.
 *
 * Layout:
 *  - 41 rows × 100 columns of VLAN cells (12×20px each)
 *  - Header: 60px for title + column labels
 *  - Left label: 50px for row numbers
 *  - Footer: 50px for legend + summary
 *
 * Colors:
 *  - FREE       → #00FF00 (green)
 *  - BUSY       → #FFFF00 (yellow)
 *  - ERROR      → #FF0000 (red)
 *  - CONFIGURED → #FF00FF (magenta)
 *  - ANOTHER    → #0000FF (blue)
 *  - UNUSED     → #555555 (dark grey)
 */
object PngOutput {

    private val STATUS_COLORS = mapOf(
        VlanStatus.FREE       to Color(0x00, 0xFF, 0x00),
        VlanStatus.BUSY       to Color(0xFF, 0xFF, 0x00),
        VlanStatus.ERROR      to Color(0xFF, 0x00, 0x00),
        VlanStatus.CONFIGURED to Color(0xFF, 0x00, 0xFF),
        VlanStatus.ANOTHER    to Color(0x00, 0x00, 0xFF),
        VlanStatus.UNUSED     to Color(0x55, 0x55, 0x55)
    )

    private const val CELL_WIDTH = 12
    private const val CELL_HEIGHT = 20
    private const val ROWS = 41
    private const val COLS = 100
    private const val HEADER_HEIGHT = 60
    private const val LABEL_WIDTH = 50
    private const val FONT_SIZE = 14
    private const val TITLE_FONT_SIZE = 18

    fun save(
        statuses: Map<Int, VlanStatus>,
        counts: Map<VlanStatus, Int>,
        outputPath: String,
        target: String,
        interfaceName: String?
    ) {
        val width = LABEL_WIDTH + COLS * CELL_WIDTH + 10
        val height = HEADER_HEIGHT + ROWS * CELL_HEIGHT + 20 + 50

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            color = Color.WHITE
            fillRect(0, 0, width, height)
        }

        val monoFont = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE)
        val titleFont = Font(Font.MONOSPACED, Font.BOLD, TITLE_FONT_SIZE)

        // Title
        g.font = titleFont
        g.color = Color.BLACK
        val title = "VLAN Distribution for $target${if (interfaceName != null) " ($interfaceName)" else ""}"
        g.drawString(title, 10, 25)

        // Column headers (0-9 groups)
        g.font = monoFont
        for (i in 0..9) {
            val x = LABEL_WIDTH + i * 10 * CELL_WIDTH - 3
            g.color = Color.BLACK
            g.drawString(i.toString(), x + 5, HEADER_HEIGHT - 5)
        }

        // VLAN grid
        for (row in 0..40) {
            val startVlan = row * 100
            val endVlan = minOf(startVlan + 99, 4094)
            val y = HEADER_HEIGHT + row * CELL_HEIGHT

            // Row label
            g.color = Color.BLACK
            g.font = monoFont
            g.drawString("%4d".format(startVlan), 5, y + FONT_SIZE)

            for ((colIdx, vlan) in (startVlan..endVlan).withIndex()) {
                val status = statuses[vlan]
                val x = LABEL_WIDTH + colIdx * CELL_WIDTH

                if (status != null) {
                    val color = STATUS_COLORS[status] ?: Color.WHITE
                    g.color = color
                    g.fillRect(x, y, CELL_WIDTH, CELL_HEIGHT)
                    g.color = Color.BLACK
                    g.drawString(status.code.toString(), x + 2, y + FONT_SIZE)
                }
            }
        }

        // Legend
        val legendY = height - 50
        drawLegendLine(g, monoFont, legendY)

        // Summary
        val summaryY = height - 30
        drawSummaryLine(g, monoFont, summaryY, counts)

        g.dispose()

        // Save file
        val dir = File(outputPath)
        if (!dir.exists()) dir.mkdirs()
        val safeName = interfaceName?.replace('/', '-')
        val filename = "free-range-$target${if (safeName != null) "-$safeName" else ""}.png"
        val file = File(dir, filename)
        ImageIO.write(image, "PNG", file)
        println("Image saved: ${file.absolutePath}")
    }

    private fun drawLegendLine(g: java.awt.Graphics2D, font: Font, y: Int) {
        g.font = font
        var x = 10

        g.color = Color.BLACK
        g.drawString("Legend: ", x, y)
        x += 8 * 8  // approx width of "Legend: "

        val legendEntries = listOf(
            VlanStatus.FREE to "=free",
            VlanStatus.BUSY to "=busy",
            VlanStatus.ERROR to "=error",
            VlanStatus.CONFIGURED to "=configured",
            VlanStatus.ANOTHER to "=another",
            VlanStatus.UNUSED to "=unused"
        )

        for ((idx, entry) in legendEntries.withIndex()) {
            val (status, label) = entry
            val color = STATUS_COLORS[status]!!
            g.color = color
            g.fillRect(x, y - FONT_SIZE + 2, 10, 10 + 2)
            g.color = Color.BLACK
            g.drawString(status.code.toString(), x + 2, y)
            x += 12
            g.drawString(label, x, y)
            x += label.length * 8
            if (idx < legendEntries.size - 1) {
                g.drawString(", ", x, y)
                x += 2 * 8
            }
        }
    }

    private fun drawSummaryLine(g: java.awt.Graphics2D, font: Font, y: Int, counts: Map<VlanStatus, Int>) {
        g.font = font
        var x = 10

        g.color = Color.BLACK
        g.drawString("Total: ", x, y)
        x += 7 * 8

        val summaryEntries = listOf(
            VlanStatus.FREE,
            VlanStatus.BUSY,
            VlanStatus.ERROR,
            VlanStatus.CONFIGURED,
            VlanStatus.ANOTHER,
            VlanStatus.UNUSED
        )

        for ((idx, status) in summaryEntries.withIndex()) {
            val count = counts[status] ?: 0
            val color = STATUS_COLORS[status]!!
            g.color = color
            g.fillRect(x, y - FONT_SIZE + 2, 10, 10 + 2)
            g.color = Color.BLACK
            g.drawString(status.code.toString(), x + 2, y)
            x += 12
            val countStr = "=$count"
            g.drawString(countStr, x, y)
            x += countStr.length * 8
            if (idx < summaryEntries.size - 1) {
                g.drawString(", ", x, y)
                x += 2 * 8
            }
        }
    }
}
