#!/bin/sh
# docker/ollama-entrypoint.sh
#
# 공식 ollama/ollama 이미지를 그대로 pull해서 쓰되(별도 이미지 빌드 없이),
# docker-compose.yml에서 이 스크립트를 바인드 마운트 + entrypoint 오버라이드로 얹어
# 컨테이너 최초 기동 시 필요한 모델을 자동으로 pull한다.
# 설계 근거: docs/advancement/2.scenario/scenario_1.md "최초 기동 자동화" 절,
#           docs/advancement/3.confirmed/scenario_1_confirmed.md
set -e

MODEL="${OLLAMA_MODEL:-qwen2.5-coder:7b}"

# 1) 원래 엔트리포인트(ollama serve)를 백그라운드로 실행
ollama serve &
SERVE_PID=$!

# 2) API가 응답할 때까지 대기(최대 60초) — serve가 뜨기 전에 pull을 시도하면 실패함
i=0
while [ "$i" -lt 60 ]; do
  if ollama list >/dev/null 2>&1; then
    break
  fi
  i=$((i + 1))
  sleep 1
done

# 3) 모델이 이미 있으면(재시작 케이스) 스킵, 없으면(최초 기동) pull
if ollama list | grep -q "$MODEL"; then
  echo "[entrypoint] $MODEL 이미 존재 — pull 생략"
else
  echo "[entrypoint] $MODEL pull 시작..."
  ollama pull "$MODEL"
  echo "[entrypoint] $MODEL pull 완료"
fi

# 4) 서버 프로세스를 포그라운드로 유지 — 컨테이너 생명주기 = 서버 생명주기
wait "$SERVE_PID"
