#!/bin/sh
SCRIPT_DIR=$(dirname "$0")
exec "${SCRIPT_DIR}/gradle/wrapper/gradle-wrapper.jar" "$@" 2>/dev/null || \
  exec gradle "$@"
