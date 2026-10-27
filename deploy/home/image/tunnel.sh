#!/bin/bash
# Pinggy tunnel: forwards https://<sub>.a.pinggy.link to the gateway over one outbound SSH
# connection (port 443), reconnecting forever with backoff.
#
# Environment (all from .env):
#   TUNNEL=off            run nothing (local play only); anything else starts the tunnel
#   PINGGY_TOKEN          Pinggy Pro token. Never printed; empty = free tier (60 min, random URL)
#   PINGGY_SUBDOMAIN      the persistent domain bound to the token in the Pinggy dashboard,
#                         e.g. mygame.a.pinggy.link (used for the printed URL / allowed origin)
#   PINGGY_SERVER         SSH host, default pro.pinggy.io with a token, a.pinggy.io without
#   SITE_USER/SITE_PASS   HTTP basic auth in front of the whole site (Pinggy "b:user:pass")
#   PINGGY_ALLOW_IPS      comma separated IPs/CIDRs allowed in (Pinggy "w:"), empty = anyone
#   PINGGY_EXTRA_OPTIONS  more '+' separated Pinggy keywords, e.g. "qr"
#   TUNNEL_TARGET         where the tunnel points, default gateway:8080
#   TUNNEL_DRY_RUN=1      print the SSH command (token redacted) and stop
set -uo pipefail

STATE=${TUNNEL_STATE_DIR:-/state}
TARGET=${TUNNEL_TARGET:-gateway:8080}
TOKEN=${PINGGY_TOKEN:-}

log() { echo "[tunnel $(date -u +%H:%M:%S)] $*"; }

redact() {
  # Anything that could carry a secret is replaced before it reaches the log.
  local s=$1
  [[ -n "$TOKEN" ]] && s=${s//"$TOKEN"/<PINGGY_TOKEN>}
  [[ -n "${SITE_PASS:-}" ]] && s=${s//"${SITE_PASS}"/<SITE_PASS>}
  printf '%s' "$s"
}

if [[ "${TUNNEL:-on}" == "off" ]]; then
  log "TUNNEL=off: no public URL. Local play: http://127.0.0.1:${LOCAL_PORT:-8080}/"
  exec sleep infinity
fi

# Pinggy reads its options from the SSH user name: keyword+keyword+...@server
opts=()
[[ -n "$TOKEN" ]] && opts+=("$TOKEN")
if [[ -n "${SITE_USER:-}" || -n "${SITE_PASS:-}" ]]; then
  if [[ -z "${SITE_USER:-}" || -z "${SITE_PASS:-}" ]]; then
    log "SITE_USER and SITE_PASS must both be set (or both empty)"; exit 1
  fi
  if [[ ! "$SITE_USER$SITE_PASS" =~ ^[A-Za-z0-9._-]+$ ]]; then
    log "SITE_USER / SITE_PASS may only use letters, digits, '.', '_' and '-'"; exit 1
  fi
  opts+=("b:${SITE_USER}:${SITE_PASS}")
fi
if [[ -n "${PINGGY_ALLOW_IPS:-}" ]]; then
  IFS=',' read -ra ips <<<"$PINGGY_ALLOW_IPS"
  for ip in "${ips[@]}"; do
    ip=${ip// /}
    [[ -z "$ip" ]] && continue
    if [[ ! "$ip" =~ ^[0-9A-Fa-f:.]+(/[0-9]{1,3})?$ ]]; then log "bad PINGGY_ALLOW_IPS entry: $ip"; exit 1; fi
    opts+=("w:$ip")
  done
fi
# Plain http visits are redirected to https.
opts+=("x:https")
if [[ -n "${PINGGY_EXTRA_OPTIONS:-}" ]]; then
  IFS='+' read -ra extra <<<"$PINGGY_EXTRA_OPTIONS"
  opts+=("${extra[@]}")
fi
if [[ -n "$TOKEN" ]]; then
  server=${PINGGY_SERVER:-pro.pinggy.io}
else
  server=${PINGGY_SERVER:-a.pinggy.io}
  opts+=("free")
fi
user=$(IFS='+'; echo "${opts[*]}")

mkdir -p "$STATE" 2>/dev/null || true
key="$STATE/id_ed25519"
known="$STATE/known_hosts"
ssh_cmd=(ssh -p 443
  -o "IdentityFile=$key" -o IdentitiesOnly=yes
  -o "UserKnownHostsFile=$known" -o StrictHostKeyChecking=accept-new
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o TCPKeepAlive=yes
  -o ExitOnForwardFailure=yes -o ConnectTimeout=20
  -o BatchMode=yes -o LogLevel=ERROR
  -T -R "0:${TARGET}"
  "${user}@${server}")

if [[ "${TUNNEL_DRY_RUN:-0}" == "1" ]]; then
  log "dry run, would run:"
  redact "$(printf '%q ' "${ssh_cmd[@]}")"; echo
  exit 0
fi

# A key pair just for Pinggy, so SSH never stops to ask for a password.
if [[ ! -s "$key" ]]; then
  ssh-keygen -q -t ed25519 -N '' -C openmmo-tunnel -f "$key" || { log "cannot create $key"; exit 1; }
fi

if [[ -n "${PINGGY_SUBDOMAIN:-}" ]]; then
  log "public URL (persistent): https://${PINGGY_SUBDOMAIN}/"
fi
[[ -z "$TOKEN" ]] && log "no PINGGY_TOKEN: free tier, the URL changes on every reconnect (60 min max)"

delay=2
while true; do
  log "connecting to ${server} -> ${TARGET}"
  started=$(date +%s)
  # Pinggy prints the URLs on this stream; keep those lines, redact everything.
  "${ssh_cmd[@]}" 2>&1 </dev/null | while IFS= read -r line; do
    line=$(redact "$line")
    if [[ "$line" =~ (https://[A-Za-z0-9.-]+) ]]; then
      log "PUBLIC URL: ${BASH_REMATCH[1]}"
      echo "${BASH_REMATCH[1]}" >"$STATE/url" 2>/dev/null || true
    else
      log "$line"
    fi
  done
  rc=${PIPESTATUS[0]}
  ran=$(( $(date +%s) - started ))
  (( ran > 120 )) && delay=2
  log "ssh exited (code $rc after ${ran}s); reconnecting in ${delay}s"
  sleep "$delay"
  delay=$(( delay * 2 )); (( delay > 60 )) && delay=60
done
