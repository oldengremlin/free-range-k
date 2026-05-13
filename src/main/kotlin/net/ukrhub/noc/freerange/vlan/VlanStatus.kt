package net.ukrhub.noc.freerange.vlan

/**
 * VLAN status codes:
 * - FREE: in a configured range, no active subscriber
 * - BUSY: in a configured range, has an active subscriber
 * - ERROR: has an active subscriber but not in any configured range
 * - CONFIGURED: has vlan-id and that vlan-id is within a range
 * - ANOTHER: has vlan-id but it's outside all ranges
 * - UNUSED: outside any range, no subscriber
 */
enum class VlanStatus(val code: Char) {
    FREE('f'),
    BUSY('b'),
    ERROR('e'),
    CONFIGURED('c'),
    ANOTHER('a'),
    UNUSED('u');

    companion object {
        fun fromCode(code: Char): VlanStatus? = entries.find { it.code == code }
    }
}
