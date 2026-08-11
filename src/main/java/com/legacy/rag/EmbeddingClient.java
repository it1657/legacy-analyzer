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

    /**
     * 여러 텍스트를 한 번의 API 호출로 임베딩한다. {@link #embed(String)}을 텍스트 수만큼
     * 반복 호출하면 네트워크 왕복이 그대로 쌓여 파일이 많은 프로젝트에서 지연의 대부분을
     * 차지하므로({@code ProjectStructureRagService.index()}가 이 메서드의 주 호출부), 배치
     * 지원 백엔드에서는 반드시 이 메서드로 한 번에 처리해야 한다.
     *
     * @param texts 임베딩할 텍스트 목록
     * @return texts와 순서가 1:1 대응하는 임베딩 벡터 목록
     */
    List<List<Double>> embedBatch(List<String> texts);
}
