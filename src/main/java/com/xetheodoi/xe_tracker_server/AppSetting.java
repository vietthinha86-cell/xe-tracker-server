package com.xetheodoi.xe_tracker_server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Lưu cấu hình hệ thống (vd Telegram Bot Token) bền vững trong database.
 */
@Entity
@Table(name = "app_settings")
public class AppSetting {

    @Id
    private String settingKey;

    @Column(length = 1000)
    private String settingValue;

    public AppSetting() {}

    public AppSetting(String settingKey, String settingValue) {
        this.settingKey = settingKey;
        this.settingValue = settingValue;
    }

    public String getSettingKey() { return settingKey; }
    public void setSettingKey(String settingKey) { this.settingKey = settingKey; }

    public String getSettingValue() { return settingValue; }
    public void setSettingValue(String settingValue) { this.settingValue = settingValue; }
}

