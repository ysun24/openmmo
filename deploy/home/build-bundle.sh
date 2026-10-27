#!/usr/bin/env bash
# Builds the OpenMMO home bundle on this (Linux) machine:
#   1. gradle installDist for game, login and gateway, and the web assets
#   2. the openmmo:<version> image (linux/amd64) from a staged context
#   3. dist/openmmo-home-<version>.zip: image tar + compose + PowerShell scripts + README
#
# usage: deploy/home/build-bundle.sh [--skip-gradle] [--version V]
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
DIST="$HERE/dist"
SKIP_GRADLE=0
VERSION=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-gradle) SKIP_GRADLE=1; shift ;;
    --version) VERSION=$2; shift 2 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [[ -z "$VERSION" ]]; then
  VERSION="$(date -u +%Y%m%d)-$(git -C "$ROOT" rev-parse --short=8 HEAD)"
  git -C "$ROOT" diff --quiet HEAD -- . ':!deploy/home/dist' 2>/dev/null || VERSION="$VERSION-dirty"
fi
if [[ ! "$VERSION" =~ ^[A-Za-z0-9._-]+$ ]]; then echo "bad version: $VERSION" >&2; exit 2; fi
echo "== OpenMMO home bundle $VERSION"

cd "$ROOT"
if [[ $SKIP_GRADLE -eq 0 ]]; then
  : "${JAVA_HOME:?set JAVA_HOME to a JDK 25}"
  GRADLE=(./gradlew --no-daemon "-Porg.gradle.java.installations.paths=$JAVA_HOME")
  echo "== gradle installDist (log: $DIST/gradle.log)"
  mkdir -p "$DIST"
  "${GRADLE[@]}" :keys:generateGame :server.game:installDist :server.login:installDist \
    :web.gateway:installDist -x test >"$DIST/gradle.log" 2>&1 \
    || { tail -n 40 "$DIST/gradle.log"; exit 1; }
  echo "== web assets"
  "${GRADLE[@]}" :web.assets:run --args="--decomp decomp --out web/static/assets" \
    >>"$DIST/gradle.log" 2>&1 || { tail -n 40 "$DIST/gradle.log"; exit 1; }
fi
for d in server.game/build/install/server.game server.login/build/install/server.login \
         web/gateway/build/install/web.gateway web/static/assets codegen/dialog \
         decomp/pokeemerald/include decomp/pokefirered/include decomp/pokeblack; do
  [[ -e "$d" ]] || { echo "missing $d (run without --skip-gradle, init submodules)" >&2; exit 1; }
done

CTX="$DIST/context"
echo "== staging image context in $CTX"
rm -rf "$CTX"
mkdir -p "$CTX/web" "$CTX/codegen" "$CTX/decomp"
cp -a server.game/build/install/server.game "$CTX/game"
cp -a server.login/build/install/server.login "$CTX/login"
cp -a web/gateway/build/install/web.gateway "$CTX/gateway"
rsync -a --exclude '.DS_Store' web/static/ "$CTX/web/static/"
cp -a codegen/dialog "$CTX/codegen/dialog"
# The gateway reads pret decomp sources (names, maps, scripts) at run time. Sound, build output
# and git metadata are not needed.
for g in pokeemerald pokefirered pokeblack; do
  rsync -a --exclude '.git' --exclude 'sound/' --exclude 'build/' --exclude 'tools/' \
    --exclude '*.o' --exclude '*.gba' --exclude '*.elf' "decomp/$g/" "$CTX/decomp/$g/"
done
cp "$HERE/image/Dockerfile" "$HERE/image/entrypoint.sh" "$HERE/image/tunnel.sh" "$CTX/"
echo "$VERSION" >"$CTX/VERSION"

echo "== docker build openmmo:$VERSION"
docker build --platform linux/amd64 --pull=false -t "openmmo:$VERSION" "$CTX" >"$DIST/docker-build.log" 2>&1 \
  || { tail -n 40 "$DIST/docker-build.log"; exit 1; }
docker image inspect "openmmo:$VERSION" --format 'image {{.Id}} {{.Architecture}} {{.Size}} bytes'

NAME="openmmo-home-$VERSION"
STAGE="$DIST/$NAME"
rm -rf "$STAGE" "$DIST/$NAME.zip"
mkdir -p "$STAGE"
echo "== docker save"
docker save "openmmo:$VERSION" | gzip -6 >"$STAGE/openmmo-image-$VERSION.tar.gz"
cp "$HERE/compose.yml" "$HERE/.env.example" "$HERE/README.md" "$STAGE/"
cp "$HERE"/scripts/*.ps1 "$STAGE/"
echo "$VERSION" >"$STAGE/VERSION"
(cd "$STAGE" && sha256sum -- * >SHA256SUMS)
# CRLF for the Windows-side text files, so Notepad and PowerShell 5 are happy.
for f in "$STAGE"/*.ps1 "$STAGE/.env.example" "$STAGE/README.md" "$STAGE/compose.yml"; do
  sed -i 's/\r$//; s/$/\r/' "$f"
done
(cd "$STAGE" && sha256sum -- * >SHA256SUMS)
(cd "$DIST" && zip -qr -X "$NAME.zip" "$NAME")
rm -rf "$CTX"
echo "== bundle: $DIST/$NAME.zip ($(du -h "$DIST/$NAME.zip" | cut -f1))"
