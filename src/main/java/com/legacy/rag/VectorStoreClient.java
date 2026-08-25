package com.legacy.rag;

import java.util.List;
import java.util.Map;

/**
 * 벡터스토어(임베딩 upsert/유사도 검색)를 감싸는 클라이언트 추상화. {@link EmbeddingClient}와
 * 동일한 이유로 인터페이스를 분리했다 — 지금은 Chroma용 구현체({@link ChromaClient}) 하나뿐이지만,
 * 채택하는 벡터스토어가 바뀌어도(예: pgvector) 이 인터페이스 구현체만 추가하면
 * {@link ProjectStructureRagService}는 무수정으로 그대로 재사용 가능하게 한다(결합도 해소,
 * 2026-08-20 PM/PL 설계).
 */
public interface VectorStoreClient {

    /**
     * 이름으로 컬렉션을 가져오거나(없으면) 생성하고, id를 반환한다.
     *
     * @param name 컬렉션 이름
     * @return 컬렉션 id
     */
    String createOrGetCollection(String name);

    /**
     * ids/embeddings/documents/metadatas는 모두 같은 길이의 병렬 리스트여야 한다.
     *
     * @param collectionId {@link #createOrGetCollection(String)}이 반환한 id
     */
    void upsert(String collectionId, List<String> ids, List<List<Double>> embeddings,
            List<String> documents, List<Map<String, Object>> metadatas);

    /**
     * @param collectionId {@link #createOrGetCollection(String)}이 반환한 id
     * @param where 메타데이터 필터(예: {"package": "com.legacy.rag"}), 필터 없으면 null
     * @return 유사도 상위 topK개 document 텍스트(쿼리 1개 기준)
     */
    List<String> query(String collectionId, List<Double> queryEmbedding, int topK, Map<String, Object> where);

    /**
     * 컬렉션을 삭제한다. id가 아니라 {@link #createOrGetCollection(String)} 호출 시 쓴 이름을
     * 그대로 넘겨야 한다(구현체별 실제 서버 계약 확인 결과 — {@link ChromaClient} 참고).
     *
     * @param name 컬렉션 이름
     */
    void deleteCollection(String name);
}
