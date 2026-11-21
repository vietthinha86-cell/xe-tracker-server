package com.xetheodoi.xe_tracker_server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataSaverVideoFlowTest {

    @Mock
    private VideoClipRepository videoClipRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private LocationHistoryRepository historyRepository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private VehicleLoanRepository loanRepository;
    @Mock
    private CaptureCommandRepository commandRepository;

    @TempDir
    Path tempMediaDir;

    @Test
    void autoCaptureTriggersOnceOnAccOnAndRespectsCooldownToSave4gSim() {
        CaptureCommandService captureService = new CaptureCommandService(commandRepository, loanRepository);
        TelemetryService telemetryService = new TelemetryService(vehicleRepository, historyRepository, null);
        telemetryService.setCaptureCommandService(captureService);

        Vehicle car = new Vehicle();
        car.setVehicleId("60A99999");
        car.setAccOn(false);
        car.setMaxSpeedAlertKmh(80);

        VehicleLoan loan = new VehicleLoan();
        loan.setId(7L);
        loan.setVehicleId("60A99999");
        loan.setBorrowerName("Trần Minh");
        loan.setStatus(VehicleLoan.STATUS_ACTIVE);
        loan.setAutoClipOnAccOn(true);
        loan.setAutoClipOnAlert(true);

        when(loanRepository.findFirstByVehicleIdAndStatusOrderByIdDesc("60A99999", VehicleLoan.STATUS_ACTIVE))
                .thenReturn(Optional.of(loan));
        when(commandRepository.findByVehicleIdAndStatusOrderByIdAsc("60A99999", CaptureCommand.STATUS_PENDING))
                .thenReturn(List.of());
        when(commandRepository.save(any(CaptureCommand.class))).thenAnswer(inv -> {
            CaptureCommand cmd = inv.getArgument(0);
            cmd.setId(101L);
            return cmd;
        });
        when(historyRepository.findTopByVehicleIdOrderByIdDesc("60A99999")).thenReturn(Optional.empty());

        // Lần 1: ACC chuyển từ false -> true => tạo đúng 1 lệnh đẩy clip 15s (LOW_360P)
        telemetryService.recordTelemetry(car, 10.90, 106.87, 15.0, 90.0, true, "2026-11-21 18:30:00");
        verify(commandRepository, times(1)).save(argThat(c ->
                "ACC_ON".equals(c.getTriggerReason())
                        && Integer.valueOf(2).equals(c.getChannel())
                        && Integer.valueOf(15).equals(c.getDurationSeconds())
                        && "LOW_360P".equals(c.getQuality())
                        && "Trần Minh".equals(c.getBorrowerName())
        ));

        // Lần 2: tắt rồi bật lại ngay trong cooldown 3 phút => không spam thêm lệnh tốn 4G SIM
        car.setAccOn(false);
        telemetryService.recordTelemetry(car, 10.91, 106.87, 18.0, 90.0, true, "2026-11-21 18:30:20");
        verify(commandRepository, times(1)).save(any(CaptureCommand.class));
    }

    @Test
    void ownerRequestsCaptureDeviceUploadsClipTaggedWithBorrowerAndAutoPrunesOldest() throws Exception {
        AuthService authService = new AuthService(userRepository, "TestSecretKey_ForUnitTests_32BytesLong!");
        CaptureCommandService captureService = new CaptureCommandService(commandRepository, loanRepository);
        VideoAndReportController controller = new VideoAndReportController(
                videoClipRepository, vehicleRepository, historyRepository, authService
        );
        controller.configureDataSaverDependencies(loanRepository, captureService, tempMediaDir.toString(), 5);

        AppUser owner = new AppUser();
        owner.setId(3L);
        owner.setUsername("chu_xe_1");
        owner.setRole("USER");
        owner.setActive(true);
        when(userRepository.findById(3L)).thenReturn(Optional.of(owner));
        String rawToken = authService.generateToken(owner);
        String bearer = "Bearer " + rawToken;

        Vehicle car = new Vehicle();
        car.setVehicleId("51K55555");
        car.setOwnerId(3L);
        car.setActivationCode("CT-55555");
        car.setLatitude(10.80);
        car.setLongitude(106.70);
        car.setSpeed(42.0);
        when(vehicleRepository.findByVehicleId("51K55555")).thenReturn(Optional.of(car));

        VehicleLoan loan = new VehicleLoan();
        loan.setId(12L);
        loan.setVehicleId("51K55555");
        loan.setBorrowerName("Lê Hoàng Nam");
        loan.setStatus(VehicleLoan.STATUS_ACTIVE);
        when(loanRepository.findFirstByVehicleIdAndStatusOrderByIdDesc("51K55555", VehicleLoan.STATUS_ACTIVE))
                .thenReturn(Optional.of(loan));

        CaptureCommand storedCmd = new CaptureCommand();
        storedCmd.setId(50L);
        when(commandRepository.save(any(CaptureCommand.class))).thenAnswer(inv -> {
            CaptureCommand c = inv.getArgument(0);
            if (c.getId() == null) c.setId(50L);
            storedCmd.setVehicleId(c.getVehicleId());
            storedCmd.setStatus(c.getStatus());
            storedCmd.setResultClipId(c.getResultClipId());
            return c;
        });

        // 1. Chủ xe gửi lệnh yêu cầu đẩy clip 15s
        ResponseEntity<Map<String, Object>> reqResp = controller.requestCapture(
                "51K55555", Map.of("channel", 2, "commandType", "CLIP", "durationSeconds", 15), bearer);
        assertEquals(HttpStatus.OK, reqResp.getStatusCode());

        // 2. Thiết bị poll lệnh bằng activationCode
        when(commandRepository.findByVehicleIdAndStatusOrderByIdAsc("51K55555", CaptureCommand.STATUS_PENDING))
                .thenReturn(List.of(storedCmd));
        ResponseEntity<Map<String, Object>> pollResp = controller.pollPendingCommands("51K55555", "CT-55555", null);
        assertEquals(HttpStatus.OK, pollResp.getStatusCode());
        @SuppressWarnings("unchecked")
        List<CaptureCommand> polled = (List<CaptureCommand>) pollResp.getBody().get("commands");
        assertEquals(1, polled.size());

        // 3. Thiết bị upload clip WebM (dùng activationCode), kèm kiểm tra auto-retention (đang có 6 clip > limit 5)
        List<VideoClip> existingLocal = new ArrayList<>();
        Path carDir = tempMediaDir.resolve("51K55555");
        Files.createDirectories(carDir);
        Path oldestFile = carDir.resolve("oldest_clip.webm");
        Files.writeString(oldestFile, "old-bytes");
        for (int i = 1; i <= 5; i++) {
            VideoClip c = new VideoClip();
            c.setId((long) i);
            c.setVehicleId("51K55555");
            c.setStorageType("LOCAL_MEDIA");
            c.setLocalFileName(i == 1 ? "oldest_clip.webm" : ("clip_" + i + ".webm"));
            existingLocal.add(c);
        }

        when(videoClipRepository.save(any(VideoClip.class))).thenAnswer(inv -> {
            VideoClip c = inv.getArgument(0);
            c.setId(600L);
            existingLocal.add(c);
            return c;
        });
        when(videoClipRepository.findByVehicleIdAndStorageTypeOrderByIdAsc("51K55555", "LOCAL_MEDIA"))
                .thenReturn(existingLocal);
        when(commandRepository.findById(50L)).thenReturn(Optional.of(storedCmd));

        MockMultipartFile clipFile = new MockMultipartFile("file", "cabin_15s.webm", "video/webm", "fake-webm-data".getBytes());
        ResponseEntity<Map<String, Object>> uploadResp = controller.uploadLocalClip(
                "51K55555", clipFile, 2, null, "OWNER_REQUEST", 15, null, 50L, "CT-55555", null, null, null, null);
        assertEquals(HttpStatus.OK, uploadResp.getStatusCode());
        assertEquals(1, uploadResp.getBody().get("prunedOldClips"));
        assertFalse(Files.exists(oldestFile), "File cũ nhất phải được tự xóa để tiết kiệm dung lượng");

        VideoClip savedClip = (VideoClip) uploadResp.getBody().get("clip");
        assertEquals("LOCAL_MEDIA", savedClip.getStorageType());
        assertEquals("VIDEO", savedClip.getMediaType());
        assertEquals(12L, savedClip.getLoanId());
        assertEquals("Lê Hoàng Nam", savedClip.getBorrowerName());
        assertEquals(CaptureCommand.STATUS_COMPLETED, storedCmd.getStatus());
        assertEquals(600L, storedCmd.getResultClipId());

        // 4. Chủ xe phát lại file qua endpoint media bằng ?token= (và chặn khi thiếu token)
        ResponseEntity<Resource> unauth = controller.streamLocalMedia("51K55555", savedClip.getLocalFileName(), null, null);
        assertEquals(HttpStatus.UNAUTHORIZED, unauth.getStatusCode());

        ResponseEntity<Resource> okStream = controller.streamLocalMedia("51K55555", savedClip.getLocalFileName(), rawToken, null);
        assertEquals(HttpStatus.OK, okStream.getStatusCode());
        assertNotNull(okStream.getBody());
        assertTrue(okStream.getBody().exists());
    }
}
