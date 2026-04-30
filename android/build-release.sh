#!/usr/bin/env bash
# Сборка release AAB для публикации в Play Store / RuStore
# Использование: ./build-release.sh
# Переменные среды: STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD

set -e
cd "$(dirname "$0")"

if [ -z "$STORE_PASSWORD" ] || [ -z "$KEY_ALIAS" ] || [ -z "$KEY_PASSWORD" ]; then
  echo "ERROR: Set STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD env vars"
  exit 1
fi

./gradlew :app:bundleRelease

AAB="app/build/outputs/bundle/release/app-release.aab"
echo ""
echo "✓ AAB собран: $AAB"
echo "  → Загрузи его в Google Play Console или RuStore"
