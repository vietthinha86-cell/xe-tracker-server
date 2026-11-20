package com.xetheodoi.xe_tracker_server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoAndReportFlowTest {

    @Mock
    private VideoClipRepository videoClipRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private LocationHistoryRepository historyRepository;
    @Mock
    private AppUserRepository userRepository;

    @Test
    void extractsGoogleDriveIdAndEnforcesUploadAuthorization() {
        assertEquals("1AbCDefGhiJkLMnoPqRstUVwxyz123456",
                VideoAndReportController.extractGoogleDriveFileId("https://drive.google.com/file/d/1AbCDefGhiJkLMnoPqRstUVwxyz123456/view?usp=sharing"));
        assertEquals("9ZyXWvUtsrQponMlKjiHgfEdcba098",
                VideoAndReportController.extractGoogleDriveFileId("https://drive.google.com/open?id=9ZyXWvUtsrQponMlKjiHgfEdcba098"));

        AuthService authService = new AuthService(userRepository, "TestSecretKey_ForUnitTests_32BytesLong!");
        VideoAndReportController controller = new VideoAndReportController(
                videoClipRepository, vehicleRepository, historyRepository, authService
        );

        Vehicle v = new Vehicle();
        v.setVehicleId("XE001");
        v.setOwnerId(1L);
        v.setActivationCode("CT-XE001");
        v.setLatitude(10.90);
        v.setLongitude(106.87);
        v.setSpeed(35.0);

        when(vehicleRepository.findByVehicleId("XE001")).thenReturn(Optional.of(v));
        when(videoClipRepository.save(any(VideoClip.class))).thenAnswer(inv -> {
            VideoClip c = inv.getArgument(0);
            c.setId(55L);
            return c;
        });

        VideoAndReportController.VideoUploadRequest badReq = new VideoAndReportController.VideoUploadRequest();
        badReq.setDriveWebUrl("https://drive.google.com/file/d/1AbCDefGhiJkLMnoPqRstUVwxyz123456/view");
        badReq.setActivationCode("WRONG");
        ResponseEntity<Map<String, Object>> forbidden = controller.addVideoClip("XE001", badReq, null);
        assertEquals(HttpStatus.UNAUTHORIZED, forbidden.getStatusCode());

        VideoAndReportController.VideoUploadRequest okReq = new VideoAndReportController.VideoUploadRequest();
        okReq.setChannel(2);
        okReq.setTriggerType("geofence");
        okReq.setDriveWebUrl("https://drive.google.com/file/d/1AbCDefGhiJkLMnoPqRstUVwxyz123456/view?usp=sharing");
        okReq.setActivationCode("CT-XE001");

        ResponseEntity<Map<String, Object>> ok = controller.addVideoClip("XE001", okReq, null);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        VideoClip saved = (VideoClip) ok.getBody().get("clip");
        assertEquals("1AbCDefGhiJkLMnoPqRstUVwxyz123456", saved.getDriveFileId());
        assertEquals("https://drive.google.com/file/d/1AbCDefGhiJkLMnoPqRstUVwxyz123456/preview", saved.getEmbedPreviewUrl());
        assertEquals("GEOFENCE", saved.getTriggerType());
        assertEquals(2, saved.getChannel());
    }

    @Test
    void computesDashboardSummaryAndCsvReportForOwner() {
        AuthService authService = new AuthService(userRepository, "TestSecretKey_ForUnitTests_32BytesLong!");
        VideoAndReportController controller = new VideoAndReportController(
                videoClipRepository, vehicleRepository, historyRepository, authService
        );

        AppUser owner = new AppUser();
        owner.setId(5L);
        owner.setUsername("chu_xe");
        owner.setRole("USER");
        owner.setActive(true);
        when(userRepository.findById(5L)).thenReturn(Optional.of(owner));
        String token = "Bearer " + authService.generateToken(owner);

        Vehicle car = new Vehicle();
        car.setVehicleId("51H88888");
        car.setVehicleName("Kia Carnival");
        car.setOwnerId(5L);
        car.setAccOn(true);
        car.setSpeed(52.4);
        car.setMaxSpeedAlertKmh(80);
        car.setLastUpdated(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        when(vehicleRepository.findByOwnerIdOrderByIdAsc(5L)).thenReturn(List.of(car));
        when(historyRepository.findByVehicleIdAndRecordedAtStartingWithOrderByIdAsc(eq("51H88888"), any()))
                .thenReturn(List.of(
                        new LocationHistory("51H88888", 10.7700, 106.7000, 30.0, "2026-11-20 10:00:00"),
                        new LocationHistory("51H88888", 10.7790, 106.7000, 52.4, "2026-11-20 10:02:00")
                ));
        when(historyRepository.findByVehicleIdOrderByIdAsc("51H88888"))
                .thenReturn(List.of(
                        new LocationHistory("51H88888", 10.7700, 106.7000, 30.0, "2026-11-20 10:00:00"),
                        new LocationHistory("51H88888", 10.7790, 106.7000, 52.4, "2026-11-20 10:02:00")
                ));
        when(videoClipRepository.countByVehicleId("51H88888")).thenReturn(3L);
        when(videoClipRepository.countByVehicleIdAndRecordedAtStartingWith(eq("51H88888"), any())).thenReturn(2L);

        ResponseEntity<Map<String, Object>> summary = controller.getDashboardSummary(token);
        assertEquals(HttpStatus.OK, summary.getStatusCode());
        assertEquals(1, summary.getBody().get("onlineCount"));
        assertEquals(1, summary.getBody().get("accOnCount"));
        assertEquals(2L, summary.getBody().get("todayVideos"));
        assertTrue(((Double) summary.getBody().get("todayKm")) > 0.9);

        ResponseEntity<byte[]> csvResp = controller.exportVehicleReportCsv(null, token);
        assertEquals(HttpStatus.OK, csvResp.getStatusCode());
        String csvText = new String(csvResp.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csvText.contains("51H88888"));
        assertTrue(csvText.contains("Kia Carnival"));
    }
}
