package com.legacy.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AdminPageController.adminDashboard()의 뷰 이름 반환을 검증한다. 02-design-v1 4.4절 근거.
 * 이 컨트롤러는 지금까지 미검증 상태였고, 이번에 신규로 커버한다.
 */
class AdminPageControllerTest {

  @Test
  void adminDashboard는_admin_dashboard_뷰_이름을_반환한다() {
    AdminPageController controller = new AdminPageController();

    assertEquals("admin/dashboard", controller.adminDashboard());
  }
}
