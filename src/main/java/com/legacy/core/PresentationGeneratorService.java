package com.legacy.core;

import com.legacy.analysis.AnalysisHistory;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.*;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class PresentationGeneratorService {

  private static final int W = 960;
  private static final int H = 540;

  // 색상 팔레트
  private static final Color BG_DARK    = new Color(15, 23, 42);    // 슬레이트 950
  private static final Color BG_CARD    = new Color(30, 41, 59);    // 슬레이트 800
  private static final Color ACCENT     = new Color(99, 179, 237);  // 파란색
  private static final Color ACCENT2    = new Color(154, 230, 180); // 초록색
  private static final Color TEXT_WHITE = Color.WHITE;
  private static final Color TEXT_GRAY  = new Color(148, 163, 184); // 슬레이트 400
  private static final Color BADGE_BLUE = new Color(37, 99, 235);
  private static final Color BADGE_GRN  = new Color(22, 163, 74);

  private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  /**
   * 분석 완료 직후 화면용 PPT — 내부 통계(파일 수, 성공률, 토큰/비용) 중심.
   */
  public byte[] generateAnalysisResultPresentation(AnalysisHistory h) throws IOException {
    log.info("📊 분석 결과 요약 PPT 생성 - historyId={}", h.getId());

    XMLSlideShow ppt = new XMLSlideShow();
    ppt.setPageSize(new Dimension(W, H));

    createResultTitleSlide(ppt, h);
    createResultSummarySlide(ppt, h);
    createResultTokenSlide(ppt, h);
    createResultClosingSlide(ppt, h);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ppt.write(baos);
    ppt.close();

    log.info("✅ 분석 요약 PPTX 생성 완료: {}KB", baos.size() / 1024);
    return baos.toByteArray();
  }

  /**
   * 내 분석이력용 고객 납품 보고서 PPT — 프로젝트 구조·패키지 구조·비즈니스 로직 중심.
   */
  public byte[] generateProjectReportPresentation(AnalysisHistory h) throws IOException {
    log.info("📊 프로젝트 분석 보고서 PPT 생성 - historyId={}", h.getId());

    XMLSlideShow ppt = new XMLSlideShow();
    ppt.setPageSize(new Dimension(W, H));

    createProjectTitleSlide(ppt, h);
    createProjectScopeSlide(ppt, h);
    createArchitectureSlide(ppt, h);
    createDomainAnalysisSlide(ppt, h);
    createLayerResponsibilitySlide(ppt, h);
    createProjectStructureSlide(ppt, h);
    createResourceStructureSlide(ppt, h);    // resources/ 설정·XML mapper 구조
    createReadmeSlides(ppt, h.getReadmeContent());
    createProjectClosingSlide(ppt, h);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ppt.write(baos);
    ppt.close();

    log.info("✅ 프로젝트 보고서 PPTX 생성 완료: {}KB", baos.size() / 1024);
    return baos.toByteArray();
  }

  // ── 고객 납품용: 표지 ────────────────────────────────────────
  private void createProjectTitleSlide(XMLSlideShow ppt, AnalysisHistory h) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addRect(slide, 0, 0, 8, H, ACCENT);
    addRect(slide, 48, 105, W - 88, 2, ACCENT);

    String projectName = extractFolderName(h.getSourcePath());
    addText(slide, projectName, 48, 120, W - 96, 80, 32, true, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
    addText(slide, "레거시 코드 분석 보고서", 48, 210, W - 96, 44, 20, false, ACCENT, TextParagraph.TextAlign.LEFT);
    addText(slide, "Legacy Code Analysis Report", 48, 252, W - 96, 30, 13, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
    addRect(slide, 48, 294, 200, 3, ACCENT2);

    String[][] info = {
        {"분석 일시", h.getCreatedAt() != null ? h.getCreatedAt().format(DT_FMT) : "-"},
        {"분석 대상", projectName},
        {"분석 상태", "COMPLETED".equals(h.getStatus()) ? "완료" : (h.getStatus() != null ? h.getStatus() : "-")},
    };
    int x = 48;
    for (String[] item : info) {
      addRoundCard(slide, x, 314, 268, 74, BG_CARD);
      addText(slide, item[0], x + 14, 322, 240, 24, 11, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addText(slide, item[1], x + 14, 344, 240, 30, 13, true, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
      x += 288;
    }

    addText(slide, "본 보고서는 AI 기반 자동 분석 도구로 생성된 레거시 코드 분석 산출물입니다. 담당 개발자 검토 후 활용하시기 바랍니다.",
        48, H - 44, W - 96, 30, 10, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
  }

  // ── 고객 납품용: 분석 범위 ────────────────────────────────────
  private void createProjectScopeSlide(XMLSlideShow ppt, AnalysisHistory h) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addSlideHeader(slide, "분석 범위", "Analysis Scope");

    int total   = h.getTotalFiles()   != null ? h.getTotalFiles()   : 0;
    int success = h.getSuccessCount() != null ? h.getSuccessCount() : 0;
    int skip    = h.getSkipCount()    != null ? h.getSkipCount()    : 0;
    int fail    = h.getFailureCount() != null ? h.getFailureCount() : 0;
    double rate = total > 0 ? (success * 100.0 / total) : 0.0;

    String[][] topCards = {
        {"분석 파일 수", total + "개", "전체 소스 파일"},
        {"주석 생성 완료", String.format("%.0f%%", rate), success + "개 파일 처리"},
    };
    int mx = 40;
    for (String[] c : topCards) {
      addRoundCard(slide, mx, 130, 420, 110, BG_CARD);
      addRect(slide, mx, 130, 420, 4, ACCENT);
      addText(slide, c[0], mx + 20, 144, 380, 26, 12, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addText(slide, c[1], mx + 20, 172, 380, 46, 30, true, ACCENT, TextParagraph.TextAlign.LEFT);
      addText(slide, c[2], mx + 20, 220, 380, 20, 11, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      mx += 460;
    }

    String[][] subCards = {
        {"주석 추가 완료", success + "개"},
        {"기존 처리 스킵", skip + "개"},
        {"처리 실패", fail + "개"},
        {"성공률", String.format("%.1f%%", rate)},
    };
    int sx = 40;
    for (String[] c : subCards) {
      addRoundCard(slide, sx, 268, 205, 72, BG_CARD);
      addText(slide, c[0], sx + 12, 278, 185, 24, 10, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addText(slide, c[1], sx + 12, 302, 185, 28, 17, true, ACCENT2, TextParagraph.TextAlign.LEFT);
      sx += 225;
    }

    addRect(slide, 40, 360, W - 80, 1, BG_CARD);
    addText(slide, "분석 경로: " + h.getSourcePath(), 40, 368, W - 80, 22, 10, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
    addText(slide, "출력 경로: " + h.getOutputPath(), 40, 388, W - 80, 22, 10, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
  }

  // ── 고객 납품용: 프로젝트/패키지 구조 ───────────────────────
  private void createProjectStructureSlide(XMLSlideShow ppt, AnalysisHistory h) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addSlideHeader(slide, "프로젝트 구조", "Project & Package Structure");

    String sourcePath = h.getSourcePath();
    if (sourcePath == null || sourcePath.isBlank()) {
      addText(slide, "(소스 경로 정보 없음)", 40, 200, W - 80, 40, 14, false, TEXT_GRAY, TextParagraph.TextAlign.CENTER);
      return;
    }

    Path root = Paths.get(sourcePath);
    String projectType = detectProjectType(root);

    switch (projectType) {
      case "java"                   -> renderJavaPackageStructure(slide, root, root.resolve("src").resolve("main").resolve("java"));
      case "react", "nextjs", "vue" -> renderFrontendStructure(slide, root, projectType);
      case "python"                 -> renderPythonStructure(slide, root);
      default -> {
        String tree = buildGeneralTree(root);
        addRoundCard(slide, 40, 125, W - 80, H - 155, BG_CARD);
        addRect(slide, 40, 125, 4, H - 155, ACCENT2);
        addText(slide, tree, 60, 136, W - 108, H - 178, 10, false, new Color(186, 230, 253), TextParagraph.TextAlign.LEFT);
      }
    }
  }

  /** 프로젝트 타입 감지 (java / react / nextjs / vue / python / general) */
  private String detectProjectType(Path root) {
    if (Files.exists(root.resolve("src").resolve("main").resolve("java"))) return "java";

    if (Files.exists(root.resolve("requirements.txt")) ||
        Files.exists(root.resolve("pyproject.toml"))   ||
        Files.exists(root.resolve("setup.py")))          return "python";

    if (Files.exists(root.resolve("package.json"))) {
      if (Files.exists(root.resolve("next.config.js"))  ||
          Files.exists(root.resolve("next.config.ts"))  ||
          Files.exists(root.resolve("next.config.mjs"))) return "nextjs";

      if (Files.exists(root.resolve("src").resolve("views")) ||
          Files.exists(root.resolve("vue.config.js"))) {
        try (Stream<Path> files = Files.walk(root.resolve("src"), 3)) {
          if (files.anyMatch(p -> p.toString().endsWith(".vue"))) return "vue";
        } catch (IOException ignored) {}
      }
      return "react";
    }
    return "general";
  }

  /** Java 패키지 구조를 비즈니스 설명 카드 레이아웃으로 렌더링 */
  private void renderJavaPackageStructure(XSLFSlide slide, Path projectRoot, Path javaRoot) {
    String rootPkg = detectRootPackage(javaRoot);
    String projectName = projectRoot.getFileName() != null ? projectRoot.getFileName().toString() : "project";

    // 루트 패키지 헤더 표시
    String header = "📦 " + projectName + (rootPkg.isEmpty() ? "" : "  ·  " + rootPkg);
    addText(slide, header, 40, 110, W - 80, 18, 10, false, ACCENT2, TextParagraph.TextAlign.LEFT);

    List<String[]> packages = buildPackageList(javaRoot);

    boolean twoCol = packages.size() > 7;
    int startY = 132;
    int cardH = 44;
    int gap = 4;
    int half = twoCol ? (packages.size() + 1) / 2 : packages.size();
    int colW = twoCol ? (W - 100) / 2 : W - 80;

    for (int i = 0; i < packages.size(); i++) {
      int col = twoCol ? i / half : 0;
      int row = twoCol ? i % half : i;
      int x = 40 + col * (colW + 20);
      int y = startY + row * (cardH + gap);
      if (y + cardH > H - 22) break;

      String[] pkg = packages.get(i); // [shortName, description, classSummary]
      addRoundCard(slide, x, y, colW, cardH, BG_CARD);
      addRect(slide, x, y, 3, cardH, ACCENT);
      // 패키지명
      addText(slide, "📁 " + pkg[0], x + 12, y + 4, 220, 17, 10, true, ACCENT, TextParagraph.TextAlign.LEFT);
      // 클래스 타입 요약 (우측)
      if (!pkg[2].isEmpty()) {
        addText(slide, pkg[2], x + colW - 192, y + 4, 184, 17, 8, false, TEXT_GRAY, TextParagraph.TextAlign.RIGHT);
      }
      // 비즈니스 설명
      addText(slide, pkg[1], x + 12, y + 23, colW - 24, 17, 9, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
    }

    // 하단 resources 한 줄 표시
    Path resRoot = projectRoot.resolve("src").resolve("main").resolve("resources");
    if (Files.exists(resRoot)) {
      int rows = twoCol ? half : packages.size();
      int bottomY = startY + rows * (cardH + gap) + 2;
      if (bottomY + 20 < H - 10) {
        addText(slide, "📋 src/main/resources/  [설정 파일 · application.properties · 템플릿 등]",
            40, bottomY, W - 80, 18, 9, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      }
    }
  }

  /** src/main/java 하위 루트 패키지 경로 감지 (단일 자식 추적) */
  private String detectRootPackage(Path javaRoot) {
    if (!Files.exists(javaRoot)) return "";
    StringBuilder pkg = new StringBuilder();
    Path current = javaRoot;
    try {
      for (int depth = 0; depth < 6; depth++) {
        List<Path> dirs;
        try (Stream<Path> children = Files.list(current)) {
          dirs = children.filter(Files::isDirectory).collect(Collectors.toList());
        }
        if (dirs.size() != 1) break;
        current = dirs.get(0);
        if (pkg.length() > 0) pkg.append(".");
        pkg.append(current.getFileName().toString());
      }
    } catch (IOException e) {
      log.warn("루트 패키지 감지 실패", e);
    }
    return pkg.toString();
  }

  /** 기본 패키지 하위 서브 패키지 목록 수집 → [shortName, description, classSummary] */
  private List<String[]> buildPackageList(Path javaRoot) {
    List<String[]> result = new ArrayList<>();
    if (!Files.exists(javaRoot)) return result;
    try {
      // 기본 패키지까지 이동
      Path basePkg = javaRoot;
      for (int depth = 0; depth < 6; depth++) {
        List<Path> dirs;
        try (Stream<Path> children = Files.list(basePkg)) {
          dirs = children.filter(Files::isDirectory).collect(Collectors.toList());
        }
        if (dirs.size() != 1) break;
        basePkg = dirs.get(0);
      }

      // 서브 패키지 목록
      List<Path> subPkgs;
      try (Stream<Path> children = Files.list(basePkg)) {
        subPkgs = children.filter(Files::isDirectory).sorted().collect(Collectors.toList());
      }

      for (Path subPkg : subPkgs) {
        String pkgName = subPkg.getFileName().toString();
        List<String> fileNames = new ArrayList<>();
        try (Stream<Path> files = Files.walk(subPkg, 5)) {
          files.filter(p -> p.toString().endsWith(".java"))
               .map(p -> p.getFileName().toString())
               .forEach(fileNames::add);
        }
        result.add(new String[]{
            pkgName,
            inferPackageDescription(pkgName, fileNames),
            summarizeClassTypes(fileNames)
        });
      }

      // 서브 패키지 없을 경우 기본 패키지 자체를 보여줌
      if (result.isEmpty()) {
        List<String> fileNames = new ArrayList<>();
        try (Stream<Path> files = Files.list(basePkg)) {
          files.filter(p -> p.toString().endsWith(".java"))
               .map(p -> p.getFileName().toString())
               .forEach(fileNames::add);
        }
        if (!fileNames.isEmpty()) {
          String name = basePkg.getFileName().toString();
          result.add(new String[]{name, inferPackageDescription(name, fileNames), summarizeClassTypes(fileNames)});
        }
      }
    } catch (IOException e) {
      log.warn("패키지 목록 수집 실패: {}", javaRoot, e);
    }
    return result;
  }

  /** 패키지 이름과 포함 파일 기반으로 비즈니스 설명 생성 */
  private String inferPackageDescription(String pkgName, List<String> fileNames) {
    return switch (pkgName.toLowerCase()) {
      case "admin"                              -> "관리자 기능 (사용자·권한·시스템 관리)";
      case "analysis", "analyzer"               -> "코드 분석 핵심 로직 (분석 실행·이력 관리)";
      case "api"                                -> "API 사용량 추적 및 모니터링";
      case "audit"                              -> "감사 로그 기록 및 이력 추적";
      case "auth", "authentication"             -> "인증 및 인가 처리 (JWT · Spring Security)";
      case "security"                           -> "보안 설정 및 접근 제어";
      case "core", "common"                     -> "공통 유틸리티 및 에러 핸들링";
      case "util", "utils"                      -> "공통 유틸리티 함수 모음";
      case "notification"                       -> "알림 서비스 (이벤트 발송·관리)";
      case "statistics", "stats"                -> "통계 집계 및 리포트 생성";
      case "user", "member", "account"          -> "사용자 정보 관리";
      case "order"                              -> "주문 생성·처리·조회";
      case "payment", "billing"                 -> "결제 및 정산 처리";
      case "product", "item", "goods"           -> "상품 정보 관리";
      case "config", "configuration"            -> "애플리케이션 설정 관리";
      case "entity", "domain", "model"          -> "도메인 엔티티 및 비즈니스 모델";
      case "dto"                                -> "데이터 전송 객체 (DTO) 모음";
      case "repository", "dao"                  -> "데이터 접근 계층 (JPA Repository)";
      case "service"                            -> "비즈니스 서비스 계층";
      case "controller"                         -> "REST API 컨트롤러 계층";
      case "exception", "error"                 -> "예외 및 에러 처리";
      case "mapper"                             -> "객체 변환 매퍼 (DTO ↔ Entity)";
      case "scheduler", "batch"                 -> "배치 작업 및 스케줄링";
      case "event"                              -> "도메인 이벤트 처리 및 발행";
      case "cache"                              -> "캐싱 처리";
      case "file", "storage"                    -> "파일 저장 및 처리";
      case "mail", "email"                      -> "이메일 발송 서비스";
      case "report"                             -> "보고서 생성 및 출력";
      case "board", "post"                      -> "게시판 기능";
      case "comment"                            -> "댓글 기능";
      case "category"                           -> "카테고리 분류 관리";
      case "role", "permission"                 -> "역할 및 권한 관리";
      case "log"                                -> "로그 기록 관리";
      case "push"                               -> "푸시 알림 발송";
      case "code"                               -> "공통 코드 관리";
      default                                   -> inferFromFileNames(fileNames);
    };
  }

  /** 파일명으로부터 패키지 역할 추론 (패키지명 매핑 불가시 사용) */
  private String inferFromFileNames(List<String> fileNames) {
    long controllers = fileNames.stream().filter(f -> f.endsWith("Controller.java")).count();
    long services    = fileNames.stream().filter(f -> f.endsWith("Service.java") || f.endsWith("ServiceImpl.java")).count();
    long repos       = fileNames.stream().filter(f -> f.endsWith("Repository.java")).count();
    long entities    = fileNames.stream().filter(f -> f.endsWith("Entity.java")).count();
    long dtos        = fileNames.stream().filter(f -> f.endsWith("Dto.java") || f.endsWith("DTO.java")).count();

    if (controllers > 0 && services > 0 && repos > 0) return "REST API · 비즈니스 로직 · 데이터 접근 전담";
    if (controllers > 0 && services > 0) return "REST API 및 비즈니스 로직 처리";
    if (controllers > 0) return "REST API 엔드포인트 처리";
    if (services > 0 && repos > 0)       return "비즈니스 로직 및 데이터 접근 처리";
    if (services > 0)                    return "비즈니스 로직 처리";
    if (repos > 0)                       return "데이터 접근 처리 (JPA)";
    if (entities > 0)                    return "도메인 엔티티 정의";
    if (dtos > 0)                        return "데이터 전송 객체 정의";
    return "기능 모듈 (" + fileNames.size() + "개 클래스)";
  }

  /** 패키지에 포함된 클래스 유형 요약 문자열 */
  private String summarizeClassTypes(List<String> fileNames) {
    List<String> types = new ArrayList<>();
    if (fileNames.stream().anyMatch(f -> f.endsWith("Controller.java")))                        types.add("Controller");
    if (fileNames.stream().anyMatch(f -> f.endsWith("Service.java") || f.endsWith("ServiceImpl.java"))) types.add("Service");
    if (fileNames.stream().anyMatch(f -> f.endsWith("Repository.java")))                        types.add("Repository");
    if (fileNames.stream().anyMatch(f -> f.endsWith("Entity.java")))                            types.add("Entity");
    if (fileNames.stream().anyMatch(f -> f.endsWith("Dto.java") || f.endsWith("DTO.java")))     types.add("DTO");
    if (fileNames.stream().anyMatch(f -> f.endsWith("Config.java")))                            types.add("Config");
    if (fileNames.stream().anyMatch(f -> f.endsWith("Filter.java")))                            types.add("Filter");
    if (fileNames.stream().anyMatch(f -> f.endsWith("Mapper.java")))                            types.add("Mapper");
    return types.isEmpty() ? fileNames.size() + "개 클래스" : String.join(" · ", types);
  }

  // ── React / Vue / Next.js 프론트엔드 구조 ──────────────────
  private void renderFrontendStructure(XSLFSlide slide, Path root, String projectType) {
    String typeBadge = switch (projectType) {
      case "nextjs" -> "Next.js";
      case "vue"    -> "Vue 3";
      default       -> "React";
    };
    Color accentColor = switch (projectType) {
      case "vue"    -> new Color(65, 184, 131);   // Vue green
      case "nextjs" -> new Color(200, 200, 200);  // Next.js light gray
      default       -> new Color(97, 218, 251);   // React cyan
    };

    String projectName = root.getFileName() != null ? root.getFileName().toString() : "project";
    addText(slide, "⚛ " + projectName + "  ·  " + typeBadge, 40, 110, W - 80, 18, 10, false, accentColor, TextParagraph.TextAlign.LEFT);

    List<String[]> dirs = buildFrontendDirList(root, projectType);

    boolean twoCol = dirs.size() > 7;
    int startY = 132;
    int cardH = 44;
    int gap = 4;
    int half = twoCol ? (dirs.size() + 1) / 2 : dirs.size();
    int colW = twoCol ? (W - 100) / 2 : W - 80;

    for (int i = 0; i < dirs.size(); i++) {
      int col = twoCol ? i / half : 0;
      int row = twoCol ? i % half : i;
      int x = 40 + col * (colW + 20);
      int y = startY + row * (cardH + gap);
      if (y + cardH > H - 22) break;

      String[] dir = dirs.get(i);
      addRoundCard(slide, x, y, colW, cardH, BG_CARD);
      addRect(slide, x, y, 3, cardH, accentColor);
      addText(slide, "📁 " + dir[0], x + 12, y + 4, 220, 17, 10, true, accentColor, TextParagraph.TextAlign.LEFT);
      if (!dir[2].isEmpty()) {
        addText(slide, dir[2], x + colW - 192, y + 4, 184, 17, 8, false, TEXT_GRAY, TextParagraph.TextAlign.RIGHT);
      }
      addText(slide, dir[1], x + 12, y + 23, colW - 24, 17, 9, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
    }

    // 하단 package.json 한 줄
    if (Files.exists(root.resolve("package.json"))) {
      int rows = twoCol ? half : dirs.size();
      int bottomY = startY + rows * (cardH + gap) + 2;
      if (bottomY + 20 < H - 10) {
        addText(slide, "📋 package.json  [의존성 및 빌드 스크립트 설정]",
            40, bottomY, W - 80, 18, 9, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      }
    }
  }

  private List<String[]> buildFrontendDirList(Path root, String projectType) {
    List<String[]> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Set<String> skipDirs = Set.of("node_modules", "dist", ".next", ".nuxt", "build", "out",
        ".git", ".idea", "coverage", ".turbo", ".vercel", "storybook-static");

    List<Path> scanRoots = new ArrayList<>();
    Path srcDir = root.resolve("src");
    if (Files.exists(srcDir)) scanRoots.add(srcDir);

    // Next.js App Router / Pages Router 추가
    if ("nextjs".equals(projectType)) {
      Path appDir   = root.resolve("app");
      Path pagesDir = root.resolve("pages");
      if (Files.exists(appDir))   scanRoots.add(0, appDir);
      if (Files.exists(pagesDir)) scanRoots.add(0, pagesDir);
    }
    if (scanRoots.isEmpty()) scanRoots.add(root);

    for (Path scanRoot : scanRoots) {
      try (Stream<Path> children = Files.list(scanRoot)) {
        List<Path> dirs = children
            .filter(Files::isDirectory)
            .filter(p -> !skipDirs.contains(p.getFileName().toString()))
            .filter(p -> !p.getFileName().toString().startsWith("."))
            .sorted()
            .collect(Collectors.toList());

        for (Path dir : dirs) {
          String dirName = dir.getFileName().toString();
          if (seen.contains(dirName)) continue;
          seen.add(dirName);

          List<String> fileNames = new ArrayList<>();
          try (Stream<Path> files = Files.walk(dir, 4)) {
            files.filter(Files::isRegularFile)
                 .map(p -> p.getFileName().toString())
                 .forEach(fileNames::add);
          } catch (IOException ignored) {}

          result.add(new String[]{
              dirName,
              inferFrontendDirDescription(dirName, fileNames, projectType),
              summarizeFrontendFileTypes(fileNames)
          });
        }
      } catch (IOException e) {
        log.warn("프론트엔드 디렉토리 목록 수집 실패: {}", scanRoot, e);
      }
    }
    return result;
  }

  private String inferFrontendDirDescription(String dirName, List<String> fileNames, String projectType) {
    return switch (dirName.toLowerCase()) {
      case "components"                -> "재사용 가능한 UI 컴포넌트 모음";
      case "pages"                     -> "nextjs".equals(projectType) ? "페이지 라우팅 (Next.js Pages Router)" : "페이지 컴포넌트 모음";
      case "app"                       -> "페이지 라우팅 및 레이아웃 (Next.js App Router)";
      case "views"                     -> "페이지별 뷰 컴포넌트 (라우팅 단위)";
      case "layouts"                   -> "공통 레이아웃 컴포넌트 (Header · Footer · Sidebar)";
      case "hooks"                     -> "커스텀 React Hooks (비즈니스 로직 재사용)";
      case "composables"               -> "Vue 3 Composables (재사용 가능한 로직)";
      case "store", "stores"           -> "전역 상태 관리 (Zustand · Pinia · Redux)";
      case "router"                    -> "클라이언트 사이드 라우팅 설정";
      case "api"                       -> "API 호출 함수 모음 (서버 통신 레이어)";
      case "services"                  -> "비즈니스 로직 및 외부 API 연동";
      case "utils", "lib", "helpers"   -> "공통 유틸리티 함수 모음";
      case "types"                     -> "TypeScript 타입 및 인터페이스 정의";
      case "context"                   -> "React Context API (전역 상태 공유)";
      case "assets"                    -> "정적 파일 (이미지 · 폰트 · 아이콘)";
      case "styles", "css", "scss"     -> "전역 스타일 및 CSS 모듈";
      case "constants"                 -> "상수 값 및 공통 설정 정의";
      case "config"                    -> "환경 설정 및 초기화";
      case "middleware"                -> "미들웨어 처리 (인증 · 로깅 등)";
      case "public"                    -> "정적 파일 서빙 (빌드 포함 assets)";
      case "test", "tests", "__tests__"-> "단위 · 통합 테스트 코드";
      case "i18n", "locales"           -> "다국어(i18n) 번역 파일";
      case "features"                  -> "기능별 모듈 분리 (Feature-based 구조)";
      case "modules"                   -> "도메인별 모듈 구성";
      case "providers"                 -> "Context Provider 및 의존성 주입";
      case "hoc"                       -> "고차 컴포넌트 (Higher-Order Components)";
      default                          -> inferFromFrontendFiles(fileNames);
    };
  }

  private String inferFromFrontendFiles(List<String> fileNames) {
    long tsx = fileNames.stream().filter(f -> f.endsWith(".tsx")).count();
    long jsx = fileNames.stream().filter(f -> f.endsWith(".jsx")).count();
    long vue = fileNames.stream().filter(f -> f.endsWith(".vue")).count();
    long ts  = fileNames.stream().filter(f -> f.endsWith(".ts")).count();
    long js  = fileNames.stream().filter(f -> f.endsWith(".js")).count();
    if (tsx > 0) return "React 컴포넌트 (" + tsx + "개 .tsx)";
    if (jsx > 0) return "React 컴포넌트 (" + jsx + "개 .jsx)";
    if (vue > 0) return "Vue 컴포넌트 ("   + vue + "개 .vue)";
    if (ts  > 0) return "TypeScript 모듈 (" + ts  + "개 .ts)";
    if (js  > 0) return "JavaScript 모듈 (" + js  + "개 .js)";
    return "기능 모듈 (" + fileNames.size() + "개 파일)";
  }

  private String summarizeFrontendFileTypes(List<String> fileNames) {
    List<String> types = new ArrayList<>();
    if (fileNames.stream().anyMatch(f -> f.endsWith(".tsx"))) types.add(".tsx");
    else if (fileNames.stream().anyMatch(f -> f.endsWith(".jsx"))) types.add(".jsx");
    if (fileNames.stream().anyMatch(f -> f.endsWith(".vue"))) types.add(".vue");
    if (fileNames.stream().anyMatch(f -> f.endsWith(".ts"))) types.add(".ts");
    else if (fileNames.stream().anyMatch(f -> f.endsWith(".js"))) types.add(".js");
    if (fileNames.stream().anyMatch(f -> f.endsWith(".css") || f.endsWith(".scss") || f.endsWith(".sass"))) types.add(".css");
    return types.isEmpty() ? fileNames.size() + "개 파일" : String.join(" · ", types);
  }

  // ── Python 프로젝트 구조 ────────────────────────────────────
  private void renderPythonStructure(XSLFSlide slide, Path root) {
    Color pyColor = new Color(255, 212, 59);
    String projectName = root.getFileName() != null ? root.getFileName().toString() : "project";
    String framework = detectPythonFramework(root);
    addText(slide, "🐍 " + projectName + "  ·  " + framework, 40, 110, W - 80, 18, 10, false, pyColor, TextParagraph.TextAlign.LEFT);

    List<String[]> modules = buildPythonModuleList(root);

    boolean twoCol = modules.size() > 7;
    int startY = 132;
    int cardH = 44;
    int gap = 4;
    int half = twoCol ? (modules.size() + 1) / 2 : modules.size();
    int colW = twoCol ? (W - 100) / 2 : W - 80;

    for (int i = 0; i < modules.size(); i++) {
      int col = twoCol ? i / half : 0;
      int row = twoCol ? i % half : i;
      int x = 40 + col * (colW + 20);
      int y = startY + row * (cardH + gap);
      if (y + cardH > H - 22) break;

      String[] mod = modules.get(i);
      addRoundCard(slide, x, y, colW, cardH, BG_CARD);
      addRect(slide, x, y, 3, cardH, pyColor);
      addText(slide, "📁 " + mod[0], x + 12, y + 4, 220, 17, 10, true, pyColor, TextParagraph.TextAlign.LEFT);
      if (!mod[2].isEmpty()) {
        addText(slide, mod[2], x + colW - 192, y + 4, 184, 17, 8, false, TEXT_GRAY, TextParagraph.TextAlign.RIGHT);
      }
      addText(slide, mod[1], x + 12, y + 23, colW - 24, 17, 9, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
    }
  }

  private String detectPythonFramework(Path root) {
    try {
      Path reqFile = root.resolve("requirements.txt");
      if (Files.exists(reqFile)) {
        String content = Files.readString(reqFile).toLowerCase();
        if (content.contains("django"))  return "Python / Django";
        if (content.contains("fastapi")) return "Python / FastAPI";
        if (content.contains("flask"))   return "Python / Flask";
      }
    } catch (IOException ignored) {}
    return "Python";
  }

  private List<String[]> buildPythonModuleList(Path root) {
    List<String[]> result = new ArrayList<>();
    Set<String> skipDirs = Set.of(".git", ".venv", "venv", "__pycache__", ".pytest_cache",
        "dist", "build", ".tox", "node_modules", ".idea", ".mypy_cache");
    try (Stream<Path> children = Files.list(root)) {
      children
          .filter(Files::isDirectory)
          .filter(p -> !skipDirs.contains(p.getFileName().toString()))
          .filter(p -> !p.getFileName().toString().startsWith("."))
          .sorted()
          .forEach(dir -> {
            String dirName = dir.getFileName().toString();
            List<String> fileNames = new ArrayList<>();
            try (Stream<Path> files = Files.walk(dir, 4)) {
              files.filter(p -> p.toString().endsWith(".py"))
                   .map(p -> p.getFileName().toString())
                   .forEach(fileNames::add);
            } catch (IOException ignored) {}
            if (fileNames.isEmpty()) return;
            result.add(new String[]{dirName, inferPythonDirDescription(dirName), fileNames.size() + "개 .py"});
          });
    } catch (IOException e) {
      log.warn("Python 모듈 목록 수집 실패: {}", root, e);
    }
    return result;
  }

  private String inferPythonDirDescription(String dirName) {
    return switch (dirName.toLowerCase()) {
      case "models"      -> "데이터 모델 및 ORM 정의";
      case "views"       -> "뷰 함수 및 핸들러 (요청/응답 처리)";
      case "serializers" -> "직렬화/역직렬화 처리 (DRF Serializer)";
      case "urls"        -> "URL 라우팅 설정";
      case "routers"     -> "API 라우터 설정 (FastAPI)";
      case "schemas"     -> "Pydantic 스키마 정의";
      case "services"    -> "비즈니스 로직 서비스 레이어";
      case "utils"       -> "공통 유틸리티 함수 모음";
      case "tests"       -> "테스트 코드";
      case "migrations"  -> "DB 마이그레이션 파일";
      case "admin"       -> "Django 관리자 설정";
      case "config"      -> "설정 및 환경 변수 관리";
      case "api"         -> "API 엔드포인트 정의";
      case "middleware"  -> "미들웨어 처리";
      case "tasks"       -> "비동기 작업 (Celery 등)";
      default            -> "Python 모듈";
    };
  }

  /** 일반(비-Java) 프로젝트 파일 트리 */
  private String buildGeneralTree(Path root) {
    List<String> lines = new ArrayList<>();
    lines.add("📦 " + (root.getFileName() != null ? root.getFileName().toString() : "project") + "/");

    try (Stream<Path> stream = Files.walk(root, 4)) {
      stream
          .filter(p -> !p.equals(root))
          .filter(p -> {
            Path rel = root.relativize(p);
            for (int i = 0; i < rel.getNameCount(); i++) {
              String seg = rel.getName(i).toString();
              if (seg.startsWith(".")) return false;
              if (seg.equals("build") || seg.equals("target") || seg.equals("out")
                  || seg.equals("bin") || seg.equals("node_modules") || seg.equals("__pycache__")) return false;
            }
            return true;
          })
          .sorted()
          .limit(50)
          .forEach(p -> {
            int depth = root.relativize(p).getNameCount();
            String indent = "  ".repeat(depth);
            String name = p.getFileName().toString();
            lines.add(indent + (Files.isDirectory(p) ? "📁 " + name + "/" : "📄 " + name));
          });
    } catch (IOException e) {
      log.warn("디렉토리 트리 읽기 실패: {}", root, e);
      return "(디렉토리 구조를 읽을 수 없습니다)";
    }

    return String.join("\n", lines);
  }

  // ── 고객 납품용: 리소스 & XML 구조 ──────────────────────────
  private void createResourceStructureSlide(XMLSlideShow ppt, AnalysisHistory h) {
    String sourcePath = h.getSourcePath();
    if (sourcePath == null || sourcePath.isBlank()) return;

    Path root = Paths.get(sourcePath);
    Path resRoot = root.resolve("src").resolve("main").resolve("resources");
    if (!Files.exists(resRoot)) return;

    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addSlideHeader(slide, "리소스 구조", "Resources & XML Configuration");

    List<String[]> configFiles  = new ArrayList<>(); // [name, description]
    List<String[]> mapperFiles  = new ArrayList<>(); // [name, namespace, querySummary]
    List<String[]> templateFiles = new ArrayList<>(); // [name, relPath]

    try (Stream<Path> files = Files.walk(resRoot, 5)) {
      files.filter(Files::isRegularFile).forEach(p -> {
        String name = p.getFileName().toString();
        String rel  = resRoot.relativize(p).toString().replace('\\', '/');
        String content = "";
        try { content = Files.readString(p); } catch (IOException ignored) {}

        if (name.endsWith(".xml")) {
          if (content.contains("<mapper") || rel.contains("mapper") || rel.contains("mybatis") || rel.contains("sqlmap")) {
            mapperFiles.add(new String[]{name, extractMapperNamespace(content), countMapperQueries(content)});
          } else {
            configFiles.add(new String[]{name, inferXmlDescription(name, rel)});
          }
        } else if (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml")) {
          configFiles.add(new String[]{name, inferConfigDescription(name)});
        } else if (name.endsWith(".html") || name.endsWith(".ftl") || name.endsWith(".vm")) {
          templateFiles.add(new String[]{name, rel});
        }
      });
    } catch (IOException e) {
      log.warn("리소스 파일 스캔 실패: {}", resRoot, e);
    }

    int y = 120;
    Color mapperColor = new Color(251, 191, 36);

    // 설정 파일 섹션
    if (!configFiles.isEmpty()) {
      addText(slide, "⚙️ 설정 파일 (Config)", 40, y, W - 80, 18, 11, true, ACCENT, TextParagraph.TextAlign.LEFT);
      y += 22;
      for (String[] cfg : configFiles) {
        if (y + 30 > H - 40) break;
        addRoundCard(slide, 40, y, W - 80, 28, BG_CARD);
        addRect(slide, 40, y, 3, 28, ACCENT);
        addText(slide, "📄 " + cfg[0], 52, y + 5, 320, 18, 10, true, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
        addText(slide, cfg[1], 380, y + 5, W - 420, 18, 9, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
        y += 32;
      }
      y += 10;
    }

    // Mapper XML 섹션
    if (!mapperFiles.isEmpty()) {
      addText(slide, "🗃️ MyBatis Mapper XML", 40, y, W - 80, 18, 11, true, mapperColor, TextParagraph.TextAlign.LEFT);
      y += 22;
      for (String[] mapper : mapperFiles) {
        if (y + 48 > H - 20) break;
        addRoundCard(slide, 40, y, W - 80, 44, BG_CARD);
        addRect(slide, 40, y, 3, 44, mapperColor);
        addText(slide, "📄 " + mapper[0], 52, y + 4, 350, 17, 10, true, mapperColor, TextParagraph.TextAlign.LEFT);
        if (!mapper[2].isEmpty()) {
          addText(slide, mapper[2], W - 270, y + 4, 230, 17, 9, false, TEXT_GRAY, TextParagraph.TextAlign.RIGHT);
        }
        String ns = mapper[1].isEmpty() ? "(namespace 없음)" : "namespace: " + mapper[1];
        addText(slide, ns, 52, y + 23, W - 92, 17, 9, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
        y += 48;
      }
      y += 10;
    }

    // 템플릿 파일 섹션
    if (!templateFiles.isEmpty() && y + 40 < H - 20) {
      addText(slide, "📋 템플릿 파일 (Templates)",  40, y, W - 80, 18, 11, true, ACCENT2, TextParagraph.TextAlign.LEFT);
      y += 22;
      StringBuilder tmplNames = new StringBuilder();
      int shown = 0;
      for (String[] t : templateFiles) {
        if (shown++ > 6) { tmplNames.append("  외 ").append(templateFiles.size() - 6).append("개 ..."); break; }
        if (tmplNames.length() > 0) tmplNames.append("  ·  ");
        tmplNames.append(t[0]);
      }
      addRoundCard(slide, 40, y, W - 80, 28, BG_CARD);
      addRect(slide, 40, y, 3, 28, ACCENT2);
      addText(slide, tmplNames.toString(), 52, y + 5, W - 92, 18, 9, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
    }

    // 아무 내용도 없을 때
    if (configFiles.isEmpty() && mapperFiles.isEmpty() && templateFiles.isEmpty()) {
      addText(slide, "src/main/resources/ 에 분석 대상 파일이 없습니다.",
          40, 250, W - 80, 40, 13, false, TEXT_GRAY, TextParagraph.TextAlign.CENTER);
    }
  }

  private String extractMapperNamespace(String content) {
    int idx = content.indexOf("namespace=\"");
    if (idx < 0) idx = content.indexOf("namespace='");
    if (idx < 0) return "";
    int start = idx + 11;
    char closing = content.charAt(idx + 10) == '"' ? '"' : '\'';
    int end = content.indexOf(closing, start);
    return end > start ? content.substring(start, end) : "";
  }

  private String countMapperQueries(String content) {
    int selects = countOccurrences(content, "<select ");
    int inserts = countOccurrences(content, "<insert ");
    int updates = countOccurrences(content, "<update ");
    int deletes = countOccurrences(content, "<delete ");
    List<String> parts = new ArrayList<>();
    if (selects > 0) parts.add("SELECT " + selects);
    if (inserts > 0) parts.add("INSERT " + inserts);
    if (updates > 0) parts.add("UPDATE " + updates);
    if (deletes > 0) parts.add("DELETE " + deletes);
    return String.join(" · ", parts);
  }

  private int countOccurrences(String text, String pattern) {
    int count = 0, idx = 0;
    while ((idx = text.indexOf(pattern, idx)) >= 0) { count++; idx += pattern.length(); }
    return count;
  }

  private String inferXmlDescription(String fileName, String rel) {
    String lower = fileName.toLowerCase();
    if (lower.contains("logback") || lower.contains("log4j")) return "로깅 설정 (Logback/Log4j)";
    if (lower.contains("security"))  return "Spring Security 설정";
    if (lower.contains("context"))   return "Spring 컨텍스트 설정";
    if (lower.contains("mvc"))       return "Spring MVC 설정";
    if (lower.contains("datasource") || lower.contains("mybatis")) return "데이터소스/MyBatis 설정";
    if (lower.contains("cache"))     return "캐시 설정";
    if (lower.contains("batch"))     return "Spring Batch 설정";
    if (lower.contains("quartz"))    return "스케줄러 설정 (Quartz)";
    if (lower.contains("web"))       return "웹 애플리케이션 설정 (web.xml)";
    if (lower.contains("beans") || lower.contains("application-context")) return "Spring Bean 설정";
    return "XML 설정 파일";
  }

  private String inferConfigDescription(String fileName) {
    String lower = fileName.toLowerCase();
    if (lower.contains("application")) {
      if (lower.contains("local"))  return "로컬 개발 환경 설정";
      if (lower.contains("dev"))    return "개발 서버 환경 설정";
      if (lower.contains("prod"))   return "운영 환경 설정";
      if (lower.contains("test"))   return "테스트 환경 설정";
      if (lower.contains("h2"))     return "H2 인메모리 DB 환경 설정";
      return "애플리케이션 핵심 설정";
    }
    if (lower.contains("log"))      return "로깅 설정";
    if (lower.contains("bootstrap")) return "Bootstrap 설정 (Spring Cloud)";
    return "설정 파일";
  }

  // ── 고객 납품용: 마무리 ──────────────────────────────────────
  private void createProjectClosingSlide(XMLSlideShow ppt, AnalysisHistory h) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addRect(slide, 0, 0, W, H / 3, BG_CARD);
    addRect(slide, 0, H / 3, W, 4, ACCENT);

    String projectName = extractFolderName(h.getSourcePath());
    addText(slide, projectName, 0, 55, W, 64, 30, true, TEXT_WHITE, TextParagraph.TextAlign.CENTER);
    addText(slide, "레거시 코드 분석 보고서", 0, 128, W, 38, 17, false, ACCENT, TextParagraph.TextAlign.CENTER);

    int total   = h.getTotalFiles()   != null ? h.getTotalFiles()   : 0;
    int success = h.getSuccessCount() != null ? h.getSuccessCount() : 0;
    double rate = total > 0 ? (success * 100.0 / total) : 0.0;

    addText(slide, String.format("총 %d개 파일 분석 완료  ·  주석 생성률 %.0f%%", total, rate),
        0, 205, W, 36, 14, false, TEXT_GRAY, TextParagraph.TextAlign.CENTER);

    addRoundCard(slide, 80, 264, W - 160, 90, BG_CARD);
    addRect(slide, 80, 264, W - 160, 3, new Color(251, 191, 36));
    addText(slide,
        "본 보고서에 포함된 분석 내용은 Claude AI가 자동 생성한 결과물입니다.\n" +
        "실제 운영 시스템 반영 전 반드시 담당 개발자의 검토 및 확인이 필요합니다.",
        100, 278, W - 200, 68, 12, false, TEXT_GRAY, TextParagraph.TextAlign.CENTER);

    addText(slide, h.getCreatedAt() != null ? h.getCreatedAt().format(DT_FMT) : "",
        0, H - 44, W, 26, 11, false, new Color(71, 85, 105), TextParagraph.TextAlign.CENTER);
  }

  // ── README.md 파싱 → 프로젝트 분석 내용 슬라이드 생성 ───────
  private void createReadmeSlides(XMLSlideShow ppt, String readmeContent) {
    if (readmeContent == null || readmeContent.isBlank()) {
      XSLFSlide slide = ppt.createSlide();
      fillBackground(slide, BG_DARK);
      addSlideHeader(slide, "프로젝트 분석 결과", "Project Analysis Report");
      addRoundCard(slide, 40, 125, W - 80, H - 155, BG_CARD);
      addText(slide, "분석된 README 내용이 없습니다.\n새로운 분석을 실행하면 AI가 프로젝트 구조와 비즈니스 로직을 분석하여 이 슬라이드를 채웁니다.",
          56, 200, W - 112, 100, 13, false, TEXT_GRAY, TextParagraph.TextAlign.CENTER);
      return;
    }

    // ## 헤더 기준으로만 섹션 분리 (# 단일, ### 이하는 섹션 내부로 처리)
    String[] sections = readmeContent.split("(?m)(?=^## )");
    int slideCount = 0;

    for (String section : sections) {
      if (slideCount >= 10) break;
      String trimmed = section.trim();
      if (trimmed.isEmpty()) continue;

      // # 제목만 있는 섹션(본문 없음)은 건너뜀
      if (!trimmed.startsWith("##")) continue;

      String[] lines = trimmed.split("\n");
      String title = lines[0].replaceAll("^#+\\s*", "").trim();
      if (title.isEmpty()) continue;

      StringBuilder body = new StringBuilder();
      boolean inCode = false;
      for (int i = 1; i < lines.length; i++) {
        String line = lines[i];
        if (line.startsWith("```")) { inCode = !inCode; continue; }
        if (inCode) continue;
        String clean = line
            .replaceAll("^###\\s+", "▸ ")   // ### 서브헤더 → 강조 표시
            .replaceAll("^#+\\s*", "")        // 나머지 # 제거
            .replaceAll("\\*\\*(.+?)\\*\\*", "[$1]") // 볼드 → 대괄호
            .replaceAll("\\*(.+?)\\*",   "$1")
            .replaceAll("`(.+?)`",        "$1")
            .replaceAll("^[-*]\\s+",      "• ")
            .replaceAll("^\\d+\\.\\s+",   "• ")
            .trim();
        if (!clean.isEmpty()) body.append(clean).append("\n");
      }

      String bodyText = body.toString().trim();
      if (bodyText.isEmpty()) continue;
      createReadmeSectionSlide(ppt, title, bodyText, ++slideCount);
    }
  }

  private void createReadmeSectionSlide(XMLSlideShow ppt, String title, String body, int idx) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);

    // 섹션마다 순환 배지 색상
    Color[] palette = {
        ACCENT,                       // 파랑
        ACCENT2,                      // 초록
        new Color(251, 146, 60),      // 주황
        new Color(196, 181, 253),     // 보라
        new Color(251, 191, 36),      // 노랑
        new Color(248, 113, 113),     // 빨강
    };
    Color ac = palette[(idx - 1) % palette.length];

    // 헤더 영역
    addRect(slide, 0, 0, W, 100, BG_CARD);
    addRect(slide, 0, 100, W, 3, ac);
    addRect(slide, 40, 30, 30, 30, ac);  // 섹션 번호 배지
    addText(slide, String.valueOf(idx), 40, 30, 30, 30, 11, true, BG_DARK, TextParagraph.TextAlign.CENTER);
    addText(slide, title, 82, 18, W - 280, 50, 22, true, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
    addText(slide, "레거시 코드 분석 보고서", W - 200, 66, 162, 22, 9, false, TEXT_GRAY, TextParagraph.TextAlign.RIGHT);

    // 내용 파싱: ▸ 서브헤더 / • 불릿 / 일반 텍스트 구분
    List<String[]> segments = new ArrayList<>();
    for (String line : body.split("\n")) {
      String l = line.trim();
      if (l.isEmpty()) continue;
      if (l.startsWith("▸ "))      segments.add(new String[]{"header", l.substring(2).trim()});
      else if (l.startsWith("• ")) segments.add(new String[]{"bullet", l.substring(2).trim()});
      else                          segments.add(new String[]{"text",   l});
    }

    int contentY = 112, contentH = H - contentY - 12;
    long headerCnt = segments.stream().filter(s -> "header".equals(s[0])).count();
    long bulletCnt = segments.stream().filter(s -> "bullet".equals(s[0])).count();

    if (headerCnt >= 2) {
      renderSubsectionCards(slide, segments, ac, contentY, contentH);
    } else if (bulletCnt >= 6) {
      renderTwoColumnBullets(slide, segments, ac, contentY, contentH);
    } else {
      renderSingleColumn(slide, segments, ac, contentY, contentH);
    }
  }

  // 서브섹션(▸) 2개 이상: 섹션별 카드 2열 레이아웃
  private void renderSubsectionCards(XSLFSlide slide, List<String[]> segments,
      Color ac, int startY, int totalH) {
    List<String> preamble = new ArrayList<>();
    List<String> hTitles = new ArrayList<>();
    List<List<String>> hBodies = new ArrayList<>();
    String curTitle = null;
    List<String> curBody = null;

    for (String[] seg : segments) {
      if ("header".equals(seg[0])) {
        if (curTitle != null) { hTitles.add(curTitle); hBodies.add(curBody); }
        curTitle = seg[1]; curBody = new ArrayList<>();
      } else if (curTitle == null) {
        preamble.add(seg[1]);
      } else {
        curBody.add(("bullet".equals(seg[0]) ? "• " : "") + seg[1]);
      }
    }
    if (curTitle != null) { hTitles.add(curTitle); hBodies.add(curBody); }

    int yOff = startY;
    if (!preamble.isEmpty()) {
      String pre = String.join("  ", preamble);
      addRoundCard(slide, 40, yOff, W - 80, 32, BG_CARD);
      addRect(slide, 40, yOff, 4, 32, ac);
      addText(slide, pre, 54, yOff + 6, W - 104, 22, 9, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      yOff += 38; totalH -= 38;
    }

    int n = Math.min(hTitles.size(), 6);
    boolean twoCol = n > 2;
    int cols = twoCol ? 2 : 1;
    int rows = twoCol ? (n + 1) / 2 : n;
    int cW = twoCol ? (W - 100) / 2 : W - 80;
    int cGap = twoCol ? 20 : 0;
    int cH = Math.max(60, (totalH - (rows - 1) * 6) / rows);

    for (int i = 0; i < n; i++) {
      int col = twoCol ? i % 2 : 0;
      int row = twoCol ? i / 2 : i;
      int x = 40 + col * (cW + cGap);
      int y = yOff + row * (cH + 6);
      if (y + cH > startY + totalH + 10) break;

      addRoundCard(slide, x, y, cW, cH, BG_CARD);
      addRect(slide, x, y, cW, 3, ac);
      addText(slide, "▸  " + hTitles.get(i), x + 12, y + 6, cW - 20, 20, 10, true, ac, TextParagraph.TextAlign.LEFT);

      List<String> bodyLines = hBodies.get(i);
      int textY = y + 30;
      int maxLines = Math.min(bodyLines.size(), Math.max(1, (cH - 36) / 16));
      for (int j = 0; j < maxLines; j++) {
        String bl = bodyLines.get(j);
        Color lc = bl.startsWith("•") ? TEXT_WHITE : TEXT_GRAY;
        addText(slide, bl, x + 12, textY, cW - 24, 16, 8, false, lc, TextParagraph.TextAlign.LEFT);
        textY += 16;
      }
      if (bodyLines.size() > maxLines) {
        addText(slide, "···", x + 12, textY, cW - 24, 14, 7, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      }
    }
  }

  // 불릿 6개 이상: 2열 카드 레이아웃
  private void renderTwoColumnBullets(XSLFSlide slide, List<String[]> segments,
      Color ac, int startY, int totalH) {
    List<String> descLines = new ArrayList<>();
    List<String> bullets = new ArrayList<>();
    for (String[] seg : segments) {
      if ("bullet".equals(seg[0]))      bullets.add(seg[1]);
      else if ("text".equals(seg[0]))   descLines.add(seg[1]);
    }

    int yOff = startY;
    if (!descLines.isEmpty()) {
      String desc = String.join("  ", descLines);
      if (desc.length() > 180) desc = desc.substring(0, 177) + "···";
      addText(slide, desc, 40, yOff, W - 80, 28, 10, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      yOff += 34; totalH -= 34;
    }

    int cW = (W - 100) / 2;
    int itemH = 28, gap = 4;
    int half = (bullets.size() + 1) / 2;

    for (int i = 0; i < Math.min(bullets.size(), 20); i++) {
      int col = i >= half ? 1 : 0;
      int row = col == 0 ? i : i - half;
      int x = 40 + col * (cW + 20);
      int y = yOff + row * (itemH + gap);
      if (y + itemH > startY + totalH) break;

      addRoundCard(slide, x, y, cW, itemH, BG_CARD);
      addRect(slide, x, y, 3, itemH, ac);
      addText(slide, bullets.get(i), x + 12, y + 5, cW - 22, itemH - 8, 9, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
    }
  }

  // 기본: 설명 카드 + 불릿/헤더 영역 분리
  private void renderSingleColumn(XSLFSlide slide, List<String[]> segments,
      Color ac, int startY, int totalH) {

    // 설명 텍스트와 구조화 항목 분리
    List<String> descs = new ArrayList<>();
    List<String[]> items = new ArrayList<>();
    for (String[] seg : segments) {
      if ("text".equals(seg[0])) descs.add(seg[1]);
      else items.add(seg);
    }

    int yOff = startY;

    // 설명 텍스트 강조 카드 (13pt 흰색, 밝은 배경)
    if (!descs.isEmpty()) {
      int descH = Math.min(descs.size() * 30 + 28, items.isEmpty() ? totalH : totalH * 2 / 5);
      Color descBg = new Color(
          Math.min(BG_CARD.getRed()   + 18, 255),
          Math.min(BG_CARD.getGreen() + 18, 255),
          Math.min(BG_CARD.getBlue()  + 18, 255));
      addRoundCard(slide, 40, yOff, W - 80, descH, descBg);
      addRect(slide, 40, yOff, 5, descH, ac);

      int ty = yOff + 16;
      for (String d : descs) {
        if (ty + 26 > yOff + descH) break;
        addText(slide, d, 58, ty, W - 116, 26, 13, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
        ty += 30;
      }

      totalH -= descH + 8;
      yOff   += descH + 8;
    }

    if (items.isEmpty()) return;

    // 불릿·헤더 카드
    addRoundCard(slide, 40, yOff, W - 80, totalH, BG_CARD);
    addRect(slide, 40, yOff, 4, totalH, ac);

    int y = yOff + 14, maxY = yOff + totalH - 14;
    for (String[] seg : items) {
      if (y >= maxY) {
        addText(slide, "  ···", 60, y, W - 120, 14, 8, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
        break;
      }
      switch (seg[0]) {
        case "header" -> {
          addText(slide, "▸  " + seg[1], 56, y, W - 112, 22, 11, true, ac, TextParagraph.TextAlign.LEFT);
          y += 26;
        }
        case "bullet" -> {
          // 컬러 도트
          addRect(slide, 60, y + 7, 7, 7, ac);
          addText(slide, seg[1], 74, y, W - 130, 22, 11, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
          y += 24;
        }
        default -> {
          addText(slide, seg[1], 56, y, W - 112, 22, 11, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
          y += 24;
        }
      }
    }
  }

  // ── 소스 직접 파싱: Java 파일을 계층별로 분류 ──────────────
  private Map<String, List<String>> collectJavaFilesByLayer(Path javaRoot) {
    Map<String, List<String>> result = new java.util.LinkedHashMap<>();
    result.put("Controller", new ArrayList<>());
    result.put("Service",    new ArrayList<>());
    result.put("Repository", new ArrayList<>());
    result.put("Entity",     new ArrayList<>());
    result.put("DTO",        new ArrayList<>());
    result.put("Config",     new ArrayList<>());

    if (!Files.exists(javaRoot)) return result;
    try (Stream<Path> files = Files.walk(javaRoot, 10)) {
      files.filter(p -> p.toString().endsWith(".java"))
           .map(p -> p.getFileName().toString().replace(".java", ""))
           .forEach(name -> {
             String lw = name.toLowerCase();
             if (lw.endsWith("controller"))                                   result.get("Controller").add(name);
             else if (lw.endsWith("service") || lw.endsWith("serviceimpl"))  result.get("Service").add(name);
             else if (lw.endsWith("repository"))                              result.get("Repository").add(name);
             else if (lw.endsWith("entity"))                                  result.get("Entity").add(name);
             else if (lw.endsWith("dto"))                                     result.get("DTO").add(name);
             else if (lw.endsWith("config") || lw.endsWith("configuration")) result.get("Config").add(name);
           });
    } catch (IOException e) {
      log.warn("계층별 파일 수집 실패: {}", javaRoot, e);
    }
    return result;
  }

  // ── 고객 납품용: 시스템 아키텍처 슬라이드 ──────────────────
  private void createArchitectureSlide(XMLSlideShow ppt, AnalysisHistory h) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addSlideHeader(slide, "시스템 아키텍처", "System Architecture");

    // 계층 흐름 다이어그램
    String[] flowNames = {"Client",  "Controller", "Service",    "Repository",  "Database"};
    String[] flowSubs  = {"요청/응답", "REST API",  "비즈니스 로직", "데이터 접근", "저장소"};
    Color[]  flowBg    = {BG_CARD, BADGE_BLUE, BADGE_GRN, new Color(161, 98, 7), new Color(109, 40, 217)};
    Color[]  flowTxt   = {TEXT_GRAY, TEXT_WHITE, TEXT_WHITE, TEXT_WHITE, TEXT_WHITE};
    Color[]  flowSub   = {TEXT_GRAY, new Color(186, 230, 253), new Color(187, 247, 208),
                          new Color(253, 230, 138), new Color(221, 214, 254)};

    int bW = 124, bH = 54, aW = 34;
    int totalBW = 5 * bW + 4 * aW;
    int bSx = (W - totalBW) / 2;

    for (int i = 0; i < 5; i++) {
      int x = bSx + i * (bW + aW);
      addRoundCard(slide, x, 118, bW, bH, flowBg[i]);
      addText(slide, flowNames[i], x, 124, bW, 22, 12, true, flowTxt[i], TextParagraph.TextAlign.CENTER);
      addText(slide, flowSubs[i],  x, 148, bW, 16,  8, false, flowSub[i], TextParagraph.TextAlign.CENTER);
      if (i < 4) {
        addText(slide, "→", x + bW, 118, aW, bH, 14, true, TEXT_GRAY, TextParagraph.TextAlign.CENTER);
      }
    }
    addRect(slide, 40, 182, W - 80, 1, BG_CARD);

    // 계층별 클래스 현황 (4열 카드)
    String sourcePath = h.getSourcePath();
    if (sourcePath == null || sourcePath.isBlank()) return;
    Path javaRoot = Paths.get(sourcePath).resolve("src").resolve("main").resolve("java");
    Map<String, List<String>> byLayer = collectJavaFilesByLayer(javaRoot);

    String[] keys  = {"Controller", "Service", "Repository", "Entity"};
    String[] icons = {"🎮", "⚙️", "🗄️", "📦"};
    String[] roles = {
        "REST API 엔드포인트\n요청 수신 및 응답 처리\n입력 검증·권한 확인",
        "비즈니스 로직 처리\n도메인 규칙·흐름 제어\n트랜잭션 경계 설정",
        "데이터베이스 접근\nCRUD 쿼리 실행\nJPA·MyBatis 처리",
        "도메인 모델·DTO\n엔티티 및 전송 객체\n데이터 구조 정의"
    };
    Color[] layerColors = {BADGE_BLUE, BADGE_GRN, new Color(161, 98, 7), new Color(109, 40, 217)};

    int cW = (W - 100) / 4;
    int cGap = (W - 80 - 4 * cW) / 3;

    for (int i = 0; i < 4; i++) {
      int x = 40 + i * (cW + cGap);
      int y = 190, cH = H - y - 14;

      addRoundCard(slide, x, y, cW, cH, BG_CARD);
      addRect(slide, x, y, cW, 3, layerColors[i]);

      addText(slide, icons[i] + "  " + keys[i], x + 10, y + 8, cW - 60, 22, 12, true, layerColors[i], TextParagraph.TextAlign.LEFT);

      List<String> files = new ArrayList<>(byLayer.getOrDefault(keys[i], List.of()));
      if ("Entity".equals(keys[i])) files.addAll(byLayer.getOrDefault("DTO", List.of()));

      addText(slide, files.size() + "개", x + cW - 46, y + 8, 40, 22, 11, true, TEXT_WHITE, TextParagraph.TextAlign.RIGHT);
      addText(slide, roles[i], x + 10, y + 34, cW - 20, 54, 8, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addRect(slide, x + 10, y + 92, cW - 20, 1, new Color(51, 65, 85));

      int fy = y + 98;
      int maxShow = Math.min(files.size(), 12);
      for (int j = 0; j < maxShow; j++) {
        addText(slide, "• " + files.get(j), x + 10, fy, cW - 20, 16, 8, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
        fy += 17;
      }
      if (files.size() > 12) {
        addText(slide, "  외 " + (files.size() - 12) + "개 ...", x + 10, fy, cW - 20, 14, 7, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      }
      if (files.isEmpty()) {
        addText(slide, "(해당 없음)", x + 10, fy, cW - 20, 16, 9, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      }
    }
  }

  // ── 고객 납품용: 도메인별 기능 분석 슬라이드 ────────────────
  private void createDomainAnalysisSlide(XMLSlideShow ppt, AnalysisHistory h) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addSlideHeader(slide, "도메인별 기능 분석", "Domain Function Analysis");

    String sourcePath = h.getSourcePath();
    if (sourcePath == null || sourcePath.isBlank()) {
      addText(slide, "(소스 경로 정보 없음)", 40, 280, W - 80, 40, 14, false, TEXT_GRAY, TextParagraph.TextAlign.CENTER);
      return;
    }

    Path javaRoot = Paths.get(sourcePath).resolve("src").resolve("main").resolve("java");
    List<String[]> packages = buildPackageList(javaRoot);

    if (packages.isEmpty()) {
      addText(slide, "Java 패키지 구조를 찾을 수 없습니다.", 40, 270, W - 80, 40, 13, false, TEXT_GRAY, TextParagraph.TextAlign.CENTER);
      return;
    }

    // 도메인 개수에 따라 카드 크기 동적 계산
    boolean twoCol = packages.size() > 4;
    int half   = twoCol ? (packages.size() + 1) / 2 : packages.size();
    int colW   = twoCol ? (W - 100) / 2 : W - 80;
    int startY = 120, maxH = H - startY - 10;
    int gap    = 6;
    int cardH  = Math.max(78, Math.min(110, (maxH - gap * (half - 1)) / half));

    // 도메인별 배지 색상 순환
    Color[] domainColors = {ACCENT2, ACCENT, new Color(251, 146, 60),
                            new Color(196, 181, 253), new Color(251, 191, 36), new Color(248, 113, 113)};

    for (int i = 0; i < packages.size(); i++) {
      int col = twoCol ? i / half : 0;
      int row = twoCol ? i % half : i;
      int x = 40 + col * (colW + 20);
      int y = startY + row * (cardH + gap);
      if (y + cardH > H - 8) break;

      String[] pkg = packages.get(i);
      Color dc = domainColors[i % domainColors.length];

      // 카드 배경 + 좌측 컬러 바
      addRoundCard(slide, x, y, colW, cardH, BG_CARD);
      addRect(slide, x, y, 5, cardH, dc);

      // 도메인명 (굵게, 13pt)
      addText(slide, pkg[0], x + 16, y + 8, colW / 2, 24, 13, true, dc, TextParagraph.TextAlign.LEFT);

      // 클래스 타입 요약 (우측, 10pt, 더 밝은 회색)
      if (pkg[2] != null && !pkg[2].isEmpty()) {
        addText(slide, pkg[2], x + colW / 2, y + 10, colW / 2 - 14, 20, 9, false,
            new Color(186, 200, 220), TextParagraph.TextAlign.RIGHT);
      }

      // 구분선
      addRect(slide, x + 16, y + 36, colW - 30, 1, new Color(51, 65, 85));

      // 기능 설명 (11pt, 흰색)
      addText(slide, pkg[1], x + 16, y + 42, colW - 28, cardH - 48, 11, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
    }
  }

  // ── 고객 납품용: 계층별 역할 정의 슬라이드 ──────────────────
  private void createLayerResponsibilitySlide(XMLSlideShow ppt, AnalysisHistory h) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addSlideHeader(slide, "계층별 역할 정의", "Layer Responsibility");

    String sourcePath = h.getSourcePath();
    Path javaRoot = (sourcePath != null && !sourcePath.isBlank())
        ? Paths.get(sourcePath).resolve("src").resolve("main").resolve("java")
        : null;
    Map<String, List<String>> byLayer = (javaRoot != null)
        ? collectJavaFilesByLayer(javaRoot)
        : new java.util.LinkedHashMap<>();

    String[][] layerDef = {
        {"🎮  Controller",
         "HTTP 요청 수신 및 응답 처리\n• @GetMapping / @PostMapping 등 매핑\n• 입력값 검증 및 응답 포맷팅\n• 인증·인가 검사 (Spring Security)\n• Service 계층 위임 처리",
         "Controller"},
        {"⚙️  Service",
         "핵심 비즈니스 로직 처리\n• 도메인 업무 규칙 구현\n• 여러 Repository 조합 처리\n• @Transactional 트랜잭션 관리\n• 도메인 이벤트 발행",
         "Service"},
        {"🗄️  Repository",
         "데이터베이스 접근 처리\n• JPA / MyBatis CRUD 처리\n• 페이징·정렬 쿼리 제공\n• @Query 커스텀 쿼리 정의\n• 영속성 컨텍스트 관리",
         "Repository"},
        {"📦  Entity / DTO",
         "데이터 구조 정의\n• @Entity: DB 테이블 매핑\n• 연관관계 (@OneToMany 등) 정의\n• DTO: 계층 간 데이터 전달\n• @Valid 유효성 검증 어노테이션",
         "Entity"},
    };
    Color[] cardColors = {BADGE_BLUE, BADGE_GRN, new Color(161, 98, 7), new Color(109, 40, 217)};

    int cardW = (W - 100) / 2;  // ~430
    int cardH = (H - 130) / 2;  // ~205
    int[][] pos = {{40, 118}, {510, 118}, {40, 118 + cardH + 10}, {510, 118 + cardH + 10}};

    for (int i = 0; i < 4; i++) {
      int x = pos[i][0], y = pos[i][1];
      addRoundCard(slide, x, y, cardW, cardH, BG_CARD);
      addRect(slide, x, y, cardW, 3, cardColors[i]);
      addRect(slide, x, y, 3, cardH, cardColors[i]);

      // 레이어명
      addText(slide, layerDef[i][0], x + 14, y + 8, cardW / 2 - 10, 24, 12, true, cardColors[i], TextParagraph.TextAlign.LEFT);

      // 역할 설명 (좌측 절반)
      addText(slide, layerDef[i][1], x + 14, y + 36, cardW / 2 - 20, cardH - 50, 8, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);

      // 세로 구분선
      addRect(slide, x + cardW / 2 + 4, y + 10, 1, cardH - 20, new Color(51, 65, 85));

      // 실제 클래스 목록 (우측 절반)
      List<String> files = new ArrayList<>(byLayer.getOrDefault(layerDef[i][2], List.of()));
      if ("Entity".equals(layerDef[i][2])) files.addAll(byLayer.getOrDefault("DTO", List.of()));

      int fx = x + cardW / 2 + 14, fw = cardW / 2 - 22;
      addText(slide, "프로젝트 클래스", fx, y + 8, fw - 40, 18, 9, true, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addText(slide, files.size() + "개", x + cardW - 14, y + 8, 30, 18, 10, true, TEXT_WHITE, TextParagraph.TextAlign.RIGHT);

      int fy = y + 30;
      int maxShow = Math.min(files.size(), 9);
      for (int j = 0; j < maxShow; j++) {
        addText(slide, "• " + files.get(j), fx, fy, fw, 16, 8, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
        fy += 18;
      }
      if (files.size() > 9) {
        addText(slide, "  외 " + (files.size() - 9) + "개 ...", fx, fy, fw, 14, 7, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      }
      if (files.isEmpty()) {
        addText(slide, "(해당 없음)", fx, fy, fw, 16, 9, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      }
    }
  }

  // ── 분석 직후용: 분석 결과 요약 ─────────────────────────────
  private void createResultSummarySlide(XMLSlideShow ppt, AnalysisHistory h) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addSlideHeader(slide, "분석 결과 요약", "Analysis Summary");

    int total    = h.getTotalFiles()   != null ? h.getTotalFiles()   : 0;
    int success  = h.getSuccessCount() != null ? h.getSuccessCount() : 0;
    int skip     = h.getSkipCount()    != null ? h.getSkipCount()    : 0;
    int fail     = h.getFailureCount() != null ? h.getFailureCount() : 0;
    double rate  = total > 0 ? (success * 100.0 / total) : 0.0;
    double timeSec = h.getProcessingTimeMs() != null ? h.getProcessingTimeMs() / 1000.0 : 0.0;
    double avgSec  = h.getAvgTimePerFile()   != null ? h.getAvgTimePerFile()   : 0.0;

    String[][] topCards = {
        {"📂 총 파일 수", String.valueOf(total), "분석 대상 전체"},
        {"✅ 성공률",      String.format("%.1f%%", rate), String.format("%d개 성공", success)},
    };
    int mx = 40;
    for (String[] c : topCards) {
      addRoundCard(slide, mx, 130, 420, 105, BG_CARD);
      addRect(slide, mx, 130, 420, 3, ACCENT);
      addText(slide, c[0], mx + 20, 143, 380, 24, 12, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addText(slide, c[1], mx + 20, 168, 380, 44, 28, true, ACCENT, TextParagraph.TextAlign.LEFT);
      addText(slide, c[2], mx + 20, 214, 380, 20, 11, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      mx += 460;
    }

    String[][] subCards = {
        {"✅ 성공", String.valueOf(success)},
        {"⏭️ 스킵", String.valueOf(skip)},
        {"❌ 실패", String.valueOf(fail)},
        {"⏱️ 소요", String.format("%.1f초", timeSec)},
    };
    int sx = 40;
    for (String[] c : subCards) {
      addRoundCard(slide, sx, 263, 205, 75, BG_CARD);
      addText(slide, c[0], sx + 12, 274, 185, 22, 11, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addText(slide, c[1], sx + 12, 296, 185, 30, 18, true, ACCENT2, TextParagraph.TextAlign.LEFT);
      sx += 225;
    }

    addRect(slide, 40, 358, W - 80, 1, BG_CARD);
    addText(slide, "소스: " + h.getSourcePath(), 40, 366, W - 80, 22, 10, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
    addText(slide, "출력: " + h.getOutputPath(), 40, 386, W - 80, 22, 10, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
    addText(slide, String.format("파일당 평균 처리시간: %.2f초", avgSec), 40, 410, W - 80, 22, 10, false, ACCENT, TextParagraph.TextAlign.LEFT);
  }

  // ── 분석 직후용: AI 토큰 & 비용 ─────────────────────────────
  private void createResultTokenSlide(XMLSlideShow ppt, AnalysisHistory h) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addSlideHeader(slide, "AI 사용량 & 비용", "Token Usage & Cost");

    long inputT  = h.getInputTokens()  != null ? h.getInputTokens()  : 0L;
    long outputT = h.getOutputTokens() != null ? h.getOutputTokens() : 0L;
    long totalT  = h.getTotalTokens()  != null ? h.getTotalTokens()  : (inputT + outputT);
    double cost  = h.getEstimatedCost() != null ? h.getEstimatedCost() : 0.0;
    String model = h.getModelName() != null ? h.getModelName() : "-";

    String[][] cards = {
        {"📥 입력 토큰",  String.format("%,d", inputT),  "Input Tokens"},
        {"📤 출력 토큰",  String.format("%,d", outputT), "Output Tokens"},
        {"📊 총 토큰",    String.format("%,d", totalT),  "Total Tokens"},
        {"💰 추정 비용",  String.format("$%.4f", cost),  "Estimated Cost (USD)"},
    };

    int y = 140;
    for (int i = 0; i < cards.length; i++) {
      int cx = (i % 2 == 0) ? 40 : 500;
      int cy = y + (i / 2) * 120;
      addRoundCard(slide, cx, cy, 400, 100, BG_CARD);
      addRect(slide, cx, cy, 4, 100, i < 2 ? ACCENT : ACCENT2);
      addText(slide, cards[i][0], cx + 20, cy + 12, 360, 26, 12, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addText(slide, cards[i][1], cx + 20, cy + 40, 360, 38, 22, true, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
      addText(slide, cards[i][2], cx + 20, cy + 78, 360, 18, 10, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
    }

    addRect(slide, 40, H - 75, W - 80, 1, BG_CARD);
    addText(slide, "사용 모델: " + model, 40, H - 62, W - 80, 22, 11, false, ACCENT, TextParagraph.TextAlign.LEFT);
  }

  // ── 분석 직후용: 표지 ────────────────────────────────────────
  private void createResultTitleSlide(XMLSlideShow ppt, AnalysisHistory h) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addRect(slide, 0, 0, 6, H, ACCENT);
    addRect(slide, 40, 110, W - 80, 2, ACCENT);

    String projectName = extractFolderName(h.getSourcePath());
    addText(slide, projectName,
        40, 130, W - 80, 80, 34, true, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
    addText(slide, "레거시 코드 분석 보고서",
        40, 220, W - 80, 40, 18, false, ACCENT, TextParagraph.TextAlign.LEFT);
    addText(slide, "Legacy Code Analysis Report — Powered by Claude AI",
        40, 258, W - 80, 30, 12, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
    addRect(slide, 40, 298, 160, 2, ACCENT2);

    String[][] info = {
        {"분석 일시", h.getCreatedAt() != null ? h.getCreatedAt().format(DT_FMT) : "-"},
        {"분석 대상", extractFolderName(h.getSourcePath())},
        {"분석 상태", "COMPLETED".equals(h.getStatus()) ? "완료" : (h.getStatus() != null ? h.getStatus() : "-")},
    };
    int x = 40;
    for (String[] item : info) {
      addRoundCard(slide, x, 318, 270, 72, BG_CARD);
      addText(slide, item[0], x + 12, 326, 246, 22, 11, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addText(slide, item[1], x + 12, 348, 246, 28, 13, true, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
      x += 290;
    }

    addText(slide, "본 보고서는 AI 기반 자동 분석 도구를 사용하여 생성된 레거시 코드 분석 산출물입니다.",
        40, H - 45, W - 80, 24, 10, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
  }

  // ── 결과 슬라이드 2: 분석 범위 요약 ─────────────────────────
  private void createResultScopeSlide(XMLSlideShow ppt, AnalysisHistory h) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addSlideHeader(slide, "분석 범위", "Analysis Scope");

    int total   = h.getTotalFiles()   != null ? h.getTotalFiles()   : 0;
    int success = h.getSuccessCount() != null ? h.getSuccessCount() : 0;
    int skip    = h.getSkipCount()    != null ? h.getSkipCount()    : 0;
    int fail    = h.getFailureCount() != null ? h.getFailureCount() : 0;
    double rate = total > 0 ? (success * 100.0 / total) : 0.0;

    // 상단 대형 수치 카드 2개
    String[][] topCards = {
        {"분석 파일 수", String.valueOf(total) + "개", "전체 소스 파일"},
        {"주석 생성 완료", String.format("%.0f%%", rate), String.valueOf(success) + "개 파일 처리"},
    };
    int mx = 40;
    for (String[] c : topCards) {
      addRoundCard(slide, mx, 130, 420, 110, BG_CARD);
      addRect(slide, mx, 130, 420, 4, ACCENT);
      addText(slide, c[0], mx + 20, 144, 380, 26, 12, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addText(slide, c[1], mx + 20, 172, 380, 46, 30, true, ACCENT, TextParagraph.TextAlign.LEFT);
      addText(slide, c[2], mx + 20, 220, 380, 20, 11, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      mx += 460;
    }

    // 하단 세부 항목
    String[][] subCards = {
        {"주석 추가 완료", String.valueOf(success) + "개"},
        {"기존 처리 스킵", String.valueOf(skip) + "개"},
        {"처리 실패", String.valueOf(fail) + "개"},
        {"처리 성공률", String.format("%.1f%%", rate)},
    };
    int sx = 40;
    for (String[] c : subCards) {
      addRoundCard(slide, sx, 268, 205, 72, BG_CARD);
      addText(slide, c[0], sx + 12, 278, 185, 24, 10, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addText(slide, c[1], sx + 12, 302, 185, 28, 17, true, ACCENT2, TextParagraph.TextAlign.LEFT);
      sx += 225;
    }

    // 경로 정보
    addRect(slide, 40, 360, W - 80, 1, BG_CARD);
    addText(slide, "분석 경로: " + h.getSourcePath(), 40, 368, W - 80, 22, 10, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
    addText(slide, "출력 경로: " + h.getOutputPath(), 40, 388, W - 80, 22, 10, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
  }

  // ── 결과 마지막 슬라이드: 마무리 ────────────────────────────
  private void createResultClosingSlide(XMLSlideShow ppt, AnalysisHistory h) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addRect(slide, 0, 0, W, H / 3, BG_CARD);
    addRect(slide, 0, H / 3, W, 4, ACCENT);

    String projectName = extractFolderName(h.getSourcePath());
    addText(slide, projectName, 0, 60, W, 60, 28, true, TEXT_WHITE, TextParagraph.TextAlign.CENTER);
    addText(slide, "레거시 코드 분석 보고서", 0, 125, W, 36, 16, false, ACCENT, TextParagraph.TextAlign.CENTER);

    int total   = h.getTotalFiles()   != null ? h.getTotalFiles()   : 0;
    int success = h.getSuccessCount() != null ? h.getSuccessCount() : 0;
    double rate = total > 0 ? (success * 100.0 / total) : 0.0;

    addText(slide, String.format("총 %d개 파일 분석 완료  ·  주석 생성률 %.0f%%", total, rate),
        0, 205, W, 36, 14, false, TEXT_GRAY, TextParagraph.TextAlign.CENTER);

    addText(slide, "본 보고서에 포함된 모든 분석 내용은 AI가 자동 생성한 결과물로,\n실제 운영 반영 전 담당 개발자의 검토가 필요합니다.",
        80, 270, W - 160, 70, 12, false, TEXT_GRAY, TextParagraph.TextAlign.CENTER);

    addRect(slide, 80, 360, W - 160, 1, new Color(51, 65, 85));
    addText(slide, h.getCreatedAt() != null ? h.getCreatedAt().format(DT_FMT) : "",
        0, 374, W, 26, 11, false, new Color(71, 85, 105), TextParagraph.TextAlign.CENTER);
  }

  // ── 헬퍼 ─────────────────────────────────────────────────────
  private String extractFolderName(String path) {
    if (path == null || path.isEmpty()) return "Unknown Project";
    String p = path.replace('\\', '/');
    if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
    int idx = p.lastIndexOf('/');
    return idx >= 0 ? p.substring(idx + 1) : p;
  }

  private String shortModelName(String model) {
    if (model == null) return "-";
    if (model.contains("sonnet")) return "Claude Sonnet";
    if (model.contains("opus"))   return "Claude Opus";
    if (model.contains("haiku"))  return "Claude Haiku";
    return model;
  }

  public byte[] generateFinalProjectPresentation() throws IOException {
    log.info("📊 Apache POI로 PPTX 생성 시작");

    XMLSlideShow ppt = new XMLSlideShow();
    ppt.setPageSize(new Dimension(W, H));

    createTitleSlide(ppt);
    createOverviewSlide(ppt);
    createFeaturesSlide(ppt);
    createEffectsSlide(ppt);
    createTechStackSlide(ppt);
    createClosingSlide(ppt);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ppt.write(baos);
    ppt.close();

    log.info("✅ PPTX 생성 완료: {}KB, 슬라이드 {}장", baos.size() / 1024, ppt.getSlides().size());
    return baos.toByteArray();
  }

  // ── 슬라이드 1: 표지 ────────────────────────────────────────
  private void createTitleSlide(XMLSlideShow ppt) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);

    // 왼쪽 강조 바
    addRect(slide, 0, 0, 6, H, ACCENT);

    // 상단 장식 선
    addRect(slide, 40, 120, W - 80, 2, ACCENT);

    // 메인 타이틀
    addText(slide, "Code Analyzer & Dashboard",
        40, 140, W - 80, 80,
        34, true, TEXT_WHITE, TextParagraph.TextAlign.LEFT);

    // 부제목
    addText(slide, "Claude AI 기반 레거시 코드 자동 분석 및 문서화 시스템",
        40, 230, W - 80, 40,
        16, false, ACCENT, TextParagraph.TextAlign.LEFT);

    // 구분선
    addRect(slide, 40, 280, 120, 2, ACCENT2);

    // 정보 박스
    String[][] info = {
        {"작성자", "정재훈"},
        {"완성도", "150% 달성"},
        {"기간", "4주 집중 개발"},
    };
    int x = 40;
    for (String[] item : info) {
      addRoundCard(slide, x, 310, 200, 70, BG_CARD);
      addText(slide, item[0], x + 10, 318, 180, 20, 11, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addText(slide, item[1], x + 10, 338, 180, 28, 14, true, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
      x += 220;
    }

    // 하단 배지
    addText(slide, "Spring Boot 3.2  ·  Java 17  ·  Claude API  ·  JWT  ·  PostgreSQL",
        40, H - 50, W - 80, 24,
        11, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
  }

  // ── 슬라이드 2: 시스템 개요 ─────────────────────────────────
  private void createOverviewSlide(XMLSlideShow ppt) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addSlideHeader(slide, "시스템 개요", "System Overview");

    String[][] items = {
        {"🎯 목적", "20년 이상 된 레거시 코드의 비즈니스 로직을 AI가 분석해\n현업 개발자가 이해할 수 있는 한글 주석을 자동 생성"},
        {"📂 대상", "Java · JavaScript · XML · Python · HTML · Properties 등\n다양한 확장자의 소스 파일 전체 자동 처리"},
        {"⚡ 방식", "원본 파일 보존 (읽기 전용) → 출력 경로에 복사 후 주석 추가\n병렬 처리 + HTTP 폴링 진행 모니터링 (2초 간격)"},
    };

    int y = 155;
    for (String[] item : items) {
      addRoundCard(slide, 40, y, W - 80, 80, BG_CARD);
      addText(slide, item[0], 60, y + 10, 160, 28, 13, true, ACCENT, TextParagraph.TextAlign.LEFT);
      addText(slide, item[1], 230, y + 10, W - 270, 60, 12, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
      y += 98;
    }
  }

  // ── 슬라이드 3: 핵심 기능 ───────────────────────────────────
  private void createFeaturesSlide(XMLSlideShow ppt) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addSlideHeader(slide, "핵심 기능", "Core Features");

    String[][] features = {
        {"📁", "파일 복사\n& 분석"},
        {"🤖", "AI 주석\n자동 생성"},
        {"📄", "README\n자동 생성"},
        {"📡", "HTTP 폴링\n모니터링"},
        {"👥", "멀티유저\n권한 관리"},
        {"📊", "관리자\n대시보드"},
    };

    int cols = 3;
    int cardW = 260, cardH = 110;
    int startX = 40, startY = 155;
    int gapX = 20, gapY = 15;

    for (int i = 0; i < features.length; i++) {
      int col = i % cols;
      int row = i / cols;
      int cx = startX + col * (cardW + gapX);
      int cy = startY + row * (cardH + gapY);

      addRoundCard(slide, cx, cy, cardW, cardH, BG_CARD);
      // 왼쪽 색상 바
      addRect(slide, cx, cy, 4, cardH, ACCENT);
      addText(slide, features[i][0], cx + 15, cy + 18, 50, 50, 30, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
      addText(slide, features[i][1], cx + 70, cy + 20, cardW - 80, 65, 13, true, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
    }
  }

  // ── 슬라이드 4: 기대 효과 ───────────────────────────────────
  private void createEffectsSlide(XMLSlideShow ppt) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addSlideHeader(slide, "기대 효과", "Expected Effects");

    // 수치 카드 (상단 2개)
    String[][] metrics = {
        {"⏱️  시간 단축", "97%", "225.5시간 → 6.5시간"},
        {"💰  비용 절감", "1,350만원", "수작업 대비 절감액"},
    };
    int mx = 40;
    for (String[] m : metrics) {
      addRoundCard(slide, mx, 145, 420, 105, BG_CARD);
      addRect(slide, mx, 145, 420, 3, ACCENT);
      addText(slide, m[0], mx + 20, 158, 380, 25, 13, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addText(slide, m[1], mx + 20, 185, 380, 44, 28, true, ACCENT, TextParagraph.TextAlign.LEFT);
      addText(slide, m[2], mx + 20, 230, 380, 22, 11, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      mx += 460;
    }

    // 하단 효과 목록
    String[][] effects = {
        {"👥 생산성", "10배 증대"},
        {"🛡️ 오류", "90% 감소"},
        {"📚 문서화", "100% 자동"},
        {"🔄 인수인계", "시간 80% 단축"},
    };
    int ex = 40;
    for (String[] e : effects) {
      addRoundCard(slide, ex, 275, 200, 70, BG_CARD);
      addText(slide, e[0], ex + 10, 285, 180, 22, 11, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
      addText(slide, e[1], ex + 10, 307, 180, 30, 15, true, ACCENT2, TextParagraph.TextAlign.LEFT);
      ex += 220;
    }

    // 하단 강조 문구
    addRect(slide, 40, H - 70, W - 80, 1, ACCENT);
    addText(slide, "\"개발자가 주석 작성에 쓰는 시간을 비즈니스 로직 개발에 집중할 수 있도록\"",
        40, H - 58, W - 80, 30, 13, false, TEXT_GRAY, TextParagraph.TextAlign.CENTER);
  }

  // ── 슬라이드 5: 기술 스택 ───────────────────────────────────
  private void createTechStackSlide(XMLSlideShow ppt) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);
    addSlideHeader(slide, "기술 스택", "Technology Stack");

    String[][] stacks = {
        {"Backend",   "Java 17  ·  Spring Boot 3.2.5  ·  Spring Security  ·  JPA"},
        {"Database",  "PostgreSQL 16  ·  Docker  ·  Hibernate ORM"},
        {"Frontend",  "HTML5  ·  CSS3  ·  Vanilla JS  ·  HTTP Polling (2s interval)"},
        {"AI / API",  "Claude API (Anthropic)  ·  Sonnet 4.6 (1M Context)"},
        {"Security",  "JWT (JJWT 0.12.5)  ·  BCrypt  ·  Role 기반 접근 제어"},
        {"Build",     "Gradle 8.x  ·  Apache POI 5.2.5  ·  Lombok  ·  WebFlux"},
    };

    Color[] barColors = {ACCENT, ACCENT2, new Color(251, 191, 36), new Color(249, 115, 22),
        new Color(167, 139, 250), new Color(244, 114, 182)};

    int y = 150;
    for (int i = 0; i < stacks.length; i++) {
      addRoundCard(slide, 40, y, W - 80, 48, BG_CARD);
      addRect(slide, 40, y, 4, 48, barColors[i % barColors.length]);
      addText(slide, stacks[i][0], 55, y + 8, 110, 32, 11, true, barColors[i % barColors.length], TextParagraph.TextAlign.LEFT);
      addText(slide, stacks[i][1], 175, y + 10, W - 215, 28, 12, false, TEXT_WHITE, TextParagraph.TextAlign.LEFT);
      y += 58;
    }
  }

  // ── 슬라이드 6: 마무리 ──────────────────────────────────────
  private void createClosingSlide(XMLSlideShow ppt) {
    XSLFSlide slide = ppt.createSlide();
    fillBackground(slide, BG_DARK);

    // 중앙 장식
    addRect(slide, W / 2 - 60, 100, 120, 3, ACCENT);

    addText(slide, "✅ 150% 달성",
        0, 120, W, 70, 40, true, ACCENT2, TextParagraph.TextAlign.CENTER);

    addText(slide, "계획 대비 초과 달성  ·  프로덕션 수준 완성도",
        0, 200, W, 36, 16, false, TEXT_GRAY, TextParagraph.TextAlign.CENTER);

    addRect(slide, W / 2 - 200, 250, 400, 1, BG_CARD);

    String[] badges = {"코드 분석", "AI 주석", "HTTP 폴링", "멀티유저", "관리자 대시보드"};
    int bx = 55;
    for (String b : badges) {
      int bw = b.length() * 13 + 20;
      addRoundCard(slide, bx, 270, bw, 32, BADGE_BLUE);
      addText(slide, b, bx, 275, bw, 22, 11, false, TEXT_WHITE, TextParagraph.TextAlign.CENTER);
      bx += bw + 10;
    }

    addText(slide, "Code Analyzer & Dashboard  |  Powered by Claude AI",
        0, H - 60, W, 28, 12, false, TEXT_GRAY, TextParagraph.TextAlign.CENTER);

    addRect(slide, 100, H - 30, W - 200, 1, BG_CARD);
  }

  // ── 공통 헬퍼 ───────────────────────────────────────────────

  private void addSlideHeader(XSLFSlide slide, String title, String subtitle) {
    addRect(slide, 0, 0, W, 105, BG_CARD);
    addRect(slide, 0, 105, W, 3, ACCENT);
    addText(slide, title,   40, 22, W - 80, 44, 24, true,  TEXT_WHITE, TextParagraph.TextAlign.LEFT);
    addText(slide, subtitle, 40, 66, W - 80, 26, 13, false, TEXT_GRAY, TextParagraph.TextAlign.LEFT);
  }

  private void fillBackground(XSLFSlide slide, Color color) {
    XSLFAutoShape bg = slide.createAutoShape();
    bg.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
    bg.setAnchor(new Rectangle2D.Double(0, 0, W, H));
    bg.setFillColor(color);
    bg.setLineColor(color);
  }

  private void addRect(XSLFSlide slide, int x, int y, int w, int h, Color color) {
    XSLFAutoShape shape = slide.createAutoShape();
    shape.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
    shape.setAnchor(new Rectangle2D.Double(x, y, w, h));
    shape.setFillColor(color);
    shape.setLineColor(color);
  }

  private void addRoundCard(XSLFSlide slide, int x, int y, int w, int h, Color color) {
    XSLFAutoShape shape = slide.createAutoShape();
    shape.setShapeType(org.apache.poi.sl.usermodel.ShapeType.ROUND_RECT);
    shape.setAnchor(new Rectangle2D.Double(x, y, w, h));
    shape.setFillColor(color);
    shape.setLineColor(new Color(51, 65, 85));
  }

  private void addText(XSLFSlide slide, String text, int x, int y, int w, int h,
      double fontSize, boolean bold, Color color, TextParagraph.TextAlign align) {
    XSLFTextBox tb = slide.createTextBox();
    tb.setAnchor(new Rectangle2D.Double(x, y, w, h));
    tb.clearText();
    XSLFTextParagraph p = tb.addNewTextParagraph();
    p.setTextAlign(align);
    p.setSpaceBefore(0.0);
    p.setSpaceAfter(0.0);
    for (String line : text.split("\n")) {
      if (p.getTextRuns().isEmpty()) {
        XSLFTextRun r = p.addNewTextRun();
        r.setText(line);
        r.setFontSize(fontSize);
        r.setBold(bold);
        r.setFontColor(color);
        r.setFontFamily("맑은 고딕");
      } else {
        p = tb.addNewTextParagraph();
        p.setTextAlign(align);
        p.setSpaceBefore(0.0);
        XSLFTextRun r = p.addNewTextRun();
        r.setText(line);
        r.setFontSize(fontSize);
        r.setBold(bold);
        r.setFontColor(color);
        r.setFontFamily("맑은 고딕");
      }
    }
  }
}
