package com.xetheodoi.xe_tracker_server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VehicleControllerGuardTest {

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private LocationHistoryRepository historyRepository;

    @InjectMocks
    private VehicleController controller;

    @Test
    void guardDetectsBreachResizesRadiusAndUnlocksCleanly() {
        Vehicle car = new Vehicle();
        car.setVehicleId("XE001");
        car.setLatitude(10.84248);
        car.setLongitude(106.79967);
        car.setSpeed(0.0);

        when(vehicleRepository.findByVehicleId("XE001")).thenReturn(Optional.of(car));
        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.findTopByVehicleIdOrderByIdDesc("XE001")).thenReturn(Optional.empty());

        // 1. Khóa bãi đỗ 100m tại vị trí xe hiện tại
        VehicleController.GuardRequest lockReq = new VehicleController.GuardRequest();
        lockReq.setEnabled(true);
        lockReq.setRadius(100);

        ResponseEntity<Map<String, Object>> lockResp = controller.updateGuard("XE001", lockReq, null);
        assertEquals(HttpStatus.OK, lockResp.getStatusCode());
        assertTrue(Boolean.TRUE.equals(car.getGuardEnabled()));
        assertEquals(100, car.getGuardRadius());
        assertFalse(Boolean.TRUE.equals(car.getGuardBreached()));
        assertEquals(0.0, car.getGuardDistanceMeters());

        // 2. Xe dịch chuyển ~222m về hướng Bắc (vượt quá bán kính 100m)
        Vehicle moved = new Vehicle();
        moved.setVehicleId("XE001");
        moved.setLatitude(10.84448);
        moved.setLongitude(106.79967);
        moved.setSpeed(25.0);

        ResponseEntity<Map<String, Object>> updateResp = controller.updateLocation(moved, null);
        assertEquals(HttpStatus.OK, updateResp.getStatusCode());
        assertTrue(Boolean.TRUE.equals(car.getGuardBreached()));
        assertTrue(car.getGuardDistanceMeters() > 200.0);

        // 3. Mở rộng bán kính lên 500m (giữ nguyên tâm đỗ ban đầu) -> không còn vượt rào
        VehicleController.GuardRequest resizeReq = new VehicleController.GuardRequest();
        resizeReq.setEnabled(true);
        resizeReq.setRadius(500);
        resizeReq.setKeepCenter(true);

        controller.updateGuard("XE001", resizeReq, null);
        assertEquals(500, car.getGuardRadius());
        assertEquals(10.84248, car.getGuardLat());
        assertFalse(Boolean.TRUE.equals(car.getGuardBreached()));

        // 4. Mở khóa bãi đỗ -> xóa tâm & tắt cảnh báo
        VehicleController.GuardRequest unlockReq = new VehicleController.GuardRequest();
        unlockReq.setEnabled(false);
        controller.updateGuard("XE001", unlockReq, null);
        assertFalse(Boolean.TRUE.equals(car.getGuardEnabled()));
        assertNull(car.getGuardLat());
        assertFalse(Boolean.TRUE.equals(car.getGuardBreached()));
    }

    @Test
    void lockWithoutLocationReturnsBadRequestAndLegacyNullGuardNormalizes() {
        when(vehicleRepository.findByVehicleId("XE999")).thenReturn(Optional.empty());
        VehicleController.GuardRequest lockReq = new VehicleController.GuardRequest();
        lockReq.setEnabled(true);
        lockReq.setRadius(100);

        ResponseEntity<Map<String, Object>> resp = controller.updateGuard("XE999", lockReq, null);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());

        Vehicle legacy = new Vehicle();
        legacy.setVehicleId("XE002");
        legacy.setLatitude(10.80);
        legacy.setLongitude(106.70);
        legacy.setGuardEnabled(null);
        legacy.setGuardRadius(null);
        legacy.setGuardBreached(null);
        legacy.setGuardDistanceMeters(null);
        when(vehicleRepository.findByVehicleId("XE002")).thenReturn(Optional.of(legacy));

        ResponseEntity<?> locResp = controller.getLocation("XE002", null);
        assertEquals(HttpStatus.OK, locResp.getStatusCode());
        Vehicle body = assertInstanceOf(Vehicle.class, locResp.getBody());
        assertFalse(body.getGuardEnabled());
        assertEquals(100, body.getGuardRadius());
        assertFalse(body.getGuardBreached());
        assertEquals(0.0, body.getGuardDistanceMeters());
    }

    @Test
    void commercialActivationCodeAndPlanExpiryEnforced() {
        Vehicle commercialCar = new Vehicle();
        commercialCar.setVehicleId("51K88888");
        commercialCar.setActivationCode("CT-998877");
        commercialCar.setRequireActivationCode(true);
        commercialCar.setPlanExpiresAt("2099-12-31");

        when(vehicleRepository.findByVehicleId("51K88888")).thenReturn(Optional.of(commercialCar));
        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.findTopByVehicleIdOrderByIdDesc("51K88888")).thenReturn(Optional.empty());

        // Sai mã kích hoạt -> bị chặn 403
        Vehicle badPing = new Vehicle();
        badPing.setVehicleId("51K88888");
        badPing.setActivationCode("WRONG-CODE");
        badPing.setLatitude(10.8);
        badPing.setLongitude(106.7);
        ResponseEntity<Map<String, Object>> badResp = controller.updateLocation(badPing, null);
        assertEquals(HttpStatus.FORBIDDEN, badResp.getStatusCode());

        // Đúng mã kích hoạt -> cho phép 200 OK
        Vehicle goodPing = new Vehicle();
        goodPing.setVehicleId("51K88888");
        goodPing.setActivationCode("ct-998877");
        goodPing.setLatitude(10.8);
        goodPing.setLongitude(106.7);
        goodPing.setSpeed(30.0);
        ResponseEntity<Map<String, Object>> goodResp = controller.updateLocation(goodPing, null);
        assertEquals(HttpStatus.OK, goodResp.getStatusCode());

        // Quá hạn gói -> bị chặn 403
        commercialCar.setPlanExpiresAt("2020-01-01");
        ResponseEntity<Map<String, Object>> expiredResp = controller.updateLocation(goodPing, null);
        assertEquals(HttpStatus.FORBIDDEN, expiredResp.getStatusCode());
    }

    @Test
    void historyAndStatsFilterByDateCleanly() {
        Vehicle v = new Vehicle();
        v.setVehicleId("XE001");
        when(vehicleRepository.findByVehicleId("XE001")).thenReturn(Optional.of(v));
        when(historyRepository.findByVehicleIdAndRecordedAtStartingWithOrderByIdAsc("XE001", "2026-11-14"))
                .thenReturn(List.of(
                        new LocationHistory("XE001", 10.8000, 106.7000, 40.0, "2026-11-14 08:00:00"),
                        new LocationHistory("XE001", 10.8090, 106.7000, 50.0, "2026-11-14 08:05:00")
                ));

        ResponseEntity<?> histResp = controller.getHistory("XE001", "2026-11-14", null);
        assertEquals(HttpStatus.OK, histResp.getStatusCode());
        List<?> pts = assertInstanceOf(List.class, histResp.getBody());
        assertEquals(2, pts.size());

        ResponseEntity<Map<String, Object>> statsResp = controller.getStats("XE001", "2026-11-14", null);
        assertEquals(HttpStatus.OK, statsResp.getStatusCode());
        assertEquals(2, statsResp.getBody().get("totalPoints"));
        assertTrue(((Number) statsResp.getBody().get("totalKm")).doubleValue() > 0.9);
    }
}
