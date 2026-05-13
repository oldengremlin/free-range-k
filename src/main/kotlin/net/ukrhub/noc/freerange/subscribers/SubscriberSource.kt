package net.ukrhub.noc.freerange.subscribers

/**
 * Source of RADIUS subscriber data.
 * Implementations can be local command, REST API, etc.
 */
interface SubscriberSource {
    /** Returns raw subscriber output */
    fun getSubscribers(): String
}
