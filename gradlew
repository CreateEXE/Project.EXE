#!/usr/bin/env bash
# =============================================================================
# Project EXE — Termux Gradle Wrapper
# Hardcodes JAVA_HOME to /data/data/com.termux/files/usr/opt/openjdk
# to bypass symlink resolution failures in Termux's PATH shims.
# =============================================================================
set -euo pipefail

TERMUX_JAVA="/data/data/com.termux/files/usr/opt/openjdk"

if [ -d "$TERMUX_JAVA" ] && [ -x "$TERMUX_JAVA/bin/java" ]; then
    export JAVA_HOME="$TERMUX_JAVA"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    : # Use caller-supplied JAVA_HOME
else
    for CANDIDATE in \
        /usr/lib/jvm/java-21-openjdk-amd64 \
        /usr/lib/jvm/java-17-openjdk-amd64 \
        /opt/homebrew/opt/openjdk@21; do
        if [ -x "$CANDIDATE/bin/java" ]; then
            export JAVA_HOME="$CANDIDATE"
            break
        fi
    done
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "ERROR: Could not find a valid JDK."
    echo "  Termux: pkg install openjdk-21"
    echo "  Ubuntu: apt install openjdk-21-jdk"
    exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"

APP_HOME="$(cd "$(dirname "$0")" && pwd -P)"
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "ERROR: gradle-wrapper.jar not found at $WRAPPER_JAR"
    echo "Run: ./setup_projectexe.sh (it will download the jar)"
    exit 1
fi

exec "$JAVA_HOME/bin/java" \
    -classpath "$WRAPPER_JAR" \
    -Dorg.gradle.appname="$(basename "$0")" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
