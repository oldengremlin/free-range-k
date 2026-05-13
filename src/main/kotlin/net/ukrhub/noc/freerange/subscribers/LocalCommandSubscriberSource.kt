package net.ukrhub.noc.freerange.subscribers

import org.apache.logging.log4j.LogManager

/**
 * Runs a local shell command to obtain RADIUS subscriber data.
 */
class LocalCommandSubscriberSource(private val command: String) : SubscriberSource {

    private val logger = LogManager.getLogger(LocalCommandSubscriberSource::class.java)

    override fun getSubscribers(): String {
        logger.debug("Running subscribers command: {}", command)
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        logger.debug("Subscribers command exit code: {}, output lines: {}", exitCode, output.lines().size)
        return output
    }
}
