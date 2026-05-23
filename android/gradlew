#!/bin/sh

# Minimal Gradle wrapper script placeholder.
# Replace with generated wrapper script if needed.

DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$JAR" ]; then
  echo "gradle-wrapper.jar is missing. Open in Android Studio and let it configure Gradle, or generate wrapper locally."
  exit 1
fi

exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
