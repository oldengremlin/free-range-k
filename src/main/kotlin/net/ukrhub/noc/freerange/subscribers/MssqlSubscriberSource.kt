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
        val url = "jdbc:jtds:sqlserver://$server:$port/$database;tds=8.0;charset=UTF-8"
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
