#!/bin/sh
# Builds the Android app inside the container. Configured by env vars (see .env.example).
set -eu

APP_URL="${APP_URL:-https://example.com/}"
APP_ID="${APP_ID:-app.family.notifyer}"
VERSION_CODE="${VERSION_CODE:-1}"
UPDATE_URL="${UPDATE_URL:-}"
GRADLE_TASKS="${GRADLE_TASKS:-assembleDirectDebug}"

cd /workspace/android

# The firebase plugin refuses to build without google-services.json.
# Use the real one if present, otherwise a placeholder (app builds, pushes won't work).
GS=app/google-services.json
if [ ! -s "$GS" ]; then
  echo ">> No android/app/google-services.json found - using a PLACEHOLDER (no push notifications)."
  cat > "$GS" <<JSON
{
  "project_info": {"project_number": "000000000000", "project_id": "placeholder", "storage_bucket": "placeholder.appspot.com"},
  "client": [{
    "client_info": {"mobilesdk_app_id": "1:000000000000:android:0000000000000000", "android_client_info": {"package_name": "$APP_ID"}},
    "api_key": [{"current_key": "placeholder-key"}]
  }],
  "configuration_version": "1"
}
JSON
  touch "$GS.placeholder"
fi
cleanup() { [ -f "$GS.placeholder" ] && rm -f "$GS" "$GS.placeholder" || true; }
trap cleanup EXIT

# shellcheck disable=SC2086
gradle --no-daemon $GRADLE_TASKS \
  -PappUrl="$APP_URL" -PappId="$APP_ID" -PversionCode="$VERSION_CODE" -PupdateUrl="$UPDATE_URL"

mkdir -p /workspace/dist
find app/build/outputs \( -name '*.apk' -o -name '*.aab' \) -exec cp -v {} /workspace/dist/ \;
echo ">> Done. Artifacts are in ./dist"
