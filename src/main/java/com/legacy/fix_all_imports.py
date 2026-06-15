#!/usr/bin/env python3
import os
import re

# 클래스별 패키지 매핑
class_to_package = {
    # Auth
    'User': 'com.legacy.auth',
    'Role': 'com.legacy.auth',
    'UserRepository': 'com.legacy.auth',
    'RoleRepository': 'com.legacy.auth',
    'AuthController': 'com.legacy.auth',
    'AuthRequest': 'com.legacy.auth',
    'AuthResponse': 'com.legacy.auth',
    'JwtTokenProvider': 'com.legacy.auth',
    'JwtAuthenticationFilter': 'com.legacy.auth',
    'SecurityConfig': 'com.legacy.auth',
    'CustomUserDetailsService': 'com.legacy.auth',
    
    # Analysis
    'AnalysisHistory': 'com.legacy.analysis',
    'AnalysisHistoryRepository': 'com.legacy.analysis',
    'SessionState': 'com.legacy.analysis',
    'SessionRepository': 'com.legacy.analysis',
    'SessionConfig': 'com.legacy.analysis',
    'AnalysisSessionManager': 'com.legacy.analysis',
    'FileAnalysisState': 'com.legacy.analysis',
    'AnalysisException': 'com.legacy.analysis',
    'AnalysisStatistics': 'com.legacy.analysis',
    'AnalysisLogEntry': 'com.legacy.analysis',
    'ClaudeService': 'com.legacy.analysis',
    'ClaudeServiceImpl': 'com.legacy.analysis',
    'PromptResolver': 'com.legacy.analysis',
    'CodeCleaner': 'com.legacy.analysis',
    'RetryHandler': 'com.legacy.analysis',
    'AnalysisLogger': 'com.legacy.analysis',
    'AnalyzeDto': 'com.legacy.analysis',
    'ProgressUpdateDto': 'com.legacy.analysis',
    'SessionDetailDto': 'com.legacy.analysis',
    'SessionSummaryDto': 'com.legacy.analysis',
    'BatchAnalysisRequestDto': 'com.legacy.analysis',
    'ApiResponseWrapper': 'com.legacy.analysis',
    
    # API Usage
    'ApiUsage': 'com.legacy.api.usage',
    'ApiUsageRepository': 'com.legacy.api.usage',
    'ApiUsageController': 'com.legacy.api.usage',
    'ApiUsageFilter': 'com.legacy.api.usage',
}

def fix_imports(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 기존 import 추출
    existing_imports = set(re.findall(r'import\s+([\w.]+);', content))
    
    # 필요한 import 찾기
    needed_imports = set()
    for class_name, package in class_to_package.items():
        # 클래스가 사용되는지 확인
        if re.search(r'\b' + class_name + r'\b', content):
            import_stmt = f'{package}.{class_name}'
            if import_stmt not in existing_imports:
                needed_imports.add(import_stmt)
    
    # import 추가
    if needed_imports:
        # 마지막 package 선언 다음에 import 추가
        lines = content.split('\n')
        insert_idx = 0
        for i, line in enumerate(lines):
            if line.startswith('package '):
                insert_idx = i + 1
                break
        
        for import_stmt in sorted(needed_imports):
            lines.insert(insert_idx, f'import {import_stmt};')
            insert_idx += 1
        
        content = '\n'.join(lines)
        
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)

# 모든 Java 파일 처리
for root, dirs, files in os.walk('.'):
    for file in files:
        if file.endswith('.java'):
            file_path = os.path.join(root, file)
            fix_imports(file_path)
            print(f'Fixed: {file_path}')

print('✓ 모든 import 수정 완료')
