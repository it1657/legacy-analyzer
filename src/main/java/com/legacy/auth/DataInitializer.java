package com.legacy.auth;

import org.springframework.beans.factory.annotation.Autowired;
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

  @Autowired
  public DataInitializer(
      RoleRepository roleRepository,
      UserRepository userRepository,
      PasswordEncoder passwordEncoder) {
    this.roleRepository = roleRepository;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public void run(String... args) throws Exception {
    log.info("[데이터 초기화] 시작");

    // 역할 초기화
    initializeRoles();

    // 관리자 사용자 초기화
    initializeAdminUser();

    log.info("[데이터 초기화] 완료");
  }

  private void initializeRoles() {
    // ADMIN 역할 생성
    if (roleRepository.findByName("ADMIN").isEmpty()) {
      Role adminRole = new Role();
      adminRole.setName("ADMIN");
      adminRole.setDescription("관리자 역할");
      roleRepository.save(adminRole);
      log.info("[역할 생성] ADMIN");
    }

    // USER 역할 생성
    if (roleRepository.findByName("USER").isEmpty()) {
      Role userRole = new Role();
      userRole.setName("USER");
      userRole.setDescription("일반 사용자 역할");
      roleRepository.save(userRole);
      log.info("[역할 생성] USER");
    }
  }

  private void initializeAdminUser() {
    // 기본 관리자 사용자 생성
    if (!userRepository.existsByUsername("admin")) {
      Role adminRole = roleRepository.findByName("ADMIN")
          .orElseThrow(() -> new RuntimeException("ADMIN 역할이 없습니다."));

      User adminUser = new User("admin", "admin@example.com",
          passwordEncoder.encode("admin123456"));
      adminUser.setRoles(new HashSet<>(Collections.singleton(adminRole)));
      adminUser.setActive(true);
      adminUser.setCreatedAt(LocalDateTime.now());
      adminUser.setUpdatedAt(LocalDateTime.now());

      userRepository.save(adminUser);
      log.info("[기본 사용자 생성] admin (비밀번호: admin123456)");
    }
  }
}
