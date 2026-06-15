#!/bin/bash

# audit 패키지: User, AnalysisHistory 필요
for f in audit/*.java; do
    if grep -q "class.*User" "$f" || grep -q "User " "$f"; then
        if ! grep -q "import com.legacy.auth.User" "$f"; then
            sed -i '/^package com.legacy.audit;/a import com.legacy.auth.User;' "$f"
        fi
    fi
    if grep -q "AnalysisHistory" "$f"; then
        if ! grep -q "import com.legacy.analysis.AnalysisHistory" "$f"; then
            sed -i '/^package com.legacy.audit;/a import com.legacy.analysis.AnalysisHistory;' "$f"
        fi
    fi
done

# notification 패키지
for f in notification/*.java; do
    if grep -q "UserRepository" "$f"; then
        if ! grep -q "import com.legacy.auth.UserRepository" "$f"; then
            sed -i '/^package com.legacy.notification;/a import com.legacy.auth.UserRepository;' "$f"
        fi
    fi
    if grep -q "AnalysisHistory" "$f"; then
        if ! grep -q "import com.legacy.analysis.AnalysisHistory" "$f"; then
            sed -i '/^package com.legacy.notification;/a import com.legacy.analysis.AnalysisHistory;' "$f"
        fi
    fi
    if grep -q "class.*User" "$f" || grep -q "User " "$f"; then
        if ! grep -q "import com.legacy.auth.User" "$f"; then
            sed -i '/^package com.legacy.notification;/a import com.legacy.auth.User;' "$f"
        fi
    fi
done

# statistics 패키지
for f in statistics/*.java; do
    if grep -q "UserRepository" "$f"; then
        if ! grep -q "import com.legacy.auth.UserRepository" "$f"; then
            sed -i '/^package com.legacy.statistics;/a import com.legacy.auth.UserRepository;' "$f"
        fi
    fi
    if grep -q "AnalysisHistoryRepository" "$f"; then
        if ! grep -q "import com.legacy.analysis.AnalysisHistoryRepository" "$f"; then
            sed -i '/^package com.legacy.statistics;/a import com.legacy.analysis.AnalysisHistoryRepository;' "$f"
        fi
    fi
    if grep -q "ApiUsageRepository" "$f"; then
        if ! grep -q "import com.legacy.api.usage.ApiUsageRepository" "$f"; then
            sed -i '/^package com.legacy.statistics;/a import com.legacy.api.usage.ApiUsageRepository;' "$f"
        fi
    fi
done

# api/usage 패키지
for f in api/usage/*.java; do
    if grep -q "class.*User" "$f" || grep -q "User " "$f"; then
        if ! grep -q "import com.legacy.auth.User" "$f"; then
            sed -i '/^package com.legacy.api.usage;/a import com.legacy.auth.User;' "$f"
        fi
    fi
done

# admin 패키지
for f in admin/*.java; do
    if grep -q "class.*User" "$f" || grep -q "User " "$f"; then
        if ! grep -q "import com.legacy.auth.User" "$f"; then
            sed -i '/^package com.legacy.admin;/a import com.legacy.auth.User;' "$f"
        fi
    fi
done

# analysis 패키지 - 자신의 클래스들은 이미 같은 패키지에 있음

echo "✓ 누락된 import 추가 완료"
