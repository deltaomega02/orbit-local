#!/usr/bin/env bash
# Orbit 실행 스크립트 (./run.sh)
set -euo pipefail
cd "$(dirname "$0")"

# .env 가 있으면 읽는다. 키가 없어도 옷장 기능은 동작하므로 없으면 안내만 한다.
if [ -f .env ]; then
  set -a; . ./.env; set +a
else
  echo "!! .env 가 없습니다. 'cp .env.example .env' 후 키를 채우면 AI 기능이 켜집니다."
fi

if [ -z "${ORBIT_JWT_SECRET:-}" ]; then
  echo "!! ORBIT_JWT_SECRET 이 없어 개발용 기본값으로 실행합니다(로컬 전용)."
fi
if [ -z "${GEMINI_API_KEY:-}" ]; then
  echo "!! GEMINI_API_KEY 가 없습니다. AI 기능(분석/추천/가상착용)은 503 이 됩니다."
fi

# JDK 탐색. macOS 는 /usr/bin/java 스텁이 항상 있어 command -v 대신 실제 실행으로 확인한다.
if ! java -version >/dev/null 2>&1; then
  for CAND in \
    "$(/usr/libexec/java_home -v 21 2>/dev/null || true)" \
    "$(brew --prefix openjdk@21 2>/dev/null || true)/libexec/openjdk.jdk/Contents/Home" \
    "$(brew --prefix openjdk 2>/dev/null || true)/libexec/openjdk.jdk/Contents/Home"
  do
    if [ -n "$CAND" ] && [ -x "$CAND/bin/java" ]; then
      export JAVA_HOME="$CAND"
      export PATH="$JAVA_HOME/bin:$PATH"
      break
    fi
  done
fi

if ! java -version >/dev/null 2>&1; then
  echo "!! JDK 21 을 찾지 못했습니다. 'brew install openjdk@21' 후 다시 실행해 주세요." >&2
  exit 1
fi

# 서버는 127.0.0.1 에만 바인딩된다. localhost 는 IPv6 ::1 로 먼저 해석될 수 있어 IPv4 주소로 안내한다.
echo "→ http://127.0.0.1:8080 (종료는 Ctrl+C)"
exec ./gradlew bootRun --console=plain
