package com.charging.backend.service;

import com.charging.backend.model.ChargeBill;
import com.charging.backend.model.ChargeMode;
import com.charging.backend.model.ChargingPile;
import com.charging.backend.model.ChargingRequest;
import com.charging.backend.model.FaultDispatchStrategy;
import com.charging.backend.model.PileState;
import com.charging.backend.model.RequestStatus;
import com.charging.backend.model.SystemConfig;
import com.charging.backend.model.UserAccount;
import org.springframework.stereotype.Service;

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
    private LocalDateTime systemNow = LocalDateTime.of(2026, 6, 1, 8, 0, 0);

    private final Map<Long, UserAccount> users = new LinkedHashMap<>();
    private final Map<String, UserAccount> userByName = new LinkedHashMap<>();
    private final Map<Long, ChargingRequest> requests = new LinkedHashMap<>();
    private final List<ChargeBill> bills = new ArrayList<>();
    private final Map<String, ChargingPile> piles = new LinkedHashMap<>();

    private final List<Long> waitingFast = new ArrayList<>();
    private final List<Long> waitingSlow = new ArrayList<>();
    private final List<Long> faultPriorityFast = new ArrayList<>();
    private final List<Long> faultPrioritySlow = new ArrayList<>();

    public StationService(BillingService billingService) {
        this.billingService = billingService;
        initPiles();
    }

    public synchronized UserAccount register(String username, String password, double batteryCapacityKwh) {
        if (userByName.containsKey(username)) {
            throw new IllegalArgumentException("username already exists");
        }
        UserAccount user = new UserAccount(userIdSeq.getAndIncrement(), username, password, batteryCapacityKwh);
        users.put(user.getId(), user);
        userByName.put(user.getUsername(), user);
        return user;
    }

    public synchronized UserAccount login(String username, String password) {
        UserAccount user = userByName.get(username);
        if (user == null || !user.getPassword().equals(password)) {
            throw new IllegalArgumentException("invalid username or password");
        }
        return user;
    }

    public synchronized ChargingRequest submitRequest(Long userId, ChargeMode mode, double requestKwh) {
        refreshAndDispatch(now());
        validateUser(userId);
        if (waitingFast.size() + waitingSlow.size() >= config.getWaitingAreaSize()) {
            throw new IllegalArgumentException("waiting area is full");
        }
        ChargingRequest req = new ChargingRequest();
        req.setId(requestIdSeq.getAndIncrement());
        req.setUserId(userId);
        req.setMode(mode);
        req.setRequestedKwh(requestKwh);
        req.setQueueNumber(nextQueueNumber(mode));
        req.setStatus(RequestStatus.WAITING_AREA);
        req.setEnqueueTime(now());
        requests.put(req.getId(), req);
        waitingListByMode(mode).add(req.getId());
        refreshAndDispatch(now());
        return req;
    }

    public synchronized ChargingRequest modifyRequest(Long userId, Long requestId, ChargeMode mode, Double requestKwh) {
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
        if (requestKwh != null) {
            if (req.getStatus() != RequestStatus.WAITING_AREA) {
                throw new IllegalArgumentException("request kwh can only be changed in waiting area");
            }
            req.setRequestedKwh(requestKwh);
        }
        refreshAndDispatch(now());
        return req;
    }

    public synchronized void cancelRequest(Long userId, Long requestId) {
        refreshAndDispatch(now());
        ChargingRequest req = resolveRequestForCancel(userId, requestId);
        if (req.getStatus() == RequestStatus.WAITING_AREA) {
            waitingListByMode(req.getMode()).remove(req.getId());
            req.setStatus(RequestStatus.CANCELED);
            return;
        }
        if (req.getStatus() == RequestStatus.QUEUED) {
            ChargingPile pile = piles.get(req.getPileId());
            if (pile != null) {
                pile.getQueueRequestIds().remove(req.getId());
            }
            req.setStatus(RequestStatus.CANCELED);
            req.setPileId(null);
            refreshAndDispatch(now());
            return;
        }
        if (req.getStatus() == RequestStatus.CHARGING) {
            ChargingPile pile = piles.get(req.getPileId());
            if (pile != null) {
                buildAndStoreBill(req, pile, now());
                pile.getQueueRequestIds().remove(req.getId());
            }
            req.setStatus(RequestStatus.CANCELED);
            req.setPileId(null);
            refreshAndDispatch(now());
        }
    }

    public synchronized ChargeBill endCharging(Long userId, Long requestId) {
        refreshAndDispatch(now());
        ChargingRequest req = resolveRequestForEnd(userId, requestId);
        if (req.getStatus() != RequestStatus.CHARGING) {
            throw new IllegalArgumentException("request is not charging");
        }
        ChargingPile pile = piles.get(req.getPileId());
        ChargeBill bill = buildAndStoreBill(req, pile, now());
        req.setStatus(RequestStatus.COMPLETED);
        pile.getQueueRequestIds().remove(req.getId());
        refreshAndDispatch(now());
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
        data.put("active", true);
        data.put("requestId", req.getId());
        data.put("status", req.getStatus());
        data.put("mode", req.getMode());
        data.put("queueNumber", req.getQueueNumber());
        data.put("requestKwh", req.getRequestedKwh());
        data.put("pileId", req.getPileId());
        data.put("frontCars", countFrontCars(req));
        data.put("enqueueTime", req.getEnqueueTime());
        data.put("startTime", req.getChargeStartTime());
        data.put("expectedFinishTime", req.getExpectedFinishTime());
        data.put("systemTime", now());
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
            item.put("pileId", req.getPileId());
            item.put("frontCars", countFrontCars(req));
            item.put("enqueueTime", req.getEnqueueTime());
            item.put("startTime", req.getChargeStartTime());
            item.put("expectedFinishTime", req.getExpectedFinishTime());
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
                car.put("batteryCapacityKwh", user == null ? 0 : user.getBatteryCapacityKwh());
                car.put("requestKwh", req.getRequestedKwh());
                car.put("queueNumber", req.getQueueNumber());
                car.put("status", req.getStatus());
                car.put("queuedMinutes", req.getEnqueueTime() == null ? 0 : Duration.between(req.getEnqueueTime(), now()).toMinutes());
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
            return;
        }
        pile.setState(targetState);
        if (targetState == PileState.SHUTDOWN) {
            movePileQueuedToWaiting(pile);
        } else if (previous == PileState.FAULT && targetState == PileState.WORKING) {
            handlePileRecoveryRebalance(pile.getMode());
        }
        refreshAndDispatch(now());
    }

    public synchronized void setFaultDispatchStrategy(FaultDispatchStrategy strategy) {
        this.faultDispatchStrategy = strategy;
    }

    public synchronized FaultDispatchStrategy getFaultDispatchStrategy() {
        return faultDispatchStrategy;
    }

    public synchronized SystemConfig getConfig() {
        return config;
    }

    public synchronized void updateConfig(Integer waitingAreaSize, Integer queueLen) {
        if (waitingAreaSize != null && waitingAreaSize > 0) {
            config.setWaitingAreaSize(waitingAreaSize);
        }
        if (queueLen != null && queueLen > 0) {
            config.setChargingQueueLen(queueLen);
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
        systemNow = systemNow.plusMinutes(minutes);
        refreshAndDispatch(systemNow);
        return systemNow;
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
        if (!faultPile.getQueueRequestIds().isEmpty()) {
            Long firstId = faultPile.getQueueRequestIds().get(0);
            ChargingRequest chargingReq = requests.get(firstId);
            if (chargingReq != null && chargingReq.getStatus() == RequestStatus.CHARGING) {
                buildAndStoreBill(chargingReq, faultPile, now());
                chargingReq.setStatus(RequestStatus.COMPLETED);
                faultPile.getQueueRequestIds().remove(0);
            }
        }
        List<Long> impacted = new ArrayList<>(faultPile.getQueueRequestIds());
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
        List<Long> merged = new ArrayList<>();
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
        faultPriorityByMode(mode).addAll(merged);
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
                buildAndStoreBill(req, pile, now());
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
            if (pile.getQueueRequestIds().isEmpty()) {
                continue;
            }
            Long firstId = pile.getQueueRequestIds().get(0);
            ChargingRequest first = requests.get(firstId);
            if (first != null && first.getStatus() == RequestStatus.CHARGING
                    && first.getExpectedFinishTime() != null
                    && !first.getExpectedFinishTime().isAfter(currentTime)) {
                buildAndStoreBill(first, pile, first.getExpectedFinishTime());
                first.setStatus(RequestStatus.COMPLETED);
                pile.getQueueRequestIds().remove(0);
            }
            startFirstIfNeeded(pile, currentTime);
        }
        dispatchByMode(ChargeMode.FAST, currentTime);
        dispatchByMode(ChargeMode.SLOW, currentTime);
    }

    private void dispatchByMode(ChargeMode mode, LocalDateTime currentTime) {
        boolean moved = true;
        while (moved) {
            moved = false;
            Long requestId = pollNextDispatchable(mode);
            if (requestId == null) {
                break;
            }
            ChargingPile bestPile = chooseBestPile(mode, currentTime);
            if (bestPile == null || bestPile.getQueueRequestIds().size() >= config.getChargingQueueLen()) {
                prependDispatchable(mode, requestId);
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

    private void prependDispatchable(ChargeMode mode, Long requestId) {
        List<Long> priority = faultPriorityByMode(mode);
        if (!priority.isEmpty()) {
            priority.add(0, requestId);
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

    private ChargeBill buildAndStoreBill(ChargingRequest req, ChargingPile pile, LocalDateTime stopAt) {
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
        bills.add(bill);
        pile.setTotalChargeCount(pile.getTotalChargeCount() + 1);
        pile.setTotalChargeHours(pile.getTotalChargeHours() + bill.getChargedHours());
        pile.setTotalChargeKwh(pile.getTotalChargeKwh() + bill.getChargedKwh());
        return bill;
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

    private boolean isActive(ChargingRequest req) {
        return req != null && isActiveStatus(req.getStatus());
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
        if (req.getStatus() == RequestStatus.WAITING_AREA) {
            List<Long> waiting = waitingListByMode(req.getMode());
            int pos = waiting.indexOf(req.getId());
            return Math.max(pos, 0);
        }
        if (req.getPileId() != null) {
            ChargingPile pile = piles.get(req.getPileId());
            if (pile != null) {
                int pos = pile.getQueueRequestIds().indexOf(req.getId());
                return Math.max(pos, 0);
            }
        }
        return 0;
    }

    private int queueOrder(Long requestId) {
        ChargingRequest req = requests.get(requestId);
        if (req == null || req.getQueueNumber() == null || req.getQueueNumber().length() < 2) {
            return Integer.MAX_VALUE;
        }
        return Integer.parseInt(req.getQueueNumber().substring(1));
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

    private LocalDateTime now() {
        return systemNow;
    }
}
