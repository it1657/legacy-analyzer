# PowerPoint COM을 사용하여 PPTX 파일 생성
# Microsoft Office (PowerPoint)가 필요합니다.

Add-Type -AssemblyName "Microsoft.Office.Interop.PowerPoint"

function Add-Title-Slide {
    param($presentation, $title, $subtitle)

    $slide = $presentation.Slides.Add(1, [Microsoft.Office.Interop.PowerPoint.PpSlideLayout]::ppLayoutTitleSlide)

    # 제목 설정
    $slide.Shapes[0].TextFrame.TextRange.Text = $title
    $slide.Shapes[0].TextFrame.TextRange.Font.Size = 54
    $slide.Shapes[0].TextFrame.TextRange.Font.Bold = $true

    # 부제 설정
    $slide.Shapes[1].TextFrame.TextRange.Text = $subtitle
    $slide.Shapes[1].TextFrame.TextRange.Font.Size = 28

    return $slide
}

function Add-Content-Slide {
    param($presentation, $title, $content)

    $slide = $presentation.Slides.Add(
        $presentation.Slides.Count + 1,
        [Microsoft.Office.Interop.PowerPoint.PpSlideLayout]::ppLayoutBulletPoints
    )

    # 제목 설정
    $slide.Shapes[0].TextFrame.TextRange.Text = $title
    $slide.Shapes[0].TextFrame.TextRange.Font.Size = 40
    $slide.Shapes[0].TextFrame.TextRange.Font.Bold = $true

    # 본문 설정
    if ($slide.Shapes.Count -gt 1) {
        $textFrame = $slide.Shapes[1].TextFrame
        $textFrame.TextRange.Text = ""

        for ($i = 0; $i -lt $content.Count; $i++) {
            if ($i -gt 0) {
                $textFrame.Paragraphs($textFrame.Paragraphs.Count).Text = $content[$i]
            } else {
                $textFrame.TextRange.Text = $content[0]
            }
        }
    }

    return $slide
}

