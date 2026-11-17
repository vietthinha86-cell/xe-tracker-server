package com.xetheodoi.xe_tracker_server;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tài khoản người dùng (Admin hoặc Chủ xe) phục vụ phân quyền và cảnh báo cá nhân.
 */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @JsonIgnore
    @Column(nullable = false)
    private String passwordHash;

    private String fullName;
    private String phone;

    @Column(nullable = false)
    private String role = "USER"; // "ADMIN" hoặc "USER"

    private Boolean active = true;
    private String telegramChatId;
    private String createdAt;

    public AppUser() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public String getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(String telegramChatId) { this.telegramChatId = telegramChatId; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(this.role);
    }

    public boolean isAccountActive() {
        return !Boolean.FALSE.equals(this.active);
    }

    /** Trả về thông tin an toàn cho client, loại bỏ passwordHash. */
    public Map<String, Object> toPublicMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("username", username);
        map.put("fullName", fullName != null ? fullName : username);
        map.put("phone", phone != null ? phone : "");
        map.put("role", role != null ? role : "USER");
        map.put("active", isAccountActive());
        map.put("telegramChatId", telegramChatId != null ? telegramChatId : "");
        map.put("createdAt", createdAt != null ? createdAt : "");
        return map;
    }
}

