#!/bin/sh

# Set default values
APP_BASE_NAME=`basename "$0"`
APP_HOME="."

# Check if gradle wrapper jar exists, else fallback to installed gradle
if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
    exec java -Xmx64m -Dorg.gradle.appname="$APP_BASE_NAME" -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
else
    exec gradle "$@"
fi
