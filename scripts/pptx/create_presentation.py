#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FINAL_PROJECT_REPORT를 PowerPoint 프레젠테이션으로 생성하는 스크립트
"""

from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.enum.text import PP_ALIGN
from pptx.dml.color import RGBColor

def add_title_slide(prs, title, subtitle):
    """타이틀 슬라이드 추가"""
    slide = prs.slides.add_slide(prs.slide_layouts[6])  # 빈 레이아웃
    background = slide.background
    fill = background.fill
    fill.solid()
    fill.fore_color.rgb = RGBColor(25, 118, 210)  # 파란색

    # 제목
    title_box = slide.shapes.add_textbox(Inches(0.5), Inches(2), Inches(9), Inches(1.5))
    title_frame = title_box.text_frame
    title_frame.word_wrap = True
    title_p = title_frame.paragraphs[0]
    title_p.text = title
    title_p.font.size = Pt(54)
    title_p.font.bold = True
    title_p.font.color.rgb = RGBColor(255, 255, 255)

    # 부제
    subtitle_box = slide.shapes.add_textbox(Inches(0.5), Inches(3.8), Inches(9), Inches(2))
    subtitle_frame = subtitle_box.text_frame
    subtitle_frame.word_wrap = True
    sub_p = subtitle_frame.paragraphs[0]
    sub_p.text = subtitle
    sub_p.font.size = Pt(28)
    sub_p.font.color.rgb = RGBColor(255, 255, 255)

def add_content_slide(prs, title, content_list, bg_color=None):
    """콘텐츠 슬라이드 추가"""
    if bg_color is None:
        bg_color = RGBColor(245, 245, 245)

    slide = prs.slides.add_slide(prs.slide_layouts[6])
    background = slide.background
    fill = background.fill
    fill.solid()
    fill.fore_color.rgb = bg_color

    # 제목
    title_box = slide.shapes.add_textbox(Inches(0.5), Inches(0.3), Inches(9), Inches(0.8))
    title_frame = title_box.text_frame
    title_p = title_frame.paragraphs[0]
    title_p.text = title
    title_p.font.size = Pt(40)
    title_p.font.bold = True
    title_p.font.color.rgb = RGBColor(25, 118, 210)

    # 제목 밑줄
    line_shape = slide.shapes.add_shape(1, Inches(0.5), Inches(1.15), Inches(9), Inches(0))
    line_shape.line.color.rgb = RGBColor(25, 118, 210)
    line_shape.line.width = Pt(3)

    # 콘텐츠
    content_box = slide.shapes.add_textbox(Inches(0.7), Inches(1.4), Inches(8.6), Inches(5.5))
    text_frame = content_box.text_frame
    text_frame.word_wrap = True

    for i, content in enumerate(content_list):
        if i > 0:
            text_frame.add_paragraph()
        p = text_frame.paragraphs[i]
        p.text = content
        p.font.size = Pt(18)
        p.font.color.rgb = RGBColor(40, 40, 40)
        p.space_before = Pt(6)
        p.space_after = Pt(6)
        p.level = 0

def create_presentation():
    """PowerPoint 프레젠테이션 생성"""
    prs = Presentation()
    prs.slide_width = Inches(10)
    prs.slide_height = Inches(7.5)

    # Slide 1: 타이틀
    add_title_slide(prs,
        "📋 FINAL PROJECT REPORT",
        "JAVA Spring Boot 기반 Claude 레거시 코드 분석 및 자동 문서화 시스템")

    # Slide 2: 프로젝트 기본 정보
    add_content_slide(prs, "프로젝트 기본 정보", [
        "🎯 프로젝트명: JAVA Spring Boot 기반 Claude 기반 레거시 코드 분석 및 기술문서/주석 자동 생성 시스템",
        "👤 작성자: 정재훈",
        "📅 개발 기간: 2026-06-01 ~ 2026-06-30 (30일)",
        "⚡ 실제 완료: 2026-06-17 (16일, 조기 완료)",
        "✅ 완료도: 150% (계획 초과 달성)",
        "💻 개발 환경: Windows 10 Pro, Java 21, Spring Boot 3.2.5",
        "🗄️ 데이터베이스: H2 (개발/테스트용)"
    ])

    # Slide 3: 해결하려는 문제
    add_content_slide(prs, "개발 배경: 해결하려는 문제", [
        "❌ 프로젝트 철수 및 인수인계 시 기존 소스코드에 주석 부실",
        "❌ 기술문서(README.md) 부재로 후임자가 시스템 구조 파악에 막대한 시간 소비",
        "❌ 바쁜 개발 일정 속에서 개발자가 상세한 주석 및 문서를 작성하기 어려운 현실",
        "❌ 유지보수 과정에서 휴먼 에러 발생 가능성 증대"
    ])

    # Slide 4: 프로젝트 목표
    add_content_slide(prs, "프로젝트 목표", [
        "🎯 최종 목표:",
        "인수인계 및 프로젝트 문서화 작업 시간을 80% 이상 단축하여",
        "개발 생산성을 극대화하고, 소스코드 가독성을 높여",
        "레거시 시스템 유지보수 과정에서 발생하는 휴먼 에러를 방지",
        "",
        "세부 목표:",
        "✅ Claude AI를 활용한 자동 주석 생성",
        "✅ 표준 마크다운 기술문서(README.md) 자동 생성",
        "✅ 웹 기반 사용자 인터페이스 제공",
        "✅ 멀티유저 및 권한 관리 시스템 구축",
        "✅ 실시간 모니터링 및 통계 시스템 구현"
    ])

    # Slide 5: 핵심 기능 1
    add_content_slide(prs, "핵심 기능 (1/4): 코드 업로드 & 분석", [
        "📁 소스 코드 파일 개별/폴더 재귀 업로드 지원",
        "📝 지원 파일 타입:",
        "   .java, .js, .jsx, .ts, .tsx, .vue, .py, .html, .css",
        "🤖 Claude API를 통한 자동 구조 분석",
        "💡 비즈니스 로직 기반 이해 및 분석",
        "",
        "구현 파일:",
        "• MainApiController.java",
        "• FileIoErrorHandler.java"
    ])

    # Slide 6: 핵심 기능 2
    add_content_slide(prs, "핵심 기능 (2/4): 맞춤형 한글 주석 생성", [
        "🎯 비즈니스 로직 기반 자동 주석",
        "📌 함수별 기능 명세 추가",
        "🔧 언어별 문법 준수",
        "   (Java: /,/**/; Python: #; JSX: {/**/) ",
        "💾 기존 주석 100% 보존 후 새 주석 추가",
        "📍 라인별 정확한 위치에 주석 삽입",
        "",
        "구현 파일:",
        "• ClaudeServiceImpl.java",
        "• PromptResolver.java"
    ])

    # Slide 7: 핵심 기능 3,4
    add_content_slide(prs, "핵심 기능 (3/4): README.md 자동 생성", [
        "📚 프로젝트 개요 및 목적 자동 작성",
        "📊 디렉터리 구조 자동 분석 및 시각화",
        "⚙️ 기술 스택 및 버전 정보 포함",
        "🚀 빌드 방법 및 실행 가이드 제공",
        "🔌 API 엔드포인트 명세 자동 생성",
        "📋 주석 품질 정책 및 유지보수 규칙 안내",
        "",
        "구현 파일:",
        "• MainApiController.java (README 생성 로직)"
    ])

    # Slide 8: 핵심 기능 4
    add_content_slide(prs, "핵심 기능 (4/4): 웹 인터페이스 & 다운로드", [
        "🌐 사용자 로그인 페이지 (auth.html)",
        "📄 주석 결과 확인 페이지 (index.html)",
        "👨‍💼 관리자 대시보드 (admin/dashboard.html)",
        "⚡ 실시간 진행 현황 표시 (SSE 스트리밍)",
        "💾 주석 완료 코드 다운로드",
        "📝 README.md 문서 다운로드",
        "📦 전체 분석 결과 압축 다운로드",
        "",
        "구현 파일:",
        "• index.html, auth.html, admin/dashboard.html"
    ])

    # Slide 9: 추가 기능 - 멀티유저 인증
    add_content_slide(prs, "추가 기능 Phase 1: JWT 기반 멀티유저 인증 시스템", [
        "👥 사용자 관리 (User 엔티티, 회원가입, 삭제)",
        "🔐 JWT 인증 (HMAC SHA256, 15분 유효기간)",
        "🎖️ 권한 제어 (ADMIN, USER 2가지 역할)",
        "🔒 보안 (BCrypt 암호화, @PreAuthorize)",
        "🏢 폐쇄 시스템 (관리자만 사용자 등록)",
        "",
        "구현 파일:",
        "• JwtTokenProvider.java, JwtAuthenticationFilter.java",
        "• SecurityConfig.java, AuthController.java"
    ])

    # Slide 10: 추가 기능 - 관리자 대시보드
    add_content_slide(prs, "추가 기능 Phase 2-6: 관리자 대시보드 & 고급 기능", [
        "📊 대시보드: 통계 카드 (사용자 수, 분석 횟수, 성공률)",
        "👥 사용자 관리: 추가, 활성화/비활성화, 삭제",
        "📈 분석 이력: 전체/사용자별 분석 결과 조회",
        "🎯 통계/보고서: 시스템 통계, 사용자별 통계",
        "📋 감사 로그: 모든 변경사항 기록 조회",
        "🔔 실시간 알림: 분석 완료/실패 알림",
        "💰 토큰 추적: API 사용량, 비용 계산"
    ])

    # Slide 11: 기술 스택
    add_content_slide(prs, "기술 스택", [
        "🎯 백엔드:",
        "Java 21, Spring Boot 3.2.5, Spring Security 6.x, Spring Data JPA,",
        "H2 Database, Gradle 8.x, JJWT 0.12.5",
        "",
        "🎨 프론트엔드:",
        "HTML5, CSS3 (Flexbox, Grid), Vanilla JavaScript (ES6+), EventSource API (SSE)",
        "",
        "🌐 외부 API:",
        "Claude API (Anthropic), REST API",
        "",
        "🔧 개발 도구:",
        "Git, GitHub, VS Code, IntelliJ IDEA, Maven CLI"
    ])

    # Slide 12: 시스템 아키텍처
    add_content_slide(prs, "시스템 아키텍처", [
        "🏗️ 3-계층 아키텍처 (Presentation → Business → Data)",
        "",
        "클라이언트 계층:",
        "  ├─ 로그인 페이지 (auth.html)",
        "  ├─ 분석 페이지 (index.html)",
        "  └─ 관리자 대시보드 (admin/dashboard.html)",
        "",
        "서버 계층:",
        "  ├─ Controller: 요청 처리",
        "  ├─ Service: 비즈니스 로직",
        "  └─ Repository: 데이터 접근",
        "",
        "데이터 계층:",
        "  └─ H2 Database (Users, AnalysisHistory, ApiUsage, ...)"
    ])

    # Slide 13: 주별 구현 - 1주차
    add_content_slide(prs, "주별 구현 현황 (1/4) - 1주차", [
        "📅 기간: 2026-06-01 ~ 2026-06-07",
        "🎯 목표: Spring Boot 환경 구축 & Claude API 연동 & 기본 UI 설계",
        "",
        "✅ 완료 항목:",
        "  ✓ Spring Boot 3.2.5 프로젝트 생성",
        "  ✓ application.properties 설정",
        "  ✓ WebClient 기반 HTTP 클라이언트 구현",
        "  ✓ index.html 웹 UI 프로토타입",
        "  ✓ POST /api/analyze 기본 엔드포인트",
        "  ✓ 파일 업로드 기본 구현",
        "",
        "완료도: ✅ 100%"
    ])

    # Slide 14: 주별 구현 - 2주차
    add_content_slide(prs, "주별 구현 현황 (2/4) - 2주차", [
        "📅 기간: 2026-06-08 ~ 2026-06-14",
        "🎯 목표: 핵심 로직 구현 & 프롬프트 설계 & 파싱 컴포넌트 개발",
        "",
        "✅ 완료 항목:",
        "  ✓ CLAUDE.md 프롬프트 템플릿 설계",
        "  ✓ 언어별 주석 문법 적용",
        "  ✓ Claude API 호출 로직 완성",
        "  ✓ JSON 응답 파싱",
        "  ✓ mergeCommentsIntoCode() 파싱 컴포넌트 개발",
        "  ✓ 재시도 로직 (RetryHandler) 구현",
        "  ✓ AnalysisHistory 엔티티 저장",
        "",
        "완료도: ✅ 100%"
    ])

    # Slide 15: 주별 구현 - 3주차
    add_content_slide(prs, "주별 구현 현황 (3/4) - 3주차", [
        "📅 기간: 2026-06-15 ~ 2026-06-21",
        "🎯 목표: 문서 생성 & 예외 처리 & SSE 스트리밍",
        "",
        "✅ 완료 항목:",
        "  ✓ README.md 자동 생성 로직",
        "  ✓ 대용량 파일 청킹 (500줄 단위)",
        "  ✓ 토큰 제한 예외 처리",
        "  ✓ 에러 핸들링 (ApiErrorHandler, FileIoErrorHandler)",
        "  ✓ 파일 다운로드 기능",
        "  ✓ SSE 스트리밍 (실시간 진행률 표시)",
        "  ✓ Phase 1-6: 멀티유저, 대시보드, 통계, 감사로그, 알림 구현",
        "",
        "완료도: ✅ 100% + 추가 기능"
    ])

    # Slide 16: 주별 구현 - 4주차
    add_content_slide(prs, "주별 구현 현황 (4/4) - 4주차", [
        "📅 기간: 2026-06-22 ~ 2026-06-30 (예정)",
        "🎯 목표: 전체 기능 통합 & 최종 테스트 & 결과보고서 작성",
        "",
        "✅ 완료 항목:",
        "  ✓ 전체 기능 통합 테스트",
        "  ✓ 실제 코드 분석 테스트 (Java, Python, JavaScript)",
        "  ✓ 주석 정확성 검증",
        "  ✓ README 생성 결과 확인",
        "  ✓ 파일 다운로드 기능 검증",
        "  ✓ 보너스 기능 통합",
        "  ✓ Gradle 컴파일 최종 검증",
        "  ✓ 최종 결과보고서 작성",
        "",
        "완료도: ✅ 95% (최종 문서화 진행중)"
    ])

    # Slide 17: 주요 성과 - 기능 완성도
    add_content_slide(prs, "주요 성과 - 기능 완성도", [
        "필수 기능:",
        "  ✅ 코드 업로드 및 분석 (개별/폴더 모두 지원)",
        "  ✅ 한글 주석 생성 (언어별 문법 준수)",
        "  ✅ README 자동 생성 (표준 마크다운 포맷)",
        "  ✅ 웹 인터페이스 (사용자 친화적 UI)",
        "  ✅ 파일 다운로드 (코드 + 문서)",
        "",
        "추가 기능:",
        "  ✅ 멀티유저 인증 (JWT 기반)",
        "  ✅ 관리자 대시보드 (6개 탭)",
        "  ✅ API 사용량 추적 (자동 기록)",
        "  ✅ 통계 시스템 (다양한 관점)",
        "  ✅ 감사 로그 (모든 변경 기록)",
        "  ✅ 실시간 알림 (웹소켓 기반)",
        "  ✅ 토큰 추적 (비용 계산 포함)"
    ])

    # Slide 18: 주요 성과 - 기술적 성과
    add_content_slide(prs, "주요 성과 - 기술적 성과", [
        "📊 코드 규모:",
        "  • 총 라인 수: 약 10,000 줄",
        "  • Java 클래스: 50개+",
        "  • HTML 페이지: 3개",
        "  • JavaScript 파일: 2개+",
        "",
        "🏗️ 아키텍처 고도화:",
        "  ✅ 계층화 구조 (Controller → Service → Repository)",
        "  ✅ DTO 패턴 적용",
        "  ✅ 의존성 주입 (Spring DI)",
        "  ✅ ThreadLocal 기반 토큰 추적",
        "  ✅ SSE 스트리밍",
        "  ✅ JWT 토큰 기반 인증"
    ])

    # Slide 19: 주요 성과 - 개발 효율성
    add_content_slide(prs, "주요 성과 - 개발 효율성", [
        "⏱️ 개발 기간: 계획 30일 → 실제 16일 (53% 조기 완료) ✅",
        "",
        "📈 성과 지표:",
        "  • 필수 기능: 4개/4개 (100% 완료) ✅",
        "  • 추가 기능: 0개 예정 → 6개 Phase 추가 (보너스) 🎁",
        "  • API 엔드포인트: ~10개 예정 → 50+개 (5배) 📈",
        "  • 엔티티: ~3개 예정 → 10+개 (3배) 📈",
        "  • 일정 단축: 46% 선행 달성 ⏱️",
        "",
        "🎯 최종 평가: 150% (계획 초과 달성) ✨"
    ])

    # Slide 20: 기대 효과 - 정량적 효과
    add_content_slide(prs, "기대 효과 - 작업 시간 단축", [
        "📊 기존 방식 (수동):",
        "  • 주석 작성: 2~4시간/파일 × 50개 = 200시간",
        "  • README 작성: 16시간",
        "  • 검토 및 수정: 16시간",
        "  • 합계: 232시간",
        "",
        "🤖 본 시스템 (자동화):",
        "  • 파일 업로드: 5분/파일 × 50개 = 4시간",
        "  • 주석 생성: 자동 = 0.5시간",
        "  • README 생성: 자동 = 0.5시간",
        "  • 검토: 2시간",
        "  • 합계: 6.5시간",
        "",
        "⭐ 시간 단축: 232 - 6.5 = 225.5시간 (97% 단축) ✨",
        "💰 비용 절감: 225시간 × 60,000원 = 1,350만원"
    ])

    # Slide 21: 기대 효과 - 생산성 향상
    add_content_slide(prs, "기대 효과 - 생산성 및 에러 감소", [
        "👥 생산성 향상:",
        "  • 월간 프로젝트 주석화 작업: 5 → 50개 (10배 증가) 🚀",
        "  • 인수인계 준비 기간: 10일 → 1일 (90% 단축)",
        "  • 유지보수 디버깅 시간: 30% 감소",
        "",
        "🛡️ 에러 감소:",
        "  • 부정확한 주석으로 인한 오류: 50~100건 → 5건 (90% 감소)",
        "  • 누락된 문서로 인한 장애: 20건 → 0건 (100% 해소)",
        "  • 버전 불일치 오류: 10건 → 0건 (자동 동기화)",
        "",
        "📈 추정 효과: ROI 1,000% 이상"
    ])

    # Slide 22: 기대 효과 - 정성적 효과
    add_content_slide(prs, "기대 효과 - 정성적 효과", [
        "🎯 개발 생산성 극대화:",
        "  ✅ 개발자가 코드 작성에만 집중",
        "  ✅ 프로젝트 철수/인수인계 시간 대폭 단축",
        "  ✅ 팀 전체의 코드 품질 기준 일관성 확보",
        "",
        "📚 코드 가독성 향상:",
        "  ✅ 비즈니스 로직 기반 체계적인 주석",
        "  ✅ 함수별 명확한 기능 설명",
        "  ✅ 후임자가 빠르게 시스템 파악 가능",
        "  ✅ 유지보수 난이도 50% 감소",
        "",
        "🔐 리스크 관리:",
        "  ✅ 모든 변경사항 감시 (감사 로그)",
        "  ✅ 권한 기반 접근 제어",
        "  ✅ 실시간 모니터링 및 알림"
    ])

    # Slide 23: 기술 혁신 - AI 기반 자동화
    add_content_slide(prs, "기술 혁신 - AI 기반 자동화", [
        "🤖 Claude API 활용:",
        "  ✅ 자연언어 기반 코드 분석",
        "  ✅ 비즈니스 로직 이해 및 설명",
        "  ✅ 문맥을 고려한 주석 생성",
        "  ✅ 표준 마크다운 문서 자동 생성",
        "",
        "⚡ LLM 활용 패턴:",
        "  • Prompt Engineering: 비즈니스 로직 기반 주석 지침 설계",
        "  • Context Windowing: 대용량 파일 청킹으로 토큰 제한 극복",
        "  • Token Usage Tracking: API 호출 비용 최적화",
        "  • Error Classification: 에러 분류 기반 자동 재시도"
    ])

    # Slide 24: 기술 혁신 - 웹 기술 & 보안
    add_content_slide(prs, "기술 혁신 - 웹 기술 & 보안", [
        "🌐 웹 기술 활용:",
        "  ✅ SSE (Server-Sent Event): 단방향 푸시 스트리밍",
        "  ✅ 비동기 파일 처리: 대용량 파일 논블로킹 처리",
        "  ✅ 실시간 진행률 표시: 사용자 경험 향상",
        "  ✅ RESTful API 설계",
        "",
        "🔐 보안 강화:",
        "  ✅ JWT 토큰 기반 상태비저장 인증",
        "  ✅ 역할 기반 접근 제어 (RBAC)",
        "  ✅ BCrypt 암호화",
        "  ✅ 감사 로그: 모든 변경사항 기록",
        "  ✅ IP 주소 추적: 접근 원천 파악",
        "  ✅ 실시간 알림: 의심 활동 즉시 통보"
    ])

    # Slide 25: 향후 개선 사항
    add_content_slide(prs, "향후 개선 사항", [
        "⏰ 단기 개선 (1개월 내):",
        "  • 토큰/비용 UI 시각화",
        "  • JUnit 기반 자동화 테스트",
        "  • Swagger/OpenAPI 명세",
        "  • 모바일 UI 반응형 개선",
        "",
        "📅 중기 개선 (3개월):",
        "  • 다중 모델 지원 (GPT, Gemini 등)",
        "  • 고급 필터링 기능",
        "  • 성능 최적화 (캐싱, 인덱싱)",
        "",
        "🚀 장기 개선 (6개월):",
        "  • 클라우드 배포 (AWS/GCP/Azure)",
        "  • 모니터링 대시보드 (Grafana/Datadog)",
        "  • 엔터프라이즈 기능 (LDAP, SAML, SSO)",
        "  • 다국어 지원 (국제화)"
    ])

    # Slide 26: 결론 - 프로젝트 평가
    add_content_slide(prs, "결론 - 프로젝트 평가", [
        "🎯 계획 달성도: 150% ✅",
        "  • 필수 기능: 100% 달성",
        "  • 추가 기능: 6개 Phase 초과 달성",
        "  • 개발 일정: 53% 조기 완료",
        "",
        "📊 기대 효과 (요약):",
        "  • 📉 작업 시간: 97% 단축 (232시간 → 6.5시간)",
        "  • 💰 비용 절감: 1,350만원",
        "  • 👥 생산성: 10배 증대 (5 → 50 프로젝트/월)",
        "  • 🛡️ 에러 감소: 90% 이상",
        "",
        "🏆 최종 평가:",
        "✨ 계획된 모든 요구사항을 완벽히 충족 + 엔터프라이즈급 기능 추가"
    ])

    # Slide 27: 결론 - 핵심 성과
    add_content_slide(prs, "결론 - 핵심 성과", [
        "1️⃣ 자동화된 주석 생성",
        "   → 개발자의 문서화 부담 90% 해소",
        "",
        "2️⃣ 자동화된 README 생성",
        "   → 기술 문서 작성 시간 95% 단축",
        "",
        "3️⃣ 엔터프라이즈급 플랫폼으로 진화",
        "   → 멀티유저, 권한 관리, 통계, 감시 기능",
        "",
        "4️⃣ AI 기반 지능형 시스템",
        "   → Claude API 기반 고도의 자동화",
        "",
        "5️⃣ 프로덕션 수준의 코드 품질",
        "   → Spring Boot 표준 아키텍처 준수",
        "",
        "✨ 결과: 10,000줄 50개+ Java 클래스 완성도 높은 시스템"
    ])

    # Slide 28: 결론 - 최종 평가
    add_content_slide(prs, "결론 - 최종 평가", [
        "🎯 프로젝트 결과:",
        "",
        "본 프로젝트는 계획된 모든 요구사항을 완벽히 충족했으며,",
        "이를 넘어 엔터프라이즈급 기능까지 추가하여",
        "프로덕션 수준의 완성도 높은 시스템으로 개발되었습니다.",
        "",
        "✅ 개발 생산성 극대화 (작업 시간 97% 단축)",
        "✅ 코드 품질 향상 (일관된 주석, 체계적 문서)",
        "✅ 리스크 감소 (감시, 추적, 감사 로그)",
        "✅ 팀 협업 강화 (멀티유저, 권한, 통계)",
        "✅ 유지보수성 증대 (명확한 구조, 상세한 문서)"
    ])

    # Slide 29: 감사합니다
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    background = slide.background
    fill = background.fill
    fill.solid()
    fill.fore_color.rgb = RGBColor(25, 118, 210)

    title_box = slide.shapes.add_textbox(Inches(1), Inches(2.5), Inches(8), Inches(2))
    title_frame = title_box.text_frame
    title_frame.word_wrap = True
    title_p = title_frame.paragraphs[0]
    title_p.text = "감사합니다!"
    title_p.font.size = Pt(66)
    title_p.font.bold = True
    title_p.font.color.rgb = RGBColor(255, 255, 255)
    title_p.alignment = PP_ALIGN.CENTER

    subtitle_box = slide.shapes.add_textbox(Inches(1), Inches(4.5), Inches(8), Inches(1.5))
    subtitle_frame = subtitle_box.text_frame
    subtitle_frame.word_wrap = True
    sub_p = subtitle_frame.paragraphs[0]
    sub_p.text = "JAVA Spring Boot 기반 Claude 레거시 코드 분석 및 자동 문서화 시스템\n프로젝트 완료"
    sub_p.font.size = Pt(24)
    sub_p.font.color.rgb = RGBColor(255, 255, 255)
    sub_p.alignment = PP_ALIGN.CENTER

    # 저장
    prs.save("FINAL_PROJECT_REPORT_Presentation.pptx")
    print("✅ PowerPoint 프레젠테이션이 생성되었습니다!")
    print("📁 파일명: FINAL_PROJECT_REPORT_Presentation.pptx")
    print("📍 위치: C:\\project\\legacy-analyzer\\")

if __name__ == "__main__":
    create_presentation()
