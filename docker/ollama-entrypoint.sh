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
# RAG(Chroma) 채택 시에만 쓰는 임베딩 전용 모델 — plan.md "RAG(Chroma)" 절 참고.
# 채팅 모델과 별개로 pull해야 함(같은 ollama 서버가 두 모델을 동시에 서빙).
# 비어 있으면(RAG 미채택 환경, 예: 회사 서버) 이 단계 자체를 건너뛴다.
EMBED_MODEL="${RAG_EMBEDDING_MODEL:-}"

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

# 3-1) 임베딩 모델 pull(RAG 채택 환경만 해당). 실패해도 컨테이너 전체를 죽이지 않는다 —
# RAG는 선택 기능이라 임베딩 pull 실패가 채팅 모델(핵심 경로)까지 막으면 안 된다.
# healthcheck는 여전히 $MODEL(채팅 모델)만 확인하므로 이 단계의 성패는 컨테이너
# healthy 판정에 영향을 주지 않는다 — RAG를 실제로 쓸 때 임베딩 호출이 실패하는
# 형태로만 드러나며, ProjectStructureRagService 쪽에서 별도로 처리한다.
if [ -n "$EMBED_MODEL" ]; then
  if ollama list | grep -q "$EMBED_MODEL"; then
    echo "[entrypoint] 임베딩 모델 $EMBED_MODEL 이미 존재 — pull 생략"
  else
    echo "[entrypoint] 임베딩 모델 $EMBED_MODEL pull 시작..."
    if ollama pull "$EMBED_MODEL"; then
      echo "[entrypoint] 임베딩 모델 $EMBED_MODEL pull 완료"
    else
      echo "[entrypoint] 경고: 임베딩 모델 $EMBED_MODEL pull 실패 — RAG 기능만 영향받고 나머지는 정상 진행"
    fi
  fi
fi

# 4) 서버 프로세스를 포그라운드로 유지 — 컨테이너 생명주기 = 서버 생명주기
wait "$SERVE_PID"
