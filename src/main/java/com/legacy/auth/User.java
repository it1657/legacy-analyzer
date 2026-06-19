package com.legacy.auth;

import jakarta.persistence.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "users")
public class User implements UserDetails {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "seq")
  private Long seq;

  @Column(name = "user_id", nullable = false, unique = true, length = 50)
  private String userId;

  @Column(name = "display_name", length = 100)
  private String displayName;

  @Column(nullable = false, unique = true, length = 100)
  private String email;

  @Column(nullable = false)
  private String passwordHash;

  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
      name = "user_roles",
      joinColumns = @JoinColumn(name = "user_seq"),
      inverseJoinColumns = @JoinColumn(name = "role_id"))
  private Set<Role> roles = new HashSet<>();

  @Column(name = "is_active")
  private boolean isActive = true;

  @Column(name = "created_at")
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  public User() {}

  public User(String userId, String email, String passwordHash) {
    this.userId = userId;
    this.email = email;
    this.passwordHash = passwordHash;
    this.createdAt = LocalDateTime.now();
    this.updatedAt = LocalDateTime.now();
  }

  public String getDisplayName() {
    return displayName != null ? displayName : userId;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  // Spring Security UserDetails — 로그인 식별자로 userId 반환
  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return roles.stream()
        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
        .toList();
  }

  @Override
  public String getPassword() { return passwordHash; }

  // Spring Security 인증 principal — userId(로그인ID) 반환
  @Override
  public String getUsername() { return userId; }

  @Override public boolean isAccountNonExpired()     { return true; }
  @Override public boolean isAccountNonLocked()      { return true; }
  @Override public boolean isCredentialsNonExpired() { return true; }
  @Override public boolean isEnabled()               { return isActive; }

  // Getter / Setter
  public Long getSeq()             { return seq; }
  public void setSeq(Long seq)     { this.seq = seq; }

  public String getUserId()           { return userId; }
  public void setUserId(String userId){ this.userId = userId; }

  public String getEmail()           { return email; }
  public void setEmail(String email) { this.email = email; }

  public String getPasswordHash()                { return passwordHash; }
  public void setPasswordHash(String passwordHash){ this.passwordHash = passwordHash; }

  public Set<Role> getRoles()          { return roles; }
  public void setRoles(Set<Role> roles){ this.roles = roles; }

  public boolean isActive()            { return isActive; }
  public void setActive(boolean active){ isActive = active; }

  public LocalDateTime getCreatedAt()                { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt)  { this.createdAt = createdAt; }

  public LocalDateTime getUpdatedAt()                { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt)  { this.updatedAt = updatedAt; }
}
