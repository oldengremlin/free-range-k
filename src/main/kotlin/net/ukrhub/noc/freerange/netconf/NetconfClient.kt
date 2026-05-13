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
import javax.xml.parsers.DocumentBuilderFactory
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

        private const val GET_CONFIG_INTERFACES_RPC = """<?xml version="1.0" encoding="UTF-8"?>
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

    /**
     * Connects to device and performs NETCONF hello exchange.
     */
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

        // Read server hello
        val serverHello = readUntilDelimiter()
        logger.debug("Server hello received ({} chars)", serverHello.length)

        // Send client hello
        send(HELLO_RPC)
        logger.debug("Client hello sent")
    }

    /**
     * Fetches the interfaces configuration via NETCONF get-config.
     * Returns parsed XML Document with namespace-unaware DOM.
     */
    fun fetchInterfacesConfig(): Document {
        logger.debug("Sending get-config RPC for interfaces")
        send(GET_CONFIG_INTERFACES_RPC)

        val response = readUntilDelimiter()
        logger.debug("Received get-config response ({} chars)", response.length)

        if (debug) {
            logger.debug("NETCONF response:\n{}", response)
        }

        return parseXmlNamespaceUnaware(response)
    }

    /**
     * Parses XML string into a Document with namespace-unaware processing.
     * This allows XPath queries without namespace prefixes.
     */
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

    /**
     * Evaluates an XPath expression on the document and returns matching NodeList.
     */
    fun xpath(doc: Document, expression: String): NodeList {
        val xpathFactory = XPathFactory.newInstance()
        val xpath = xpathFactory.newXPath()
        val compiled = xpath.compile(expression)
        return compiled.evaluate(doc, XPathConstants.NODESET) as NodeList
    }

    /**
     * Sends a string to the NETCONF channel.
     */
    private fun send(data: String) {
        outputStream.write(data.toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }

    /**
     * Reads from the NETCONF channel until the ]]>]]> delimiter is found.
     */
    private fun readUntilDelimiter(): String {
        val buffer = StringBuilder()
        val delimBytes = NETCONF_DELIMITER.toByteArray(Charsets.UTF_8)
        val delimLen = delimBytes.size
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
        try {
            send(CLOSE_SESSION_RPC)
        } catch (_: Exception) {}
        try {
            if (::session.isInitialized && session.isConnected) {
                session.disconnect()
            }
        } catch (_: Exception) {}
    }

    /**
     * Data class for parsed interface VLAN configuration.
     */
    data class InterfaceVlanData(
        val ranges: Map<String, List<IntRange>>,      // interface -> list of VLAN ranges from dynamic-profile
        val demuxUnits: Map<String, List<Int>>,        // interface -> list of unit numbers (demux, unnumbered)
        val anotherVlans: Map<String, List<Int>>,      // interface -> list of explicit vlan-id values
        val interfacesWithRanges: List<String>         // interfaces that have vlan-id-range
    )

    /**
     * Parses all relevant VLAN data from the interfaces configuration document.
     */
    fun parseInterfaceVlanData(doc: Document): InterfaceVlanData {
        val rangesMap = mutableMapOf<String, MutableList<IntRange>>()
        val demuxMap = mutableMapOf<String, MutableList<Int>>()
        val anotherMap = mutableMapOf<String, MutableList<Int>>()
        val interfacesWithRanges = mutableListOf<String>()

        val interfaces = xpath(doc, "//interfaces/interface")
        logger.debug("Found {} interface elements", interfaces.length)

        for (i in 0 until interfaces.length) {
            val iface = interfaces.item(i) as? Element ?: continue
            val ifaceName = iface.getElementsByTagName("name").item(0)?.textContent?.trim() ?: continue

            // Check for vlan-id-range (command_ranges / command_interfaces)
            val vlanIdRanges = iface.getElementsByTagName("vlan-id-range")
            if (vlanIdRanges.length > 0) {
                if (!interfacesWithRanges.contains(ifaceName)) {
                    interfacesWithRanges.add(ifaceName)
                }
                val list = rangesMap.getOrPut(ifaceName) { mutableListOf() }
                for (j in 0 until vlanIdRanges.length) {
                    val rangeText = vlanIdRanges.item(j)?.textContent?.trim() ?: continue
                    parseVlanRange(rangeText)?.let { list.add(it) }
                }
            }

            // Check units for demux (unnumbered-address) and vlan-id
            val units = iface.getElementsByTagName("unit")
            for (j in 0 until units.length) {
                val unit = units.item(j) as? Element ?: continue
                val unitName = unit.getElementsByTagName("name").item(0)?.textContent?.trim() ?: continue
                val unitNum = unitName.toIntOrNull() ?: continue

                // command_demux: units with unnumbered-address
                val unnumbered = unit.getElementsByTagName("unnumbered-address")
                if (unnumbered.length > 0 && unitNum > 0) {
                    demuxMap.getOrPut(ifaceName) { mutableListOf() }.add(unitNum)
                }

                // command_another: units with explicit vlan-id
                val vlanIdElements = unit.getElementsByTagName("vlan-id")
                if (vlanIdElements.length > 0) {
                    val vlanId = vlanIdElements.item(0)?.textContent?.trim()?.toIntOrNull()
                    if (vlanId != null && vlanId > 0) {
                        anotherMap.getOrPut(ifaceName) { mutableListOf() }.add(vlanId)
                    }
                }
            }
        }

        return InterfaceVlanData(rangesMap, demuxMap, anotherMap, interfacesWithRanges)
    }

    private fun parseVlanRange(text: String): IntRange? {
        val trimmed = text.trim()
        return if (trimmed.contains('-')) {
            val parts = trimmed.split('-')
            val start = parts[0].toIntOrNull() ?: return null
            val end = parts[1].toIntOrNull() ?: return null
            start..end
        } else {
            val single = trimmed.toIntOrNull() ?: return null
            single..single
        }
    }
}
