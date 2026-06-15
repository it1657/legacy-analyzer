package com.legacy;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPageController {

  // 관리자 대시보드 페이지
  @GetMapping("/dashboard")
  public String adminDashboard() {
    return "admin/dashboard";
  }
}
