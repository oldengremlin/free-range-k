package net.ukrhub.noc.freerange.netconf

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSubsystem
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.apache.logging.log4j.LogManager
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.InputStream
import java.io.OutputStream
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * NETCONF client for Juniper devices using JSch SSH library.
 * Supports both "subsystem-netconf" and "exec" open channel modes.
 * Uses NETCONF 1.0 framing with ]]>]]> delimiter.
 */
class NetconfClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val openChannel: String = "subsystem-netconf",
    private val debug: Boolean = false
) : AutoCloseable {

    private val logger = LogManager.getLogger(NetconfClient::class.java)

    private lateinit var session: Session
    private lateinit var inputStream: InputStream
    private lateinit var outputStream: OutputStream

    companion object {
        private const val NETCONF_DELIMITER = "]]>]]>"
        private const val HELLO_RPC = """<?xml version="1.0" encoding="UTF-8"?>
<hello xmlns="urn:ietf:params:xml:ns:netconf:base:1.0">
  <capabilities>
    <capability>urn:ietf:params:xml:ns:netconf:base:1.0</capability>
  </capabilities>
</hello>
]]>]]>"""

        private const val GET_CONFIG_RPC = """<?xml version="1.0" encoding="UTF-8"?>
<rpc message-id="1" xmlns="urn:ietf:params:xml:ns:netconf:base:1.0">
  <get-config>
    <source><running/></source>
    <filter type="subtree">
      <configuration>
        <interfaces/>
      </configuration>
    </filter>
  </get-config>
</rpc>
]]>]]>"""

        private const val CLOSE_SESSION_RPC = """<?xml version="1.0" encoding="UTF-8"?>
<rpc message-id="9999" xmlns="urn:ietf:params:xml:ns:netconf:base:1.0">
  <close-session/>
</rpc>
]]>]]>"""
    }

    fun connect() {
        logger.debug("Connecting to {}:{} as {} via {}", host, port, username, openChannel)

        val jsch = JSch()
        session = jsch.getSession(username, host, port).apply {
            setPassword(password)
            setConfig("StrictHostKeyChecking", "no")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            connect(30_000)
        }
        logger.debug("SSH session established")

        if (openChannel.equals("exec", ignoreCase = true)) {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("xml-mode netconf need-trailer")
            inputStream = channel.inputStream
            outputStream = channel.outputStream
            channel.connect(10_000)
        } else {
            val channel = session.openChannel("subsystem") as ChannelSubsystem
            channel.setSubsystem("netconf")
            inputStream = channel.inputStream
            outputStream = channel.outputStream
            channel.connect(10_000)
        }

        val serverHello = readUntilDelimiter()
        logger.debug("Server hello received ({} chars)", serverHello.length)

        send(HELLO_RPC)
        logger.debug("Client hello sent")
    }

    fun fetchInterfacesConfig(): Document {
        logger.debug("Sending get-config RPC for interfaces")
        send(GET_CONFIG_RPC)

        val response = readUntilDelimiter()
        logger.debug("Received get-config response ({} chars)", response.length)

        if (debug) {
            System.err.println("=== NETCONF response ===")
            System.err.println(response)
            System.err.println("=== end NETCONF response ===")
        }

        return parseXmlNamespaceUnaware(response)
    }

    private fun parseXmlNamespaceUnaware(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setErrorHandler(null)
        }
        return builder.parse(xml.byteInputStream())
    }

    fun xpath(doc: Document, expression: String): NodeList {
        val xpathFactory = XPathFactory.newInstance()
        val xpath = xpathFactory.newXPath()
        val compiled = xpath.compile(expression)
        return compiled.evaluate(doc, XPathConstants.NODESET) as NodeList
    }

    private fun send(data: String) {
        outputStream.write(data.toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }

    private fun readUntilDelimiter(): String {
        val buffer = StringBuilder()
        val readBuf = ByteArray(4096)

        while (true) {
            val available = inputStream.available()
            val toRead = if (available > 0) minOf(available, readBuf.size) else 1
            val bytesRead = inputStream.read(readBuf, 0, toRead)
            if (bytesRead < 0) break

            buffer.append(String(readBuf, 0, bytesRead, Charsets.UTF_8))

            val str = buffer.toString()
            val delimIdx = str.indexOf(NETCONF_DELIMITER)
            if (delimIdx >= 0) {
                return str.substring(0, delimIdx)
            }
        }
        return buffer.toString()
    }

    override fun close() {
        try { send(CLOSE_SESSION_RPC) } catch (_: Exception) {}
        try {
            if (::session.isInitialized && session.isConnected) session.disconnect()
        } catch (_: Exception) {}
    }

    data class InterfaceVlanData(
        val ranges: Map<String, List<IntRange>>,
        val demuxUnits: Map<String, List<Int>>,
        val anotherVlans: Map<String, List<Int>>,
        val interfacesWithRanges: List<String>
    )

    /**
     * Parses VLAN data from the interfaces config document.
     *
     * Maps directly to the three Ruby CLI commands:
     * - command_ranges: matches "dynamic-profile ... ranges START END" lines
     *   → XML: interface/unit/dynamic-profile/ranges (with low/high children or text)
     * - command_demux:  matches "unit N ... unnumbered-address" lines
     *   → XML: interface/unit[unnumbered-address]
     * - command_another: matches "vlan-id N" lines
     *   → XML: interface/unit/vlan-id
     */
    fun parseInterfaceVlanData(doc: Document): InterfaceVlanData {
        val rangesMap = mutableMapOf<String, MutableList<IntRange>>()
        val demuxMap = mutableMapOf<String, MutableList<Int>>()
        val anotherMap = mutableMapOf<String, MutableList<Int>>()
        val interfacesWithRanges = mutableListOf<String>()

        val interfaces = xpath(doc, "//interfaces/interface")
        logger.debug("Found {} interface elements", interfaces.length)

        if (debug && interfaces.length > 0) {
            dumpInterfaceXml(interfaces)
        }

        for (i in 0 until interfaces.length) {
            val iface = interfaces.item(i) as? Element ?: continue
            val ifaceName = iface.getElementsByTagName("name").item(0)?.textContent?.trim() ?: continue

            val ifaceRangeList = mutableListOf<IntRange>()

            val units = iface.getElementsByTagName("unit")
            for (j in 0 until units.length) {
                val unit = units.item(j) as? Element ?: continue
                val unitNum = unit.getElementsByTagName("name").item(0)?.textContent?.trim()?.toIntOrNull()

                // command_ranges: dynamic-profile > ranges
                val dpElements = unit.getElementsByTagName("dynamic-profile")
                for (k in 0 until dpElements.length) {
                    val dp = dpElements.item(k) as? Element ?: continue
                    extractDynamicProfileRanges(dp, ifaceRangeList)
                }

                if (unitNum != null && unitNum > 0) {
                    // command_demux: units with unnumbered-address
                    if (unit.getElementsByTagName("unnumbered-address").length > 0) {
                        demuxMap.getOrPut(ifaceName) { mutableListOf() }.add(unitNum)
                    }

                    // command_another: units with explicit vlan-id
                    val vlanIdElem = unit.getElementsByTagName("vlan-id").item(0)
                    val vlanId = vlanIdElem?.textContent?.trim()?.toIntOrNull()
                    if (vlanId != null && vlanId > 0) {
                        anotherMap.getOrPut(ifaceName) { mutableListOf() }.add(vlanId)
                    }
                }
            }

            if (ifaceRangeList.isNotEmpty()) {
                rangesMap[ifaceName] = ifaceRangeList
                if (!interfacesWithRanges.contains(ifaceName)) interfacesWithRanges.add(ifaceName)
            }
        }

        logger.debug("Interfaces with ranges: {}", interfacesWithRanges)
        logger.debug("Total range entries: {}", rangesMap.values.sumOf { it.size })

        return InterfaceVlanData(rangesMap, demuxMap, anotherMap, interfacesWithRanges)
    }

    /**
     * Extracts VLAN ranges from a <dynamic-profile> element.
     *
     * Junos CLI: "dynamic-profile <name> ranges START END"
     * In XML this becomes a <ranges> child of <dynamic-profile>.
     * The range may be encoded as:
     *   - structured:  <ranges><low>1527</low><high>1702</high></ranges>
     *   - text hyphen: <ranges>1527-1702</ranges>
     *   - text space:  <ranges>1527 1702</ranges>
     *   - single:      <ranges>239</ranges>
     */
    private fun extractDynamicProfileRanges(dp: Element, result: MutableList<IntRange>) {
        val rangesNodes = dp.getElementsByTagName("ranges")
        for (i in 0 until rangesNodes.length) {
            val elem = rangesNodes.item(i) as? Element ?: continue

            // Structured: <low>/<high> children
            val lowText = elem.getElementsByTagName("low").item(0)?.textContent?.trim()
            val highText = elem.getElementsByTagName("high").item(0)?.textContent?.trim()
            if (lowText != null && highText != null) {
                val start = lowText.toIntOrNull()
                val end = highText.toIntOrNull()
                if (start != null && end != null && start <= end) {
                    result.add(start..end)
                    continue
                }
            }

            // Text: "1527-1702" or "1527 1702" or "239"
            val text = elem.textContent?.trim() ?: continue
            parseVlanRange(text)?.let { result.add(it) }
        }
    }

    private fun parseVlanRange(text: String): IntRange? {
        val trimmed = text.trim()
        if (trimmed.contains('-')) {
            val parts = trimmed.split('-', limit = 2)
            if (parts.size == 2) {
                val start = parts[0].trim().toIntOrNull() ?: return null
                val end = parts[1].trim().toIntOrNull() ?: return null
                if (start <= end) return start..end
            }
        }
        if (trimmed.contains(' ')) {
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) {
                val start = parts[0].toIntOrNull() ?: return null
                val end = parts[1].toIntOrNull() ?: return null
                if (start <= end) return start..end
            }
        }
        val single = trimmed.toIntOrNull() ?: return null
        return single..single
    }

    private fun dumpInterfaceXml(interfaces: NodeList) {
        val tf = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }
        for (i in 0 until minOf(interfaces.length, 5)) {
            val elem = interfaces.item(i) as? Element ?: continue
            val sw = StringWriter()
            tf.transform(DOMSource(elem), StreamResult(sw))
            System.err.println("=== Interface XML [${i + 1}/${interfaces.length}] ===")
            System.err.println(sw.toString().take(3000))
        }
    }
}
