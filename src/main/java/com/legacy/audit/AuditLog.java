package com.legacy.audit;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id")
  private Long userId;

  @Column(name = "username", length = 100)
  private String username;

  @Column(name = "action", length = 50, nullable = false)
  private String action; // CREATE, UPDATE, DELETE, LOGIN, LOGOUT

  @Column(name = "target", length = 50, nullable = false)
  private String target; // USER, ANALYSIS, ANALYSIS_HISTORY, SETTINGS, etc.

  @Column(name = "target_id")
  private Long targetId;

  @Column(name = "target_name", length = 255)
  private String targetName; // 대상의 이름 (예: 사용자명, 파일명)

  @Column(name = "status", length = 20)
  private String status; // SUCCESS, FAILURE

  @Column(name = "changes", length = 2000)
  private String changes; // JSON format of changes

  @Column(name = "details", length = 500)
  private String details; // 추가 설명

  @Column(name = "timestamp", nullable = false)
  private LocalDateTime timestamp;

  @Column(name = "ip_address", length = 50)
  private String ipAddress;

  // 생성자
  public AuditLog() {
  }

  public AuditLog(Long userId, String username, String action, String target, Long targetId,
      String targetName, String status, String ipAddress) {
    this.userId = userId;
    this.username = username;
    this.action = action;
    this.target = target;
    this.targetId = targetId;
    this.targetName = targetName;
    this.status = status;
    this.ipAddress = ipAddress;
    this.timestamp = LocalDateTime.now();
  }

  // Getter/Setter
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
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

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public String getTarget() {
    return target;
  }

  public void setTarget(String target) {
    this.target = target;
  }

  public Long getTargetId() {
    return targetId;
  }

  public void setTargetId(Long targetId) {
    this.targetId = targetId;
  }

  public String getTargetName() {
    return targetName;
  }

  public void setTargetName(String targetName) {
    this.targetName = targetName;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getChanges() {
    return changes;
  }

  public void setChanges(String changes) {
    this.changes = changes;
  }

  public String getDetails() {
    return details;
  }

  public void setDetails(String details) {
    this.details = details;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public void setIpAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }
}
