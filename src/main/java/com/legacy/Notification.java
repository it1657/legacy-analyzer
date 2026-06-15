package com.legacy;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
public class Notification {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "type", length = 50, nullable = false)
  private String type; // ANALYSIS_COMPLETED, ANALYSIS_FAILED, USER_CREATED, ERROR, etc.

  @Column(name = "title", length = 200, nullable = false)
  private String title;

  @Column(name = "message", length = 1000)
  private String message;

  @Column(name = "target_id")
  private Long targetId; // 분석 ID, 사용자 ID 등

  @Column(name = "target_type", length = 50)
  private String targetType; // ANALYSIS, USER, etc.

  @Column(name = "is_read")
  private boolean isRead = false;

  @Column(name = "read_at")
  private LocalDateTime readAt;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "action_url", length = 255)
  private String actionUrl; // 클릭 시 이동할 URL

  // 생성자
  public Notification() {
  }

  public Notification(Long userId, String type, String title, String message) {
    this.userId = userId;
    this.type = type;
    this.title = title;
    this.message = message;
    this.createdAt = LocalDateTime.now();
  }

  public Notification(Long userId, String type, String title, String message, Long targetId,
      String targetType) {
    this(userId, type, title, message);
    this.targetId = targetId;
    this.targetType = targetType;
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

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public Long getTargetId() {
    return targetId;
  }

  public void setTargetId(Long targetId) {
    this.targetId = targetId;
  }

  public String getTargetType() {
    return targetType;
  }

  public void setTargetType(String targetType) {
    this.targetType = targetType;
  }

  public boolean isRead() {
    return isRead;
  }

  public void setRead(boolean read) {
    isRead = read;
  }

  public LocalDateTime getReadAt() {
    return readAt;
  }

  public void setReadAt(LocalDateTime readAt) {
    this.readAt = readAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public String getActionUrl() {
    return actionUrl;
  }

  public void setActionUrl(String actionUrl) {
    this.actionUrl = actionUrl;
  }
}
