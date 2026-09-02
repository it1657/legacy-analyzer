package com.legacy.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class DataInitializer implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

  private final RoleRepository roleRepository;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  // 기본 계정 시딩 여부 및 초기 비밀번호는 환경변수로 오버라이드 가능(기본값은 기존 동작과 동일).
  private final boolean seedDefaultAccounts;
  private final String defaultAdminPassword;
  private final String defaultTestPassword;

  @Autowired
  public DataInitializer(
      RoleRepository roleRepository,
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      @Value("${app.security.seed-default-accounts:true}") boolean seedDefaultAccounts,
      @Value("${app.security.default-admin-password:admin}") String defaultAdminPassword,
      @Value("${app.security.default-test-password:1}") String defaultTestPassword) {
    this.roleRepository = roleRepository;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.seedDefaultAccounts = seedDefaultAccounts;
    this.defaultAdminPassword = defaultAdminPassword;
    this.defaultTestPassword = defaultTestPassword;
  }

  @Override
  public void run(String... args) throws Exception {
    log.info("[데이터 초기화] 시작");
    initializeRoles();
    initializeAdminUser();
    initializeTestUser();
    log.info("[데이터 초기화] 완료");
  }

  private void initializeRoles() {
    if (roleRepository.findByName("ADMIN").isEmpty()) {
      Role adminRole = new Role();
      adminRole.setName("ADMIN");
      adminRole.setDescription("관리자 역할");
      roleRepository.save(adminRole);
      log.info("[역할 생성] ADMIN");
    }
    if (roleRepository.findByName("USER").isEmpty()) {
      Role userRole = new Role();
      userRole.setName("USER");
      userRole.setDescription("일반 사용자 역할");
      roleRepository.save(userRole);
      log.info("[역할 생성] USER");
    }
  }

  private void initializeTestUser() {
    if (!seedDefaultAccounts) {
      log.info("[기본 사용자 생성] userId={} 설정(app.security.seed-default-accounts=false)에 의해 건너뜀",
          "test");
      return;
    }
    if (!userRepository.existsByUserId("test")) {
      Role userRole = roleRepository.findByName("USER")
          .orElseThrow(() -> new RuntimeException("USER 역할이 없습니다."));

      User testUser = new User("test", "test@example.com",
          passwordEncoder.encode(defaultTestPassword));
      testUser.setDisplayName("테스트사용자");
      testUser.setRoles(new HashSet<>(Collections.singleton(userRole)));
      testUser.setActive(true);
      testUser.setCreatedAt(LocalDateTime.now());
      testUser.setUpdatedAt(LocalDateTime.now());

      userRepository.save(testUser);
      log.info("[기본 사용자 생성] userId=test (비밀번호는 보안상 로그에 기록하지 않음)");
    }
  }

  private void initializeAdminUser() {
    if (!seedDefaultAccounts) {
      log.info("[기본 사용자 생성] userId={} 설정(app.security.seed-default-accounts=false)에 의해 건너뜀",
          "admin");
      return;
    }
    if (!userRepository.existsByUserId("admin")) {
      Role adminRole = roleRepository.findByName("ADMIN")
          .orElseThrow(() -> new RuntimeException("ADMIN 역할이 없습니다."));

      User adminUser = new User("admin", "admin@example.com",
          passwordEncoder.encode(defaultAdminPassword));
      adminUser.setDisplayName("관리자");
      adminUser.setRoles(new HashSet<>(Collections.singleton(adminRole)));
      adminUser.setActive(true);
      adminUser.setCreatedAt(LocalDateTime.now());
      adminUser.setUpdatedAt(LocalDateTime.now());

      userRepository.save(adminUser);
      log.info("[기본 사용자 생성] userId=admin (비밀번호는 보안상 로그에 기록하지 않음)");
    }
  }
}
