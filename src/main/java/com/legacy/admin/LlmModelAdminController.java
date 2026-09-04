package com.legacy.admin;

import com.legacy.analysis.llm.LlmModelOption;
import com.legacy.analysis.llm.LlmModelOptionService;
import com.legacy.analysis.llm.LlmProvider;
import com.legacy.analysis.llm.OllamaModelDiscoveryClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 관리자용 LLM 모델 목록 CRUD API 6종.
 * 실제 도메인 규칙(활성 모델 최소 1개 유지, failover 대상 단일성 등)은 {@link LlmModelOptionService}
 * (com.legacy.analysis.llm 패키지)가 담당하고, 이 컨트롤러는 HTTP 계층 변환만 맡는다 —
 * com.legacy.admin → com.legacy.analysis 기존 의존 방향을 그대로 지킨다(역방향 금지).
 *
 * 근거: analyzer-plan docs/chat/etc/2026-08-21-llm-model-db-crud-and-credit-exhaustion-failover-design.md
 */
@RestController
@RequestMapping("/api/admin/llm-models")
@PreAuthorize("hasRole('ADMIN')")
public class LlmModelAdminController {

  private static final Logger log = LoggerFactory.getLogger(LlmModelAdminController.class);

  private final LlmModelOptionService llmModelOptionService;
  private final OllamaModelDiscoveryClient ollamaModelDiscoveryClient;

  @Autowired
  public LlmModelAdminController(LlmModelOptionService llmModelOptionService,
      OllamaModelDiscoveryClient ollamaModelDiscoveryClient) {
    this.llmModelOptionService = llmModelOptionService;
    this.ollamaModelDiscoveryClient = ollamaModelDiscoveryClient;
  }

  // 1. 전체 목록 조회 (활성/비활성 무관)
  @GetMapping
  @ResponseBody
  public List<Map<String, Object>> listModels() {
    return llmModelOptionService.listAll().stream()
        .map(this::toResponseMap)
        .collect(Collectors.toList());
  }

  // 2. 등록
  @PostMapping
  @ResponseBody
  public ResponseEntity<?> createModel(@RequestBody Map<String, Object> request) {
    try {
      String modelKey = (String) request.get("modelKey");
      String displayName = (String) request.get("displayName");
      LlmProvider provider = LlmProvider.valueOf(String.valueOf(request.get("provider")).toUpperCase());
      int displayOrder = request.get("displayOrder") == null ? 0
          : Integer.parseInt(String.valueOf(request.get("displayOrder")));

      // REQ-003(2026-09): 관리자 수동 등록 경로만 Ollama 실제 설치 여부를 하드 검증한다.
      // (기동 시 시드 경로는 여전히 검증 없는 create()를 쓴다 — 설계 §3.2-bis)
      LlmModelOption saved = llmModelOptionService.createWithOllamaValidation(
          modelKey, displayName, provider, displayOrder);
      return ResponseEntity.ok(toResponseMap(saved));
    } catch (IllegalArgumentException | IllegalStateException e) {
      return errorResponse(e.getMessage());
    } catch (Exception e) {
      log.error("[LLM 모델 등록 실패]", e);
      return errorResponse("모델 등록 실패: " + e.getMessage());
    }
  }

  // 3. 수정 (표시명/노출순서)
  @PutMapping("/{id}")
  @ResponseBody
  public ResponseEntity<?> updateModel(@PathVariable Long id, @RequestBody Map<String, Object> request) {
    try {
      String displayName = (String) request.get("displayName");
      int displayOrder = request.get("displayOrder") == null ? 0
          : Integer.parseInt(String.valueOf(request.get("displayOrder")));
      LlmModelOption saved = llmModelOptionService.update(id, displayName, displayOrder);
      return ResponseEntity.ok(toResponseMap(saved));
    } catch (IllegalArgumentException | IllegalStateException e) {
      return errorResponse(e.getMessage());
    } catch (Exception e) {
      log.error("[LLM 모델 수정 실패]", e);
      return errorResponse("모델 수정 실패: " + e.getMessage());
    }
  }

