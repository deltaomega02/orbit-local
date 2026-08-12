#!/usr/bin/env bash
# Orbit 실행 스크립트. 이 파일을 더블클릭하거나 터미널에서 ./run.sh 하면 됩니다.
set -euo pipefail
cd "$(dirname "$0")"

# .env 가 있으면 읽어들인다. 없으면 안내만 하고 계속 진행한다 —
# 키가 없어도 옷장 기능은 동작하므로 실행 자체를 막을 이유는 없다.
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

# JDK 를 찾는다.
# macOS 에는 /usr/bin/java 스텁이 항상 존재해서 `command -v java` 는 런타임이
# 없어도 성공한다. 그래서 존재가 아니라 **실제로 동작하는지**로 판단한다.
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

echo "→ http://localhost:8080 (종료는 Ctrl+C)"
exec ./gradlew bootRun --console=plain
