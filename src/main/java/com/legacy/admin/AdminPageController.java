package com.legacy.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
public class AdminPageController {

  // 관리자 대시보드 페이지 (클라이언트에서 토큰 검증)
  @GetMapping("/dashboard")
  public String adminDashboard() {
    return "admin/dashboard";
  }
}
