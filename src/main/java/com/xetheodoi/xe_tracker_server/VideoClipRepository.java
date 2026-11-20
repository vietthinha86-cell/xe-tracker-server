package com.xetheodoi.xe_tracker_server;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VideoClipRepository extends JpaRepository<VideoClip, Long> {
    List<VideoClip> findByVehicleIdOrderByRecordedAtDescIdDesc(String vehicleId);
    List<VideoClip> findByVehicleIdAndRecordedAtStartingWithOrderByRecordedAtDescIdDesc(String vehicleId, String datePrefix);
    long countByVehicleId(String vehicleId);
    long countByVehicleIdAndRecordedAtStartingWith(String vehicleId, String datePrefix);
    void deleteByVehicleId(String vehicleId);
}
