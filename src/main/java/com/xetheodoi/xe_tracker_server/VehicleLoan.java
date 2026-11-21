package com.xetheodoi.xe_tracker_server;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Phiên cho mượn / giao xe tự lái để chủ xe gắn hành trình, cảnh báo và video clip vào đúng người lái.
 */
@Entity
@Table(name = "vehicle_loans")
public class VehicleLoan {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_RETURNED = "RETURNED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String vehicleId;
    private String borrowerName;
    private String borrowerPhone;
    private String borrowerNote;       // CCCD, mục đích mượn, ghi chú giao nhận
    private String startedAt;          // yyyy-MM-dd HH:mm:ss
    private String expectedReturnAt;   // yyyy-MM-dd HH:mm hoặc yyyy-MM-dd HH:mm:ss
    private String returnedAt;         // yyyy-MM-dd HH:mm:ss
    private String status = STATUS_ACTIVE;
    private Integer maxSpeedAlertKmh;
    private Boolean autoClipOnAccOn = true;   // Tự đẩy clip ngắn khi người mượn nổ máy
    private Boolean autoClipOnAlert = true;   // Tự đẩy clip ngắn khi quá tốc độ / rời vùng khóa

    public VehicleLoan() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getVehicleId() { return vehicleId; }
    public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }

    public String getBorrowerName() { return borrowerName; }
    public void setBorrowerName(String borrowerName) { this.borrowerName = borrowerName; }

    public String getBorrowerPhone() { return borrowerPhone; }
    public void setBorrowerPhone(String borrowerPhone) { this.borrowerPhone = borrowerPhone; }

    public String getBorrowerNote() { return borrowerNote; }
    public void setBorrowerNote(String borrowerNote) { this.borrowerNote = borrowerNote; }

    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }

    public String getExpectedReturnAt() { return expectedReturnAt; }
    public void setExpectedReturnAt(String expectedReturnAt) { this.expectedReturnAt = expectedReturnAt; }

    public String getReturnedAt() { return returnedAt; }
    public void setReturnedAt(String returnedAt) { this.returnedAt = returnedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getMaxSpeedAlertKmh() { return maxSpeedAlertKmh; }
    public void setMaxSpeedAlertKmh(Integer maxSpeedAlertKmh) { this.maxSpeedAlertKmh = maxSpeedAlertKmh; }

    public Boolean getAutoClipOnAccOn() { return autoClipOnAccOn; }
    public void setAutoClipOnAccOn(Boolean autoClipOnAccOn) { this.autoClipOnAccOn = autoClipOnAccOn; }

    public Boolean getAutoClipOnAlert() { return autoClipOnAlert; }
    public void setAutoClipOnAlert(Boolean autoClipOnAlert) { this.autoClipOnAlert = autoClipOnAlert; }
}
