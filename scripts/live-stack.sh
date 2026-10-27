#!/usr/bin/env bash
# Starts the local dev stack (game, login, web gateway), all bound to 127.0.0.1.
# Requires the two postgres containers from .env (openmmo-login-db / openmmo-game-db) to be up and
# a prior build: ./gradlew :keys:generateGame installDist :web.gateway:installDist
#
#   scripts/live-stack.sh start      # logs and pid files in build/live/
#   scripts/live-stack.sh status
#   scripts/live-stack.sh stop       # kills exactly the pids in build/live/*.pid (after checking them)
#
# Each server is started detached (setsid + nohup, its own session and process group, stdin from
# /dev/null), so it survives the shell or agent that started it. The pid files hold the java pid
# (the install scripts exec java), which is also the process group id.
#
# Ports: game 7777, login 2106, gateway 8080 (override with GAME_PORT / LOGIN_PORT / GW_PORT).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
LIVE=$ROOT/build/live
mkdir -p "$LIVE"
GAME_PORT=${GAME_PORT:-7777}
LOGIN_PORT=${LOGIN_PORT:-2106}
GW_PORT=${GW_PORT:-8080}
export JAVA_HOME=${JAVA_HOME:-$HOME/.jdks/jdk-25.0.5+8}

# detach NAME CMD... : start CMD in its own session, record its pid
detach() {
  local name=$1; shift
  setsid nohup "$@" >"$LIVE/$name.log" 2>&1 </dev/null &
  local pid=$!
  echo "$pid" >"$LIVE/$name.pid"
}

# is_ours PID : the pid is alive and is one of this checkout's java servers
is_ours() {
  [[ -r /proc/$1/cmdline ]] && tr '\0' ' ' </proc/$1/cmdline | grep -qF -- "$ROOT/"
}

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
      detach game server.game/build/install/server.game/bin/server.game
    wait_port "$GAME_PORT"
    GAME_SERVER_PORT=$GAME_PORT JAVA_OPTS="-Dserver.host=127.0.0.1 -Dserver.port=$LOGIN_PORT" \
      detach login server.login/build/install/server.login/bin/server.login
    wait_port "$LOGIN_PORT"
    detach gateway web/gateway/build/install/web.gateway/bin/web.gateway --host 127.0.0.1 --port "$GW_PORT" \
      --login-port "$LOGIN_PORT" --static web/static --key keys/build/game.public.pem --decomp decomp
    wait_port "$GW_PORT"
    echo "up: game $GAME_PORT login $LOGIN_PORT gateway http://127.0.0.1:$GW_PORT/"
    ;;
  status)
    for n in game login gateway; do
      if [[ -f $LIVE/$n.pid ]]; then
        pid=$(cat "$LIVE/$n.pid")
        if is_ours "$pid"; then
          echo "$n pid $pid running (pgid $(ps -o pgid= -p "$pid" | tr -d ' '))"
        else
          echo "$n pid $pid dead"
        fi
      else
        echo "$n not started by this script"
      fi
    done
    ;;
  stop)
    for n in gateway login game; do
      [[ -f $LIVE/$n.pid ]] || continue
      pid=$(cat "$LIVE/$n.pid")
      if is_ours "$pid"; then
        kill "$pid"
        for _ in $(seq 1 40); do [[ -d /proc/$pid ]] || break; sleep 0.25; done
        echo "$n pid $pid stopped"
      else
        echo "$n pid $pid not running"
      fi
      rm -f "$LIVE/$n.pid"
    done
    ;;
  *) echo "usage: $0 start|status|stop" >&2; exit 2 ;;
esac
