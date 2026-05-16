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
package net.ukrhub.noc.freerange

import net.ukrhub.noc.freerange.netconf.NetconfClient
import net.ukrhub.noc.freerange.output.PngOutput
import net.ukrhub.noc.freerange.output.SvgOutput
import net.ukrhub.noc.freerange.output.TableOutput
import net.ukrhub.noc.freerange.output.TextOutput
import net.ukrhub.noc.freerange.output.WebOutput
import net.ukrhub.noc.freerange.subscribers.LocalCommandSubscriberSource
import net.ukrhub.noc.freerange.subscribers.MssqlSubscriberSource
import net.ukrhub.noc.freerange.vlan.VlanProcessor
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.config.Configurator
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.system.exitProcess

@Command(
    name = "free-range",
    mixinStandardHelpOptions = true,
    description = ["Analyzes VLAN distribution on Juniper network devices via NETCONF."],
    version = ["free-range 1.0.0"]
)
class FreeRangeCommand : Runnable {

    @Parameters(index = "0", description = ["Router hostname or IP (alternative to -H)"], arity = "0..1")
    var hostArg: String? = null

    @Option(names = ["-H", "--host"], description = ["Comma-separated list of router hostnames/IPs"])
    var hostsOpt: String? = null

    @Option(names = ["-s", "--suffix"], description = ["Domain suffix appended to bare hostnames (e.g. ukrhub.net)"])
    var suffix: String? = null

    @Option(names = ["-u", "--username"], description = ["SSH/NETCONF username"])
    var username: String? = null

    @Option(names = ["-p", "--password"], description = ["SSH/NETCONF password"])
    var password: String? = null

    @Option(names = ["-n", "--no-color"], description = ["Disable colored output"])
    var noColor: Boolean = false

    @Option(names = ["-d", "--debug"], description = ["Enable debug mode"])
    var debug: Boolean = false

    @Option(names = ["-t", "--table"], description = ["Display VLAN distribution table"])
    var table: Boolean = false

    @Option(names = ["-g", "--table-png"], description = ["Save VLAN distribution as PNG to given directory"])
    var tablePng: String? = null

    @Option(names = ["-i", "--interface"], description = ["Interface name (e.g. xe-0/0/2) or 'all'"])
    var interfaceName: String? = null

    @Option(names = ["-c", "--config"], description = ["Path to YAML config file"])
    var configFile: String? = null

    @Option(names = ["--web"], description = ["Generate index.html dashboard in -g directory (requires -g)"])
    var web: Boolean = false

    @Option(names = ["-G", "--global"], description = ["Show global VLAN aggregation tab in --web dashboard (requires 2+ routers)"])
    var global: Boolean = false

    private val logger = LogManager.getLogger(FreeRangeCommand::class.java)
    private val maxConcurrent = (System.getenv("FREE_RANGE_MAX_CONCURRENT") ?: "5").toInt()
    private val semaphore = Semaphore(maxConcurrent)

