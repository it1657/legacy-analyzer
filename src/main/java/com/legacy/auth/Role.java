package com.legacy.auth;

import jakarta.persistence.*;

import java.util.Objects;

@Entity
@Table(name = "roles")
public class Role {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 50)
  private String name;

  @Column(length = 200)
  private String description;

  public Role() {
  }

  public Role(String name, String description) {
    this.name = name;
    this.description = description;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  // 역할은 name이 유일 키(@Column(unique = true))이므로 name 기준으로 동등성을 정의한다.
  // 이게 없으면 Set<Role>의 add/remove가 인스턴스 동일성에 의존해, 영속성 컨텍스트가 다른 경우
  // 권한 해제가 조용히 실패하거나 같은 역할이 중복 추가될 수 있다 (TASK-006, 2026-09).
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Role role)) return false;
    return Objects.equals(name, role.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name);
  }
}
