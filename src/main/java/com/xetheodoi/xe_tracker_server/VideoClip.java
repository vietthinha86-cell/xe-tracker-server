package com.xetheodoi.xe_tracker_server;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Bản ghi video hành trình / cabin của xe (lưu trên Google Drive của chủ xe hoặc link stream trực tiếp).
 */
@Entity
@Table(name = "video_clips")
public class VideoClip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String vehicleId;
    private Integer channel = 1;
    private String channelLabel;
    private String title;
    private String triggerType = "MANUAL"; // MANUAL, TRIP, GEOFENCE, OVERSPEED, ACCIDENT
    private String recordedAt;             // yyyy-MM-dd HH:mm:ss
    private Integer durationSeconds = 60;
    private String storageType = "GOOGLE_DRIVE"; // GOOGLE_DRIVE, DIRECT_URL
    private String driveFileId;
    private String driveWebUrl;
    private String streamUrl;
    private Double latitude;
    private Double longitude;
    private Double speed;

    public VideoClip() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getVehicleId() { return vehicleId; }
    public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }

    public Integer getChannel() { return channel; }
    public void setChannel(Integer channel) { this.channel = channel; }

    public String getChannelLabel() { return channelLabel; }
    public void setChannelLabel(String channelLabel) { this.channelLabel = channelLabel; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public String getRecordedAt() { return recordedAt; }
    public void setRecordedAt(String recordedAt) { this.recordedAt = recordedAt; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }

    public String getDriveFileId() { return driveFileId; }
    public void setDriveFileId(String driveFileId) { this.driveFileId = driveFileId; }

    public String getDriveWebUrl() { return driveWebUrl; }
    public void setDriveWebUrl(String driveWebUrl) { this.driveWebUrl = driveWebUrl; }

    public String getStreamUrl() { return streamUrl; }
    public void setStreamUrl(String streamUrl) { this.streamUrl = streamUrl; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public Double getSpeed() { return speed; }
    public void setSpeed(Double speed) { this.speed = speed; }

    public String getEmbedPreviewUrl() {
        if (driveFileId != null && !driveFileId.isBlank()) {
            return "https://drive.google.com/file/d/" + driveFileId.trim() + "/preview";
        }
        return streamUrl != null && !streamUrl.isBlank() ? streamUrl : driveWebUrl;
    }
}
