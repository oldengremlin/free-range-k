package net.ukrhub.noc.freerange.vlan

import net.ukrhub.noc.freerange.netconf.NetconfClient
import org.apache.logging.log4j.LogManager

/**
 * Processes VLAN data and computes per-VLAN status assignments.
 *
 * Status priority (matches Ruby logic):
 * 1. Mark all VLANs in configured ranges as FREE
 * 2. Overlay active subscribers: if in range → BUSY, else → ERROR
 * 3. Fill remaining 1-4094 as UNUSED
 * 4. Overlay "another" (explicit vlan-id): if already non-UNUSED → CONFIGURED, else → ANOTHER
 */
class VlanProcessor {

    private val logger = LogManager.getLogger(VlanProcessor::class.java)

    data class VlanResult(
        val statuses: Map<Int, VlanStatus>,       // vlan → status for all 1..4094
        val counts: Map<VlanStatus, Int>           // status → count
    )

    /**
     * Builds VLAN status map for a specific interface (or all interfaces if null).
     *
     * @param vlanData       parsed NETCONF interface data
     * @param activeVlans    set of VLAN IDs that have active RADIUS subscribers
     * @param interfaceName  specific interface to analyze, or null for all
     */
    fun process(
        vlanData: NetconfClient.InterfaceVlanData,
        activeVlans: Set<Int>,
        interfaceName: String?
    ): VlanResult {
        // Collect ranges for the target interface(s)
        val configuredRanges: List<IntRange>
        val demuxUnits: List<Int>
        val anotherVlans: List<Int>

        if (interfaceName != null) {
            configuredRanges = vlanData.ranges[interfaceName] ?: emptyList()
            demuxUnits = vlanData.demuxUnits[interfaceName] ?: emptyList()
            anotherVlans = vlanData.anotherVlans[interfaceName] ?: emptyList()
        } else {
            configuredRanges = vlanData.ranges.values.flatten()
            demuxUnits = vlanData.demuxUnits.values.flatten()
            anotherVlans = vlanData.anotherVlans.values.flatten()
        }

        logger.debug(
            "Processing: {} configured ranges, {} demux units, {} another vlans, {} active subscribers",
            configuredRanges.size, demuxUnits.size, anotherVlans.size, activeVlans.size
        )

        // Build combined set of "in-range" VLANs (from dynamic-profile ranges + demux units)
        val inRangeVlans = mutableSetOf<Int>()
        for (range in configuredRanges) {
            for (v in range) inRangeVlans.add(v)
        }
        for (unit in demuxUnits) {
            if (unit > 0) inRangeVlans.add(unit)
        }

        val allVlans = mutableMapOf<Int, VlanStatus>()

        // Step 1: mark in-range VLANs as FREE
        for (v in inRangeVlans) allVlans[v] = VlanStatus.FREE

        // Step 2: overlay active subscribers
        for (v in activeVlans) {
            if (v > 0) {
                allVlans[v] = if (inRangeVlans.contains(v)) VlanStatus.BUSY else VlanStatus.ERROR
            }
        }

        // Step 3: fill remaining 1..4094 as UNUSED
        for (v in 1..4094) {
            if (!allVlans.containsKey(v)) allVlans[v] = VlanStatus.UNUSED
        }

        // Step 4: overlay "another" vlan-id values
        // FREE  + vlan-id → CONFIGURED  (slot in range, configured but idle)
        // UNUSED + vlan-id → ANOTHER    (vlan-id outside any range)
        // BUSY / ERROR stay unchanged   (active subscriber takes priority)
        for (v in anotherVlans) {
            if (v > 0) {
                when (allVlans[v]) {
                    VlanStatus.FREE   -> allVlans[v] = VlanStatus.CONFIGURED
                    VlanStatus.UNUSED -> allVlans[v] = VlanStatus.ANOTHER
                    else -> {}
                }
            }
        }

        // Count statuses
        val counts = VlanStatus.entries.associateWith { 0 }.toMutableMap()
        for (status in allVlans.values) {
            counts[status] = (counts[status] ?: 0) + 1
        }

        logger.debug("Status counts: {}", counts)

        return VlanResult(allVlans, counts)
    }

    /**
     * Parses raw subscriber output and returns set of active VLAN IDs
     * for the given host target and optional interface filter.
     *
     * Line format: dhcp[_hex]_INTERFACE:VLAN@TARGET ...
     */
    fun parseActiveSubscribers(
        raw: String,
        target: String,
        interfaceFilter: String?
    ): Set<Int> {
        val pattern = Regex("""dhcp(?:_[0-9a-fA-F.]+)?_([^:]+):(\d+)@${Regex.fromLiteral(target)}""")
        val result = mutableSetOf<Int>()

        for (line in raw.lines()) {
            val first = line.trim().split(Regex("\\s+")).firstOrNull() ?: continue
            val match = pattern.find(first) ?: continue
            val iface = match.groupValues[1]
            val vlan = match.groupValues[2].toIntOrNull() ?: continue
            if (vlan > 0) {
                if (interfaceFilter == null || iface == interfaceFilter) {
                    result.add(vlan)
                }
            }
        }

        logger.debug("Parsed {} active subscriber VLANs for target={}, interface={}", result.size, target, interfaceFilter)
        return result
    }
}
