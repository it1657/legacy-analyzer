package com.legacy.auth;

// 로그인 요청 DTO
public class AuthRequest {
  private String userId;
  private String email;
  private String password;

  public AuthRequest() {}

  public AuthRequest(String userId, String email, String password) {
    this.userId = userId;
    this.email = email;
    this.password = password;
  }

  public String getUserId()            { return userId; }
  public void setUserId(String userId) { this.userId = userId; }

  public String getEmail()             { return email; }
  public void setEmail(String email)   { this.email = email; }

  public String getPassword()              { return password; }
  public void setPassword(String password) { this.password = password; }
}
