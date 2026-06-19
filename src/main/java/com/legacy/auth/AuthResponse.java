package com.legacy.auth;

// 로그인 응답 DTO
public class AuthResponse {
  private String token;
  private String type = "Bearer";
  private Long seq;        // DB PK (시퀀스)
  private String userId;   // 로그인 ID
  private java.util.List<String> roles;
  private String message;

  public AuthResponse(String token, Long seq, String userId, java.util.List<String> roles) {
    this.token = token;
    this.seq = seq;
    this.userId = userId;
    this.roles = roles;
  }

  public AuthResponse(String token, Long seq, String userId) {
    this.token = token;
    this.seq = seq;
    this.userId = userId;
  }

  public AuthResponse(String message) {
    this.message = message;
  }

  public String getToken()             { return token; }
  public void setToken(String token)   { this.token = token; }

  public String getType()              { return type; }
  public void setType(String type)     { this.type = type; }

  public Long getSeq()                 { return seq; }
  public void setSeq(Long seq)         { this.seq = seq; }

  public String getUserId()            { return userId; }
  public void setUserId(String userId) { this.userId = userId; }

  public String getMessage()               { return message; }
  public void setMessage(String message)   { this.message = message; }

  public java.util.List<String> getRoles()           { return roles; }
  public void setRoles(java.util.List<String> roles) { this.roles = roles; }
}
