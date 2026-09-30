#!/usr/bin/env bash
# Tabby — build & launch on the iOS simulator (run from VS Code's integrated terminal)
# Usage: ./run.sh
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

SIM="iPhone 18 Pro"
SCHEME="SpendTracker"
BUNDLE_ID="com.maghizhan.spendtracker"
DERIVED="./DerivedData"
APP="$DERIVED/Build/Products/Debug-iphonesimulator/SpendTracker.app"

printf '==> Generating Xcode project from project.yml\n'
xcodegen generate

printf '==> Building %s for %s\n' "$SCHEME" "$SIM"
# Build locally signed rather than disabling signing. The explicit re-sign step
# below embeds the App Group entitlement in the final simulator bundles.
xcodebuild -project SpendTracker.xcodeproj -scheme "$SCHEME" \
  -destination "platform=iOS Simulator,name=$SIM" \
  -derivedDataPath "$DERIVED" \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY="-" \
  build

# Xcode's simulator build signature can omit entitlements even when the target
# declares CODE_SIGN_ENTITLEMENTS. Re-sign the nested widget first, then its host
# app, so simctl provisions one App Group container for both processes.
WIDGET="$APP/PlugIns/SpendTrackerWidget.appex"
printf '==> Embedding App Group entitlements in simulator bundles\n'
codesign --force --sign - \
  --entitlements SpendTrackerWidget/SpendTrackerWidget.entitlements \
  "$WIDGET"
codesign --force --sign - \
  --entitlements SpendTracker/SpendTracker.entitlements \
  "$APP"

printf '==> Booting simulator (ignore “already booted”)\n'
xcrun simctl boot "$SIM" 2>/dev/null || true
xcrun simctl bootstatus "$SIM" -b

printf '==> Installing app\n'
xcrun simctl install "$SIM" "$APP"

printf '==> Launching %s\n' "$BUNDLE_ID"
xcrun simctl launch "$SIM" "$BUNDLE_ID"

# Some Xcode installations contain simctl but not the windowed Simulator.app.
# Launch it only when present; the headless screenshot fallback remains useful.
DEVELOPER_DIR="$(xcode-select -p)"
SIMULATOR_APP="$DEVELOPER_DIR/Applications/Simulator.app"
if [[ -d "$SIMULATOR_APP" ]]; then
  open "$SIMULATOR_APP"
  printf '==> Done. Tabby is running on %s.\n' "$SIM"
else
  SCREENSHOT="$PROJECT_DIR/tabby-current.png"
  xcrun simctl io "$SIM" screenshot "$SCREENSHOT"
  printf '==> Done. Simulator.app is unavailable, so Tabby is running headlessly.\n'
  printf '    Current screen captured at: %s\n' "$SCREENSHOT"
  printf '    Open it with: open "%s"\n' "$SCREENSHOT"
fi
