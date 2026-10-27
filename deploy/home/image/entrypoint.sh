#!/bin/bash
# OpenMMO home image. The first argument picks what runs:
#   keys     create the shared server keypair in /keys (once; never overwrites)
#   login    login server            (needs /keys/game.private.pem)
#   game     game server             (needs /keys/game.private.pem)
#   gateway  web gateway + browser client on :8080 (needs /keys/game.public.pem)
#   tunnel   Pinggy SSH tunnel to the gateway (see tunnel.sh)
#   invite   invite codes: invite create|list|revoke ... (runs the login server CLI)
#   version  print the image version
set -euo pipefail

APP=/opt/openmmo
KEYS=${OPENMMO_KEYS_DIR:-/keys}
cmd=${1:-help}
shift || true

need_key() {
  if [[ ! -s "$KEYS/$1" ]]; then
    echo "missing $KEYS/$1 - the 'keys' service creates it on the first start" >&2
    exit 1
  fi
}

case "$cmd" in
  keys)
    umask 077
    if [[ -s "$KEYS/game.private.pem" && -s "$KEYS/game.public.pem" ]]; then
      echo "keys: keypair already present in $KEYS, keeping it"
      exit 0
    fi
    if [[ -e "$KEYS/game.private.pem" || -e "$KEYS/game.public.pem" ]]; then
      echo "keys: only half a keypair in $KEYS; refusing to overwrite. Restore it from a backup." >&2
      exit 1
    fi
    # PKCS#8 private / X.509 public, P-256: the same encodings :keys:generateGame writes.
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$KEYS/game.private.pem.tmp"
    openssl pkey -in "$KEYS/game.private.pem.tmp" -pubout -out "$KEYS/game.public.pem.tmp"
    chmod 600 "$KEYS/game.private.pem.tmp"
    chmod 644 "$KEYS/game.public.pem.tmp"
    mv "$KEYS/game.public.pem.tmp" "$KEYS/game.public.pem"
    mv "$KEYS/game.private.pem.tmp" "$KEYS/game.private.pem"
    echo "keys: created a new P-256 keypair in $KEYS"
    ;;
  login)
    need_key game.private.pem
    export OPENMMO_GAME_PRIVATE_KEY_FILE="$KEYS/game.private.pem"
    exec "$APP/login/bin/server.login" "$@"
    ;;
  invite)
    need_key game.private.pem
    export OPENMMO_GAME_PRIVATE_KEY_FILE="$KEYS/game.private.pem"
    # Logs to stderr would be nicer; keep the CLI's own lines easy to pick out instead.
    exec "$APP/login/bin/server.login" invite "$@"
    ;;
  game)
    need_key game.private.pem
    export OPENMMO_GAME_PRIVATE_KEY_FILE="$KEYS/game.private.pem"
    exec "$APP/game/bin/server.game" "$@"
    ;;
  gateway)
    need_key game.public.pem
    origins=${GATEWAY_ALLOW_ORIGIN:-}
    if [[ -z "$origins" ]]; then
      if [[ -n "${PINGGY_SUBDOMAIN:-}" ]]; then
        origins="https://${PINGGY_SUBDOMAIN}"
      else
        # Pinggy's random URLs (paid and free tier). Exactly one label is matched.
        origins="https://*.a.pinggy.link,https://*.a.free.pinggy.link,https://*.pinggy.link"
      fi
    fi
    cd "$APP"
    exec "$APP/gateway/bin/web.gateway" \
      --host 0.0.0.0 --port 8080 \
      --static "$APP/web/static" --decomp "$APP/decomp" \
      --key "$KEYS/game.public.pem" \
      --login-host "${LOGIN_HOST:-login}" --login-port "${LOGIN_PORT:-2106}" \
      --game-host "${GAME_HOST:-game}" \
      --trust-forwarded "${GATEWAY_TRUST_FORWARDED:-true}" \
      --allow-origin "$origins" "$@"
    ;;
  tunnel)
    exec /opt/openmmo/tunnel.sh "$@"
    ;;
  version)
    cat "$APP/VERSION"
    ;;
  *)
    sed -n '2,10p' "$0"
    exit 2
    ;;
esac
