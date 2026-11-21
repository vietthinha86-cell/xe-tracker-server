package com.xetheodoi.xe_tracker_server;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Lệnh yêu cầu camera/thiết bị trên xe đẩy clip ngắn (5–30s) hoặc ảnh chụp khoang lái theo sự kiện / theo yêu cầu chủ xe để tiết kiệm 4G SIM.
 */
@Entity
@Table(name = "capture_commands")
public class CaptureCommand {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    public static final String TYPE_CLIP = "CLIP";
    public static final String TYPE_SNAPSHOT = "SNAPSHOT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String vehicleId;
    private Integer channel = 2;              // Mặc định kênh 2 (Trong Cabin để nhìn người lái)
    private String commandType = TYPE_CLIP;   // CLIP hoặc SNAPSHOT
    private Integer durationSeconds = 15;     // 5..30s để tiết kiệm 4G SIM
    private String quality = "LOW_360P";      // LOW_360P (~1.5MB/15s) hoặc MED_480P (~3MB/15s)
    private String triggerReason = "OWNER_REQUEST"; // OWNER_REQUEST, ACC_ON, OVERSPEED, GEOFENCE_BREACH
    private String status = STATUS_PENDING;
    private String requestedAt;               // yyyy-MM-dd HH:mm:ss
    private String completedAt;
    private Long resultClipId;
    private Long loanId;
    private String borrowerName;

    public CaptureCommand() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getVehicleId() { return vehicleId; }
    public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }

    public Integer getChannel() { return channel; }
    public void setChannel(Integer channel) { this.channel = channel; }

    public String getCommandType() { return commandType; }
    public void setCommandType(String commandType) { this.commandType = commandType; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }

    public String getTriggerReason() { return triggerReason; }
    public void setTriggerReason(String triggerReason) { this.triggerReason = triggerReason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRequestedAt() { return requestedAt; }
    public void setRequestedAt(String requestedAt) { this.requestedAt = requestedAt; }

    public String getCompletedAt() { return completedAt; }
    public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }

    public Long getResultClipId() { return resultClipId; }
    public void setResultClipId(Long resultClipId) { this.resultClipId = resultClipId; }

    public Long getLoanId() { return loanId; }
    public void setLoanId(Long loanId) { this.loanId = loanId; }

    public String getBorrowerName() { return borrowerName; }
    public void setBorrowerName(String borrowerName) { this.borrowerName = borrowerName; }
}
