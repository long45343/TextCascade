#!/usr/bin/env bash
set -euo pipefail

GRADLE_HOME="/home/long45343/.gradle/wrapper/dists/gradle-8.7-all/aan3ydargesu18aqyqjwhr3pc/gradle-8.7"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/clipkotlin-gradle-home}"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  echo "Gradle 8.7 was not found at $GRADLE_HOME" >&2
  echo "Install Gradle 8.7 or replace this script with a standard Gradle wrapper." >&2
  exit 1
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
