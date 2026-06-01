package com.charging.backend.model;

import java.util.ArrayList;
import java.util.List;

public class ChargingPile {
    private String id;
    private ChargeMode mode;
    private PileState state = PileState.WORKING;
    private List<Long> queueRequestIds = new ArrayList<>();
    private long totalChargeCount;
    private double totalChargeHours;
    private double totalChargeKwh;

    public ChargingPile() {
    }

    public ChargingPile(String id, ChargeMode mode) {
        this.id = id;
        this.mode = mode;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ChargeMode getMode() {
        return mode;
    }

    public void setMode(ChargeMode mode) {
        this.mode = mode;
    }

    public PileState getState() {
        return state;
    }

    public void setState(PileState state) {
        this.state = state;
    }

    public List<Long> getQueueRequestIds() {
        return queueRequestIds;
    }

    public void setQueueRequestIds(List<Long> queueRequestIds) {
        this.queueRequestIds = queueRequestIds;
    }

    public long getTotalChargeCount() {
        return totalChargeCount;
    }

    public void setTotalChargeCount(long totalChargeCount) {
        this.totalChargeCount = totalChargeCount;
    }

    public double getTotalChargeHours() {
        return totalChargeHours;
    }

    public void setTotalChargeHours(double totalChargeHours) {
        this.totalChargeHours = totalChargeHours;
    }

    public double getTotalChargeKwh() {
        return totalChargeKwh;
    }

    public void setTotalChargeKwh(double totalChargeKwh) {
        this.totalChargeKwh = totalChargeKwh;
    }
}
