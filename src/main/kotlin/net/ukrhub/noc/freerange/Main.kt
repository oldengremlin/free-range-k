package net.ukrhub.noc.freerange

import net.ukrhub.noc.freerange.netconf.NetconfClient
import net.ukrhub.noc.freerange.output.PngOutput
import net.ukrhub.noc.freerange.output.TableOutput
import net.ukrhub.noc.freerange.output.TextOutput
import net.ukrhub.noc.freerange.subscribers.LocalCommandSubscriberSource
import net.ukrhub.noc.freerange.vlan.VlanProcessor
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.config.Configurator
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import kotlin.system.exitProcess

@Command(
    name = "free-range",
    mixinStandardHelpOptions = true,
    description = ["Analyzes VLAN distribution on Juniper network devices via NETCONF."],
    version = ["free-range 1.0.0"]
)
class FreeRangeCommand : Runnable {

    @Parameters(index = "0", description = ["Router hostname or IP address"], arity = "0..1")
    var host: String? = null

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

    private val logger = LogManager.getLogger(FreeRangeCommand::class.java)

    override fun run() {
        val hostArg = host
            ?: System.getenv("FREE_RANGE_HOST")
            ?: run {
                System.err.println("Error: host argument is required.")
                System.err.println("Usage: free-range <host> [options]")
                System.err.println("Run 'free-range --help' for usage information.")
                exitProcess(1)
            }

        // Resolve full configuration
        val config = try {
            AppConfig.resolve(
                host = hostArg,
                cliUsername = username,
                cliPassword = password,
                cliPort = null,
                cliNoColor = noColor,
                cliDebug = debug,
                cliTable = table,
                cliTablePng = tablePng,
                cliInterface = interfaceName,
                cliConfigFile = configFile
            )
        } catch (e: IllegalStateException) {
            System.err.println("Error: ${e.message}")
            exitProcess(1)
        }

        // Enable debug logging if requested
        if (config.debug) {
            Configurator.setLevel("net.ukrhub.noc.freerange", Level.DEBUG)
            logger.debug("Debug mode enabled")
            logger.debug("Config: host={}, port={}, username={}, openChannel={}", config.host, config.netconfPort, config.username, config.openChannel)
            logger.debug("Options: noColor={}, table={}, tablePng={}, interface={}", config.noColor, config.table, config.tablePng, config.interfaceName)
        }

        // Step 1: Fetch RADIUS subscribers
        System.err.println("Fetching subscribers...")
        val subscriberSource = LocalCommandSubscriberSource(config.subscribersCommand)
        val rawSubscribers = try {
            subscriberSource.getSubscribers()
        } catch (e: Exception) {
            System.err.println("Error fetching subscribers: ${e.message}")
            logger.debug("Subscriber fetch exception", e)
            exitProcess(1)
        }

        if (rawSubscribers.isBlank()) {
            System.err.println("Error: subscribers command returned empty output. Check path or access.")
            exitProcess(1)
        }
        logger.debug("Received {} lines of subscriber data", rawSubscribers.lines().size)

        // Step 2: Connect via NETCONF and fetch interfaces config
        System.err.println("Connecting to ${config.host}...")
        val netconfClient = NetconfClient(
            host = config.host,
            port = config.netconfPort,
            username = config.username,
            password = config.password,
            openChannel = config.openChannel,
            debug = config.debug
        )

        val vlanData = try {
            netconfClient.use { client ->
                client.connect()
                System.err.println("Connected. Fetching interface configuration...")
                val doc = client.fetchInterfacesConfig()
                client.parseInterfaceVlanData(doc)
            }
        } catch (e: Exception) {
            System.err.println("Error communicating with device: ${e.message}")
            logger.debug("NETCONF exception", e)
            exitProcess(1)
        }

        logger.debug(
            "Parsed NETCONF data: {} interfaces with ranges, {} with demux, {} with another vlans",
            vlanData.ranges.size, vlanData.demuxUnits.size, vlanData.anotherVlans.size
        )

        val processor = VlanProcessor()

        // Step 3: Determine which interfaces to process
        val interfacesToProcess: List<String?> = when (config.interfaceName) {
            "all" -> {
                val ifaces = vlanData.interfacesWithRanges
                if (ifaces.isEmpty()) {
                    System.err.println("Error: no interfaces with configured ranges found.")
                    exitProcess(1)
                }
                logger.debug("Processing all {} interfaces: {}", ifaces.size, ifaces)
                ifaces
            }
            null -> listOf(null)   // all combined, no filter
            else -> listOf(config.interfaceName)
        }

        // Step 4: Process and output for each interface
        for (iface in interfacesToProcess) {
            val activeVlans = processor.parseActiveSubscribers(rawSubscribers, config.hostLabel, iface)

            if (config.debug) {
                logger.debug("Interface: {}, active VLANs: {}", iface ?: "all", activeVlans.size)
            }

            val result = processor.process(vlanData, activeVlans, iface)

            when {
                config.tablePng != null -> {
                    PngOutput.save(result.statuses, result.counts, config.tablePng!!, config.hostLabel, iface)
                }
                config.table -> {
                    TableOutput.print(result.statuses, result.counts, config.useColor, config.hostLabel, iface)
                }
                else -> {
                    TextOutput.print(result.statuses, config.useColor, config.hostLabel, iface)
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    val cmd = CommandLine(FreeRangeCommand())
    val exitCode = cmd.execute(*args)
    exitProcess(exitCode)
}
