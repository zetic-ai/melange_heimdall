#!/bin/bash

# Setup script for Melange Heimdall
# Configures Melange Personal Key for both Android and iOS demo apps

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IOS_VM="$SCRIPT_DIR/demo-ios/ZeticMLangeProxyDemo/DemoViewModel.swift"
ANDROID_PROPS="$SCRIPT_DIR/demo-android/local.properties"

# --- Restore mode ---

if [ "$1" = "--restore" ]; then
    echo ""
    echo "  Restoring placeholders..."

    if [ -f "$IOS_VM" ]; then
        perl -i -pe 's/(environment\["ZETIC_PERSONAL_KEY"\]\s*\?\?\s*)"[^"]*"/${1}"YOUR_MLANGE_KEY"/g' "$IOS_VM"
        echo "  [ok] iOS:     restored DemoViewModel.swift"
    fi

    if [ -f "$ANDROID_PROPS" ]; then
        if grep -q "^ZETIC_PERSONAL_KEY=" "$ANDROID_PROPS" 2>/dev/null; then
            perl -i -pe 's/^ZETIC_PERSONAL_KEY=.*/ZETIC_PERSONAL_KEY=YOUR_MLANGE_KEY/' "$ANDROID_PROPS"
            echo "  [ok] Android: restored local.properties"
        fi
    fi

    echo ""
    echo "  Placeholders restored. Safe to commit."
    echo ""
    exit 0
fi

# --- Setup mode ---

echo ""
echo "  Melange Heimdall - Setup"
echo "  ========================"
echo ""
echo "  Get your free Melange Personal Key at: https://melange.zetic.ai"
echo "  Go to Settings > Personal Access Token"
echo ""

read -p "  Melange Personal Key (dev_...): " MLANGE_KEY

if [ -z "$MLANGE_KEY" ]; then
    echo ""
    echo "  Error: Melange Personal Key is required."
    exit 1
fi

# --- Android: write to local.properties ---

if [ -f "$ANDROID_PROPS" ] && grep -q "^ZETIC_PERSONAL_KEY=" "$ANDROID_PROPS" 2>/dev/null; then
    # Update existing key
    perl -i -pe 's/^ZETIC_PERSONAL_KEY=.*/ZETIC_PERSONAL_KEY='"$MLANGE_KEY"'/' "$ANDROID_PROPS"
else
    # Preserve existing content (like sdk.dir) and append
    echo "ZETIC_PERSONAL_KEY=$MLANGE_KEY" >> "$ANDROID_PROPS"
fi

echo ""
echo "  [ok] Android: wrote ZETIC_PERSONAL_KEY to demo-android/local.properties"

# --- iOS: patch DemoViewModel.swift ---

if [ -f "$IOS_VM" ]; then
    perl -i -pe 's/(environment\["ZETIC_PERSONAL_KEY"\]\s*\?\?\s*)"[^"]*"/${1}"'"$MLANGE_KEY"'"/g' "$IOS_VM"
    echo "  [ok] iOS:     patched DemoViewModel.swift"
else
    echo "  [!!] iOS:     DemoViewModel.swift not found, skipping"
fi

echo ""
echo "  Done! Melange key configured for both platforms."
echo ""
echo "  Next steps:"
echo "    Android:  cd demo-android && ./gradlew installDebug"
echo "    iOS:      cd demo-ios && open ZeticMLangeProxyDemo.xcodeproj"
echo ""
echo "  Before committing, restore placeholders with:"
echo "    ./setup.sh --restore"
echo ""