  // 4. 활성/비활성 토글 (비활성화 시 "활성 모델 0개" 방지 검증이 걸릴 수 있음)
  @PatchMapping("/{id}/active")
  @ResponseBody
  public ResponseEntity<?> setActive(@PathVariable Long id, @RequestBody Map<String, Object> request) {
    try {
      boolean active = Boolean.TRUE.equals(request.get("active"));
      LlmModelOption saved = llmModelOptionService.setActive(id, active);
      return ResponseEntity.ok(toResponseMap(saved));
    } catch (IllegalArgumentException | IllegalStateException e) {
      return errorResponse(e.getMessage());
    } catch (Exception e) {
      log.error("[LLM 모델 활성상태 변경 실패]", e);
      return errorResponse("활성상태 변경 실패: " + e.getMessage());
    }
  }

  // 5. failover 대상 지정/해제 (지정 시 기존 대상은 자동 해제 — 정확히 0/1개만 유지)
  @PatchMapping("/{id}/failover-target")
  @ResponseBody
  public ResponseEntity<?> setFailoverTarget(@PathVariable Long id, @RequestBody Map<String, Object> request) {
    try {
      boolean isTarget = Boolean.TRUE.equals(request.get("failoverTarget"));
      LlmModelOption saved = llmModelOptionService.setFailoverTarget(id, isTarget);
      return ResponseEntity.ok(toResponseMap(saved));
    } catch (IllegalArgumentException | IllegalStateException e) {
      return errorResponse(e.getMessage());
    } catch (Exception e) {
      log.error("[LLM 모델 failover 대상 변경 실패]", e);
      return errorResponse("failover 대상 변경 실패: " + e.getMessage());
    }
  }

  // 6. 삭제 (대상이 유일한 활성 모델이면 거부)
  @DeleteMapping("/{id}")
  @ResponseBody
  public ResponseEntity<?> deleteModel(@PathVariable Long id) {
    try {
      llmModelOptionService.delete(id);
      Map<String, Object> body = new HashMap<>();
      body.put("success", true);
      body.put("message", "삭제되었습니다.");
      return ResponseEntity.ok(body);
    } catch (IllegalArgumentException | IllegalStateException e) {
      return errorResponse(e.getMessage());
    } catch (Exception e) {
      log.error("[LLM 모델 삭제 실패]", e);
      return errorResponse("모델 삭제 실패: " + e.getMessage());
    }
  }

  // 7. Ollama에 실제 설치된 모델 목록 조회 (관리자 등록 폼의 자동완성 datalist용, REQ-003 / 2026-09)
  // 조회 실패(Ollama 미기동/비Ollama 백엔드 등)도 정상 흐름의 일부라 500을 던지지 않고 항상 200으로
  // available=false를 돌려준다 — 프런트는 이 경우 자동완성만 비우고 자유 입력을 그대로 허용한다.
  @GetMapping("/ollama-installed")
  @ResponseBody
  public Map<String, Object> listOllamaInstalledModels() {
    Optional<List<String>> discovered = ollamaModelDiscoveryClient.listInstalledModels();
    Map<String, Object> result = new HashMap<>();
    result.put("available", discovered.isPresent());
    result.put("models", discovered.orElse(List.of()));
    return result;
  }

  private ResponseEntity<Map<String, Object>> errorResponse(String message) {
    Map<String, Object> body = new HashMap<>();
    body.put("success", false);
    body.put("message", message);
    return ResponseEntity.badRequest().body(body);
  }

  private Map<String, Object> toResponseMap(LlmModelOption option) {
    Map<String, Object> map = new HashMap<>();
    map.put("id", option.getId());
    map.put("modelKey", option.getModelKey());
    map.put("displayName", option.getDisplayName());
    map.put("provider", option.getProvider().name());
    map.put("displayOrder", option.getDisplayOrder());
    map.put("active", option.isActive());
    map.put("failoverTarget", option.isFailoverTarget());
    return map;
  }
}
