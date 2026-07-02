# 📚 프로젝트 문서

프로젝트 관련 모든 문서 및 리소스를 관리하는 디렉터리입니다.

## 📁 구조

```
docs/
├── presentations/     ← 프레젠테이션 리소스
│   └── FINAL_PROJECT_REPORT_Presentation.html
├── guides/           ← 사용 가이드 및 튜토리얼
│   └── PowerPoint_변환가이드.md
├── technical/        ← 기술 문서 및 명세
│   ├── ANALYSIS_METRICS_DB_SCHEMA.md
│   └── TOKEN_EXTRACTION_IMPLEMENTATION.md
└── README.md         ← 이 파일
```

---

## 📊 presentations/ - 프레젠테이션 리소스

### FINAL_PROJECT_REPORT_Presentation.html
- **형식**: HTML (Reveal.js 기반 인터랙티브 프레젠테이션)
- **슬라이드**: 22개
- **특징**: 
  - 모든 웹 브라우저에서 즉시 열 수 있음
  - 키보드 네비게이션 지원 (스페이스바, 화살표)
  - 전체 화면 모드 지원 (F 키)
  - 프린트 기능 지원 (Ctrl+P)

#### 사용 방법:
```bash
# 웹 브라우저에서 열기
1. 파일 탐색기 → HTML 파일 더블클릭
2. 또는 브라우저에서 Ctrl+O로 파일 열기
3. 스페이스바로 슬라이드 이동
```

---

## 📖 guides/ - 사용 가이드

### PowerPoint_변환가이드.md
- **대상**: 기술 사용자, 개발자
- **내용**:
  - HTML → PowerPoint 변환 방법 (3가지)
  - Microsoft PowerPoint 직접 변환
  - LibreOffice Impress 사용
  - Google Slides 온라인 변환
  - Python 자동화 스크립트

#### 주요 내용:
```markdown
- PowerPoint 형식 변환 방법
- 각 방법별 장단점 비교
- 단계별 변환 프로세스
- 문제 해결 팁
```

---

## 🔧 technical/ - 기술 문서

### ANALYSIS_METRICS_DB_SCHEMA.md
- **목적**: Claude API 토큰 메트릭 데이터베이스 설계
- **대상**: 개발자, 시스템 아키텍트
- **주요 내용**:
  - TokenUsage 엔티티 구조
  - 토큰 추적 메커니즘
  - 비용 계산 로직
  - DB 스키마 설계

### TOKEN_EXTRACTION_IMPLEMENTATION.md
- **목적**: Claude API 토큰 추출 구현 상세 문서
- **대상**: 백엔드 개발자
- **주요 내용**:
  - API 응답 파싱 방식
  - 토큰 사용량 계산
  - 모델별 요금 적용
  - 구현 코드 예시

---

## 📌 문서 사용 가이드

### 프로젝트 이해
1. **docs/README.md** 읽기 → 문서 디렉터리 전체 개요
2. **HTML 프레젠테이션** 보기 → 시각적 이해

### 기술 심화
1. **technical/** 문서 읽기 → 시스템 설계
2. **guides/** 문서 읽기 → 사용 방법
3. **scripts/** 코드 분석 → 구현 방식

### 관리자 학습
1. HTML 프레젠테이션 → 프로젝트 현황 파악
2. PowerPoint 변환 가이드 → PPT 생성 방법
3. 기술 문서 → 시스템 심화 이해

---

## 🔗 문서 간 연관성

```
docs/README.md (문서 인덱스)
    │
    ├─→ presentations/ (시각화)
    │   └─→ FINAL_PROJECT_REPORT_Presentation.html
    │
    ├─→ guides/ (사용 방법)
    │   └─→ PowerPoint_변환가이드.md
    │
    └─→ technical/ (기술 심화)
        ├─→ ANALYSIS_METRICS_DB_SCHEMA.md
        └─→ TOKEN_EXTRACTION_IMPLEMENTATION.md
```

---

## 📖 관련 링크

| 문서 | 위치 | 설명 |
|------|------|------|
| 문서 인덱스 | docs/ | 문서 디렉터리 전체 안내 |
| 스크립트 가이드 | scripts/ | 자동화 스크립트 사용법 |
| HTML 프레젠테이션 | docs/presentations/ | 웹 기반 슬라이드 |
| 변환 가이드 | docs/guides/ | 포맷 변환 방법 |
| DB 설계 | docs/technical/ | 데이터베이스 명세 |
| 토큰 구현 | docs/technical/ | API 연동 상세 |

---

## 💾 파일 목록

### presentations/ (프레젠테이션)
- `FINAL_PROJECT_REPORT_Presentation.html` (27KB)
  - 22개 슬라이드
  - 전문적 디자인
  - 대화형 네비게이션

### guides/ (가이드)
- `PowerPoint_변환가이드.md` (5KB)
  - 3가지 변환 방법
  - 단계별 설명
  - 트러블슈팅

### technical/ (기술 문서)
- `ANALYSIS_METRICS_DB_SCHEMA.md` (8KB)
  - 토큰 메트릭 설계
  - DB 스키마
  
- `TOKEN_EXTRACTION_IMPLEMENTATION.md` (6KB)
  - 토큰 추출 로직
  - 비용 계산

---

## 🎯 자주 묻는 질문

**Q: 어느 문서부터 읽어야 하나?**
```
A: 프로젝트에 처음 온 경우
1. docs/README.md (이 파일)
2. HTML 프레젠테이션 보기
3. docs/technical/ 기술 문서 확인
```

**Q: PPT는 어떻게 얻나?**
```
A: 3가지 방법
1. 웹 관리자 대시보드 → "PPT 다운로드" 버튼
2. scripts/pptx/ 스크립트 실행
3. docs/guides/PowerPoint_변환가이드.md 참고
```

**Q: 기술 상세는 어디 있나?**
```
A: docs/technical/ 디렉터리
- 데이터베이스 설계
- API 연동 상세
- 토큰 추적 구현
```

---

## 📝 문서 유지보수

- **마지막 업데이트**: 2026-06-19
- **작성자**: 정재훈
- **관리자**: 개발팀

---

**💡 팁:** 각 문서의 상단에 목차(Table of Contents)가 있으니 빠르게 찾고 싶은 부분을 탐색할 수 있습니다.
