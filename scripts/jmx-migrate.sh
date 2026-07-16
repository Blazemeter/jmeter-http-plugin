#!/bin/sh
# Migrates stock JMeter HTTP Request samplers to the BlazeMeter HTTP sampler in a .jmx file
# or directory. Run from a real JMeter installation with jmeter-bzm-http2-*.jar installed
# under lib/ext/ (this script assumes it lives at <jmeter.home>/bin/jmx-migrate.sh).
#
# Usage: see `jmx-migrate.sh --help`

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
JMETER_HOME_DIR=$(cd "$SCRIPT_DIR/.." && pwd)

exec java -Djava.awt.headless=true \
  -cp "$JMETER_HOME_DIR/lib/*:$JMETER_HOME_DIR/lib/ext/*" \
  com.blazemeter.jmeter.http2.sampler.JmxBlazeMeterHttpMigratorCli "$@"
