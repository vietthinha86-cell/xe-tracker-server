package com.xetheodoi.xe_tracker_server;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API quản lý Video hành trình (1–4 mắt + lưu Google Drive), Bảng điều khiển tổng quan và Báo cáo xuất Excel/CSV.
 */
@RestController
@CrossOrigin(origins = "*")
public class VideoAndReportController {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern DRIVE_FILE_PATH_PATTERN = Pattern.compile("/file/d/([a-zA-Z0-9_-]{10,})");
    private static final Pattern DRIVE_ID_QUERY_PATTERN = Pattern.compile("[?&]id=([a-zA-Z0-9_-]{10,})");
    private static final List<String> DEFAULT_CHANNEL_LABELS = List.of(
            "Kênh 1 • Phía trước",
            "Kênh 2 • Trong Cabin",
            "Kênh 3 • Hông trái / Sau",
            "Kênh 4 • Hông phải / Phụ"
    );

    private final VideoClipRepository videoClipRepository;
    private final VehicleRepository vehicleRepository;
    private final LocationHistoryRepository historyRepository;
    private final AuthService authService;

    public VideoAndReportController(
            VideoClipRepository videoClipRepository,
            VehicleRepository vehicleRepository,
            LocationHistoryRepository historyRepository,
            AuthService authService
    ) {
        this.videoClipRepository = videoClipRepository;
        this.vehicleRepository = vehicleRepository;
        this.historyRepository = historyRepository;
        this.authService = authService;
    }

    public static String extractGoogleDriveFileId(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        Matcher m1 = DRIVE_FILE_PATH_PATTERN.matcher(url);
        if (m1.find()) {
            return m1.group(1);
        }
        Matcher m2 = DRIVE_ID_QUERY_PATTERN.matcher(url);
        if (m2.find()) {
            return m2.group(1);
        }
        return null;
    }

    public static String defaultChannelLabel(int ch) {
        if (ch >= 1 && ch <= DEFAULT_CHANNEL_LABELS.size()) {
            return DEFAULT_CHANNEL_LABELS.get(ch - 1);
        }
        return "Kênh " + ch;
    }