try {
    Write-Host "🚀 PowerPoint COM을 사용하여 프레젠테이션 생성 시작..."

    # PowerPoint 애플리케이션 생성
    $pptx = New-Object -ComObject PowerPoint.Application

    # 새 프레젠테이션 생성
    $prs = $pptx.Presentations.Add()

    Write-Host "✅ PowerPoint 프레젠테이션 생성 완료"

    # Slide 1: 타이틀
    Write-Host "📝 슬라이드 1 추가: 타이틀"
    Add-Title-Slide $prs "📋 FINAL PROJECT REPORT" `
        "JAVA Spring Boot 기반 Claude 레거시 코드 분석 및 자동 문서화 시스템"

    # Slide 2: 기본 정보
    Write-Host "📝 슬라이드 2 추가: 프로젝트 기본 정보"
    Add-Content-Slide $prs "프로젝트 기본 정보" @(
        "🎯 프로젝트명: JAVA Spring Boot 기반 Claude 기반 레거시 코드 분석 및 기술문서/주석 자동 생성 시스템",
        "👤 작성자: 정재훈",
        "📅 개발 기간: 2026-06-01 ~ 2026-06-30 (30일)",
        "⚡ 실제 완료: 2026-06-17 (16일, 조기 완료)",
        "✅ 완료도: 150% (계획 초과 달성)",
        "💻 개발 환경: Windows 10 Pro, Java 21, Spring Boot 3.2.5",
        "🗄️ 데이터베이스: H2 (개발/테스트용)"
    )

    # Slide 3: 해결하려는 문제
    Write-Host "📝 슬라이드 3 추가: 개발 배경"
    Add-Content-Slide $prs "개발 배경: 해결하려는 문제" @(
        "❌ 프로젝트 철수 및 인수인계 시 기존 소스코드에 주석 부실",
        "❌ 기술문서(README.md) 부재로 후임자가 시스템 구조 파악에 막대한 시간 소비",
        "❌ 바쁜 개발 일정 속에서 개발자가 상세한 주석 및 문서를 작성하기 어려운 현실",
        "❌ 유지보수 과정에서 휴먼 에러 발생 가능성 증대"
    )

    # Slide 4: 프로젝트 목표
    Write-Host "📝 슬라이드 4 추가: 프로젝트 목표"
    Add-Content-Slide $prs "프로젝트 목표" @(
        "🎯 최종 목표: 인수인계 및 문서화 시간을 80% 이상 단축",
        "✅ Claude AI를 활용한 자동 주석 생성",
        "✅ 표준 마크다운 기술문서(README.md) 자동 생성",
        "✅ 웹 기반 사용자 인터페이스 제공",
        "✅ 멀티유저 및 권한 관리 시스템 구축",
        "✅ 실시간 모니터링 및 통계 시스템 구현"
    )

    # Slide 5: 핵심 기능
    Write-Host "📝 슬라이드 5 추가: 핵심 기능"
    Add-Content-Slide $prs "핵심 기능 - 코드 업로드 및 분석" @(
        "📁 소스 코드 파일 개별/폴더 재귀 업로드 지원",
        "📝 지원 파일 타입: .java, .js, .jsx, .ts, .tsx, .vue, .py, .html, .css",
        "🤖 Claude API를 통한 자동 구조 분석",
        "💡 비즈니스 로직 기반 이해 및 분석"
    )

    # Slide 6: 주석 생성
    Write-Host "📝 슬라이드 6 추가: 맞춤형 한글 주석 생성"
    Add-Content-Slide $prs "핵심 기능 - 맞춤형 한글 주석 생성" @(
        "🎯 비즈니스 로직 기반 자동 주석",
        "📌 함수별 기능 명세 추가",
        "🔧 언어별 문법 준수 (Java/Python/JSX)",
        "💾 기존 주석 100% 보존 후 새 주석 추가",
        "📍 라인별 정확한 위치에 주석 삽입"
    )

    # Slide 7: README 생성
    Write-Host "📝 슬라이드 7 추가: README 자동 생성"
    Add-Content-Slide $prs "핵심 기능 - README.md 자동 생성" @(
        "📚 프로젝트 개요 및 목적 자동 작성",
        "📊 디렉터리 구조 자동 분석 및 시각화",
        "⚙️ 기술 스택 및 버전 정보 포함",
        "🚀 빌드 방법 및 실행 가이드 제공",
        "🔌 API 엔드포인트 명세 자동 생성"
    )

    # Slide 8: 웹 인터페이스
    Write-Host "📝 슬라이드 8 추가: 웹 인터페이스"
    Add-Content-Slide $prs "핵심 기능 - 웹 인터페이스 & 다운로드" @(
        "🌐 사용자 로그인 페이지 (auth.html)",
        "📄 주석 결과 확인 페이지 (index.html)",
        "👨‍💼 관리자 대시보드 (admin/dashboard.html)",
        "⚡ 실시간 진행 현황 표시 (SSE 스트리밍)",
        "💾 코드/문서/전체 분석 결과 다운로드"
    )

    # Slide 9: 추가 기능
    Write-Host "📝 슬라이드 9 추가: 추가 기능"
    Add-Content-Slide $prs "추가 기능 Phase 1-6" @(
        "🔐 Phase 1: JWT 멀티유저 인증 (BCrypt 암호화)",
        "👨‍💼 Phase 2: 관리자 대시보드 (6개 탭)",
        "📊 Phase 3: API 사용량 추적",
        "📈 Phase 4: 통계 & 보고서 시스템",
        "📋 Phase 5: 감사 로그 (IP 추적)",
        "🔔 Phase 6: 실시간 알림 (WebSocket)"
    )

    # Slide 10: 기술 스택
    Write-Host "📝 슬라이드 10 추가: 기술 스택"
    Add-Content-Slide $prs "기술 스택" @(
        "🎯 백엔드: Java 21, Spring Boot 3.2.5, Spring Security 6.x, H2 Database, Gradle",
        "🎨 프론트엔드: HTML5, CSS3, Vanilla JavaScript, EventSource API (SSE)",
        "🌐 외부 API: Claude API (Anthropic), REST API",
        "🔧 개발 도구: Git, GitHub, VS Code, IntelliJ IDEA"
    )

    # Slide 11: 주별 구현 현황
    Write-Host "📝 슬라이드 11 추가: 주별 구현 현황"
    Add-Content-Slide $prs "주별 구현 현황" @(
        "1주 (06-01~07): 환경 구축 & UI 프로토타입 - 100% 완료",
        "2주 (06-08~14): 핵심 로직 구현 - 100% 완료",
        "3주 (06-15~21): 문서 생성 & 예외 처리 - 100% 완료",
        "4주 (06-22~30): 통합 테스트 & 최종화 - 95% (문서화 진행중)"
    )

    # Slide 12: 기능 완성도
    Write-Host "📝 슬라이드 12 추가: 기능 완성도"
    Add-Content-Slide $prs "주요 성과 - 기능 완성도" @(
        "필수 기능 (100% 달성):",
        "  ✅ 코드 업로드 및 분석",
        "  ✅ 한글 주석 생성",
        "  ✅ README 자동 생성",
        "  ✅ 웹 인터페이스 & 파일 다운로드",
        "추가 기능: 6개 Phase 초과 달성"
    )

    # Slide 13: 개발 효율성
    Write-Host "📝 슬라이드 13 추가: 개발 효율성"
    Add-Content-Slide $prs "주요 성과 - 개발 효율성" @(
        "📊 개발 기간: 계획 30일 → 실제 16일 (53% 조기 완료)",
        "✅ 필수 기능: 4개/4개 (100% 완료)",
        "🎁 추가 기능: 0개 예정 → 6개 Phase (보너스)",
        "📈 API 엔드포인트: ~10개 예정 → 50+개 (5배 증가)",
        "🏆 최종 평가: 150% (계획 초과 달성)"
    )

    # Slide 14: 시간 단축 효과
    Write-Host "📝 슬라이드 14 추가: 시간 단축 효과"
    Add-Content-Slide $prs "기대 효과 - 작업 시간 단축" @(
        "📊 기존 방식: 232시간 (주석 200h + README 16h + 검토 16h)",
        "🤖 자동화: 6.5시간 (업로드 4h + 자동 생성 1h + 검토 2h)",
        "⭐ 시간 단축: 225.5시간 (97% 단축)",
        "💰 비용 절감: 1,350만원"
    )

    # Slide 15: 생산성 향상
    Write-Host "📝 슬라이드 15 추가: 생산성 향상"
    Add-Content-Slide $prs "기대 효과 - 생산성 & 에러 감소" @(
        "👥 생산성 향상:",
        "  • 월간 프로젝트: 5 → 50개 (10배 증대)",
        "  • 인수인계 기간: 10일 → 1일",
        "🛡️ 에러 감소: 90% 이상",
        "📈 ROI: 1,000% 이상"
    )

    # Slide 16: 기술 혁신
    Write-Host "📝 슬라이드 16 추가: 기술 혁신"
    Add-Content-Slide $prs "기술 혁신" @(
        "🤖 AI 기반 자동화: Claude API 기반 자연언어 코드 분석",
        "🌐 웹 기술: SSE 스트리밍, 비동기 파일 처리, 실시간 진행률",
        "🔐 보안: JWT 토큰, 역할 기반 접근 제어, 감사 로그",
        "📊 모니터링: 토큰 사용량 추적, 비용 계산, 실시간 알림"
    )

    # Slide 17: 향후 개선
    Write-Host "📝 슬라이드 17 추가: 향후 개선 사항"
    Add-Content-Slide $prs "향후 개선 사항" @(
        "⏰ 단기 (1개월): 토큰 UI, JUnit 테스트, Swagger 문서, 모바일 UI",
        "📅 중기 (3개월): 다중 모델 지원, 고급 필터링, 성능 최적화",
        "🚀 장기 (6개월): 클라우드 배포, ML 최적화, 엔터프라이즈 기능, 국제화"
    )

    # Slide 18: 핵심 성과
    Write-Host "📝 슬라이드 18 추가: 핵심 성과"
    Add-Content-Slide $prs "핵심 성과" @(
        "1️⃣ 자동화된 주석 생성 → 개발자의 부담 90% 해소",
        "2️⃣ 자동화된 README 생성 → 문서 작성 시간 95% 단축",
        "3️⃣ 엔터프라이즈급 플랫폼 진화 → 멀티유저, 권한, 통계",
        "4️⃣ AI 기반 지능형 시스템 → Claude API 기반 자동화",
        "5️⃣ 프로덕션 수준의 코드 → Spring Boot 표준 아키텍처"
    )

    # Slide 19: 최종 평가
    Write-Host "📝 슬라이드 19 추가: 최종 평가"
    Add-Content-Slide $prs "최종 평가" @(
        "✅ 계획된 모든 요구사항 완벽히 충족",
        "✅ 엔터프라이즈급 기능 추가",
        "✅ 프로덕션 수준의 완성도 높은 시스템",
        "✨ 150% 달성도 - 계획 초과 달성"
    )

    # Slide 20: 결과물
    Write-Host "📝 슬라이드 20 추가: 결과물"
    Add-Content-Slide $prs "결과물" @(
        "📊 코드 규모: 10,000줄, 50개+ Java 클래스, 3개 HTML, 2개+ JS",
        "🏗️ 아키텍처: 계층화 구조, DTO 패턴, Spring DI",
        "🔧 기능: 50+개 API, 10+개 데이터 모델, 6개 탭 대시보드",
        "🌐 인터페이스: 완전한 웹 기반 UI, Claude AI 자동화"
    )

    # Slide 21: 핵심 가치
    Write-Host "📝 슬라이드 21 추가: 핵심 가치"
    Add-Content-Slide $prs "핵심 가치" @(
        "✅ 개발 생산성 극대화 (시간 97% 단축)",
        "✅ 코드 품질 향상 (일관된 주석, 체계적 문서)",
        "✅ 리스크 감소 (감시, 추적, 감사 로그)",
        "✅ 팀 협업 강화 (멀티유저, 권한, 통계)",
        "✅ 유지보수성 증대 (명확한 구조, 상세 문서)"
    )

    # Slide 22: 마무리
    Write-Host "📝 슬라이드 22 추가: 감사합니다"
    Add-Title-Slide $prs "감사합니다!" `
        "JAVA Spring Boot 기반 Claude 레거시 코드 분석 및 자동 문서화 시스템 | 프로젝트 완료"

    # 파일 저장
    Write-Host "💾 파일 저장 중..."
    $outputPath = "C:\project\legacy-analyzer\FINAL_PROJECT_REPORT_Presentation.pptx"
    $prs.SaveAs($outputPath, [Microsoft.Office.Interop.PowerPoint.PpSaveAsFileType]::ppSaveAsDefault)

    Write-Host "✅ PowerPoint 프레젠테이션이 생성되었습니다!"
    Write-Host "📁 파일명: FINAL_PROJECT_REPORT_Presentation.pptx"
    Write-Host "📍 위치: $outputPath"
    Write-Host "📊 총 슬라이드: 22개"

    # 리소스 정리
    $prs.Close()
    $pptx.Quit()
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($prs)
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($pptx)

    Write-Host "`n🎉 작업 완료!"
}
catch {
    Write-Host "❌ 오류 발생: $_"
    Write-Host ""
    Write-Host "💡 해결 방법:"
    Write-Host "1. Microsoft PowerPoint가 설치되어 있는지 확인"
    Write-Host "2. PowerShell을 관리자 권한으로 실행"
    Write-Host "3. HTML 버전 사용: FINAL_PROJECT_REPORT_Presentation.html"
}
