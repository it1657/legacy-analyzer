package com.legacy.core;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * 소스 경로를 스캔해 프로젝트 타입(java / react / nextjs / vue / python / general)을 감지한다.
 * PresentationGeneratorService, ClaudeServiceImpl, MainApiController가 공통으로 사용한다.
 */
@Service
public class ProjectTypeDetector {

  /** 프로젝트 타입 감지 (java / react / nextjs / vue / python / general) */
  public String detectProjectType(Path root) {
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

  /** 문자열 경로용 오버로드 — null/미존재 경로는 안전하게 "general" 반환 */
  public String detectProjectType(String sourceFolderPath) {
    if (sourceFolderPath == null || sourceFolderPath.isBlank()) return "general";
    try {
      Path root = Paths.get(sourceFolderPath);
      if (!Files.exists(root)) return "general";
      return detectProjectType(root);
    } catch (Exception e) {
      return "general";
    }
  }
}
