#!/bin/bash
set -e

export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="/usr/local/lib/jdk-17.0.14+7/Contents/Home"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

echo "=== ANDROID_HOME=$ANDROID_HOME"
echo "=== JAVA_HOME=$JAVA_HOME"
echo "=== Java version:"
"$JAVA_HOME/bin/java" -version 2>&1

echo "=== Building APK..."
chmod +x gradlew
./gradlew assembleDebug --no-daemon

echo ""
echo "=== Build complete!"
find app/build/outputs/apk -name "*.apk" -exec ls -lh {} \;
