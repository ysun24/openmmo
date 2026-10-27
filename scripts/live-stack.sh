#!/usr/bin/env bash
# Starts the local dev stack (game, login, web gateway), all bound to 127.0.0.1.
# Requires the two postgres containers from .env (openmmo-login-db / openmmo-game-db) to be up and
# a prior build: ./gradlew :keys:generateGame installDist :web.gateway:installDist
#
#   scripts/live-stack.sh start      # logs and pid files in build/live/
#   scripts/live-stack.sh status
#
# Ports: game 7777, login 2106, gateway 8080 (override with GAME_PORT / LOGIN_PORT / GW_PORT).
# Stop the processes by the pids printed by `status` (build/live/*.pid).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
LIVE=$ROOT/build/live
mkdir -p "$LIVE"
GAME_PORT=${GAME_PORT:-7777}
LOGIN_PORT=${LOGIN_PORT:-2106}
GW_PORT=${GW_PORT:-8080}
export JAVA_HOME=${JAVA_HOME:-$HOME/.jdks/jdk-25.0.5+8}

wait_port() {
  for _ in $(seq 1 120); do
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && return 0
    sleep 0.5
  done
  echo "port $1 did not open" >&2
  return 1
}

case "${1:-start}" in
  start)
    set -a; . ./.env; set +a
    export OPENMMO_GAME_PRIVATE_KEY_FILE=$ROOT/keys/build/game.private.pem
    export OPENMMO_ADMIN_USERNAME=${OPENMMO_ADMIN_USERNAME:-yicheng}
    export OPENMMO_ADMIN_PASSWORD=${OPENMMO_ADMIN_PASSWORD:-pikachu123}
    JAVA_OPTS="-Dserver.host=127.0.0.1 -Dserver.port=$GAME_PORT" \
      nohup server.game/build/install/server.game/bin/server.game >"$LIVE/game.log" 2>&1 &
    echo $! >"$LIVE/game.pid"
    wait_port "$GAME_PORT"
    GAME_SERVER_PORT=$GAME_PORT JAVA_OPTS="-Dserver.host=127.0.0.1 -Dserver.port=$LOGIN_PORT" \
      nohup server.login/build/install/server.login/bin/server.login >"$LIVE/login.log" 2>&1 &
    echo $! >"$LIVE/login.pid"
    wait_port "$LOGIN_PORT"
    nohup web/gateway/build/install/web.gateway/bin/web.gateway --host 127.0.0.1 --port "$GW_PORT" \
      --login-port "$LOGIN_PORT" --static web/static --key keys/build/game.public.pem --decomp decomp \
      >"$LIVE/gateway.log" 2>&1 &
    echo $! >"$LIVE/gateway.pid"
    wait_port "$GW_PORT"
    echo "up: game $GAME_PORT login $LOGIN_PORT gateway http://127.0.0.1:$GW_PORT/"
    ;;
  status)
    for n in game login gateway; do
      if [[ -f $LIVE/$n.pid ]]; then
        pid=$(cat "$LIVE/$n.pid")
        if kill -0 "$pid" 2>/dev/null; then echo "$n pid $pid running"; else echo "$n pid $pid dead"; fi
      else
        echo "$n not started by this script"
      fi
    done
    ;;
  *) echo "usage: $0 start|status" >&2; exit 2 ;;
esac
