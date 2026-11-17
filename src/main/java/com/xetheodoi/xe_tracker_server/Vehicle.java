package com.xetheodoi.xe_tracker_server;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;

@Entity
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String vehicleId;   // mã định danh xe / biển số, vd "XE001"
    private String vehicleName; // tên gợi nhớ, vd "Toyota Vios Trắng"
    private Long ownerId;       // ID chủ sở hữu trong bảng app_users
    private String activationCode; // mã kích hoạt ghép nối thiết bị & nhận xe
    private Boolean requireActivationCode = false;
    private String planExpiresAt;  // hạn gói cước "yyyy-MM-dd" (null = không giới hạn)
    private Integer maxSpeedAlertKmh = 90;

    private Double latitude;
    private Double longitude;
    private Double speed;       // vận tốc km/h
    private Double bearing;     // góc hướng di chuyển (0-360 độ)
    private String lastUpdated; // thời gian cập nhật gần nhất

    // Khóa bãi đỗ chống trộm (đồng bộ qua Server/DB)
    private Boolean guardEnabled = false;
    private Double guardLat;
    private Double guardLng;
    private Integer guardRadius = 100;
    private Boolean guardBreached = false;
    private Double guardDistanceMeters = 0.0;
    private String guardActivatedAt;

    @JsonIgnore
    private Long lastTelegramAlertEpochMs = 0L;

    @JsonIgnore
    private Long lastSpeedAlertEpochMs = 0L;

    public Vehicle() {}

    // Getters và Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getVehicleId() { return vehicleId; }
    public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public Double getSpeed() { return speed; }
    public void setSpeed(Double speed) { this.speed = speed; }

    public String getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated; }

    public Boolean getGuardEnabled() { return guardEnabled; }
    public void setGuardEnabled(Boolean guardEnabled) { this.guardEnabled = guardEnabled; }

    public Double getGuardLat() { return guardLat; }
    public void setGuardLat(Double guardLat) { this.guardLat = guardLat; }

    public Double getGuardLng() { return guardLng; }
    public void setGuardLng(Double guardLng) { this.guardLng = guardLng; }

    public Integer getGuardRadius() { return guardRadius; }
    public void setGuardRadius(Integer guardRadius) { this.guardRadius = guardRadius; }

    public Boolean getGuardBreached() { return guardBreached; }
    public void setGuardBreached(Boolean guardBreached) { this.guardBreached = guardBreached; }

    public Double getGuardDistanceMeters() { return guardDistanceMeters; }
    public void setGuardDistanceMeters(Double guardDistanceMeters) { this.guardDistanceMeters = guardDistanceMeters; }

    public String getGuardActivatedAt() { return guardActivatedAt; }
    public void setGuardActivatedAt(String guardActivatedAt) { this.guardActivatedAt = guardActivatedAt; }

    public String getVehicleName() { return vehicleName; }
    public void setVehicleName(String vehicleName) { this.vehicleName = vehicleName; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getActivationCode() { return activationCode; }
    public void setActivationCode(String activationCode) { this.activationCode = activationCode; }

    public Boolean getRequireActivationCode() { return requireActivationCode; }
    public void setRequireActivationCode(Boolean requireActivationCode) { this.requireActivationCode = requireActivationCode; }

    public String getPlanExpiresAt() { return planExpiresAt; }
    public void setPlanExpiresAt(String planExpiresAt) { this.planExpiresAt = planExpiresAt; }

    public Integer getMaxSpeedAlertKmh() { return maxSpeedAlertKmh; }
    public void setMaxSpeedAlertKmh(Integer maxSpeedAlertKmh) { this.maxSpeedAlertKmh = maxSpeedAlertKmh; }

    public Double getBearing() { return bearing; }
    public void setBearing(Double bearing) { this.bearing = bearing; }

    public Long getLastTelegramAlertEpochMs() { return lastTelegramAlertEpochMs; }
    public void setLastTelegramAlertEpochMs(Long lastTelegramAlertEpochMs) { this.lastTelegramAlertEpochMs = lastTelegramAlertEpochMs; }

    public Long getLastSpeedAlertEpochMs() { return lastSpeedAlertEpochMs; }
    public void setLastSpeedAlertEpochMs(Long lastSpeedAlertEpochMs) { this.lastSpeedAlertEpochMs = lastSpeedAlertEpochMs; }

    public boolean isPlanExpired() {
        if (planExpiresAt == null || planExpiresAt.trim().isEmpty()) {
            return false;
        }
        try {
            return java.time.LocalDate.now().isAfter(java.time.LocalDate.parse(planExpiresAt.trim()));
        } catch (Exception ignored) {
            return false;
        }
    }
}
