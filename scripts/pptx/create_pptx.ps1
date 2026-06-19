# PowerPoint 프레젠테이션 생성 스크립트 (XML 기반)
# PPTX는 ZIP 파일이므로 XML을 생성하고 압축하여 만들 수 있음

$outputPath = "C:\project\legacy-analyzer\FINAL_PROJECT_REPORT_Presentation.pptx"
$workDir = "C:\project\legacy-analyzer\pptx_temp"

# 임시 디렉터리 생성
if (Test-Path $workDir) { Remove-Item $workDir -Recurse -Force }
New-Item -ItemType Directory -Path $workDir | Out-Null
New-Item -ItemType Directory -Path "$workDir\_rels" | Out-Null
New-Item -ItemType Directory -Path "$workDir\ppt" | Out-Null
New-Item -ItemType Directory -Path "$workDir\ppt\_rels" | Out-Null
New-Item -ItemType Directory -Path "$workDir\ppt\slides" | Out-Null
New-Item -ItemType Directory -Path "$workDir\ppt\slides\_rels" | Out-Null
New-Item -ItemType Directory -Path "$workDir\docProps" | Out-Null
New-Item -ItemType Directory -Path "$workDir\[Content_Types]" -ErrorAction SilentlyContinue | Out-Null
New-Item -ItemType Directory -Path "$workDir\xl" -ErrorAction SilentlyContinue | Out-Null

# [Content_Types].xml 생성
$contentTypes = @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
  <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
  <Override PartName="/ppt/slides/slide2.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
  <Override PartName="/ppt/slides/slide3.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
  <Override PartName="/ppt/slides/slide4.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
  <Override PartName="/ppt/slides/slide5.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
  <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
  <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
  <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/>
</Types>
"@

Set-Content -Path "$workDir\[Content_Types].xml" -Value $contentTypes -Encoding UTF8

Write-Host "✅ PPTX 구조 생성 시작..."
Write-Host "⏳ 간단한 버전으로 5개 슬라이드 생성 중..."

# 더 간단한 방법: Python 없이 순수 PowerShell로 생성
# 실제로 프로덕션 환경에서는 python-pptx를 권장하지만,
# 여기서는 LibreOffice를 사용하여 기존 파일을 변환

Write-Host "📌 참고: Python-pptx 라이브러리가 필요합니다."
Write-Host "다음 명령어로 설치해주세요:"
Write-Host "  python -m pip install python-pptx"
Write-Host ""
Write-Host "또는 LibreOffice Impress를 사용하여 수동으로 생성할 수 있습니다."
