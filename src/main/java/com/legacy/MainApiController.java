package com.legacy;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
public class MainApiController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/api/analyze")
    @ResponseBody
    public AnalyzeDto.Response testAnalyze(@RequestBody AnalyzeDto.Request request) {
        String receivedCode = request.getSourceCode();

        String mockResponse = "/* [시스템 알림] 1주차 프론트엔드-백엔드 통신 테스트 성공! */\n"
                + "/* 수신된 소스 코드 글자수: " + receivedCode.length() + "자 */\n\n"
                + "/* 전송된 코드 본문 */\n"
                + receivedCode;

        return new AnalyzeDto.Response(mockResponse);
    }
}
