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
package net.ukrhub.noc.freerange.vlan

/**
 * VLAN status codes:
 * - FREE: in a configured range, no active subscriber
 * - BUSY: in a configured range, has an active subscriber
 * - ERROR: has an active subscriber but not in any configured range
 * - CONFIGURED: has vlan-id and that vlan-id is within a range
 * - ANOTHER: has vlan-id but it's outside all ranges
 * - UNUSED: outside any range, no subscriber
 * - SHARED: VLAN ID is active (non-UNUSED) on two or more routers simultaneously
 */
enum class VlanStatus(val code: Char) {
    FREE('f'),
    BUSY('b'),
    ERROR('e'),
    CONFIGURED('c'),
    ANOTHER('a'),
    UNUSED('u'),
    SHARED('s');

    companion object {
        fun fromCode(code: Char): VlanStatus? = entries.find { it.code == code }
    }
}
