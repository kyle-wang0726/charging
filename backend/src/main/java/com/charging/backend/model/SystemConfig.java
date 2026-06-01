package com.charging.backend.model;

public class SystemConfig {
    private int fastChargingPileNum = 2;
    private int slowChargingPileNum = 3;
    private int waitingAreaSize = 20;
    private int chargingQueueLen = 6;
    private double fastPower = 30.0;
    private double slowPower = 10.0;
    private double serviceFeePerKwh = 0.8;

    public int getFastChargingPileNum() {
        return fastChargingPileNum;
    }

    public void setFastChargingPileNum(int fastChargingPileNum) {
        this.fastChargingPileNum = fastChargingPileNum;
    }

    public int getSlowChargingPileNum() {
        return slowChargingPileNum;
    }

    public void setSlowChargingPileNum(int slowChargingPileNum) {
        this.slowChargingPileNum = slowChargingPileNum;
    }

    public int getWaitingAreaSize() {
        return waitingAreaSize;
    }

    public void setWaitingAreaSize(int waitingAreaSize) {
        this.waitingAreaSize = waitingAreaSize;
    }

    public int getChargingQueueLen() {
        return chargingQueueLen;
    }

    public void setChargingQueueLen(int chargingQueueLen) {
        this.chargingQueueLen = chargingQueueLen;
    }

    public double getFastPower() {
        return fastPower;
    }

    public void setFastPower(double fastPower) {
        this.fastPower = fastPower;
    }

    public double getSlowPower() {
        return slowPower;
    }

    public void setSlowPower(double slowPower) {
        this.slowPower = slowPower;
    }

    public double getServiceFeePerKwh() {
        return serviceFeePerKwh;
    }

    public void setServiceFeePerKwh(double serviceFeePerKwh) {
        this.serviceFeePerKwh = serviceFeePerKwh;
    }
}
