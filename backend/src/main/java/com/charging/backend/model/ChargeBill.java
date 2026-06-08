package com.charging.backend.model;

import java.time.LocalDateTime;

public class ChargeBill {
    private String billNo;
    private Long requestId;
    private String billStatus;
    private LocalDateTime generatedAt;
    private Long userId;
    private String pileId;
    private double chargedKwh;
    private double chargedHours;
    private LocalDateTime startTime;
    private LocalDateTime stopTime;
    private String vehicleNumber;
    private double chargeFee;
    private double serviceFee;
    private double totalFee;

    public String getBillNo() {
        return billNo;
    }

    public void setBillNo(String billNo) {
        this.billNo = billNo;
    }

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public String getBillStatus() {
        return billStatus;
    }

    public void setBillStatus(String billStatus) {
        this.billStatus = billStatus;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(LocalDateTime generatedAt) {
        this.generatedAt = generatedAt;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPileId() {
        return pileId;
    }

    public void setPileId(String pileId) {
        this.pileId = pileId;
    }

    public double getChargedKwh() {
        return chargedKwh;
    }

    public void setChargedKwh(double chargedKwh) {
        this.chargedKwh = chargedKwh;
    }

    public double getChargedHours() {
        return chargedHours;
    }

    public void setChargedHours(double chargedHours) {
        this.chargedHours = chargedHours;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getStopTime() {
        return stopTime;
    }

    public void setStopTime(LocalDateTime stopTime) {
        this.stopTime = stopTime;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public void setVehicleNumber(String vehicleNumber) {
        this.vehicleNumber = vehicleNumber;
    }

    public double getChargeFee() {
        return chargeFee;
    }

    public void setChargeFee(double chargeFee) {
        this.chargeFee = chargeFee;
    }

    public double getServiceFee() {
        return serviceFee;
    }

    public void setServiceFee(double serviceFee) {
        this.serviceFee = serviceFee;
    }

    public double getTotalFee() {
        return totalFee;
    }

    public void setTotalFee(double totalFee) {
        this.totalFee = totalFee;
    }
}
