# 📜 프로젝트 스크립트

이 디렉터리는 프로젝트 자동화 및 유틸리티 스크립트를 포함합니다.

## 📁 구조

```
scripts/
├── pptx/          ← PowerPoint 프레젠테이션 생성 스크립트
├── README.md      ← 이 파일
└── ...
```

---

## 🎬 PPTX 생성 스크립트 (scripts/pptx/)

### 📌 개요

최종 프로젝트 보고서를 PowerPoint PPTX 형식으로 자동 생성합니다.

### 📄 스크립트 목록

| 파일명 | 언어 | 설명 | 사용 환경 |
|--------|------|------|----------|
| `create_presentation.py` | Python | Apache POI 기반 PPTX 생성 | Linux/Mac/Windows (Python 필요) |
| `create_pptx.ps1` | PowerShell | XML 기반 PPTX 구조 생성 | Windows (PowerShell) |
| `create_pptx_com.ps1` | PowerShell | COM을 통한 PowerPoint 제어 | Windows (PowerPoint 필수) |

---

## 🚀 사용 방법

### 1️⃣ Python 스크립트 사용 (권장)

#### 필수 요구사항:
```bash
pip install python-pptx
```

#### 실행:
```bash
cd scripts/pptx
python create_presentation.py
```

#### 결과:
```
FINAL_PROJECT_REPORT_Presentation.pptx
```

---

### 2️⃣ PowerShell COM 스크립트 사용

#### 필수 요구사항:
- Windows 환경
- Microsoft PowerPoint 설치
- PowerShell 5.1 이상

#### 실행:
```powershell
cd scripts\pptx
Set-ExecutionPolicy -ExecutionPolicy Bypass -Scope Process -Force
.\create_pptx_com.ps1
```

#### 결과:
```
C:\project\legacy-analyzer\FINAL_PROJECT_REPORT_Presentation.pptx
```

---

### 3️⃣ 웹 기반 다운로드 (권장)

**현재 권장 방법:**

1. 애플리케이션 시작
   ```bash
   ./gradlew bootRun
   ```

2. 관리자 대시보드 접속
   ```
   http://localhost:8803/admin/dashboard
   ```

3. 로그인
   ```
   ID: admin
   PW: admin
   ```

4. navbar의 "📊 PPT 다운로드" 버튼 클릭
5. PPTX 자동 다운로드

---

## 📊 프레젠테이션 내용

생성되는 PPTX는 다음을 포함합니다:

- **타이틀 슬라이드**: 프로젝트명, 작성자
- **기본 정보**: 프로젝트 개요, 개발 기간, 완료도
- **배경 & 목표**: 문제점, 해결 방안, 목표
- **핵심 기능**: 4가지 주요 기능
- **추가 기능**: Phase 1-6
- **기술 스택**: 사용된 기술들
- **개발 현황**: 주별 진행률
- **성과**: 효율성, 시간 단축, 생산성
- **기술 혁신**: 주요 기술 업적
- **향후 개선**: 단/중/장기 계획
- **최종 평가**: 완료도, 핵심 가치

---

## 💡 팁

### 스크립트 선택 기준

| 상황 | 추천 |
|------|------|
| 자동화, 서버 배포 | ✅ **웹 기반 다운로드** |
| 로컬 개발 환경 | ✅ Python 또는 PowerShell |
| PowerPoint 없음 | ✅ Python (가장 간단) |
| PowerPoint 있음 | ✅ PowerShell COM |

### 문제 해결

**Python 설치 안됨?**
```bash
# Windows
python --version

# 없으면 설치
https://www.python.org/downloads/
```

**PowerPoint COM 오류?**
```
PowerPoint가 설치되어 있지 않습니다.
→ Microsoft Office 설치 필요
```

**웹 다운로드 실패?**
```
1. JWT 토큰 확인 (로그인 필수)
2. 관리자 권한 확인 (/admin/dashboard)
3. 서버 로그 확인
```

---

## 📝 관련 문서

- `docs/presentations/FINAL_PROJECT_REPORT_Presentation.html` - HTML 프레젠테이션
- `docs/guides/PowerPoint_변환가이드.md` - 변환 방법 상세 가이드
- `docs/README.md` - 문서 디렉터리 안내

---

**Last Updated**: 2026-06-19