    override fun run() {
        if (web && tablePng == null) {
            logger.error("Error: --web requires -g/--table-png to specify output directory.")
            exitProcess(1)
        }

        val hosts = resolveHosts()
        val effectiveWeb = web || System.getenv("FREE_RANGE_WEB")?.isNotEmpty() == true
        val effectiveGlobal = global || System.getenv("FREE_RANGE_GLOBAL")?.isNotEmpty() == true
        val effectivePng = tablePng ?: System.getenv("FREE_RANGE_TABLE_PNG")

        if (effectiveWeb && effectivePng == null) {
            logger.error("Error: FREE_RANGE_WEB requires FREE_RANGE_TABLE_PNG to be set.")
            exitProcess(1)
        }

        // Fetch RADIUS subscribers once — they're global across all routers
        val firstConfig = buildConfig(hosts.first())
        if (firstConfig.debug) Configurator.setLevel("net.ukrhub.noc.freerange", Level.DEBUG)
        val rawSubscribers = fetchSubscribers(firstConfig)

        val processor = VlanProcessor()

        if (effectiveWeb) {
            logger.info("Processing {} host(s) in parallel (max {} concurrent)", hosts.size, maxConcurrent)
            val results = ConcurrentHashMap<String, WebOutput.RouterResult>()
            runParallel(hosts) { host ->
                semaphore.acquireUninterruptibly()
                try {
                    results[host] = processHostForWeb(host, rawSubscribers, processor, effectivePng!!)
                } finally {
                    semaphore.release()
                }
            }
            val routerResults = hosts.mapNotNull { results[it] }
            val allResults = if (effectiveGlobal && routerResults.size > 1) {
                val globalResult = processor.mergeGlobal(routerResults.map { it.overallVlanResult })
                val globalSvg = SvgOutput.save(globalResult.statuses, globalResult.counts, effectivePng!!, "GLOBAL", null, isGlobal = true)
                val globalEntry = WebOutput.RouterResult("Global", globalSvg, globalResult, emptyList())
                listOf(globalEntry) + routerResults
            } else {
                routerResults
            }
            WebOutput.generate(allResults, effectivePng!!)
        } else if (effectivePng != null) {
            logger.info("Processing {} host(s) in parallel (max {} concurrent)", hosts.size, maxConcurrent)
            runParallel(hosts) { host ->
                semaphore.acquireUninterruptibly()
                try {
                    processHost(buildConfig(host), rawSubscribers, processor)
                } finally {
                    semaphore.release()
                }
            }
        } else {
            for (host in hosts) {
                processHost(buildConfig(host), rawSubscribers, processor)
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun runParallel(hosts: List<String>, task: (String) -> Unit) {
        if (hosts.isEmpty()) return
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            for (host in hosts) {
                executor.submit {
                    try {
                        task(host)
                    } catch (ex: Exception) {
                        logger.error("Task failed for {}: {}", host, ex.message)
                        logger.debug("Task exception for {}", host, ex)
                    }
                }
            }
        }
    }

    private fun resolveHosts(): List<String> {
        val raw = hostsOpt?.split(',')?.map { it.trim() }
            ?: hostArg?.let { listOf(it) }
            ?: System.getenv("FREE_RANGE_HOST")?.split(',')?.map { it.trim() }
            ?: run {
                logger.error("Error: host is required.")
                logger.error("Usage: free-range <host> [options]  or  free-range -H <host>[,<host>...] [options]")
                exitProcess(1)
            }
        val effectiveSuffix = suffix ?: System.getenv("FREE_RANGE_SUFFIX")
        return raw.map { h -> if (effectiveSuffix != null && !h.contains('.')) "$h.$effectiveSuffix" else h }
    }

    private fun buildConfig(host: String): AppConfig = try {
        AppConfig.resolve(
            host = host,
            cliUsername = username,
            cliPassword = password,
            cliPort = null,
            cliNoColor = noColor,
            cliDebug = debug,
            cliTable = table,
            cliTablePng = tablePng,
            cliInterface = interfaceName,
            cliConfigFile = configFile,
            cliSuffix = suffix
        )
    } catch (e: IllegalStateException) {
        logger.error("Error: ${e.message}")
        exitProcess(1)
    }

    private fun fetchSubscribers(config: AppConfig): String {
        val source = if (config.accServer != null && config.accUser != null && config.accPassword != null) {
            logger.info("Fetching subscribers from MSSQL (${config.accServer})...")
            MssqlSubscriberSource(
                server = config.accServer,
                database = config.accDatabase,
                user = config.accUser,
                password = config.accPassword,
                port = config.accPort,
            )
        } else {
            logger.info("Fetching subscribers via command...")
            LocalCommandSubscriberSource(config.subscribersCommand)
        }
        val raw = try {
            source.getSubscribers()
        } catch (e: Exception) {
            logger.error("Error fetching subscribers: ${e.message}")
            logger.debug("Subscriber fetch exception", e)
            exitProcess(1)
        }
        if (raw.isBlank()) {
            logger.error("Error: subscriber source returned empty output.")
            exitProcess(1)
        }
        logger.debug("Received {} lines of subscriber data", raw.lines().size)
        return raw
    }

    private fun fetchVlanData(config: AppConfig): NetconfClient.InterfaceVlanData {
        logger.info("Connecting to ${config.host}...")
        return try {
            NetconfClient(
                host = config.host,
                port = config.netconfPort,
                username = config.username,
                password = config.password,
                openChannel = config.openChannel,
                debug = config.debug
            ).use { client ->
                client.connect()
                logger.info("Connected. Fetching interface configuration...")
                val doc = client.fetchInterfacesConfig()
                client.parseInterfaceVlanData(doc)
            }
        } catch (e: Exception) {
            logger.error("Error communicating with ${config.host}: ${e.message}")
            logger.debug("NETCONF exception", e)
            throw RuntimeException("NETCONF failed for ${config.host}", e)
        }
    }

    private fun processHost(config: AppConfig, rawSubscribers: String, processor: VlanProcessor) {
        val vlanData = fetchVlanData(config)

        val interfacesToProcess: List<String?> = when (config.interfaceName) {
            "all" -> {
                val ifaces = vlanData.interfacesWithRanges
                if (ifaces.isEmpty()) {
                    logger.error("No interfaces with configured ranges found on ${config.host}.")
                    throw RuntimeException("No interfaces on ${config.host}")
                }
                ifaces
            }
            null -> listOf(null)
            else -> listOf(config.interfaceName)
        }

        for (iface in interfacesToProcess) {
            val activeVlans = processor.parseActiveSubscribers(rawSubscribers, config.hostLabel, iface)
            val result = processor.process(vlanData, activeVlans, iface)
            when {
                config.tablePng != null -> PngOutput.save(result.statuses, result.counts, config.tablePng!!, config.hostLabel, iface)
                config.table -> TableOutput.print(result.statuses, result.counts, config.useColor, config.hostLabel, iface)
                else -> TextOutput.print(result.statuses, config.useColor, config.hostLabel, iface)
            }
        }
    }

    private fun processHostForWeb(
        host: String,
        rawSubscribers: String,
        processor: VlanProcessor,
        dir: String
    ): WebOutput.RouterResult {
        val config = buildConfig(host)
        val vlanData = fetchVlanData(config)

        // Overall (no interface filter)
        val overallActive = processor.parseActiveSubscribers(rawSubscribers, config.hostLabel, null)
        val overallResult = processor.process(vlanData, overallActive, null)
        val overallSvg = SvgOutput.save(overallResult.statuses, overallResult.counts, dir, config.hostLabel, null)

        // Per-interface
        val ifaceResults = vlanData.interfacesWithRanges.map { iface ->
            val active = processor.parseActiveSubscribers(rawSubscribers, config.hostLabel, iface)
            val result = processor.process(vlanData, active, iface)
            val svgFile = SvgOutput.save(result.statuses, result.counts, dir, config.hostLabel, iface)
            WebOutput.IfaceResult(iface, svgFile)
        }

        return WebOutput.RouterResult(config.hostLabel, overallSvg, overallResult, ifaceResults)
    }
}

fun main(args: Array<String>) {
    val cmd = CommandLine(FreeRangeCommand())
    exitProcess(cmd.execute(*args))
}
