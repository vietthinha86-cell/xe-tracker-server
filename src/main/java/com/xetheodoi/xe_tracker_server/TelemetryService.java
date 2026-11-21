package com.xetheodoi.xe_tracker_server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Dịch vụ lưu tọa độ, trạng thái ACC, lịch sử hành trình và kích hoạt cảnh báo chống trộm/tốc độ dùng chung cho cả HTTP và JT808 TCP.
 */
@Service
public class TelemetryService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final double MIN_HISTORY_DISTANCE_KM = 0.012;

    private final VehicleRepository vehicleRepository;
    private final LocationHistoryRepository historyRepository;
    private final TelegramNotificationService telegramService;

    @Autowired(required = false)
    private CaptureCommandService captureCommandService;

    public TelemetryService(
            VehicleRepository vehicleRepository,
            LocationHistoryRepository historyRepository,
            @Autowired(required = false) TelegramNotificationService telegramService
    ) {
        this.vehicleRepository = vehicleRepository;
        this.historyRepository = historyRepository;
        this.telegramService = telegramService;
    }

    public void setCaptureCommandService(CaptureCommandService captureCommandService) {
        this.captureCommandService = captureCommandService;
    }

    public boolean recordTelemetry(
            Vehicle vehicle,
            double latitude,
            double longitude,
            double speed,
            Double bearing,
            Boolean accOn,
            String recordedAt
    ) {
        String ts = (recordedAt != null && !recordedAt.isBlank())
                ? recordedAt.trim()
                : LocalDateTime.now().format(FORMATTER);

        boolean wasBreachedBefore = Boolean.TRUE.equals(vehicle.getGuardEnabled())
                && Boolean.TRUE.equals(vehicle.getGuardBreached());
        boolean wasAccOnBefore = Boolean.TRUE.equals(vehicle.getAccOn());

        double cleanSpeed = Math.max(0.0, speed);
        vehicle.setLatitude(latitude);
        vehicle.setLongitude(longitude);
        vehicle.setSpeed(cleanSpeed);
        if (bearing != null) {
            vehicle.setBearing(bearing);
        }
        if (accOn != null) {
            vehicle.setAccOn(accOn);
        } else if (cleanSpeed > 2.0) {
            vehicle.setAccOn(true);
        }
        vehicle.setLastUpdated(ts);
        GeoUtils.evaluateGuardState(vehicle);
        vehicleRepository.save(vehicle);

        boolean savedHistory = saveHistoryPointIfMoved(vehicle.getVehicleId(), latitude, longitude, cleanSpeed, ts);
        if (telegramService != null) {
            telegramService.processTelemetryAlerts(vehicle, wasBreachedBefore);
        }

        if (captureCommandService != null) {
            boolean isAccOnNow = Boolean.TRUE.equals(vehicle.getAccOn());
            if (!wasAccOnBefore && isAccOnNow) {
                captureCommandService.enqueueAutoCaptureIfAllowed(vehicle, "ACC_ON", 2, CaptureCommand.TYPE_CLIP, 15);
            }
            boolean isBreachedNow = Boolean.TRUE.equals(vehicle.getGuardEnabled())
                    && Boolean.TRUE.equals(vehicle.getGuardBreached());
            if (!wasBreachedBefore && isBreachedNow) {
                captureCommandService.enqueueAutoCaptureIfAllowed(vehicle, "GEOFENCE_BREACH", 2, CaptureCommand.TYPE_CLIP, 15);
            }
            int speedLimit = vehicle.getMaxSpeedAlertKmh() != null && vehicle.getMaxSpeedAlertKmh() > 0
                    ? vehicle.getMaxSpeedAlertKmh() : 90;
            if (cleanSpeed > speedLimit) {
                captureCommandService.enqueueAutoCaptureIfAllowed(vehicle, "OVERSPEED", 1, CaptureCommand.TYPE_CLIP, 15);
            }
        }
        return savedHistory;
    }

    public Optional<Vehicle> findVehicleByTerminalId(String terminalId) {
        if (terminalId == null || terminalId.isBlank()) {
            return Optional.empty();
        }
        String raw = terminalId.trim();
        Optional<Vehicle> byImei = vehicleRepository.findByImeiIgnoreCase(raw);
        if (byImei.isPresent()) {
            return byImei;
        }
        String trimmedZeros = raw.replaceFirst("^0+(?!$)", "");
        if (!trimmedZeros.equals(raw)) {
            byImei = vehicleRepository.findByImeiIgnoreCase(trimmedZeros);
            if (byImei.isPresent()) {
                return byImei;
            }
        }
        Optional<Vehicle> byVid = vehicleRepository.findByVehicleId(raw.toUpperCase());
        if (byVid.isPresent()) {
            return byVid;
        }
        return trimmedZeros.equals(raw)
                ? Optional.empty()
                : vehicleRepository.findByVehicleId(trimmedZeros.toUpperCase());
    }

    public boolean saveHistoryPointIfMoved(String vId, double lat, double lng, double speed, String timestamp) {
        Optional<LocationHistory> lastOpt = historyRepository.findTopByVehicleIdOrderByIdDesc(vId);
        if (lastOpt.isEmpty()) {
            historyRepository.save(new LocationHistory(vId, lat, lng, speed, timestamp));
            return true;
        }
        LocationHistory last = lastOpt.get();
        double movedKm = GeoUtils.calculateDistanceKm(last.getLatitude(), last.getLongitude(), lat, lng);
        if (movedKm >= MIN_HISTORY_DISTANCE_KM) {
            historyRepository.save(new LocationHistory(vId, lat, lng, speed, timestamp));
            return true;
        }
        return false;
    }
}
