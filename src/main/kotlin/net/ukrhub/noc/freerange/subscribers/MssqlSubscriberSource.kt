package net.ukrhub.noc.freerange.subscribers

import org.apache.logging.log4j.LogManager
import java.sql.DriverManager

class MssqlSubscriberSource(
    private val server: String,
    private val database: String,
    private val user: String,
    private val password: String,
    private val port: Int = 1433,
) : SubscriberSource {

    private val logger = LogManager.getLogger(MssqlSubscriberSource::class.java)

    override fun getSubscribers(): String {
        val url = "jdbc:sqlserver://$server:$port;databaseName=$database;encrypt=false;trustServerCertificate=true"
        logger.debug("Connecting to MSSQL: {}", url)

        val lines = mutableListOf<String>()
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(QUERY).use { rs ->
                    while (rs.next()) {
                        lines.add(rs.getString(1))
                    }
                }
            }
        }

        logger.debug("Fetched {} subscriber usernames from MSSQL", lines.size)
        return lines.joinToString("\n")
    }

    companion object {
        private const val QUERY = """
            SELECT Username
            FROM [AccEquipment_V2_Release].[dbo].[CustomerServices.ServiceDetails]
            WHERE Username LIKE 'dhcp_%'
        """
    }
}
