#!/bin/sh
# Runs the VLAN distribution report once per hour in a loop.
# All configuration is taken from environment variables:
#
#   FREE_RANGE_HOST          — comma-separated list of Juniper router hostnames
#   FREE_RANGE_SUFFIX        — domain suffix appended to bare hostnames (e.g. ukrhub.net)
#   FREE_RANGE_USERNAME      — SSH/NETCONF username
#   FREE_RANGE_PASSWORD      — SSH/NETCONF password
#   FREE_RANGE_TABLE_PNG     — output directory for PNG + index.html (e.g. /usr/share/nginx/html)
#   FREE_RANGE_WEB           — set to non-empty to generate index.html dashboard
#   FREE_RANGE_SUBSCRIBERS_CMD — command to fetch RADIUS subscribers
#   OPENCHANNEL              — netconf channel mode (subsystem-netconf or exec)

while true; do
    date >&2
    "$JAVA_HOME/bin/java" -jar /usr/local/bin/free-range.jar
    sleep 3600
done
