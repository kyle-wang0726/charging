package com.charging.backend.service;

import com.charging.backend.model.ChargeBill;
import com.charging.backend.model.ChargeMode;
import com.charging.backend.model.ChargingPile;
import com.charging.backend.model.ChargingRequest;
import com.charging.backend.model.DispatchStrategy;
import com.charging.backend.model.FaultDispatchStrategy;
import com.charging.backend.model.PileState;
import com.charging.backend.model.RequestStatus;
import com.charging.backend.model.SystemConfig;
import com.charging.backend.model.UserAccount;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class StationService {
    private final BillingService billingService;
    private final SystemConfig config = new SystemConfig();

    private final AtomicLong userIdSeq = new AtomicLong(1000);
    private final AtomicLong requestIdSeq = new AtomicLong(1);
    private final AtomicLong billSeq = new AtomicLong(1);
    private int fastQueueSeq = 1;
    private int slowQueueSeq = 1;

    private FaultDispatchStrategy faultDispatchStrategy = FaultDispatchStrategy.PRIORITY;
    private DispatchStrategy dispatchStrategy = DispatchStrategy.NORMAL;
    private LocalDateTime systemNow = LocalDateTime.of(2026, 6, 1, 6, 0, 0);

    @Value("${log.file-path:./logs/log.txt}")
    private String logFilePath;

    @Value("${users.file-path:./data/users.json}")
    private String usersFilePath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initLogFile() {
        try {
            File file = new File(logFilePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (PrintWriter pw = new PrintWriter(new FileWriter(file, false))) {
                pw.println("=== 充电系统日志 ===");
                pw.println("启动时间: " + systemNow);
                pw.println();
            }
        } catch (IOException e) {
            System.err.println("Failed to init log file: " + e.getMessage());
        }
    }

    @PostConstruct
    public void initUsers() {
        try {
            File file = new File(usersFilePath);
            if (file.exists()) {
                List<UserAccount> loaded = objectMapper.readValue(file, new TypeReference<List<UserAccount>>() {});
                for (UserAccount user : loaded) {
                    if (user.getUsername() != null && !user.getUsername().isEmpty()
                            && !userByName.containsKey(user.getUsername())) {
                        users.put(user.getId(), user);
                        userByName.put(user.getUsername(), user);
                        if (user.getId() >= userIdSeq.get()) {
                            userIdSeq.set(user.getId() + 1);
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load users: " + e.getMessage());
        }
    }

    private final Map<Long, UserAccount> users = new LinkedHashMap<>();
    private final Map<String, UserAccount> userByName = new LinkedHashMap<>();
    private final Map<Long, ChargingRequest> requests = new LinkedHashMap<>();
    private final List<ChargeBill> bills = new ArrayList<>();
    private final Map<String, ChargingPile> piles = new LinkedHashMap<>();

    private final List<Long> waitingFast = new ArrayList<>();
    private final List<Long> waitingSlow = new ArrayList<>();
    private final List<Long> faultPriorityFast = new ArrayList<>();
    private final List<Long> faultPrioritySlow = new ArrayList<>();
    private final List<Long> batchWaitingOrder = new ArrayList<>();

    public StationService(BillingService billingService) {
        this.billingService = billingService;
        initPiles();
    }

    public synchronized UserAccount register(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("username cannot be empty");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("password cannot be empty");
        }
        if (userByName.containsKey(username)) {
            throw new IllegalArgumentException("username already exists");
        }
        UserAccount user = new UserAccount(userIdSeq.getAndIncrement(), username, password);
        users.put(user.getId(), user);
        userByName.put(user.getUsername(), user);
        saveUsers();
        return user;
    }

    public synchronized UserAccount login(String username, String password) {
        UserAccount user = userByName.get(username);
        if (user == null || !user.getPassword().equals(password)) {
            throw new IllegalArgumentException("invalid username or password");
        }
        return user;
    }

    public synchronized ChargingRequest submitRequest(Long userId, ChargeMode mode, double requestKwh, double batteryCapacityKwh, String vehicleNumber) {
        refreshAndDispatch(now());
        validateUser(userId);
        if (vehicleNumber == null || vehicleNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("vehicle number cannot be empty");
        }
        validateRequestAmounts(requestKwh, batteryCapacityKwh);
        if (stationLoadForAdmission() >= admissionCapacity()) {
            throw new IllegalArgumentException("waiting area is full, cannot create request");
        }
        ChargingRequest req = new ChargingRequest();
        req.setId(requestIdSeq.getAndIncrement());
        req.setUserId(userId);
        req.setMode(mode);
        req.setBatteryCapacityKwh(batteryCapacityKwh);
        req.setRequestedKwh(requestKwh);
        req.setVehicleNumber(vehicleNumber.trim());
        req.setQueueNumber(nextQueueNumber(mode));
        req.setStatus(RequestStatus.WAITING_AREA);
        req.setEnqueueTime(now());
        requests.put(req.getId(), req);
        waitingListByMode(mode).add(req.getId());
        refreshAndDispatch(now());
        logCurrentState();
        return req;
    }

    public synchronized ChargingRequest modifyRequest(Long userId, Long requestId, ChargeMode mode, Double requestKwh, Double batteryCapacityKwh) {
        refreshAndDispatch(now());
        ChargingRequest req = resolveRequestForModify(userId, requestId);
        if (mode != null && req.getMode() != mode) {
            if (req.getStatus() != RequestStatus.WAITING_AREA) {
                throw new IllegalArgumentException("mode can only be changed in waiting area");
            }
            waitingListByMode(req.getMode()).remove(req.getId());
            req.setMode(mode);
            req.setQueueNumber(nextQueueNumber(mode));
            waitingListByMode(mode).add(req.getId());
        }
        if (batteryCapacityKwh != null) {
            if (req.getStatus() != RequestStatus.WAITING_AREA) {
                throw new IllegalArgumentException("battery capacity can only be changed in waiting area");
            }
            if (batteryCapacityKwh <= 0) {
                throw new IllegalArgumentException("battery capacity must be greater than 0");
            }
        }
        if (requestKwh != null) {
            if (req.getStatus() != RequestStatus.WAITING_AREA) {
                throw new IllegalArgumentException("request kwh can only be changed in waiting area");
            }
            if (requestKwh <= 0) {
                throw new IllegalArgumentException("request kwh must be greater than 0");
            }
        }
        double newCapacity = batteryCapacityKwh != null ? batteryCapacityKwh : req.getBatteryCapacityKwh();
        double newRequestKwh = requestKwh != null ? requestKwh : req.getRequestedKwh();
        validateRequestAmounts(newRequestKwh, newCapacity);
        if (batteryCapacityKwh != null) {
            req.setBatteryCapacityKwh(batteryCapacityKwh);
        }
        if (requestKwh != null) {
            req.setRequestedKwh(requestKwh);
        }
        refreshAndDispatch(now());
        logCurrentState();
        return req;
    }

    public synchronized void cancelRequest(Long userId, Long requestId) {
        refreshAndDispatch(now());
        ChargingRequest req = resolveRequestForCancel(userId, requestId);
        if (req.getStatus() == RequestStatus.WAITING_AREA) {
            waitingListByMode(req.getMode()).remove(req.getId());
            faultPriorityByMode(req.getMode()).remove(req.getId());
            batchWaitingOrder.remove(req.getId());
            createCancelBill(req, now());
            req.setStatus(RequestStatus.CANCELED);
            logCurrentState();
            return;
        }
        if (req.getStatus() == RequestStatus.QUEUED) {
            ChargingPile pile = piles.get(req.getPileId());
            if (pile != null) {
                pile.getQueueRequestIds().remove(req.getId());
            }
            createCancelBill(req, now());
            req.setStatus(RequestStatus.CANCELED);
            req.setPileId(null);
            refreshAndDispatch(now());
            logCurrentState();
            return;
        }
        if (req.getStatus() == RequestStatus.CHARGING) {
            ChargingPile pile = piles.get(req.getPileId());
            if (pile != null) {
                buildAndStoreBill(req, pile, now(), "提前结束");
                pile.getQueueRequestIds().remove(req.getId());
            }
            req.setStatus(RequestStatus.CANCELED);
            req.setPileId(null);
            refreshAndDispatch(now());
            logCurrentState();
        }
    }

    public synchronized ChargeBill endCharging(Long userId, Long requestId) {
        refreshAndDispatch(now());
        ChargingRequest req = resolveRequestForEnd(userId, requestId);
        if (req.getStatus() != RequestStatus.CHARGING) {
            throw new IllegalArgumentException("request is not charging");
        }
        ChargingPile pile = piles.get(req.getPileId());
        ChargeBill bill = buildAndStoreBill(req, pile, now(), "提前结束");
        req.setStatus(RequestStatus.COMPLETED);
        pile.getQueueRequestIds().remove(req.getId());
        refreshAndDispatch(now());
        logCurrentState();
        return bill;
    }

    public synchronized Map<String, Object> getQueueInfo(Long userId, Long requestId) {
        refreshAndDispatch(now());
        ChargingRequest req;
        try {
            req = resolveRequestForQueueInfo(userId, requestId);
        } catch (IllegalArgumentException ex) {
            if ("no active request".equals(ex.getMessage())) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("active", false);
                data.put("systemTime", now());
                return data;
            }
            throw ex;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        LocalDateTime[] estimated = estimateStartAndFinish(req, now());
        data.put("active", true);
        data.put("requestId", req.getId());
        data.put("status", req.getStatus());
        data.put("mode", req.getMode());
        data.put("queueNumber", req.getQueueNumber());
        data.put("requestKwh", req.getRequestedKwh());
        data.put("batteryCapacityKwh", req.getBatteryCapacityKwh());
        data.put("pileId", req.getPileId());
        data.put("queueArea", queueAreaOf(req));
        data.put("inFaultQueue", isInFaultPriorityQueue(req));
        data.put("frontCars", countFrontCars(req));
        data.put("enqueueTime", req.getEnqueueTime());
        data.put("startTime", estimated[0]);
        data.put("expectedFinishTime", estimated[1]);
        data.put("systemTime", now());
        if (req.getStatus() == RequestStatus.CHARGING) {
            ChargingPile pile = piles.get(req.getPileId());
            if (pile != null) {
                ChargeBill preview = buildPreviewBill(req, pile, now());
                data.put("chargedKwh", preview.getChargedKwh());
                data.put("chargedFee", preview.getTotalFee());
            } else {
                data.put("chargedKwh", 0);
                data.put("chargedFee", 0);
            }
        } else {
            data.put("chargedKwh", 0);
            data.put("chargedFee", 0);
        }
        data.put("multipleActiveRequests", findActiveRequestsByUser(userId).size() > 1);
        return data;
    }

    public synchronized List<Map<String, Object>> getUserRequests(Long userId, boolean includeFinished) {
        refreshAndDispatch(now());
        validateUser(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChargingRequest req : requests.values()) {
            if (!Objects.equals(req.getUserId(), userId)) {
                continue;
            }
            if (!includeFinished && !isActive(req)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("requestId", req.getId());
            item.put("status", req.getStatus());
            item.put("mode", req.getMode());
            item.put("queueNumber", req.getQueueNumber());
            item.put("requestKwh", req.getRequestedKwh());
            item.put("batteryCapacityKwh", req.getBatteryCapacityKwh());
            item.put("pileId", req.getPileId());
            item.put("vehicleNumber", req.getVehicleNumber());
            item.put("queueArea", queueAreaOf(req));
            item.put("inFaultQueue", isInFaultPriorityQueue(req));
            item.put("frontCars", countFrontCars(req));
            item.put("enqueueTime", req.getEnqueueTime());
            LocalDateTime[] estimated = estimateStartAndFinish(req, now());
            item.put("startTime", estimated[0]);
            item.put("expectedFinishTime", estimated[1]);
            result.add(item);
        }
        return result;
    }

    public synchronized List<ChargeBill> getUserBills(Long userId) {
        return bills.stream().filter(b -> Objects.equals(b.getUserId(), userId)).collect(Collectors.toList());
    }

    public synchronized List<Map<String, Object>> getPileStatuses() {
        refreshAndDispatch(now());
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChargingPile pile : piles.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pileId", pile.getId());
            item.put("mode", pile.getMode());
            item.put("state", pile.getState());
            item.put("totalChargeCount", pile.getTotalChargeCount());
            item.put("totalChargeHours", round(pile.getTotalChargeHours()));
            item.put("totalChargeKwh", round(pile.getTotalChargeKwh()));
            List<Map<String, Object>> queueCars = new ArrayList<>();
            for (Long requestId : pile.getQueueRequestIds()) {
                ChargingRequest req = requests.get(requestId);
                if (req == null) {
                    continue;
                }
                UserAccount user = users.get(req.getUserId());
                Map<String, Object> car = new LinkedHashMap<>();
                car.put("userId", req.getUserId());
                car.put("username", user == null ? "" : user.getUsername());
                car.put("requestId", req.getId());
                car.put("batteryCapacityKwh", req.getBatteryCapacityKwh());
                car.put("requestKwh", req.getRequestedKwh());
                car.put("vehicleNumber", req.getVehicleNumber());
                car.put("queueNumber", req.getQueueNumber());
                car.put("status", req.getStatus());
                car.put("queuedMinutes", req.getEnqueueTime() == null ? 0 : Duration.between(req.getEnqueueTime(), now()).toMinutes());
                LocalDateTime[] estimated = estimateStartAndFinish(req, now());
                car.put("startTime", estimated[0]);
                car.put("expectedFinishTime", estimated[1]);
                queueCars.add(car);
            }
            item.put("queueCars", queueCars);
            result.add(item);
        }
        return result;
    }

    public synchronized void changePileState(String pileId, PileState targetState) {
        refreshAndDispatch(now());
        ChargingPile pile = requirePile(pileId);
        PileState previous = pile.getState();
        if (previous == targetState) {
            return;
        }
        if (targetState == PileState.FAULT) {
            handlePileFault(pile);
            refreshAndDispatch(now());
            logCurrentState();
            return;
        }
        pile.setState(targetState);
        if (targetState == PileState.SHUTDOWN) {
            movePileQueuedToWaiting(pile);
        } else if (previous == PileState.FAULT && targetState == PileState.WORKING) {
            handlePileRecoveryRebalance(pile.getMode());
        }
        refreshAndDispatch(now());
        logCurrentState();
    }

    public synchronized void setFaultDispatchStrategy(FaultDispatchStrategy strategy) {
        this.faultDispatchStrategy = strategy;
    }

    public synchronized FaultDispatchStrategy getFaultDispatchStrategy() {
        return faultDispatchStrategy;
    }

    public synchronized void setDispatchStrategy(DispatchStrategy strategy) {
        if (this.dispatchStrategy == DispatchStrategy.BATCH_SHORTEST_TOTAL_TIME
                && strategy != DispatchStrategy.BATCH_SHORTEST_TOTAL_TIME) {
            releaseBatchWaitingToModeQueues();
        }
        this.dispatchStrategy = strategy == null ? DispatchStrategy.NORMAL : strategy;
        refreshAndDispatch(now());
    }

    public synchronized DispatchStrategy getDispatchStrategy() {
        return dispatchStrategy;
    }

    public synchronized SystemConfig getConfig() {
        return config;
    }

    public synchronized void updateConfig(Integer waitingAreaSize,
                                          Integer queueLen,
                                          Integer fastPileNum,
                                          Integer slowPileNum,
                                          Double fastPower,
                                          Double slowPower) {
        boolean pileCountChanged = false;
        if (fastPileNum != null && fastPileNum > 0 && fastPileNum != config.getFastChargingPileNum()) {
            pileCountChanged = true;
        }
        if (slowPileNum != null && slowPileNum > 0 && slowPileNum != config.getSlowChargingPileNum()) {
            pileCountChanged = true;
        }
        if (pileCountChanged && hasActiveRequests()) {
            throw new IllegalArgumentException("cannot change pile count while requests are active");
        }
        if (waitingAreaSize != null && waitingAreaSize > 0) {
            config.setWaitingAreaSize(waitingAreaSize);
        }
        if (queueLen != null && queueLen > 0) {
            config.setChargingQueueLen(queueLen);
        }
        if (fastPileNum != null && fastPileNum > 0) {
            config.setFastChargingPileNum(fastPileNum);
        }
        if (slowPileNum != null && slowPileNum > 0) {
            config.setSlowChargingPileNum(slowPileNum);
        }
        if (fastPower != null && fastPower > 0) {
            config.setFastPower(fastPower);
        }
        if (slowPower != null && slowPower > 0) {
            config.setSlowPower(slowPower);
        }
        if (pileCountChanged) {
            initPiles();
        }
        refreshAndDispatch(now());
    }

    public synchronized List<Map<String, Object>> report(String period) {
        LocalDateTime current = now();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChargingPile pile : piles.values()) {
            long count = 0;
            double hours = 0;
            double kwh = 0;
            double chargeFee = 0;
            double serviceFee = 0;
            double totalFee = 0;
            for (ChargeBill bill : bills) {
                if (!Objects.equals(bill.getPileId(), pile.getId())) {
                    continue;
                }
                if (!inPeriod(bill.getGeneratedAt(), current, period)) {
                    continue;
                }
                count++;
                hours += bill.getChargedHours();
                kwh += bill.getChargedKwh();
                chargeFee += bill.getChargeFee();
                serviceFee += bill.getServiceFee();
                totalFee += bill.getTotalFee();
            }
            for (ChargingRequest req : requests.values()) {
                if (req.getStatus() != RequestStatus.CHARGING || !Objects.equals(req.getPileId(), pile.getId())) {
                    continue;
                }
                LocalDateTime stop = current;
                if (req.getExpectedFinishTime() != null && req.getExpectedFinishTime().isBefore(stop)) {
                    stop = req.getExpectedFinishTime();
                }
                ChargeBill active = buildPreviewBill(req, pile, stop);
                if (!inPeriod(active.getGeneratedAt(), current, period)) {
                    continue;
                }
                hours += active.getChargedHours();
                kwh += active.getChargedKwh();
                chargeFee += active.getChargeFee();
                serviceFee += active.getServiceFee();
                totalFee += active.getTotalFee();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", (period == null ? "day" : period).toUpperCase(Locale.ROOT));
            row.put("pileId", pile.getId());
            row.put("totalChargeCount", count);
            row.put("totalChargeHours", round(hours));
            row.put("totalChargeKwh", round(kwh));
            row.put("totalChargeFee", round(chargeFee));
            row.put("totalServiceFee", round(serviceFee));
            row.put("totalFee", round(totalFee));
            rows.add(row);
        }
        return rows;
    }

    public synchronized LocalDateTime getSystemTime() {
        return systemNow;
    }

    public synchronized LocalDateTime advanceTime(long minutes) {
        if (minutes <= 0) {
            throw new IllegalArgumentException("minutes must be greater than 0");
        }
        LocalDateTime target = systemNow.plusMinutes(minutes);

        // 先在当前时刻完成一次调度，确保可立即开始的请求进入充电态
        refreshAndDispatch(systemNow);

        // 在 [systemNow, target] 区间内按“下一辆车完成时间”逐事件推进
        while (true) {
            LocalDateTime nextFinish = findEarliestChargingFinishAtOrBefore(target);
            if (nextFinish == null) {
                break;
            }
            systemNow = nextFinish;
            refreshAndDispatch(systemNow);
        }

        // 最后推进到目标时刻并收尾调度
        systemNow = target;
        refreshAndDispatch(systemNow);
        return systemNow;
    }

    private void logCurrentState() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(logFilePath, true))) {
            pw.println("=== " + now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " ===");

            // 充电区日志
            pw.println("充电区:");
            for (ChargingPile pile : piles.values()) {
                if (pile.getQueueRequestIds().isEmpty()) {
                    continue;
                }
                pw.println("(" + pile.getId() + ")");
                for (Long requestId : pile.getQueueRequestIds()) {
                    ChargingRequest req = requests.get(requestId);
                    if (req == null) continue;
                    String vn = req.getVehicleNumber() != null ? req.getVehicleNumber() : "--";
                    double chargedKwh = 0;
                    double fee = 0;
                    if (req.getStatus() == RequestStatus.CHARGING) {
                        ChargeBill preview = buildPreviewBill(req, pile, now());
                        chargedKwh = preview.getChargedKwh();
                        fee = preview.getTotalFee();
                    }
                    pw.printf("(%s,%.2f,%.2f)%n", vn, chargedKwh, fee);
                }
            }

            // 等候区日志
            pw.print("等候区:");
            List<Long> waitingAll = new ArrayList<>();
            waitingAll.addAll(faultPriorityFast);
            waitingAll.addAll(faultPrioritySlow);
            waitingAll.addAll(waitingFast);
            waitingAll.addAll(waitingSlow);
            waitingAll.addAll(batchWaitingOrder);
            if (waitingAll.isEmpty()) {
                pw.println("(无)");
            } else {
                StringBuilder sb = new StringBuilder();
                for (Long id : waitingAll) {
                    ChargingRequest req = requests.get(id);
                    if (req == null) continue;
                    String vn = req.getVehicleNumber() != null ? req.getVehicleNumber() : "--";
                    String type = req.getMode() == ChargeMode.FAST ? "F" : "T";
                    sb.append(String.format("(%s,%s,%.2f)", vn, type, req.getRequestedKwh()));
                    sb.append("-");
                }
                if (sb.length() > 0) sb.setLength(sb.length() - 1);
                pw.println(sb.toString());
            }
            pw.println();
        } catch (IOException e) {
            System.err.println("Failed to write log: " + e.getMessage());
        }
    }

    private void initPiles() {
        piles.clear();
        for (int i = 1; i <= config.getFastChargingPileNum(); i++) {
            piles.put("F" + i, new ChargingPile("F" + i, ChargeMode.FAST));
        }
        for (int i = 1; i <= config.getSlowChargingPileNum(); i++) {
            piles.put("T" + i, new ChargingPile("T" + i, ChargeMode.SLOW));
        }
    }

    private void handlePileFault(ChargingPile faultPile) {
        faultPile.setState(PileState.FAULT);
        List<Long> impacted = new ArrayList<>();
        if (!faultPile.getQueueRequestIds().isEmpty()) {
            Long firstId = faultPile.getQueueRequestIds().get(0);
            ChargingRequest chargingReq = requests.get(firstId);
            if (chargingReq != null && chargingReq.getStatus() == RequestStatus.CHARGING) {
                ChargeBill partial = buildAndStoreBill(chargingReq, faultPile, now(), "故障中断");
                double remaining = round(Math.max(0.0, chargingReq.getRequestedKwh() - partial.getChargedKwh()));
                faultPile.getQueueRequestIds().remove(0);
                if (remaining > 0.0) {
                    chargingReq.setFaultInterrupted(true);
                    chargingReq.setRequestedKwh(remaining);
                    chargingReq.setStatus(RequestStatus.WAITING_AREA);
                    chargingReq.setPileId(null);
                    chargingReq.setChargeStartTime(null);
                    chargingReq.setChargeStopTime(null);
                    chargingReq.setExpectedFinishTime(null);
                    impacted.add(chargingReq.getId());
                } else {
                    chargingReq.setStatus(RequestStatus.COMPLETED);
                    chargingReq.setPileId(null);
                }
            }
        }
        impacted.addAll(faultPile.getQueueRequestIds());
        faultPile.getQueueRequestIds().clear();
        if (impacted.isEmpty()) {
            return;
        }

        if (faultDispatchStrategy == FaultDispatchStrategy.PRIORITY) {
            for (Long id : impacted) {
                ChargingRequest req = requests.get(id);
                if (req == null) {
                    continue;
                }
                req.setStatus(RequestStatus.WAITING_AREA);
                req.setPileId(null);
                faultPriorityByMode(req.getMode()).add(req.getId());
            }
            return;
        }

        ChargeMode mode = faultPile.getMode();
        List<Long> merged = new ArrayList<>(impacted);
        for (ChargingPile pile : piles.values()) {
            if (pile.getState() != PileState.WORKING || pile.getMode() != mode) {
                continue;
            }
            List<Long> toRemove = new ArrayList<>();
            for (Long requestId : pile.getQueueRequestIds()) {
                ChargingRequest req = requests.get(requestId);
                if (req != null && req.getStatus() == RequestStatus.QUEUED) {
                    merged.add(requestId);
                    toRemove.add(requestId);
                }
            }
            pile.getQueueRequestIds().removeAll(toRemove);
        }
        merged.sort(Comparator.comparingInt(this::queueOrder));
        for (Long id : merged) {
            ChargingRequest req = requests.get(id);
            if (req == null) {
                continue;
            }
            req.setPileId(null);
            req.setStatus(RequestStatus.WAITING_AREA);
            faultPriorityByMode(mode).add(id);
        }
    }

    private void handlePileRecoveryRebalance(ChargeMode mode) {
        // 合并故障优先队列 + 其他 WORKING 同类型充电桩中尚未充电的车辆
        List<Long> all = new ArrayList<>();
        all.addAll(faultPriorityByMode(mode));
        faultPriorityByMode(mode).clear();
        for (ChargingPile pile : piles.values()) {
            if (pile.getState() != PileState.WORKING || pile.getMode() != mode) {
                continue;
            }
            List<Long> toRemove = new ArrayList<>();
            for (Long requestId : pile.getQueueRequestIds()) {
                ChargingRequest req = requests.get(requestId);
                if (req != null && req.getStatus() == RequestStatus.QUEUED) {
                    all.add(requestId);
                    toRemove.add(requestId);
                }
            }
            pile.getQueueRequestIds().removeAll(toRemove);
        }
        // 全部合为一组，按排队号码先后顺序重新排序
        all.sort(Comparator.comparingInt(this::queueOrder));
        for (Long id : all) {
            ChargingRequest req = requests.get(id);
            if (req != null) {
                req.setStatus(RequestStatus.FAULT_DISPATCH);
            }
        }
        faultPriorityByMode(mode).addAll(all);
    }

    private void movePileQueuedToWaiting(ChargingPile pile) {
        List<Long> queued = new ArrayList<>(pile.getQueueRequestIds());
        pile.getQueueRequestIds().clear();
        for (Long id : queued) {
            ChargingRequest req = requests.get(id);
            if (req == null) {
                continue;
            }
            if (req.getStatus() == RequestStatus.CHARGING) {
                buildAndStoreBill(req, pile, now(), "提前结束");
                req.setStatus(RequestStatus.COMPLETED);
                continue;
            }
            req.setStatus(RequestStatus.WAITING_AREA);
            req.setPileId(null);
            waitingListByMode(req.getMode()).add(id);
        }
    }

    private void refreshAndDispatch(LocalDateTime currentTime) {
        for (ChargingPile pile : piles.values()) {
            while (true) {
                if (pile.getQueueRequestIds().isEmpty()) {
                    break;
                }
                startFirstIfNeeded(pile, currentTime);
                Long firstId = pile.getQueueRequestIds().get(0);
                ChargingRequest first = requests.get(firstId);
                if (first == null || first.getStatus() != RequestStatus.CHARGING
                        || first.getExpectedFinishTime() == null
                        || first.getExpectedFinishTime().isAfter(currentTime)) {
                    break;
                }
                buildAndStoreBill(first, pile, first.getExpectedFinishTime(), completionStatus(first));
                first.setStatus(RequestStatus.COMPLETED);
                pile.getQueueRequestIds().remove(0);
            }
            startFirstIfNeeded(pile, currentTime);
        }
        if (dispatchStrategy == DispatchStrategy.BATCH_SHORTEST_TOTAL_TIME) {
            dispatchBatchShortest(currentTime);
        } else if (dispatchStrategy == DispatchStrategy.SINGLE_SHORTEST_TOTAL_TIME) {
            dispatchSingleShortestByMode(ChargeMode.FAST, currentTime);
            dispatchSingleShortestByMode(ChargeMode.SLOW, currentTime);
        } else {
            dispatchByMode(ChargeMode.FAST, currentTime);
            dispatchByMode(ChargeMode.SLOW, currentTime);
        }
    }

    private void dispatchByMode(ChargeMode mode, LocalDateTime currentTime) {
        boolean moved = true;
        while (moved) {
            moved = false;
            boolean fromFaultPriority = !faultPriorityByMode(mode).isEmpty();
            Long requestId = pollNextDispatchable(mode);
            if (requestId == null) {
                break;
            }
            ChargingPile bestPile = chooseBestPile(mode, currentTime);
            if (bestPile == null || bestPile.getQueueRequestIds().size() >= config.getChargingQueueLen()) {
                prependDispatchable(mode, requestId, fromFaultPriority);
                break;
            }
            ChargingRequest req = requests.get(requestId);
            if (req == null || req.getStatus() == RequestStatus.CANCELED || req.getStatus() == RequestStatus.COMPLETED) {
                continue;
            }
            req.setStatus(RequestStatus.QUEUED);
            req.setPileId(bestPile.getId());
            bestPile.getQueueRequestIds().add(req.getId());
            startFirstIfNeeded(bestPile, currentTime);
            moved = true;
        }
    }

    private void dispatchSingleShortestByMode(ChargeMode mode, LocalDateTime currentTime) {
        int freeSlots = freeSlotsByMode(mode);
        if (freeSlots <= 0) {
            return;
        }
        List<Long> faultPriorityBefore = new ArrayList<>(faultPriorityByMode(mode));
        List<Long> selected = pollDispatchableBatchByMode(mode, freeSlots, freeSlots > 1);
        for (Long requestId : selected) {
            ChargingRequest req = requests.get(requestId);
            if (req == null || req.getStatus() == RequestStatus.CANCELED || req.getStatus() == RequestStatus.COMPLETED) {
                continue;
            }
            ChargingPile bestPile = chooseBestPile(mode, currentTime);
            if (bestPile == null || bestPile.getQueueRequestIds().size() >= config.getChargingQueueLen()) {
                prependDispatchable(mode, requestId, faultPriorityBefore.contains(requestId));
                break;
            }
            req.setStatus(RequestStatus.QUEUED);
            req.setPileId(bestPile.getId());
            bestPile.getQueueRequestIds().add(req.getId());
            startFirstIfNeeded(bestPile, currentTime);
        }
    }

    private void dispatchBatchShortest(LocalDateTime currentTime) {
        if (batchWaitingOrder.isEmpty()) {
            if (hasChargingAreaRequests()) {
                return;
            }
            if (waitingStationLoad() < totalStationCapacity()) {
                return;
            }
            batchWaitingOrder.addAll(pollAllWaitingRequests());
            batchWaitingOrder.sort(Comparator
                    .comparingDouble((Long id) -> requestOrMax(id).getRequestedKwh())
                    .thenComparingLong(id -> id));
        }

        while (!batchWaitingOrder.isEmpty()) {
            ChargingPile bestPile = chooseBestAnyPileForBatch(batchWaitingOrder.get(0), currentTime);
            if (bestPile == null || bestPile.getQueueRequestIds().size() >= config.getChargingQueueLen()) {
                break;
            }
            Long id = batchWaitingOrder.remove(0);
            ChargingRequest req = requests.get(id);
            if (req == null || req.getStatus() == RequestStatus.CANCELED || req.getStatus() == RequestStatus.COMPLETED) {
                continue;
            }
            req.setMode(bestPile.getMode());
            req.setStatus(RequestStatus.QUEUED);
            req.setPileId(bestPile.getId());
            bestPile.getQueueRequestIds().add(id);
            startFirstIfNeeded(bestPile, currentTime);
        }
    }

    private List<Long> pollDispatchableBatchByMode(ChargeMode mode, int limit, boolean shortestFirst) {
        List<Long> result = new ArrayList<>();
        List<Long> combined = new ArrayList<>();
        combined.addAll(faultPriorityByMode(mode));
        combined.addAll(waitingListByMode(mode));
        if (shortestFirst) {
            combined.sort(Comparator
                    .comparingLong((Long id) -> durationSeconds(requests.get(id)))
                    .thenComparingLong(id -> id));
        }
        for (Long id : combined) {
            if (result.size() >= limit) {
                break;
            }
            if (faultPriorityByMode(mode).remove(id) || waitingListByMode(mode).remove(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private List<Long> pollAllWaitingRequests() {
        List<Long> ids = new ArrayList<>();
        ids.addAll(faultPriorityFast);
        ids.addAll(faultPrioritySlow);
        ids.addAll(waitingFast);
        ids.addAll(waitingSlow);
        faultPriorityFast.clear();
        faultPrioritySlow.clear();
        waitingFast.clear();
        waitingSlow.clear();
        return ids;
    }

    private void releaseBatchWaitingToModeQueues() {
        for (Long id : new ArrayList<>(batchWaitingOrder)) {
            ChargingRequest req = requests.get(id);
            if (req != null && req.getStatus() == RequestStatus.WAITING_AREA) {
                waitingListByMode(req.getMode()).add(id);
            }
        }
        batchWaitingOrder.clear();
    }

    private Long pollNextDispatchable(ChargeMode mode) {
        List<Long> priority = faultPriorityByMode(mode);
        if (!priority.isEmpty()) {
            return priority.remove(0);
        }
        List<Long> waiting = waitingListByMode(mode);
        if (waiting.isEmpty()) {
            return null;
        }
        return waiting.remove(0);
    }

    private void prependDispatchable(ChargeMode mode, Long requestId, boolean faultPriority) {
        if (faultPriority) {
            faultPriorityByMode(mode).add(0, requestId);
            return;
        }
        waitingListByMode(mode).add(0, requestId);
    }

    private void startFirstIfNeeded(ChargingPile pile, LocalDateTime currentTime) {
        if (pile.getState() != PileState.WORKING || pile.getQueueRequestIds().isEmpty()) {
            return;
        }
        ChargingRequest first = requests.get(pile.getQueueRequestIds().get(0));
        if (first == null || first.getStatus() == RequestStatus.CHARGING) {
            return;
        }
        first.setStatus(RequestStatus.CHARGING);
        first.setChargeStartTime(currentTime);
        double hours = first.getRequestedKwh() / powerByMode(first.getMode());
        long seconds = Math.max(1, Math.round(hours * 3600));
        first.setExpectedFinishTime(currentTime.plusSeconds(seconds));
    }

    private ChargingPile chooseBestPile(ChargeMode mode, LocalDateTime currentTime) {
        double minTotal = Double.MAX_VALUE;
        ChargingPile best = null;
        for (ChargingPile pile : piles.values()) {
            if (pile.getMode() != mode || pile.getState() != PileState.WORKING) {
                continue;
            }
            if (pile.getQueueRequestIds().size() >= config.getChargingQueueLen()) {
                continue;
            }
            double waitHours = 0.0;
            for (Long requestId : pile.getQueueRequestIds()) {
                ChargingRequest req = requests.get(requestId);
                if (req == null) {
                    continue;
                }
                if (req.getStatus() == RequestStatus.CHARGING) {
                    double rem = Duration.between(currentTime, req.getExpectedFinishTime()).toMinutes() / 60.0;
                    waitHours += Math.max(0, rem);
                } else if (req.getStatus() == RequestStatus.QUEUED) {
                    waitHours += req.getRequestedKwh() / powerByMode(req.getMode());
                }
            }
            if (waitHours < minTotal || (Math.abs(waitHours - minTotal) < 0.0001
                    && (best == null || pile.getId().compareTo(best.getId()) < 0))) {
                minTotal = waitHours;
                best = pile;
            }
        }
        return best;
    }

    private ChargingPile chooseBestAnyPileForBatch(Long requestId, LocalDateTime currentTime) {
        ChargingRequest target = requests.get(requestId);
        if (target == null) {
            return null;
        }
        double minFinishHours = Double.MAX_VALUE;
        ChargingPile best = null;
        for (ChargingPile pile : piles.values()) {
            if (pile.getState() != PileState.WORKING || pile.getQueueRequestIds().size() >= config.getChargingQueueLen()) {
                continue;
            }
            double tailHours = pileTailHours(pile, currentTime);
            double finishHours = tailHours + target.getRequestedKwh() / powerByMode(pile.getMode());
            if (finishHours < minFinishHours || (Math.abs(finishHours - minFinishHours) < 0.0001
                    && (best == null || pile.getId().compareTo(best.getId()) < 0))) {
                minFinishHours = finishHours;
                best = pile;
            }
        }
        return best;
    }

    private double pileTailHours(ChargingPile pile, LocalDateTime currentTime) {
        double waitHours = 0.0;
        for (Long requestId : pile.getQueueRequestIds()) {
            ChargingRequest req = requests.get(requestId);
            if (req == null) {
                continue;
            }
            if (req.getStatus() == RequestStatus.CHARGING && req.getExpectedFinishTime() != null) {
                double rem = Duration.between(currentTime, req.getExpectedFinishTime()).toSeconds() / 3600.0;
                waitHours += Math.max(0, rem);
            } else if (req.getStatus() == RequestStatus.QUEUED) {
                waitHours += req.getRequestedKwh() / powerByMode(pile.getMode());
            }
        }
        return waitHours;
    }

    private ChargingRequest requestOrMax(Long id) {
        ChargingRequest req = requests.get(id);
        if (req != null) {
            return req;
        }
        ChargingRequest placeholder = new ChargingRequest();
        placeholder.setRequestedKwh(Double.MAX_VALUE);
        return placeholder;
    }

    private ChargeBill buildAndStoreBill(ChargingRequest req, ChargingPile pile, LocalDateTime stopAt, String billStatus) {
        req.setChargeStopTime(stopAt);
        double power = powerByMode(req.getMode());
        ChargeBill bill = billingService.buildBill(
                "B" + billSeq.getAndIncrement(),
                req.getUserId(),
                pile.getId(),
                req.getChargeStartTime(),
                stopAt,
                power,
                req.getRequestedKwh(),
                config
        );
        bill.setRequestId(req.getId());
        bill.setBillStatus(billStatus);
        bill.setVehicleNumber(req.getVehicleNumber());
        bills.add(bill);
        pile.setTotalChargeCount(pile.getTotalChargeCount() + 1);
        pile.setTotalChargeHours(pile.getTotalChargeHours() + bill.getChargedHours());
        pile.setTotalChargeKwh(pile.getTotalChargeKwh() + bill.getChargedKwh());
        return bill;
    }

    private ChargeBill createCancelBill(ChargingRequest req, LocalDateTime at) {
        ChargeBill bill = billingService.buildBill(
                "B" + billSeq.getAndIncrement(),
                req.getUserId(),
                req.getPileId() == null ? "-" : req.getPileId(),
                req.getChargeStartTime(),
                at,
                powerByMode(req.getMode()),
                req.getRequestedKwh(),
                config
        );
        bill.setRequestId(req.getId());
        bill.setBillStatus("已取消");
        bill.setVehicleNumber(req.getVehicleNumber());
        bills.add(bill);
        return bill;
    }

    private ChargeBill buildPreviewBill(ChargingRequest req, ChargingPile pile, LocalDateTime stopAt) {
        ChargeBill bill = billingService.buildBill(
                "PREVIEW",
                req.getUserId(),
                pile.getId(),
                req.getChargeStartTime(),
                stopAt,
                powerByMode(req.getMode()),
                req.getRequestedKwh(),
                config
        );
        bill.setRequestId(req.getId());
        bill.setBillStatus("充电中");
        bill.setVehicleNumber(req.getVehicleNumber());
        return bill;
    }

    private String completionStatus(ChargingRequest req) {
        return req.isFaultInterrupted() ? "已完成（故障后）" : "已完成";
    }

    private String nextQueueNumber(ChargeMode mode) {
        if (mode == ChargeMode.FAST) {
            return "F" + fastQueueSeq++;
        }
        return "T" + slowQueueSeq++;
    }

    private UserAccount validateUser(Long userId) {
        UserAccount user = users.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("user not found");
        }
        return user;
    }

    private void validateRequestAmounts(double requestKwh, double batteryCapacityKwh) {
        if (batteryCapacityKwh <= 0) {
            throw new IllegalArgumentException("battery capacity must be greater than 0");
        }
        if (requestKwh <= 0) {
            throw new IllegalArgumentException("request kwh must be greater than 0");
        }
        if (requestKwh > batteryCapacityKwh) {
            throw new IllegalArgumentException("request kwh cannot exceed battery capacity");
        }
    }

    private int admissionCapacity() {
        if (dispatchStrategy == DispatchStrategy.BATCH_SHORTEST_TOTAL_TIME) {
            return totalStationCapacity();
        }
        return config.getWaitingAreaSize();
    }

    private int stationLoadForAdmission() {
        if (dispatchStrategy == DispatchStrategy.BATCH_SHORTEST_TOTAL_TIME) {
            return (int) requests.values().stream().filter(this::isActive).count();
        }
        return waitingAreaLoad();
    }

    private int waitingAreaLoad() {
        return waitingFast.size()
                + waitingSlow.size()
                + batchWaitingOrder.size();
    }

    private int waitingStationLoad() {
        return waitingFast.size()
                + waitingSlow.size()
                + faultPriorityFast.size()
                + faultPrioritySlow.size()
                + batchWaitingOrder.size();
    }

    private int totalStationCapacity() {
        return config.getWaitingAreaSize()
                + (config.getFastChargingPileNum() + config.getSlowChargingPileNum()) * config.getChargingQueueLen();
    }

    private int freeSlotsByMode(ChargeMode mode) {
        int free = 0;
        for (ChargingPile pile : piles.values()) {
            if (pile.getMode() == mode && pile.getState() == PileState.WORKING) {
                free += Math.max(0, config.getChargingQueueLen() - pile.getQueueRequestIds().size());
            }
        }
        return free;
    }

    private boolean hasChargingAreaRequests() {
        for (ChargingPile pile : piles.values()) {
            if (!pile.getQueueRequestIds().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isActive(ChargingRequest req) {
        return req != null && isActiveStatus(req.getStatus());
    }

    private boolean hasActiveRequests() {
        for (ChargingRequest req : requests.values()) {
            if (isActive(req)) {
                return true;
            }
        }
        return false;
    }

    private boolean isActiveStatus(RequestStatus status) {
        return status == RequestStatus.WAITING_AREA
                || status == RequestStatus.QUEUED
                || status == RequestStatus.CHARGING;
    }

    private List<ChargingRequest> findActiveRequestsByUser(Long userId) {
        List<ChargingRequest> list = new ArrayList<>();
        for (ChargingRequest req : requests.values()) {
            if (Objects.equals(req.getUserId(), userId) && isActive(req)) {
                list.add(req);
            }
        }
        return list;
    }

    private ChargingRequest resolveActiveRequest(Long userId, Long requestId) {
        validateUser(userId);
        if (requestId != null) {
            ChargingRequest req = requests.get(requestId);
            if (req == null || !Objects.equals(req.getUserId(), userId)) {
                throw new IllegalArgumentException("request not found for user");
            }
            if (!isActive(req)) {
                throw new IllegalArgumentException("request is not active");
            }
            return req;
        }
        List<ChargingRequest> active = findActiveRequestsByUser(userId);
        if (active.isEmpty()) {
            throw new IllegalArgumentException("no active request");
        }
        if (active.size() > 1) {
            throw new IllegalArgumentException("multiple active requests, requestId is required");
        }
        return active.get(0);
    }

    private ChargingRequest resolveRequestForModify(Long userId, Long requestId) {
        if (requestId != null) {
            return resolveActiveRequest(userId, requestId);
        }
        List<ChargingRequest> waiting = findActiveRequestsByUser(userId).stream()
                .filter(req -> req.getStatus() == RequestStatus.WAITING_AREA)
                .toList();
        if (waiting.isEmpty()) {
            throw new IllegalArgumentException("no waiting-area request to modify");
        }
        if (waiting.size() > 1) {
            throw new IllegalArgumentException("multiple waiting-area requests, requestId is required");
        }
        return waiting.get(0);
    }

    private ChargingRequest resolveRequestForCancel(Long userId, Long requestId) {
        if (requestId != null) {
            return resolveActiveRequest(userId, requestId);
        }
        List<ChargingRequest> active = findActiveRequestsByUser(userId);
        if (active.isEmpty()) {
            throw new IllegalArgumentException("no active request");
        }
        if (active.size() == 1) {
            return active.get(0);
        }
        List<ChargingRequest> waiting = active.stream().filter(req -> req.getStatus() == RequestStatus.WAITING_AREA).toList();
        if (waiting.size() == 1) {
            return waiting.get(0);
        }
        throw new IllegalArgumentException("multiple active requests, requestId is required");
    }

    private ChargingRequest resolveRequestForEnd(Long userId, Long requestId) {
        if (requestId != null) {
            return resolveActiveRequest(userId, requestId);
        }
        List<ChargingRequest> charging = findActiveRequestsByUser(userId).stream()
                .filter(req -> req.getStatus() == RequestStatus.CHARGING)
                .toList();
        if (charging.isEmpty()) {
            throw new IllegalArgumentException("no charging request to end");
        }
        if (charging.size() > 1) {
            throw new IllegalArgumentException("multiple charging requests, requestId is required");
        }
        return charging.get(0);
    }

    private ChargingRequest resolveRequestForQueueInfo(Long userId, Long requestId) {
        if (requestId != null) {
            return resolveActiveRequest(userId, requestId);
        }
        List<ChargingRequest> active = findActiveRequestsByUser(userId);
        if (active.isEmpty()) {
            throw new IllegalArgumentException("no active request");
        }
        if (active.size() == 1) {
            return active.get(0);
        }
        List<ChargingRequest> waiting = active.stream().filter(req -> req.getStatus() == RequestStatus.WAITING_AREA).toList();
        if (!waiting.isEmpty()) {
            return waiting.get(0);
        }
        return active.get(0);
    }

    private ChargingPile requirePile(String pileId) {
        ChargingPile pile = piles.get(pileId);
        if (pile == null) {
            throw new IllegalArgumentException("pile not found");
        }
        return pile;
    }

    private List<Long> waitingListByMode(ChargeMode mode) {
        return mode == ChargeMode.FAST ? waitingFast : waitingSlow;
    }

    private List<Long> faultPriorityByMode(ChargeMode mode) {
        return mode == ChargeMode.FAST ? faultPriorityFast : faultPrioritySlow;
    }

    private int countFrontCars(ChargingRequest req) {
        if (req == null || req.getStatus() == RequestStatus.CANCELED || req.getStatus() == RequestStatus.COMPLETED) {
            return 0;
        }
        if (req.getStatus() == RequestStatus.WAITING_AREA) {
            int chargingAreaFront = countChargingAreaByMode(req.getMode());
            int batchIndex = batchWaitingOrder.indexOf(req.getId());
            if (batchIndex >= 0) {
                return chargingAreaFront + batchIndex;
            }
            List<Long> priority = faultPriorityByMode(req.getMode());
            int p = priority.indexOf(req.getId());
            if (p >= 0) {
                return chargingAreaFront + p;
            }
            List<Long> waiting = waitingListByMode(req.getMode());
            int w = waiting.indexOf(req.getId());
            return chargingAreaFront + (w < 0 ? priority.size() : priority.size() + w);
        }
        if (req.getPileId() != null) {
            ChargingPile pile = piles.get(req.getPileId());
            if (pile != null) {
                int pos = pile.getQueueRequestIds().indexOf(req.getId());
                return Math.max(pos, 0);
            }
        }
        // fallback: mode-level queue order
        int currentOrder = queueOrder(req.getId());
        int count = 0;
        for (ChargingRequest other : requests.values()) {
            if (other == null || Objects.equals(other.getId(), req.getId())) {
                continue;
            }
            if (other.getMode() != req.getMode() || !isActive(other)) {
                continue;
            }
            if (queueOrder(other.getId()) < currentOrder) {
                count++;
            }
        }
        return count;
    }

    private int countChargingAreaByMode(ChargeMode mode) {
        int count = 0;
        for (ChargingPile pile : piles.values()) {
            for (Long id : pile.getQueueRequestIds()) {
                ChargingRequest other = requests.get(id);
                if (other != null && other.getMode() == mode && isActive(other)) {
                    count++;
                }
            }
        }
        return count;
    }

    private LocalDateTime[] estimateStartAndFinish(ChargingRequest req, LocalDateTime currentTime) {
        if (req == null) {
            return new LocalDateTime[]{null, null};
        }
        if (req.getStatus() == RequestStatus.CHARGING) {
            LocalDateTime start = req.getChargeStartTime() == null ? currentTime : req.getChargeStartTime();
            LocalDateTime finish = req.getExpectedFinishTime() == null
                    ? start.plusSeconds(durationSeconds(req))
                    : req.getExpectedFinishTime();
            return new LocalDateTime[]{start, finish};
        }
        if (req.getStatus() == RequestStatus.QUEUED) {
            return estimateQueuedStartAndFinish(req, currentTime);
        }
        if (req.getStatus() == RequestStatus.WAITING_AREA) {
            return estimateWaitingStartAndFinish(req, currentTime);
        }
        return new LocalDateTime[]{req.getChargeStartTime(), req.getExpectedFinishTime()};
    }

    private LocalDateTime[] estimateQueuedStartAndFinish(ChargingRequest target, LocalDateTime currentTime) {
        if (target.getPileId() == null) {
            return new LocalDateTime[]{null, null};
        }
        ChargingPile pile = piles.get(target.getPileId());
        if (pile == null) {
            return new LocalDateTime[]{null, null};
        }
        LocalDateTime cursor = currentTime;
        for (Long id : pile.getQueueRequestIds()) {
            ChargingRequest req = requests.get(id);
            if (req == null) {
                continue;
            }
            if (req.getStatus() == RequestStatus.CHARGING) {
                LocalDateTime start = req.getChargeStartTime() == null ? currentTime : req.getChargeStartTime();
                LocalDateTime finish = req.getExpectedFinishTime() == null
                        ? start.plusSeconds(durationSeconds(req))
                        : req.getExpectedFinishTime();
                if (finish.isBefore(currentTime)) {
                    finish = currentTime;
                }
                if (Objects.equals(req.getId(), target.getId())) {
                    return new LocalDateTime[]{start, finish};
                }
                cursor = finish;
                continue;
            }
            LocalDateTime start = cursor;
            LocalDateTime finish = start.plusSeconds(durationSeconds(req));
            if (Objects.equals(req.getId(), target.getId())) {
                return new LocalDateTime[]{start, finish};
            }
            cursor = finish;
        }
        return new LocalDateTime[]{null, null};
    }

    private LocalDateTime[] estimateWaitingStartAndFinish(ChargingRequest target, LocalDateTime currentTime) {
        ChargeMode mode = target.getMode();
        List<ChargingPile> workingPiles = piles.values().stream()
                .filter(p -> p.getMode() == mode && p.getState() == PileState.WORKING)
                .toList();
        if (workingPiles.isEmpty()) {
            return new LocalDateTime[]{null, null};
        }

        Map<String, LocalDateTime> tailByPile = new LinkedHashMap<>();
        for (ChargingPile pile : workingPiles) {
            tailByPile.put(pile.getId(), estimatePileTailFinish(pile, currentTime));
        }

        List<Long> priorityList = faultPriorityByMode(mode);
        int priorityIndex = priorityList.indexOf(target.getId());
        int stepsBeforeTarget;
        if (priorityIndex >= 0) {
            stepsBeforeTarget = priorityIndex;
        } else {
            int waitingIndex = waitingListByMode(mode).indexOf(target.getId());
            if (waitingIndex < 0) {
                waitingIndex = 0;
            }
            stepsBeforeTarget = priorityList.size() + waitingIndex;
        }
        long avgSecs = Math.max(1L, Math.round(averageWaitingDurationSeconds(mode)));

        for (int i = 0; i <= stepsBeforeTarget; i++) {
            boolean isTarget = i == stepsBeforeTarget;
            long duration = isTarget ? durationSeconds(target) : avgSecs;

            String bestPile = null;
            LocalDateTime bestStart = null;
            for (Map.Entry<String, LocalDateTime> entry : tailByPile.entrySet()) {
                if (bestStart == null || entry.getValue().isBefore(bestStart)) {
                    bestStart = entry.getValue();
                    bestPile = entry.getKey();
                }
            }
            if (bestPile == null || bestStart == null) {
                return new LocalDateTime[]{null, null};
            }
            LocalDateTime finish = bestStart.plusSeconds(duration);
            tailByPile.put(bestPile, finish);
            if (isTarget) {
                return new LocalDateTime[]{bestStart, finish};
            }
        }
        return new LocalDateTime[]{null, null};
    }

    private LocalDateTime estimatePileTailFinish(ChargingPile pile, LocalDateTime currentTime) {
        LocalDateTime cursor = currentTime;
        for (Long id : pile.getQueueRequestIds()) {
            ChargingRequest req = requests.get(id);
            if (req == null) {
                continue;
            }
            if (req.getStatus() == RequestStatus.CHARGING) {
                LocalDateTime finish = req.getExpectedFinishTime() == null
                        ? cursor.plusSeconds(durationSeconds(req))
                        : req.getExpectedFinishTime();
                if (finish.isBefore(currentTime)) {
                    finish = currentTime;
                }
                cursor = finish;
            } else {
                cursor = cursor.plusSeconds(durationSeconds(req));
            }
        }
        return cursor;
    }

    private long durationSeconds(ChargingRequest req) {
        if (req == null) {
            return 1L;
        }
        double power = powerByMode(req.getMode());
        double hours = req.getRequestedKwh() / power;
        return Math.max(1L, Math.round(hours * 3600));
    }

    private double averageWaitingDurationSeconds(ChargeMode mode) {
        double sum = 0;
        int count = 0;
        for (ChargingRequest req : requests.values()) {
            if (req.getMode() == mode && isActive(req)) {
                sum += durationSeconds(req);
                count++;
            }
        }
        if (count == 0) {
            return mode == ChargeMode.FAST ? 3600 : 7200;
        }
        return sum / count;
    }

    private int queueOrder(Long requestId) {
        ChargingRequest req = requests.get(requestId);
        if (req == null || req.getQueueNumber() == null || req.getQueueNumber().length() < 2) {
            return Integer.MAX_VALUE;
        }
        return Integer.parseInt(req.getQueueNumber().substring(1));
    }

    private boolean isInFaultPriorityQueue(ChargingRequest req) {
        if (req == null) {
            return false;
        }
        return faultPriorityByMode(req.getMode()).contains(req.getId());
    }

    private String queueAreaOf(ChargingRequest req) {
        if (req == null) {
            return "NONE";
        }
        if (req.getStatus() == RequestStatus.WAITING_AREA) {
            return isInFaultPriorityQueue(req) ? "FAULT_WAITING" : "WAITING_AREA";
        }
        if (req.getStatus() == RequestStatus.QUEUED || req.getStatus() == RequestStatus.CHARGING) {
            return "CHARGING_AREA";
        }
        return "NONE";
    }

    private boolean inPeriod(LocalDateTime time, LocalDateTime current, String period) {
        if (time == null) {
            return false;
        }
        String p = period == null ? "day" : period.toLowerCase(Locale.ROOT);
        if ("week".equals(p)) {
            WeekFields wf = WeekFields.of(Locale.getDefault());
            return time.getYear() == current.getYear()
                    && time.get(wf.weekOfWeekBasedYear()) == current.get(wf.weekOfWeekBasedYear());
        }
        if ("month".equals(p)) {
            return time.getYear() == current.getYear() && time.getMonth() == current.getMonth();
        }
        LocalDate d = time.toLocalDate();
        return d.equals(current.toLocalDate());
    }

    private double powerByMode(ChargeMode mode) {
        return mode == ChargeMode.FAST ? config.getFastPower() : config.getSlowPower();
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private LocalDateTime findEarliestChargingFinishAtOrBefore(LocalDateTime upperBound) {
        LocalDateTime earliest = null;
        for (ChargingRequest req : requests.values()) {
            if (req.getStatus() != RequestStatus.CHARGING || req.getExpectedFinishTime() == null) {
                continue;
            }
            LocalDateTime finish = req.getExpectedFinishTime();
            if (finish.isAfter(upperBound)) {
                continue;
            }
            if (earliest == null || finish.isBefore(earliest)) {
                earliest = finish;
            }
        }
        return earliest;
    }

    private LocalDateTime now() {
        return systemNow;
    }

    private void saveUsers() {
        try {
            File file = new File(usersFilePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, new ArrayList<>(users.values()));
        } catch (IOException e) {
            System.err.println("Failed to save users: " + e.getMessage());
        }
    }
}
