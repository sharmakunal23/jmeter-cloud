#!/bin/sh
set -e
# JMETER_PROPS: space-separated -J flags injected via pod/container env.
# Example: JMETER_PROPS="-Jthreads=50 -Jrampup=60 -JtestDuration=300"
# Values must not contain spaces. The variable undergoes word-splitting so
# each "-Jkey=value" token is passed as a distinct argument to JMeter.
exec jmeter -n ${JMETER_PROPS:-} "$@"