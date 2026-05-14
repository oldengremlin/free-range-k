package net.ukrhub.noc.freerange

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Log {
    private val FMT = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH)

    fun info(message: String) = print(message)
    fun error(message: String) = print(message)

    private fun print(message: String) {
        val ts = ZonedDateTime.now().format(FMT)
        System.err.println("- - - [$ts] $message")
    }
}
