package net.ukrhub.noc.freerange

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream

/**
 * Application configuration loaded from CLI args, environment variables, and YAML file.
 * Priority: CLI > ENV > YAML > default
 */
data class AppConfig(
    val host: String,
    val username: String,
    val password: String,
    val suffix: String? = null,
    val port: Int = 22,
    val netconfPort: Int = 22,
    val noColor: Boolean = false,
    val debug: Boolean = false,
    val table: Boolean = false,
    val tablePng: String? = null,
    val interfaceName: String? = null,
    val subscribersCommand: String = "ssh -C -x roffice /usr/local/share/noc/bin/radius-subscribers",
    val openChannel: String = "subsystem-netconf",
    val accServer: String? = null,
    val accDatabase: String = "AccEquipment_V2_Release",
    val accUser: String? = null,
    val accPassword: String? = null,
    val accPort: Int = 1433,
) {
    companion object {
        /**
         * Loads configuration from YAML file.
         */
        fun loadYaml(path: String): Map<String, Any?> {
            val file = File(path)
            if (!file.exists()) return emptyMap()
            return FileInputStream(file).use { stream ->
                @Suppress("UNCHECKED_CAST")
                Yaml().load<Map<String, Any?>>(stream) ?: emptyMap()
            }
        }

        /**
         * Resolves configuration by merging CLI, ENV, YAML and defaults.
         */
        fun resolve(
            host: String,
            cliUsername: String?,
            cliPassword: String?,
            cliPort: Int?,
            cliNoColor: Boolean,
            cliDebug: Boolean,
            cliTable: Boolean,
            cliTablePng: String?,
            cliInterface: String?,
            cliConfigFile: String?,
            cliSuffix: String? = null,
        ): AppConfig {
            // Determine config file path
            val configFilePath = cliConfigFile
                ?: System.getenv("FREE_RANGE_CONFIG")
                ?: "${System.getProperty("user.home")}/.free-range.yaml"

            val yaml = loadYaml(configFilePath)

            fun yamlStr(key: String) = yaml[key]?.toString()
            fun yamlBool(key: String) = yaml[key]?.toString()?.lowercase()?.let { it == "true" || it == "1" }
            fun yamlInt(key: String) = yaml[key]?.toString()?.toIntOrNull()

            val username = cliUsername
                ?: System.getenv("FREE_RANGE_USERNAME")
                ?: System.getenv("WHOAMI")
                ?: yamlStr("username")
                ?: error("Username not specified. Use -u/--username, FREE_RANGE_USERNAME, WHOAMI env, or YAML config.")

            val password = cliPassword
                ?: System.getenv("FREE_RANGE_PASSWORD")
                ?: System.getenv("WHATISMYPASSWD")
                ?: yamlStr("password")
                ?: error("Password not specified. Use -p/--password, FREE_RANGE_PASSWORD, WHATISMYPASSWD env, or YAML config.")

            val port = cliPort
                ?: System.getenv("FREE_RANGE_PORT")?.toIntOrNull()
                ?: yamlInt("port")
                ?: 22

            val netconfPort = System.getenv("FREE_RANGE_NETCONF_PORT")?.toIntOrNull() ?: port

            val noColor = cliNoColor
                || System.getenv("FREE_RANGE_NO_COLOR")?.isNotEmpty() == true
                || yamlBool("no_color") == true

            val debug = cliDebug
                || System.getenv("FREE_RANGE_DEBUG")?.isNotEmpty() == true
                || yamlBool("debug") == true

            val table = cliTable
                || System.getenv("FREE_RANGE_TABLE")?.isNotEmpty() == true
                || yamlBool("table") == true

            val tablePng = cliTablePng
                ?: System.getenv("FREE_RANGE_TABLE_PNG")

            val interfaceName = cliInterface
                ?: System.getenv("FREE_RANGE_INTERFACE")

            val subscribersCommand = System.getenv("FREE_RANGE_SUBSCRIBERS_CMD")
                ?: yamlStr("subscribers_command")
                ?: "ssh -C -x roffice /usr/local/share/noc/bin/radius-subscribers"

            val openChannel = System.getenv("OPENCHANNEL")
                ?: yamlStr("openchannel")
                ?: "subsystem-netconf"

            val suffix = cliSuffix
                ?: System.getenv("FREE_RANGE_SUFFIX")
                ?: yamlStr("suffix")

            val accServer = System.getenv("FREE_RANGE_ACC_SERVER") ?: yamlStr("acc_server")
            val accDatabase = System.getenv("FREE_RANGE_ACC_DATABASE") ?: yamlStr("acc_database") ?: "AccEquipment_V2_Release"
            val accUser = System.getenv("FREE_RANGE_ACC_USER") ?: yamlStr("acc_user")
            val accPassword = System.getenv("FREE_RANGE_ACC_PASSWORD") ?: yamlStr("acc_password")
            val accPort = System.getenv("FREE_RANGE_ACC_PORT")?.toIntOrNull() ?: yamlInt("acc_port") ?: 1433

            return AppConfig(
                host = host,
                username = username,
                password = password,
                suffix = suffix,
                port = port,
                netconfPort = netconfPort,
                noColor = noColor,
                debug = debug,
                table = table,
                tablePng = tablePng,
                interfaceName = interfaceName,
                subscribersCommand = subscribersCommand,
                openChannel = openChannel,
                accServer = accServer,
                accDatabase = accDatabase,
                accUser = accUser,
                accPassword = accPassword,
                accPort = accPort,
            )
        }
    }

    /** Host label used in output and filenames: strips the known suffix, or uses full hostname */
    val hostLabel: String get() {
        if (suffix != null && host.endsWith(".$suffix")) return host.removeSuffix(".$suffix")
        return host
    }

    /** Whether to use ANSI color in terminal output */
    val useColor: Boolean
        get() {
            if (noColor) return false
            val term = System.getenv("TERM")
            return term != null && term != "dumb"
        }
}
