#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
IOS_XCODE_DIR="$ROOT_DIR/mobile/iosApp/iosXcode"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/melokeep_outputs}"
DERIVED_DATA="${DERIVED_DATA:-$IOS_XCODE_DIR/build/DerivedData}"
RAW_VER="${MELOKEEP_VERSION_NAME:-1.0.0}"
MARKETING_VER="${RAW_VER#v}"
MARKETING_VER="${MARKETING_VER%%-*}"
[ -n "$MARKETING_VER" ] || MARKETING_VER="1.0.0"
PROJECT_VER="${MELOKEEP_VERSION_CODE:-1}"
IPA_FILENAME="${IPA_FILENAME:-MeloKeep-unsigned.ipa}"

echo "==> [1/3] Compiling Kotlin/Native Release Framework (:iosCompose:linkReleaseFrameworkIosArm64) [v$MARKETING_VER ($PROJECT_VER)]..."
cd "$ROOT_DIR/mobile"
chmod +x ./gradlew
./gradlew :iosCompose:linkReleaseFrameworkIosArm64 --no-daemon --console=plain

echo "==> [2/3] Building unsigned MeloKeep.app via xcodebuild..."
xcodebuild \
  -project "$IOS_XCODE_DIR/MeloKeep.xcodeproj" \
  -scheme MeloKeep \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$DERIVED_DATA" \
  MARKETING_VERSION="$MARKETING_VER" \
  CURRENT_PROJECT_VERSION="$PROJECT_VER" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  DEVELOPMENT_TEAM="" \
  build

APP_PATH="$DERIVED_DATA/Build/Products/Release-iphoneos/MeloKeep.app"
if [ ! -d "$APP_PATH" ]; then
  echo "ERROR: Built MeloKeep.app not found at $APP_PATH" >&2
  exit 1
fi

echo "==> [3/3] Packaging $IPA_FILENAME..."
mkdir -p "$OUT_DIR"
STAGE_DIR="$(mktemp -d)"
trap 'rm -rf "$STAGE_DIR"' EXIT
mkdir -p "$STAGE_DIR/Payload"
cp -R "$APP_PATH" "$STAGE_DIR/Payload/MeloKeep.app"
(
  cd "$STAGE_DIR"
  zip -qry "$OUT_DIR/$IPA_FILENAME" Payload
)

echo "SUCCESS: $OUT_DIR/$IPA_FILENAME ($(du -h "$OUT_DIR/$IPA_FILENAME" | awk '{print $1}'))"
