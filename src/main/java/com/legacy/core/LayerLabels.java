package com.legacy.core;

/**
 * README "## 계층별 역할 정의" 섹션의 필수 하위 제목과 PPT 계층 카드 키를
 * 하나의 상수로 묶어 두 곳(ClaudeServiceImpl, PresentationGeneratorService)이
 * 서로 다른 문자열을 쓰는 drift를 방지한다.
 */
public final class LayerLabels {
  private LayerLabels() {}

  public static final String[] FRONTEND = {"Pages/Routes", "Components", "State/Hooks", "API/Services"};
  public static final String[] PYTHON   = {"Router/View", "Service/Logic", "Model", "Config"};
  public static final String[] JAVA     = {"Controller 계층", "Service 계층", "Repository 계층", "Entity / DTO"};
}
