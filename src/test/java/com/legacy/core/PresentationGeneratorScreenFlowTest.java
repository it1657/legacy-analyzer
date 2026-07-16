package com.legacy.core;

import com.legacy.analysis.AnalysisHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 부분 분석 범위(selectedRelativePaths)가 화면 흐름 다이어그램 엣지 추출에 반영되는지,
 * 그리고 실제 PPTX 생성까지 예외 없이 동작하는지 검증한다.
 */
class PresentationGeneratorScreenFlowTest {

  private void touch(Path file) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, "");
  }

  private Path buildFakeNextjsProject(Path tempDir) throws Exception {
    Path root = tempDir.resolve("fake-nextjs-project");
    touch(root.resolve("package.json"));
    touch(root.resolve("next.config.js"));
    touch(root.resolve("app/page.tsx"));
    touch(root.resolve("app/layout.tsx"));
    touch(root.resolve("app/login/page.tsx"));
    touch(root.resolve("app/dashboard/page.tsx"));
    touch(root.resolve("app/dashboard/settings/page.tsx"));
    touch(root.resolve("src/components/Button.tsx"));
    return root;
  }

  private Path buildFakeReactRouterProject(Path tempDir) throws Exception {
    Path root = tempDir.resolve("fake-react-router-project");
    touch(root.resolve("package.json"));
    Path appFile = root.resolve("src/App.tsx");
    Files.createDirectories(appFile.getParent());
    Files.writeString(appFile, """
        import Home from './pages/Home';
        import Login from './pages/Login';
        function App() {
          return (
            <Routes>
              <Route path="/" element={<Home />} />
              <Route path="/login" element={<Login />} />
            </Routes>
          );
        }
        """);
    return root;
  }

  @SuppressWarnings("unchecked")
  private List<Object> buildEdges(PresentationGeneratorService svc, Path root, String projectType,
      Set<String> selectedRelativePaths) throws Exception {
    Method m = PresentationGeneratorService.class.getDeclaredMethod(
        "buildScreenFlowEdges", Path.class, String.class, Set.class);
    m.setAccessible(true);
    return (List<Object>) m.invoke(svc, root, projectType, selectedRelativePaths);
  }

  private String edgeToString(Object edge) throws Exception {
    Method from = edge.getClass().getDeclaredMethod("from");
    Method to = edge.getClass().getDeclaredMethod("to");
    from.setAccessible(true);
    to.setAccessible(true);
    return from.invoke(edge) + " -> " + to.invoke(edge);
  }

  private List<String> edgeStrings(List<Object> edges) {
    return edges.stream().map(e -> {
      try { return edgeToString(e); } catch (Exception e2) { throw new RuntimeException(e2); }
    }).toList();
  }

  @Test
  void nextjsFileBasedRouting_fullScope(@TempDir Path tempDir) throws Exception {
    ProjectTypeDetector detector = new ProjectTypeDetector();
    PresentationGeneratorService svc = new PresentationGeneratorService(detector);
    Path root = buildFakeNextjsProject(tempDir);

    String type = detector.detectProjectType(root);
    assertEquals("nextjs", type, "next.config.js + package.json 있으면 nextjs로 감지되어야 함");

    List<String> edges = edgeStrings(buildEdges(svc, root, type, null));

    assertEquals(4, edges.size(), "app 하위: layout, login, dashboard, dashboard/settings = 4개 엣지 기대");
    assertTrue(edges.contains("app -> layout"));
    assertTrue(edges.contains("app -> login"));
    assertTrue(edges.contains("app -> dashboard"));
    assertTrue(edges.contains("dashboard -> settings"));
    assertTrue(edges.stream().noneMatch(s -> s.contains("page")), "page.tsx는 별도 노드가 되면 안 됨");
  }

  @Test
  void nextjsFileBasedRouting_partialScope_excludesUnselectedBranch(@TempDir Path tempDir) throws Exception {
    ProjectTypeDetector detector = new ProjectTypeDetector();
    PresentationGeneratorService svc = new PresentationGeneratorService(detector);
    Path root = buildFakeNextjsProject(tempDir);
    String type = detector.detectProjectType(root);

    // dashboard 관련 파일은 전혀 선택하지 않고, layout/login만 선택
    Set<String> selected = Set.of("app/layout.tsx", "app/login/page.tsx");
    List<String> edges = edgeStrings(buildEdges(svc, root, type, selected));

    assertTrue(edges.contains("app -> layout"));
    assertTrue(edges.contains("app -> login"));
    assertFalse(edges.contains("app -> dashboard"), "선택 범위 밖인 dashboard는 엣지에 없어야 함");
    assertFalse(edges.contains("dashboard -> settings"), "선택 범위 밖인 dashboard/settings는 엣지에 없어야 함");
  }

  @Test
  void reactRouterRegexFallback(@TempDir Path tempDir) throws Exception {
    ProjectTypeDetector detector = new ProjectTypeDetector();
    PresentationGeneratorService svc = new PresentationGeneratorService(detector);
    Path root = buildFakeReactRouterProject(tempDir);

    String type = detector.detectProjectType(root);
    assertEquals("react", type);

    List<String> edges = edgeStrings(buildEdges(svc, root, type, null));

    assertEquals(2, edges.size(), "<Route> 2개 -> 엣지 2개 기대");
    assertTrue(edges.contains("App -> Home"));
    assertTrue(edges.contains("App -> Login"));
  }

  @Test
  void noRoutingEvidence_returnsEmpty(@TempDir Path tempDir) throws Exception {
    ProjectTypeDetector detector = new ProjectTypeDetector();
    PresentationGeneratorService svc = new PresentationGeneratorService(detector);
    // 라우팅과 무관한 평범한 파일 하나만 있는 빈 프로젝트 - 근거가 전혀 없는 케이스
    Path root = tempDir.resolve("plain-project");
    touch(root.resolve("README.md"));

    List<Object> edges = buildEdges(svc, root, "general", null);
    assertTrue(edges.isEmpty(), "라우팅 근거가 없으면 빈 리스트여야 함(슬라이드 생략 트리거)");
  }

  private void deleteRecursively(Path root) throws Exception {
    try (var stream = Files.walk(root)) {
      stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
      });
    }
  }

  @Test
  void snapshotSurvivesDeletedSourceDirectory(@TempDir Path tempDir) throws Exception {
    ProjectTypeDetector detector = new ProjectTypeDetector();
    PresentationGeneratorService svc = new PresentationGeneratorService(detector);
    Path root = buildFakeNextjsProject(tempDir);

    // 1) 분석 완료 시점을 흉내내어 소스가 아직 존재할 때 스냅샷을 만들고 AnalysisHistory에 저장(JSON 직렬화)한다.
    ProjectStructureSnapshot snapshot = svc.buildStructureSnapshot(root, null);
    assertFalse(snapshot.packages.isEmpty(), "스냅샷에 패키지 카드가 있어야 함");
    assertFalse(snapshot.screenFlowEdges.isEmpty(), "스냅샷에 화면 흐름 엣지가 있어야 함");

    AnalysisHistory h = new AnalysisHistory(1L, "test-session", root.toString(), root.toString());
    h.setTotalFiles(6);
    h.setSuccessCount(6);
    h.setSkipCount(0);
    h.setFailureCount(0);
    h.setReadmeContent("## 아키텍처 구조\n일부 내용\n");
    h.setStructureSnapshot(snapshot);

    assertNotNull(h.getStructureSnapshotJson(), "JSON으로 직렬화되어 있어야 함");
    ProjectStructureSnapshot roundTripped = h.getStructureSnapshot();
    assertNotNull(roundTripped, "역직렬화가 성공해야 함");
    assertEquals(snapshot.packages.size(), roundTripped.packages.size(), "패키지 카드 개수가 왕복 후에도 유지되어야 함");
    assertEquals(snapshot.screenFlowEdges.size(), roundTripped.screenFlowEdges.size(), "화면 흐름 엣지 개수가 왕복 후에도 유지되어야 함");

    // 2) 업로드 분석의 write-back+cleanup을 흉내내어 소스 디렉터리를 통째로 삭제한다.
    deleteRecursively(root);
    assertFalse(Files.exists(root), "테스트 전제: 소스 디렉터리가 실제로 삭제돼야 함");

    // 3) 소스가 이미 사라진 상태에서도, 저장된 스냅샷만으로 PPT가 정상 생성돼야 한다 (이번 리팩터링의 핵심 회귀 검증).
    byte[] pptx = svc.generateProjectReportPresentation(h);
    assertTrue(pptx.length > 1000, "소스가 삭제된 뒤에도 PPTX가 정상 생성돼야 함");

    try (org.apache.poi.xslf.usermodel.XMLSlideShow ppt =
        new org.apache.poi.xslf.usermodel.XMLSlideShow(new java.io.ByteArrayInputStream(pptx))) {
      boolean foundConnector = false;
      for (var slide : ppt.getSlides()) {
        for (var shape : slide.getShapes()) {
          if (shape instanceof org.apache.poi.xslf.usermodel.XSLFConnectorShape) foundConnector = true;
        }
      }
      assertTrue(foundConnector, "소스 삭제 후에도 화면 흐름 슬라이드가 저장된 스냅샷 기준으로 그려져야 함");
    }
  }

  @Test
  void fullPptGeneration_smokeTest(@TempDir Path tempDir) throws Exception {
    ProjectTypeDetector detector = new ProjectTypeDetector();
    PresentationGeneratorService svc = new PresentationGeneratorService(detector);
    Path root = buildFakeNextjsProject(tempDir);

    AnalysisHistory h = new AnalysisHistory(1L, "test-session", root.toString(), root.toString());
    h.setTotalFiles(6);
    h.setSuccessCount(6);
    h.setSkipCount(0);
    h.setFailureCount(0);
    h.setReadmeContent("## 아키텍처 구조\n### 화면 흐름\n\n## 도메인별 기능 분석\n일부 내용\n");
    h.setSelectedRelativePaths(Set.of("app/layout.tsx", "app/login/page.tsx"));

    byte[] pptx = svc.generateProjectReportPresentation(h);
    assertTrue(pptx.length > 1000, "PPTX 바이트가 비정상적으로 작음");

    // 화면 흐름 슬라이드에 실제 커넥터(화살표) 도형이 있는지 확인
    try (org.apache.poi.xslf.usermodel.XMLSlideShow ppt =
        new org.apache.poi.xslf.usermodel.XMLSlideShow(new java.io.ByteArrayInputStream(pptx))) {
      boolean foundConnector = false;
      for (var slide : ppt.getSlides()) {
        for (var shape : slide.getShapes()) {
          if (shape instanceof org.apache.poi.xslf.usermodel.XSLFConnectorShape) foundConnector = true;
        }
      }
      assertTrue(foundConnector, "화면 흐름 슬라이드에 커넥터(화살표) 도형이 있어야 함");
    }
  }
}
