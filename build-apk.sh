#!/usr/bin/env bash
# Build the WiFi Scanner release APK (offline dependency mode, same as NNS).
set -e
cd ~/workspace/wifi-scanner-android
export JAVA_HOME=$HOME/workspace/tools/jdk17
export ANDROID_SDK_ROOT=$HOME/workspace/android-sdk
export ANDROID_HOME=$HOME/workspace/android-sdk
export GRADLE_USER_HOME=$HOME/workspace/.gradle
export PATH=$JAVA_HOME/bin:$PATH
export JAVA_OPTS="-Djava.net.preferIPv4Stack=true"
export GRADLE_OPTS="$JAVA_OPTS"

$HOME/workspace/tools/gradle-8.11.1/bin/gradle --stop > /dev/null 2>&1 || true
sleep 1
rm -f $HOME/workspace/.gradle/caches/journal-1/journal-1.lock

$HOME/workspace/tools/gradle-8.11.1/bin/gradle assembleRelease --offline \
  -I $HOME/workspace/tools/nns-init.gradle \
  -Dorg.gradle.java.home=$JAVA_HOME \
  "$@"

echo "----"
ls -la app/build/outputs/apk/release/ 2>/dev/null || true
