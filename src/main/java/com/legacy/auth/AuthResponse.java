package com.legacy.auth;

// 로그인 응답 DTO
public class AuthResponse {
  private String token;
  private String type = "Bearer";
  private Long userId;
  private String username;
  private java.util.List<String> roles;
  private String message;

  public AuthResponse(String token, Long userId, String username, java.util.List<String> roles) {
    this.token = token;
    this.userId = userId;
    this.username = username;
    this.roles = roles;
  }

  public AuthResponse(String token, Long userId, String username) {
    this.token = token;
    this.userId = userId;
    this.username = username;
  }

  public AuthResponse(String message) {
    this.message = message;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public java.util.List<String> getRoles() {
    return roles;
  }

  public void setRoles(java.util.List<String> roles) {
    this.roles = roles;
  }
}
