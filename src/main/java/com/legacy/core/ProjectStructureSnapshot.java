package com.legacy.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 완료 시점에 한 번 계산해 DB(AnalysisHistory)에 JSON으로 저장하는 프로젝트 구조 스냅샷.
 * PPT 보고서 생성이 다운로드할 때마다 디스크를 재스캔하지 않고 이 데이터만으로 슬라이드를 그리도록
 * 만들기 위한 것 — 업로드 분석은 write-back 후 소스가 삭제되어 재스캔 자체가 불가능해지기 때문에,
 * 그리고 다운로드할 때마다 결과가 달라지는 것을 막기 위해 도입했다.
 */
public class ProjectStructureSnapshot {

  public String projectType;

  /** 도메인/구조 카드 - java/frontend/python/general 전 타입이 동일한 [name, description, summary] 셰이프를 쓴다. */
  public List<DirCard> packages = new ArrayList<>();

  /** 아키텍처/계층별 역할 카드 - 키 이름만 타입별로 다르고(Controller vs Pages/Routes 등) 셰이프는 동일하다. */
  public Map<String, List<String>> byLayer = new LinkedHashMap<>();

  /** renderJavaPackageStructure의 "📦 project · rootPkg" 같은 헤더 한 줄 (java/frontend/python 전용, general은 미사용). */
  public String headerLabel;

  /** general 타입 전용 - buildGeneralTree()가 만드는 들여쓰기 텍스트 트리. */
  public String generalTree;

  public List<ScreenFlowEdge> screenFlowEdges = new ArrayList<>();

  public List<ResourceEntry> configFiles = new ArrayList<>();
  public List<ResourceEntry> mapperFiles = new ArrayList<>();
  public List<ResourceEntry> templateFiles = new ArrayList<>();

  /** 구조 슬라이드 하단의 "📋 package.json" / "📋 src/main/resources/" 안내 한 줄 표시 여부. */
  public boolean hasPackageJsonOrResourcesDir;

  /** src/main/resources/ 디렉터리 존재 여부 - 리소스 슬라이드를 아예 생성할지 판단하는 용도(위 필드와는 별개). */
  public boolean hasResourcesDir;

  public record DirCard(String name, String description, String summary) {}

  public record ScreenFlowEdge(String from, String to) {}

  /** configFiles=[name,description], mapperFiles=[name,namespace,querySummary], templateFiles=[name,relPath] 공용 셰이프. */
  public record ResourceEntry(String a, String b, String c) {}
}
