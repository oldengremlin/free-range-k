package net.ukrhub.noc.freerange.output

import net.ukrhub.noc.freerange.vlan.VlanProcessor
import net.ukrhub.noc.freerange.vlan.VlanStatus
import java.io.File

object WebOutput {

    data class IfaceResult(val name: String, val pngFile: String)

    data class RouterResult(
        val hostLabel: String,
        val overallPng: String,
        val overallVlanResult: VlanProcessor.VlanResult,
        val interfaces: List<IfaceResult>
    )

    fun generate(routers: List<RouterResult>, outputDir: String) {
        val file = File(outputDir, "index.html")
        file.writeText(buildHtml(routers))
        println("Web index saved: ${file.absolutePath}")
    }

    private fun buildHtml(routers: List<RouterResult>): String = buildString {
        appendLine(
            """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>VLAN Distribution</title>
<style>
${CSS.trimIndent()}
</style>
</head>
<body>
<h1>VLAN Distribution</h1>"""
        )

        // Top-level router tab bar
        append("<div class=\"tab-bar\" id=\"routers-bar\">")
        for ((ri, r) in routers.withIndex()) {
            val active = if (ri == 0) " active" else ""
            append("<button class=\"tab$active\" onclick=\"switchTab('routers',$ri)\">")
            append(escapeHtml(r.hostLabel))
            append("</button>")
        }
        appendLine("</div>")

        // Router panels
        appendLine("<div class=\"panels\" id=\"routers-panels\">")
        for ((ri, r) in routers.withIndex()) {
            val active = if (ri == 0) " active" else ""
            appendLine("<div class=\"panel$active\">")

            // Text summary above the tabs
            appendLine("<pre>${buildSummaryHtml(r.overallVlanResult)}</pre>")

            // Inner tab bar
            append("<div class=\"tab-bar inner\" id=\"r$ri-bar\">")
            append("<button class=\"tab active\" onclick=\"switchTab('r$ri',0)\">overall</button>")
            for ((ii, iface) in r.interfaces.withIndex()) {
                append("<button class=\"tab\" onclick=\"switchTab('r$ri',${ii + 1})\">")
                append(escapeHtml(iface.name))
                append("</button>")
            }
            appendLine("</div>")

            // Inner panels
            appendLine("<div class=\"panels\" id=\"r$ri-panels\">")

            // Overall panel: PNG only
            appendLine("<div class=\"panel active\">")
            appendLine("<img src=\"${escapeHtml(r.overallPng)}\" alt=\"${escapeHtml(r.hostLabel)}\">")
            appendLine("</div>")

            // Per-interface panels: PNG only
            for (iface in r.interfaces) {
                appendLine("<div class=\"panel\">")
                appendLine("<img src=\"${escapeHtml(iface.pngFile)}\" alt=\"${escapeHtml(iface.name)}\">")
                appendLine("</div>")
            }

            appendLine("</div>") // r$ri-panels
            appendLine("</div>") // router panel
        }
        appendLine("</div>") // routers-panels

        appendLine(
            """<script>
${JS.trimIndent()}
</script>
</body>
</html>"""
        )
    }

    private fun buildSummaryHtml(result: VlanProcessor.VlanResult): String = buildString {
        val statuses = result.statuses
        val sorted = statuses.keys.sorted()
        if (sorted.isEmpty()) return ""

        val parts = mutableListOf<String>()
        var rangeStart = sorted[0]
        var rangeEnd = rangeStart
        var curStatus = statuses[rangeStart]!!

        fun flush(s: Int, e: Int, st: VlanStatus) {
            val range = if (s == e) "$s" else "$s-$e"
            parts.add("<span style=\"color:${colorForStatus(st)}\">$range(${st.code})</span>")
        }

        for (idx in 1 until sorted.size) {
            val vlan = sorted[idx]
            val st = statuses[vlan]!!
            if (vlan == rangeEnd + 1 && st == curStatus) {
                rangeEnd = vlan
            } else {
                flush(rangeStart, rangeEnd, curStatus)
                rangeStart = vlan; rangeEnd = vlan; curStatus = st
            }
        }
        flush(rangeStart, rangeEnd, curStatus)

        append(parts.joinToString(","))
        append("\nTotal: ")
        append(VlanStatus.entries.joinToString(", ") { s ->
            "<span style=\"color:${colorForStatus(s)}\">${s.code}=${result.counts[s] ?: 0}</span>"
        })
    }

    private fun colorForStatus(status: VlanStatus): String = when (status) {
        VlanStatus.FREE       -> "#33dd66"
        VlanStatus.BUSY       -> "#ffcc00"
        VlanStatus.ERROR      -> "#ff5555"
        VlanStatus.CONFIGURED -> "#dd55ff"
        VlanStatus.ANOTHER    -> "#4499ff"
        VlanStatus.UNUSED     -> "#888888"
    }

    private fun escapeHtml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private val CSS = """
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: monospace; padding: 12px; background: #f0f0f0; color: #333; }
        h1 { font-size: 1.2em; margin-bottom: 10px; color: #111; }
        .tab-bar { display: flex; flex-wrap: wrap; gap: 2px; }
        .tab-bar.inner { margin-top: 12px; }
        .tab {
            padding: 5px 14px; cursor: pointer; border: 1px solid #aaa;
            background: #ddd; color: #444; font-family: monospace; font-size: 0.9em;
            border-bottom: none; border-radius: 4px 4px 0 0;
        }
        .tab.active { background: #fff; color: #000; border-color: #777; font-weight: bold; }
        .tab:hover:not(.active) { background: #e8e8e8; }
        .panels > .panel { display: none; border: 1px solid #777; background: #fff; padding: 10px; }
        .panels > .panel.active { display: block; }
        img { max-width: 100%; display: block; }
        pre {
            margin-top: 10px; padding: 8px; background: #1a1a1a; color: #ccc;
            font-size: 1em; overflow-x: auto; white-space: pre-wrap; line-height: 1.5;
        }
    """

    private val JS = """
        function switchTab(groupId, idx) {
            document.getElementById(groupId + '-bar')
                .querySelectorAll('.tab')
                .forEach(function(t, i) { t.classList.toggle('active', i === idx); });
            document.getElementById(groupId + '-panels')
                .querySelectorAll(':scope > .panel')
                .forEach(function(p, i) { p.classList.toggle('active', i === idx); });
        }
    """
}
