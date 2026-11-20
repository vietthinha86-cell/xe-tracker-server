package com.xetheodoi.xe_tracker_server;

/**
 * Các hàm tính toán tọa độ địa lý và đánh giá trạng thái hàng rào chống trộm dùng chung.
 */
public final class GeoUtils {

    private GeoUtils() {}

    public static double calculateDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        final int earthRadiusKm = 6371;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }

    public static int sanitizeGuardRadius(Integer radius) {
        if (radius == null || radius <= 0) {
            return 100;
        }
        return Math.max(20, Math.min(5000, radius));
    }

    public static void evaluateGuardState(Vehicle vehicle) {
        boolean enabled = Boolean.TRUE.equals(vehicle.getGuardEnabled());
        int radius = sanitizeGuardRadius(vehicle.getGuardRadius());
        vehicle.setGuardEnabled(enabled);
        vehicle.setGuardRadius(radius);

        if (enabled && vehicle.getGuardLat() != null && vehicle.getGuardLng() != null
                && vehicle.getLatitude() != null && vehicle.getLongitude() != null) {
            double distMeters = calculateDistanceKm(
                    vehicle.getGuardLat(), vehicle.getGuardLng(),
                    vehicle.getLatitude(), vehicle.getLongitude()
            ) * 1000.0;
            vehicle.setGuardDistanceMeters(Math.round(distMeters * 10.0) / 10.0);
            vehicle.setGuardBreached(distMeters > radius);
        } else {
            vehicle.setGuardDistanceMeters(0.0);
            vehicle.setGuardBreached(false);
        }
    }
}
