#!/bin/sh
# Launched by the nginx entrypoint; runs the collector loop in the background.
/usr/local/bin/free-range.sh &