    private static String buildLiveChannelUrl(String baseUrl, int ch) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String trimmed = baseUrl.trim();
        if (trimmed.contains("{ch}")) {
            return trimmed.replace("{ch}", String.valueOf(ch));
        }
        if (trimmed.endsWith(".m3u8") || trimmed.endsWith(".flv") || trimmed.endsWith(".mp4")) {
            return ch == 1 ? trimmed : "";
        }
        return trimmed + (trimmed.endsWith("/") ? "" : "/") + "ch" + ch + ".flv";
    }

    private List<Vehicle> getAccessibleVehicles(AppUser user) {
        return user.isAdmin()
                ? vehicleRepository.findAllByOrderByIdAsc()
                : vehicleRepository.findByOwnerIdOrderByIdAsc(user.getId());
    }

    private Optional<Vehicle> findAuthorizedVehicle(String vehicleId, AppUser user) {
        String cleanId = vehicleId.trim().toUpperCase();
        Optional<Vehicle> opt = vehicleRepository.findByVehicleId(cleanId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        Vehicle v = opt.get();
        if (!user.isAdmin() && !Objects.equals(v.getOwnerId(), user.getId())) {
            return Optional.empty();
        }
        return Optional.of(v);
    }

    @GetMapping("/api/videos/{vehicleId}")
    public ResponseEntity<?> listVideos(
            @PathVariable String vehicleId,
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "channel", required = false) Integer channel,
            @RequestParam(value = "triggerType", required = false) String triggerType,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Chưa đăng nhập!"));
        }
        String cleanId = vehicleId.trim().toUpperCase();
        Optional<Vehicle> rawOpt = vehicleRepository.findByVehicleId(cleanId);
        if (rawOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "error", "message", "Không tìm thấy xe " + cleanId));
        }
        Vehicle v = rawOpt.get();
        if (!userOpt.get().isAdmin() && !Objects.equals(v.getOwnerId(), userOpt.get().getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "error", "message", "Không có quyền truy cập video của xe này!"));
        }

        List<VideoClip> clips = (date != null && !date.isBlank())
                ? videoClipRepository.findByVehicleIdAndRecordedAtStartingWithOrderByRecordedAtDescIdDesc(cleanId, date.trim())
                : videoClipRepository.findByVehicleIdOrderByRecordedAtDescIdDesc(cleanId);

        List<VideoClip> filtered = new ArrayList<>();
        String triggerFilter = triggerType != null ? triggerType.trim().toUpperCase() : "";
        for (VideoClip c : clips) {
            if (channel != null && !Objects.equals(c.getChannel(), channel)) continue;
            if (!triggerFilter.isEmpty() && !triggerFilter.equalsIgnoreCase(c.getTriggerType())) continue;
            filtered.add(c);
        }

        int channels = v.getCameraChannels() != null ? Math.max(1, Math.min(4, v.getCameraChannels())) : 2;
        List<Map<String, Object>> liveChannels = new ArrayList<>();
        for (int ch = 1; ch <= channels; ch++) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("channel", ch);
            cm.put("label", defaultChannelLabel(ch));
            cm.put("liveUrl", buildLiveChannelUrl(v.getStreamBaseUrl(), ch));
            liveChannels.add(cm);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("vehicleId", cleanId);
        resp.put("cameraChannels", channels);
        resp.put("streamBaseUrl", v.getStreamBaseUrl() != null ? v.getStreamBaseUrl() : "");
        resp.put("googleDriveFolderUrl", v.getGoogleDriveFolderUrl() != null ? v.getGoogleDriveFolderUrl() : "");
        resp.put("imei", v.getImei() != null ? v.getImei() : "");
        resp.put("liveChannels", liveChannels);
        resp.put("clips", filtered);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/videos/{vehicleId}")
    public ResponseEntity<Map<String, Object>> addVideoClip(
            @PathVariable String vehicleId,
            @RequestBody VideoUploadRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        String cleanId = vehicleId.trim().toUpperCase();
        Optional<Vehicle> vOpt = vehicleRepository.findByVehicleId(cleanId);
        if (vOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "error", "message", "Không tìm thấy xe " + cleanId));
        }
        Vehicle vehicle = vOpt.get();

        Optional<AppUser> callerOpt = authService.authenticateRequest(authHeader);
        boolean authorizedByUser = callerOpt.isPresent()
                && (callerOpt.get().isAdmin() || Objects.equals(vehicle.getOwnerId(), callerOpt.get().getId()));
        boolean authorizedByDeviceCode = false;
        if (req.getActivationCode() != null && !req.getActivationCode().isBlank() && vehicle.getActivationCode() != null) {
            authorizedByDeviceCode = vehicle.getActivationCode().trim().equalsIgnoreCase(req.getActivationCode().trim());
        }

        if (!authorizedByUser && !authorizedByDeviceCode) {
            HttpStatus status = callerOpt.isEmpty() ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
            return ResponseEntity.status(status)
                    .body(Map.of("status", "error", "message", "Thiếu quyền đăng video hoặc sai mã kích hoạt xe!"));
        }
        if (vehicle.isPlanExpired()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "expired", "message", "Gói cước xe đã hết hạn!"));
        }

        String rawDriveUrl = req.getDriveWebUrl() != null ? req.getDriveWebUrl().trim() : "";
        String rawStreamUrl = req.getStreamUrl() != null ? req.getStreamUrl().trim() : "";
        String extractedDriveId = (req.getDriveFileId() != null && !req.getDriveFileId().isBlank())
                ? req.getDriveFileId().trim()
                : extractGoogleDriveFileId(rawDriveUrl);

        if ((extractedDriveId == null || extractedDriveId.isBlank()) && rawDriveUrl.isBlank() && rawStreamUrl.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Vui lòng cung cấp link Google Drive hoặc link video trực tiếp!"));
        }

        int ch = req.getChannel() != null ? Math.max(1, Math.min(4, req.getChannel())) : 2;
        VideoClip clip = new VideoClip();
        clip.setVehicleId(cleanId);
        clip.setChannel(ch);
        clip.setChannelLabel(req.getChannelLabel() != null && !req.getChannelLabel().isBlank()
                ? req.getChannelLabel().trim()
                : defaultChannelLabel(ch));
        String trigger = req.getTriggerType() != null && !req.getTriggerType().isBlank()
                ? req.getTriggerType().trim().toUpperCase()
                : "MANUAL";
        clip.setTriggerType(trigger);
        clip.setRecordedAt(req.getRecordedAt() != null && !req.getRecordedAt().isBlank()
                ? req.getRecordedAt().trim()
                : LocalDateTime.now().format(FORMATTER));
        clip.setDurationSeconds(req.getDurationSeconds() != null && req.getDurationSeconds() > 0 ? req.getDurationSeconds() : 60);
        clip.setDriveFileId(extractedDriveId);
        clip.setDriveWebUrl(!rawDriveUrl.isBlank() ? rawDriveUrl : (extractedDriveId != null
                ? "https://drive.google.com/file/d/" + extractedDriveId + "/view"
                : null));
        clip.setStreamUrl(rawStreamUrl.isBlank() ? null : rawStreamUrl);
        clip.setStorageType(extractedDriveId != null || !rawDriveUrl.isBlank() ? "GOOGLE_DRIVE" : "DIRECT_URL");
        clip.setTitle(req.getTitle() != null && !req.getTitle().isBlank()
                ? req.getTitle().trim()
                : (clip.getChannelLabel() + " • " + clip.getRecordedAt()));
        clip.setLatitude(req.getLatitude() != null ? req.getLatitude() : vehicle.getLatitude());
        clip.setLongitude(req.getLongitude() != null ? req.getLongitude() : vehicle.getLongitude());
        clip.setSpeed(req.getSpeed() != null ? req.getSpeed() : vehicle.getSpeed());

        clip = videoClipRepository.save(clip);
        return ResponseEntity.ok(Map.of("status", "ok", "clip", clip));
    }

    @DeleteMapping("/api/videos/{vehicleId}/{clipId}")
    public ResponseEntity<Map<String, Object>> deleteClip(
            @PathVariable String vehicleId,
            @PathVariable Long clipId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Chưa đăng nhập!"));
        }
        String cleanId = vehicleId.trim().toUpperCase();
        if (findAuthorizedVehicle(cleanId, userOpt.get()).isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "error", "message", "Không có quyền xóa video của xe này!"));
        }
        Optional<VideoClip> clipOpt = videoClipRepository.findById(clipId);
        if (clipOpt.isEmpty() || !cleanId.equalsIgnoreCase(clipOpt.get().getVehicleId())) {
            return ResponseEntity.notFound().build();
        }
        videoClipRepository.delete(clipOpt.get());
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Đã xóa bản ghi video."));
    }

    @PostMapping("/api/videos/{vehicleId}/config")
    public ResponseEntity<Map<String, Object>> updateCameraConfig(
            @PathVariable String vehicleId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Chưa đăng nhập!"));
        }
        Optional<Vehicle> vOpt = findAuthorizedVehicle(vehicleId, userOpt.get());
        if (vOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "error", "message", "Không tìm thấy hoặc không có quyền cấu hình xe này!"));
        }
        Vehicle v = vOpt.get();
        if (body.containsKey("cameraChannels") && body.get("cameraChannels") instanceof Number n) {
            v.setCameraChannels(Math.max(1, Math.min(4, n.intValue())));
        }
        if (body.containsKey("streamBaseUrl")) {
            String s = body.get("streamBaseUrl") != null ? body.get("streamBaseUrl").toString().trim() : "";
            v.setStreamBaseUrl(s.isEmpty() ? null : s);
        }
        if (body.containsKey("googleDriveFolderUrl")) {
            String g = body.get("googleDriveFolderUrl") != null ? body.get("googleDriveFolderUrl").toString().trim() : "";
            v.setGoogleDriveFolderUrl(g.isEmpty() ? null : g);
        }
        if (body.containsKey("imei") && userOpt.get().isAdmin()) {
            String im = body.get("imei") != null ? body.get("imei").toString().trim() : "";
            v.setImei(im.isEmpty() ? null : im);
        }
        v = vehicleRepository.save(v);
        return ResponseEntity.ok(Map.of("status", "ok", "vehicle", v, "message", "Đã cập nhật cấu hình camera & Google Drive!"));
    }

    @GetMapping("/api/dashboard/summary")
    public ResponseEntity<Map<String, Object>> getDashboardSummary(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Chưa đăng nhập!"));
        }
        List<Vehicle> vehicles = getAccessibleVehicles(userOpt.get());
        String today = LocalDate.now().toString();
        LocalDateTime now = LocalDateTime.now();

        int onlineCount = 0;
        int offlineCount = 0;
        int alertCount = 0;
        int accOnCount = 0;
        double todayTotalKm = 0.0;
        long todayVideos = 0;
        List<Map<String, Object>> fleetRows = new ArrayList<>();

        for (Vehicle v : vehicles) {
            GeoUtils.evaluateGuardState(v);
            boolean online = isVehicleOnline(v, now);
            if (online) onlineCount++;
            else offlineCount++;

            int limitSpeed = v.getMaxSpeedAlertKmh() != null && v.getMaxSpeedAlertKmh() > 0 ? v.getMaxSpeedAlertKmh() : 90;
            boolean overspeed = online && v.getSpeed() != null && v.getSpeed() > limitSpeed;
            boolean breached = Boolean.TRUE.equals(v.getGuardEnabled()) && Boolean.TRUE.equals(v.getGuardBreached());
            if (breached || overspeed) {
                alertCount++;
            }
            if (online && Boolean.TRUE.equals(v.getAccOn())) {
                accOnCount++;
            }

            List<LocationHistory> pts = historyRepository.findByVehicleIdAndRecordedAtStartingWithOrderByIdAsc(v.getVehicleId(), today);
            RouteStats stats = computeRouteStats(pts);
            todayTotalKm += stats.totalKm;

            long vClips = videoClipRepository.countByVehicleId(v.getVehicleId());
            todayVideos += videoClipRepository.countByVehicleIdAndRecordedAtStartingWith(v.getVehicleId(), today);

            fleetRows.add(buildVehicleReportRow(v, online, breached, overspeed, stats, vClips));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("totalVehicles", vehicles.size());
        resp.put("onlineCount", onlineCount);
        resp.put("offlineCount", offlineCount);
        resp.put("alertCount", alertCount);
        resp.put("accOnCount", accOnCount);
        resp.put("todayKm", Math.round(todayTotalKm * 100.0) / 100.0);
        resp.put("todayVideos", todayVideos);
        resp.put("fleet", fleetRows);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/api/reports/vehicles")
    public ResponseEntity<Map<String, Object>> getVehicleReports(
            @RequestParam(value = "date", required = false) String date,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Chưa đăng nhập!"));
        }
        String cleanDate = (date != null && !date.isBlank()) ? date.trim() : "";
        List<Vehicle> vehicles = getAccessibleVehicles(userOpt.get());
        LocalDateTime now = LocalDateTime.now();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Vehicle v : vehicles) {
            GeoUtils.evaluateGuardState(v);
            boolean online = isVehicleOnline(v, now);
            int limitSpeed = v.getMaxSpeedAlertKmh() != null && v.getMaxSpeedAlertKmh() > 0 ? v.getMaxSpeedAlertKmh() : 90;
            boolean overspeed = online && v.getSpeed() != null && v.getSpeed() > limitSpeed;
            boolean breached = Boolean.TRUE.equals(v.getGuardEnabled()) && Boolean.TRUE.equals(v.getGuardBreached());

            List<LocationHistory> pts = cleanDate.isEmpty()
                    ? historyRepository.findByVehicleIdOrderByIdAsc(v.getVehicleId())
                    : historyRepository.findByVehicleIdAndRecordedAtStartingWithOrderByIdAsc(v.getVehicleId(), cleanDate);
            RouteStats stats = computeRouteStats(pts);
            long clipCount = cleanDate.isEmpty()
                    ? videoClipRepository.countByVehicleId(v.getVehicleId())
                    : videoClipRepository.countByVehicleIdAndRecordedAtStartingWith(v.getVehicleId(), cleanDate);

            rows.add(buildVehicleReportRow(v, online, breached, overspeed, stats, clipCount));
        }

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "date", cleanDate.isEmpty() ? "ALL" : cleanDate,
                "rows", rows
        ));
    }

    @GetMapping("/api/reports/export")
    public ResponseEntity<byte[]> exportVehicleReportCsv(
            @RequestParam(value = "date", required = false) String date,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        Optional<AppUser> userOpt = authService.authenticateRequest(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ResponseEntity<Map<String, Object>> reportResp = getVehicleReports(date, authHeader);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) reportResp.getBody().get("rows");

        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("MaXe,TenXe,TrangThai,ACC,TocDoHienTai_kmh,QuangDuong_km,TocDoToiDa_kmh,DiemGPS,SoVideo,CapNhatCuoi\n");
        for (Map<String, Object> r : rows) {
            csv.append(escapeCsv(r.get("vehicleId"))).append(',')
                    .append(escapeCsv(r.get("vehicleName"))).append(',')
                    .append(Boolean.TRUE.equals(r.get("online")) ? "Truc tuyen" : "Ngoai tuyen").append(',')
                    .append(Boolean.TRUE.equals(r.get("accOn")) ? "Bat" : "Tat").append(',')
                    .append(r.get("currentSpeed")).append(',')
                    .append(r.get("totalKm")).append(',')
                    .append(r.get("maxSpeedKmh")).append(',')
                    .append(r.get("pointCount")).append(',')
                    .append(r.get("videoCount")).append(',')
                    .append(escapeCsv(r.get("lastUpdated"))).append('\n');
        }

        String suffix = (date != null && !date.isBlank()) ? date.trim() : "all";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bao-cao-xe-" + suffix + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String escapeCsv(Object raw) {
        String s = raw != null ? raw.toString() : "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private boolean isVehicleOnline(Vehicle v, LocalDateTime now) {
        if (v.getLastUpdated() == null || v.getLastUpdated().isBlank()) return false;
        try {
            LocalDateTime updated = LocalDateTime.parse(v.getLastUpdated().trim(), FORMATTER);
            long diff = Math.abs(Duration.between(updated, now).getSeconds());
            return diff <= 45;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Map<String, Object> buildVehicleReportRow(
            Vehicle v,
            boolean online,
            boolean breached,
            boolean overspeed,
            RouteStats stats,
            long videoCount
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("vehicleId", v.getVehicleId());
        row.put("vehicleName", v.getVehicleName() != null ? v.getVehicleName() : v.getVehicleId());
        row.put("online", online);
        row.put("accOn", Boolean.TRUE.equals(v.getAccOn()));
        row.put("alert", breached || overspeed);
        row.put("breached", breached);
        row.put("overspeed", overspeed);
        row.put("currentSpeed", v.getSpeed() != null ? Math.round(v.getSpeed() * 10.0) / 10.0 : 0.0);
        row.put("totalKm", Math.round(stats.totalKm * 100.0) / 100.0);
        row.put("maxSpeedKmh", Math.round(stats.maxSpeed * 10.0) / 10.0);
        row.put("pointCount", stats.pointCount);
        row.put("videoCount", videoCount);
        row.put("cameraChannels", v.getCameraChannels() != null ? v.getCameraChannels() : 2);
        row.put("lastUpdated", v.getLastUpdated() != null ? v.getLastUpdated() : "");
        row.put("planExpiresAt", v.getPlanExpiresAt() != null ? v.getPlanExpiresAt() : "");
        row.put("planExpired", v.isPlanExpired());
        return row;
    }

    private RouteStats computeRouteStats(List<LocationHistory> points) {
        double totalKm = 0.0;
        double maxSpeed = 0.0;
        for (int i = 0; i < points.size(); i++) {
            LocationHistory p2 = points.get(i);
            if (i > 0) {
                LocationHistory p1 = points.get(i - 1);
                totalKm += GeoUtils.calculateDistanceKm(p1.getLatitude(), p1.getLongitude(), p2.getLatitude(), p2.getLongitude());
            }
            if (p2.getSpeed() != null && p2.getSpeed() > maxSpeed) {
                maxSpeed = p2.getSpeed();
            }
        }
        return new RouteStats(totalKm, maxSpeed, points.size());
    }

    private record RouteStats(double totalKm, double maxSpeed, int pointCount) {}

    public static class VideoUploadRequest {
        private Integer channel;
        private String channelLabel;
        private String title;
        private String triggerType;
        private String recordedAt;
        private Integer durationSeconds;
        private String driveFileId;
        private String driveWebUrl;
        private String streamUrl;
        private String activationCode;
        private Double latitude;
        private Double longitude;
        private Double speed;

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
        public String getDriveFileId() { return driveFileId; }
        public void setDriveFileId(String driveFileId) { this.driveFileId = driveFileId; }
        public String getDriveWebUrl() { return driveWebUrl; }
        public void setDriveWebUrl(String driveWebUrl) { this.driveWebUrl = driveWebUrl; }
        public String getStreamUrl() { return streamUrl; }
        public void setStreamUrl(String streamUrl) { this.streamUrl = streamUrl; }
        public String getActivationCode() { return activationCode; }
        public void setActivationCode(String activationCode) { this.activationCode = activationCode; }
        public Double getLatitude() { return latitude; }
        public void setLatitude(Double latitude) { this.latitude = latitude; }
        public Double getLongitude() { return longitude; }
        public void setLongitude(Double longitude) { this.longitude = longitude; }
        public Double getSpeed() { return speed; }
        public void setSpeed(Double speed) { this.speed = speed; }
    }
}
