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
