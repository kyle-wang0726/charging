package com.charging.backend.model;

import java.time.LocalDateTime;

public class ChargingRequest {
    private Long id;
    private Long userId;
    private ChargeMode mode;
    private double batteryCapacityKwh;
    private double requestedKwh;
    private String queueNumber;
    private RequestStatus status;
    private String pileId;
    private LocalDateTime enqueueTime;
    private LocalDateTime chargeStartTime;
    private LocalDateTime chargeStopTime;
    private LocalDateTime expectedFinishTime;
    private String vehicleNumber;
    private boolean faultInterrupted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public ChargeMode getMode() {
        return mode;
    }

    public void setMode(ChargeMode mode) {
        this.mode = mode;
    }

    public double getBatteryCapacityKwh() {
        return batteryCapacityKwh;
    }

    public void setBatteryCapacityKwh(double batteryCapacityKwh) {
        this.batteryCapacityKwh = batteryCapacityKwh;
    }

    public double getRequestedKwh() {
        return requestedKwh;
    }

    public void setRequestedKwh(double requestedKwh) {
        this.requestedKwh = requestedKwh;
    }

    public String getQueueNumber() {
        return queueNumber;
    }

    public void setQueueNumber(String queueNumber) {
        this.queueNumber = queueNumber;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public String getPileId() {
        return pileId;
    }

    public void setPileId(String pileId) {
        this.pileId = pileId;
    }

    public LocalDateTime getEnqueueTime() {
        return enqueueTime;
    }

    public void setEnqueueTime(LocalDateTime enqueueTime) {
        this.enqueueTime = enqueueTime;
    }

    public LocalDateTime getChargeStartTime() {
        return chargeStartTime;
    }

    public void setChargeStartTime(LocalDateTime chargeStartTime) {
        this.chargeStartTime = chargeStartTime;
    }

    public LocalDateTime getChargeStopTime() {
        return chargeStopTime;
    }

    public void setChargeStopTime(LocalDateTime chargeStopTime) {
        this.chargeStopTime = chargeStopTime;
    }

    public LocalDateTime getExpectedFinishTime() {
        return expectedFinishTime;
    }

    public void setExpectedFinishTime(LocalDateTime expectedFinishTime) {
        this.expectedFinishTime = expectedFinishTime;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public void setVehicleNumber(String vehicleNumber) {
        this.vehicleNumber = vehicleNumber;
    }

    public boolean isFaultInterrupted() {
        return faultInterrupted;
    }

    public void setFaultInterrupted(boolean faultInterrupted) {
        this.faultInterrupted = faultInterrupted;
    }
}
