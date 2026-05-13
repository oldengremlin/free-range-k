package net.ukrhub.noc.freerange.netconf

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSubsystem
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.apache.logging.log4j.LogManager
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
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
        <dynamic-profiles/>
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
     * Fetches interfaces + dynamic-profiles configuration via NETCONF get-config.
     * Returns parsed XML Document with namespace-unaware DOM.
     */
    fun fetchInterfacesConfig(): Document {
        logger.debug("Sending get-config RPC for interfaces + dynamic-profiles")
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
     *
     * Searches for VLAN ranges in multiple locations because Junos XML structure varies:
     * - Directly on interface or unit: <vlan-id-range> or <vlan-id-list>
     * - Under unit's <dynamic-profile> element: unit//dynamic-profile//vlan-id-range
     * - Under top-level <dynamic-profiles> section, mapped back to interfaces via profile name
     */
    fun parseInterfaceVlanData(doc: Document): InterfaceVlanData {
        val rangesMap = mutableMapOf<String, MutableList<IntRange>>()
        val demuxMap = mutableMapOf<String, MutableList<Int>>()
        val anotherMap = mutableMapOf<String, MutableList<Int>>()
        val interfacesWithRanges = mutableListOf<String>()

        // --- Step 1: parse top-level <dynamic-profiles> section ---
        // Build a map: profile-name -> list of vlan ranges defined in that profile
        val profileRanges = mutableMapOf<String, MutableList<IntRange>>()
        val dynamicProfiles = xpath(doc, "//dynamic-profiles/profile")
        logger.debug("Found {} top-level dynamic-profile elements", dynamicProfiles.length)
        for (i in 0 until dynamicProfiles.length) {
            val profile = dynamicProfiles.item(i) as? Element ?: continue
            val profileName = profile.getElementsByTagName("name").item(0)?.textContent?.trim() ?: continue
            val list = profileRanges.getOrPut(profileName) { mutableListOf() }
            extractVlanRangesFromElement(profile, list)
        }
        if (debug) {
            logger.debug("Dynamic profile ranges: {}", profileRanges)
        }

        // --- Step 2: parse <interfaces> section ---
        val interfaces = xpath(doc, "//interfaces/interface")
        logger.debug("Found {} interface elements", interfaces.length)

        if (debug && interfaces.length > 0) {
            // Dump first 3 interfaces as XML to help diagnose structure issues
            val tf = TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            }
            for (i in 0 until minOf(interfaces.length, 3)) {
                val elem = interfaces.item(i) as? Element ?: continue
                val sw = StringWriter()
                tf.transform(DOMSource(elem), StreamResult(sw))
                System.err.println("=== Interface XML [${i+1}/${interfaces.length}] ===")
                System.err.println(sw.toString().take(3000))
            }
        }

        for (i in 0 until interfaces.length) {
            val iface = interfaces.item(i) as? Element ?: continue
            val ifaceName = iface.getElementsByTagName("name").item(0)?.textContent?.trim() ?: continue

            // --- VLAN range search 1: direct vlan-id-range / vlan-id-list on interface or unit ---
            val ifaceRangeList = rangesMap.getOrPut(ifaceName) { mutableListOf() }
            val countBefore = ifaceRangeList.size
            extractVlanRangesFromElement(iface, ifaceRangeList)
            if (ifaceRangeList.size > countBefore && !interfacesWithRanges.contains(ifaceName)) {
                interfacesWithRanges.add(ifaceName)
            }

            // --- VLAN range search 2: profile references on the interface itself ---
            // <dynamic-profile> or <apply-macro> or <profile> child elements that reference a named profile
            val profileRefNames = collectProfileReferences(iface)
            for (profileRef in profileRefNames) {
                val pRanges = profileRanges[profileRef]
                if (!pRanges.isNullOrEmpty()) {
                    ifaceRangeList.addAll(pRanges)
                    if (!interfacesWithRanges.contains(ifaceName)) {
                        interfacesWithRanges.add(ifaceName)
                    }
                }
            }

            // --- Units: demux detection and explicit vlan-id ---
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

                // Profile references on unit level
                val unitProfileRefs = collectProfileReferences(unit)
                for (profileRef in unitProfileRefs) {
                    val pRanges = profileRanges[profileRef]
                    if (!pRanges.isNullOrEmpty()) {
                        ifaceRangeList.addAll(pRanges)
                        if (!interfacesWithRanges.contains(ifaceName)) {
                            interfacesWithRanges.add(ifaceName)
                        }
                    }
                }
            }

            if (ifaceRangeList.isEmpty()) {
                rangesMap.remove(ifaceName)
            }
        }

        logger.debug("Interfaces with ranges: {}", interfacesWithRanges)
        logger.debug("Total range entries: {}", rangesMap.values.sumOf { it.size })

        return InterfaceVlanData(rangesMap, demuxMap, anotherMap, interfacesWithRanges)
    }

    /**
     * Extracts VLAN ranges from an element's descendants, trying multiple known element names:
     * - vlan-id-range (e.g. "100-200" or "100 200")
     * - vlan-id-list  (alternative Junos name)
     * - members       (Ethernet-switching VLAN member ranges)
     */
    private fun extractVlanRangesFromElement(elem: Element, result: MutableList<IntRange>) {
        for (tagName in listOf("vlan-id-range", "vlan-id-list", "members")) {
            val nodes = elem.getElementsByTagName(tagName)
            for (k in 0 until nodes.length) {
                val text = nodes.item(k)?.textContent?.trim() ?: continue
                // A "members" value that is a single integer is a unit number, not a range — skip
                if (tagName == "members" && !text.contains('-') && !text.contains(' ')) continue
                parseVlanRange(text)?.let { result.add(it) }
            }
        }
    }

    /**
     * Collects dynamic-profile names referenced by an element.
     * Junos uses various XML paths to reference a profile:
     * - <dynamic-profile><profile-name>X</profile-name></dynamic-profile>
     * - <dynamic-profile><name>X</name></dynamic-profile>
     * - <apply-macro><name>X</name></apply-macro>
     */
    private fun collectProfileReferences(elem: Element): List<String> {
        val refs = mutableListOf<String>()
        val dpNodes = elem.getElementsByTagName("dynamic-profile")
        for (i in 0 until dpNodes.length) {
            val dp = dpNodes.item(i) as? Element ?: continue
            // look for profile-name or name child
            for (childTag in listOf("profile-name", "name")) {
                val child = dp.getElementsByTagName(childTag).item(0)
                val name = child?.textContent?.trim()
                if (!name.isNullOrEmpty()) refs.add(name)
            }
        }
        return refs
    }

    private fun parseVlanRange(text: String): IntRange? {
        val trimmed = text.trim()
        // Handle "N-M" format
        if (trimmed.contains('-')) {
            val parts = trimmed.split('-', limit = 2)
            if (parts.size == 2) {
                val start = parts[0].trim().toIntOrNull() ?: return null
                val end = parts[1].trim().toIntOrNull() ?: return null
                if (start <= end) return start..end
            }
        }
        // Handle "N M" space-separated format
        if (trimmed.contains(' ')) {
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) {
                val start = parts[0].toIntOrNull() ?: return null
                val end = parts[1].toIntOrNull() ?: return null
                if (start <= end) return start..end
            }
        }
        // Single value
        val single = trimmed.toIntOrNull() ?: return null
        return single..single
    }
}
