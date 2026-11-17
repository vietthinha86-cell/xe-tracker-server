package com.xetheodoi.xe_tracker_server;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "location_history")
public class LocationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String vehicleId;
    private Double latitude;
    private Double longitude;
    private Double speed;
    private String recordedAt;

    public LocationHistory() {}

    public LocationHistory(String vehicleId, Double latitude, Double longitude, Double speed, String recordedAt) {
        this.vehicleId = vehicleId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speed = speed;
        this.recordedAt = recordedAt;
    }

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

    public String getRecordedAt() { return recordedAt; }
    public void setRecordedAt(String recordedAt) { this.recordedAt = recordedAt; }
}

