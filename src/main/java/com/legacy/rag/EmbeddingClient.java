package com.legacy.rag;

import java.util.List;

/**
 * 텍스트를 임베딩 벡터로 변환하는 클라이언트 추상화. {@code com.legacy.analysis.llm.LlmClient}와
 * 동일한 이유로 인터페이스를 분리했다 — 지금은 로컬 백엔드(Ollama)용 구현체 하나뿐이지만,
 * 채택하는 백엔드가 늘어나면(예: OpenAI 표준 `/v1/embeddings`) 구현체만 추가하면 되게 한다.
 */
public interface EmbeddingClient {

    /**
     * @param text 임베딩할 텍스트
     * @return 임베딩 벡터(float 성분을 담은 double 리스트)
     */
    List<Double> embed(String text);
}
