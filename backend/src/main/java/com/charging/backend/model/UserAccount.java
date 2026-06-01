package com.charging.backend.model;

public class UserAccount {
    private Long id;
    private String username;
    private String password;
    private double batteryCapacityKwh;

    public UserAccount() {
    }

    public UserAccount(Long id, String username, String password, double batteryCapacityKwh) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.batteryCapacityKwh = batteryCapacityKwh;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public double getBatteryCapacityKwh() {
        return batteryCapacityKwh;
    }

    public void setBatteryCapacityKwh(double batteryCapacityKwh) {
        this.batteryCapacityKwh = batteryCapacityKwh;
    }
}
