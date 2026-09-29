#!/usr/bin/env bash
# Полная копия сервера Vanilla+ 26.2 с модом «Рикошет» — для проверки перед выкладкой.
# Описание: tools/testserver/README.md.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="${RIKOSHET_SERVER_SRC:-$HOME/common/projects/mc-fabric-26.2/server}"
WORK="${RIKOSHET_TEST_DIR:-$REPO/run/full-server}"
MEM="${MEM:-4G}"
JAR=fabric-server-mc.26.2-loader.0.19.5-launcher.1.1.2.jar
FIFO="$WORK/.console"
PIDFILE="$WORK/.pid"
LOG="$WORK/logs/latest.log"

java_bin() {
  if [[ -n "${JAVA_HOME:-}" ]]; then echo "$JAVA_HOME/bin/java"; else echo "$(/usr/libexec/java_home -v 25)/bin/java"; fi
}

running() {
  [[ -f "$PIDFILE" ]] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null
}

cmd_prepare() {
  running && { echo "сервер запущен — сначала stop"; exit 1; }
  mkdir -p "$WORK"
  # Данные EasyAuth (хеши паролей) и логи не копируем; мир — копией, исходник не трогаем
  rsync -a --delete \
    --exclude /EasyAuth/ --exclude /logs/ --exclude /crash-reports/ --exclude /rikoshet/ \
    --exclude 'mods/rikoshet-*.jar' --exclude .console --exclude .pid \
    "$SRC/" "$WORK/"
  (cd "$REPO" && ./gradlew -q build -x test)
  rm -f "$WORK"/mods/rikoshet-*.jar
  # Только jar текущей версии: в build/libs могут лежать старые, а два jar мода — падение на старте
  local version; version="$(sed -n 's/^mod_version=//p' "$REPO/gradle.properties")"
  cp "$REPO/build/libs/rikoshet-$version.jar" "$WORK/mods/"
  # Фейковые игроки /rickdev не входят в список игроков, и пустой сервер встаёт на паузу;
  # для замеров нагрузки паузу можно выключить: PAUSE_EMPTY=0 testserver.sh prepare
  if [[ -n "${PAUSE_EMPTY:-}" ]]; then
    sed -i '' "s/^pause-when-empty-seconds=.*/pause-when-empty-seconds=$PAUSE_EMPTY/" "$WORK/server.properties"
  fi
  # Ресурспак мода раздаёт Polymer autohost; вне dev-среды он выключен, пока не включишь в конфиге
  if [[ ! -f "$WORK/config/polymer/auto-host.json" ]]; then
    mkdir -p "$WORK/config/polymer"
    printf '{\n  "enabled": true\n}\n' >"$WORK/config/polymer/auto-host.json"
  fi
  # Конфиг мода по умолчанию с включёнными флагами: FEATURES="chronicle newspaper" testserver.sh prepare
  if [[ -n "${FEATURES:-}" ]]; then
    mkdir -p "$WORK/config"
    cp "$REPO/src/main/resources/rikoshet/default-config.json5" "$WORK/config/rikoshet.json5"
    for f in $FEATURES; do
      grep -q "^    $f: false," "$WORK/config/rikoshet.json5" || { echo "нет флага $f"; exit 1; }
      sed -i '' "s/^    $f: false,/    $f: true,/" "$WORK/config/rikoshet.json5"
    done
  fi
  echo "готово: $WORK (мод $(ls "$WORK"/mods/rikoshet-*.jar | xargs basename))"
}

cmd_start() {
  running && { echo "уже запущен, pid $(cat "$PIDFILE")"; exit 0; }
  [[ -f "$WORK/$JAR" ]] || { echo "нет копии сервера — сначала prepare"; exit 1; }
  rm -f "$FIFO"; mkfifo "$FIFO"
  mkdir -p "$WORK/logs"
  : >"$WORK/logs/stdout.log"  # иначе «Done» найдётся в логе прошлого запуска
  (
    cd "$WORK"
    # Держим FIFO открытым на запись, чтобы консоль сервера не получила EOF
    exec 3<>"$FIFO"
    nohup "$(java_bin)" -Xms"$MEM" -Xmx"$MEM" -XX:+UseG1GC -Drikoshet.dev=true -jar "$JAR" nogui <&3 >"$WORK/logs/stdout.log" 2>&1 &
    echo $! >"$PIDFILE"
  )
  echo "запуск, pid $(cat "$PIDFILE"); ключ OpenRouter: $([[ -n "${OPENROUTER_API_KEY:-}" ]] && echo есть || echo нет)"
  for _ in $(seq 1 180); do
    if grep -q 'Done (' "$WORK/logs/stdout.log" 2>/dev/null; then
      grep -m1 'Done (' "$WORK/logs/stdout.log"; return 0
    fi
    running || { echo "сервер упал:"; tail -40 "$WORK/logs/stdout.log"; exit 1; }
    sleep 1
  done
  echo "не дождался Done за 180 с"; exit 1
}

cmd_cmd() {
  running || { echo "сервер не запущен"; exit 1; }
  local before; before=$(wc -l <"$WORK/logs/stdout.log")
  printf '%s\n' "$*" >"$FIFO"
  sleep "${WAIT:-2}"
  tail -n +"$((before + 1))" "$WORK/logs/stdout.log"
}

cmd_stop() {
  running || { echo "не запущен"; return 0; }
  printf 'stop\n' >"$FIFO"
  for _ in $(seq 1 60); do running || break; sleep 1; done
  running && kill "$(cat "$PIDFILE")"
  rm -f "$PIDFILE" "$FIFO"
  echo "остановлен"
}

# Доля главного потока, потраченная кодом мода: JFR с выборкой раз в 1 мс, локально.
# spark profiler не используем — он выгружает отчёт на внешний сайт.
cmd_profile() {
  running || { echo "сервер не запущен"; exit 1; }
  local secs="${1:-60}" out="$WORK/logs/profile.jfr" jcmd jfr
  jcmd="$(dirname "$(java_bin)")/jcmd"; jfr="$(dirname "$(java_bin)")/jfr"
  grep -q 'pausing' "$WORK/logs/stdout.log" && echo "внимание: сервер на паузе (пуст) — тики не идут, замер неполный"
  rm -f "$out"
  "$jcmd" "$(cat "$PIDFILE")" JFR.start name=rikoshet settings=profile 'jdk.ExecutionSample#period=1ms' filename="$out" >/dev/null
  echo "пишу профиль ${secs} с…"
  sleep "$secs"
  "$jcmd" "$(cat "$PIDFILE")" JFR.stop name=rikoshet >/dev/null
  "$jfr" print --json --events jdk.ExecutionSample "$out" | python3 "$(dirname "$0")/jfr-share.py" "$secs"
}

cmd_log() {
  grep -aE "${1:-rikoshet|Рикошет|\[Рик\]|ERROR|Exception}" "$WORK/logs/stdout.log" | tail -n "${2:-60}"
}

case "${1:-}" in
  prepare) cmd_prepare ;;
  start) cmd_start ;;
  cmd) shift; cmd_cmd "$@" ;;
  stop) cmd_stop ;;
  log) shift; cmd_log "$@" ;;
  profile) shift; cmd_profile "$@" ;;
  *) echo "использование: $0 prepare|start|cmd <команда>|log [regex] [строк]|profile [секунд]|stop"; exit 1 ;;
esac
