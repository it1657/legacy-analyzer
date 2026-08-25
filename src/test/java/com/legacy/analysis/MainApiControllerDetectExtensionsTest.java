package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-07-29 설계안 4.2절 유틸(detectExtensions) 검증 — 이미 갖고 있는 fileList에서 확장자
 * 집합만 뽑아내는지(디스크 재스캔 없이), 대소문자·중복을 정규화하는지 확인한다.
 *
 * MainApiController는 생성자 의존성이 12개라, 이 메서드가 실제로 쓰는 것이 없으므로
 * 기존 테스트(MainApiControllerLlmProviderTest)와 동일하게 전부 null로 넘기고
 * private 메서드는 리플렉션으로 접근한다.
 */
class MainApiControllerDetectExtensionsTest {

  @SuppressWarnings("unchecked")
  private Set<String> invoke(MainApiController controller, List<Path> fileList) throws Exception {
    Method method = MainApiController.class.getDeclaredMethod("detectExtensions", List.class);
    method.setAccessible(true);
    return (Set<String>) method.invoke(controller, fileList);
  }

  private MainApiController newController() throws Exception {
    return new MainApiController(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  @Test
  void 파일_목록에서_확장자_집합을_추출한다() throws Exception {
    MainApiController controller = newController();
    List<Path> fileList = List.of(
        Path.of("src/Main.java"), Path.of("src/App.vue"), Path.of("src/util.py"));

    Set<String> result = invoke(controller, fileList);

    assertEquals(Set.of(".java", ".vue", ".py"), result);
  }

  @Test
  void 대문자_확장자는_소문자로_정규화된다() throws Exception {
    MainApiController controller = newController();
    List<Path> fileList = List.of(Path.of("legacy/Old.JAVA"));

    Set<String> result = invoke(controller, fileList);

    assertEquals(Set.of(".java"), result);
  }

  @Test
  void 동일_확장자_파일이_여러개여도_중복없이_한번만_담긴다() throws Exception {
    MainApiController controller = newController();
    List<Path> fileList = List.of(
        Path.of("a/A.java"), Path.of("b/B.java"), Path.of("c/C.java"));

    Set<String> result = invoke(controller, fileList);

    assertEquals(Set.of(".java"), result);
  }

  @Test
  void 빈_목록이면_빈_집합을_반환한다() throws Exception {
    MainApiController controller = newController();

    assertTrue(invoke(controller, List.of()).isEmpty());
    assertTrue(invoke(controller, null).isEmpty());
  }

  @Test
  void 확장자가_없는_파일명은_무시된다() throws Exception {
    MainApiController controller = newController();
    List<Path> fileList = List.of(Path.of("Dockerfile"), Path.of("src/Main.java"));

    Set<String> result = invoke(controller, fileList);

    assertEquals(Set.of(".java"), result);
  }
}
