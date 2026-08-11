package com.legacy.analysis;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 1.5(2026-08-11, 설계안 3.4절)로 role-properties.md/role-yaml.md를 신설하면서 발견한
 * 버그를 검증한다: normalizeComment()는 원래 `.py` 파일만 `#` 주석으로 강제하고, 그 외
 * 확장자는 마커가 없으면 전부 `//`를 붙이는 fallback을 탔다. `.properties`/`.yml`도 이 fallback을
 * 타면 `//`는 두 파일 형식 어디에서도 유효한 주석 마커가 아니라서 실제 분석 결과 파일에 문법상
 * 잘못된 주석이 삽입된다 — isHashCommentFamily()로 `.py`와 동일하게 `#` 스타일을 강제하도록 수정.
 *
 * 같은 이유로 role-css.md 신설(2026-08-11 후속) 시 `.css`도 동일한 문제가 있음을 확인 —
 * 표준 CSS는 `//` 한 줄 주석을 지원하지 않으므로 isCssFamily()로 `/* ... *&#47;` 스타일을
 * 강제하도록 수정. role-gradle.md(Groovy DSL)는 기존과 동일하게 `//`가 유효한 주석이라
 * 별도 처리가 필요 없음을 함께 검증한다.
 */
class ClaudeServiceImplNormalizeCommentTest {

  private String invoke(String comment, String extension) throws Exception {
    ClaudeServiceImpl service = new ClaudeServiceImpl(null, null, null, null, null);
    Method method = ClaudeServiceImpl.class.getDeclaredMethod("normalizeComment", String.class, String.class);
    method.setAccessible(true);
    return (String) method.invoke(service, comment, extension);
  }

  @Test
  void properties_파일에서_이미_hash_주석이면_그대로_통과한다() throws Exception {
    String result = invoke("# DB 접속 정보", ".properties");
    assertEquals("# DB 접속 정보", result);
  }

  @Test
  void properties_파일에서_슬래시_주석이_들어오면_hash_스타일로_변환된다() throws Exception {
    String result = invoke("// DB 접속 정보", ".properties");
    assertEquals("# DB 접속 정보", result);
  }

  @Test
  void properties_파일에서_마커_없는_텍스트는_slash가_아니라_hash가_붙는다() throws Exception {
    // 수정 전 버그: 이 케이스가 "// DB 접속 정보"로 잘못 변환되었음(.properties에는 // 무효)
    String result = invoke("DB 접속 정보", ".properties");
    assertEquals("# DB 접속 정보", result);
  }

  @Test
  void yaml_파일에서_마커_없는_텍스트에_hash가_붙는다() throws Exception {
    String result = invoke("postgres 컨테이너 헬스체크 설정", ".yml");
    assertEquals("# postgres 컨테이너 헬스체크 설정", result);
  }

  @Test
  void yaml_확장자_yaml도_동일하게_처리된다() throws Exception {
    String result = invoke("빌드 스텝 설명", ".yaml");
    assertEquals("# 빌드 스텝 설명", result);
  }

  @Test
  void java_파일은_기존과_동일하게_slash_스타일이_그대로_유지된다() throws Exception {
    // 회귀 방지: isHashCommentFamily 도입으로 기존 .py 외 확장자 동작이 바뀌면 안 됨
    String result = invoke("// 주문 취소 정책 검증", ".java");
    assertEquals("// 주문 취소 정책 검증", result);
  }

  @Test
  void css_파일에서_이미_블록_주석이면_그대로_통과한다() throws Exception {
    String result = invoke("/* 카드 레이아웃 여백 조정 */", ".css");
    assertEquals("/* 카드 레이아웃 여백 조정 */", result);
  }

  @Test
  void css_파일에서_슬래시_주석이_들어오면_블록_주석으로_변환된다() throws Exception {
    // 수정 전 버그: CSS는 // 한 줄 주석을 지원하지 않아 그대로 두면 무효한 주석이 삽입됨
    String result = invoke("// 카드 레이아웃 여백 조정", ".css");
    assertEquals("/* 카드 레이아웃 여백 조정 */", result);
  }

  @Test
  void css_파일에서_마커_없는_텍스트도_블록_주석으로_변환된다() throws Exception {
    String result = invoke("카드 레이아웃 여백 조정", ".css");
    assertEquals("/* 카드 레이아웃 여백 조정 */", result);
  }

  @Test
  void css_파일에서_여러줄_슬래시_주석은_여러줄_블록_주석으로_변환된다() throws Exception {
    String result = invoke("// 첫 줄 설명\n// 둘째 줄 설명", ".css");
    assertEquals("/*\n첫 줄 설명\n둘째 줄 설명\n*/", result);
  }

  @Test
  void gradle_파일은_slash_스타일이_기존_java와_동일하게_그대로_유지된다() throws Exception {
    // Groovy DSL은 Java와 동일하게 // 이 유효한 주석이므로 별도 변환 로직이 필요 없음을 확인
    String result = invoke("// 회사 사내망 전용 Maven 저장소", ".gradle");
    assertEquals("// 회사 사내망 전용 Maven 저장소", result);
  }
}
