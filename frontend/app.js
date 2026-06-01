const byId = (id) => document.getElementById(id);

const userMessage = byId("userMessage");
const adminMessage = byId("adminMessage");
const queueInfoView = byId("queueInfoView");
const billsView = byId("billsView");
const requestsView = byId("requestsView");
const pileView = byId("pileView");
const reportView = byId("reportView");
const systemTimeText = byId("systemTimeText");

const ZH = {
  needLogin: "请先登录",
  requestFailed: "请求失败",
  noActiveRequest: "当前没有进行中的充电请求。",
  noRequests: "暂无订单记录。",
  selectRequestHint: "请先在“我的订单”中选择一条订单。",
  noBills: "暂无充电详单。",
  noPileData: "暂无充电桩数据。",
  noReportData: "暂无报表数据。",
  registerOk: "注册成功",
  loginOk: "登录成功，当前用户ID：",
  submitOk: "请求已提交",
  modifyOk: "请求已修改",
  cancelOk: "请求已取消",
  endOk: "已结束充电",
  requestSelected: "已选中订单 requestId=",
  userRefreshed: "用户端数据已刷新",
  pilesRefreshed: "充电桩状态已刷新",
  strategyOk: "调度策略已更新",
  pileStateOk: "充电桩状态已更新",
  configOk: "系统配置已更新",
  reportRefreshed: "报表已刷新",
  overviewRefreshed: "总览已刷新",
  advanced: "系统时间已推进 ",
  advancedSuffix: " 分钟",
  badMinutes: "分钟数必须大于0",
};

function baseUrl() {
  return byId("baseUrl").value.trim().replace(/\/$/, "");
}

function setMessage(el, text, isError = false) {
  el.textContent = text;
  el.style.color = isError ? "#b91c1c" : "#0f766e";
}

function formatDateTime(value) {
  if (!value) return "--";
  return String(value).replace("T", " ");
}

function modeLabel(mode) {
  if (mode === "FAST") return "快充";
  if (mode === "SLOW") return "慢充";
  return mode || "--";
}

function statusLabel(status) {
  const map = {
    WAITING_AREA: "等候区",
    QUEUED: "充电区排队",
    CHARGING: "充电中",
    COMPLETED: "已完成",
    CANCELED: "已取消",
  };
  return map[status] || status || "--";
}

function queueAreaLabel(area) {
  const map = {
    WAITING_AREA: "等候区",
    FAULT_WAITING: "故障等候队列",
    CHARGING_AREA: "充电区",
    NONE: "--",
  };
  return map[area] || area || "--";
}

function pileStateLabel(state) {
  const map = {
    WORKING: "工作中",
    SHUTDOWN: "已关闭",
    FAULT: "故障",
  };
  return map[state] || state || "--";
}

function periodLabel(period) {
  const map = {
    DAY: "日报",
    WEEK: "周报",
    MONTH: "月报",
  };
  return map[period] || period || "--";
}

function userIdValue() {
  const userId = byId("userId").value.trim();
  if (!userId) {
    throw new Error(ZH.needLogin);
  }
  return Number(userId);
}

function requestIdValue() {
  const val = (byId("requestId")?.value || "").trim();
  if (!val) return null;
  const num = Number(val);
  if (!Number.isFinite(num) || num <= 0) {
    throw new Error("requestId 必须是正数");
  }
  return num;
}

function clearQueueInfoWithHint() {
  queueInfoView.innerHTML = `<div>${ZH.selectRequestHint}</div>`;
}

async function request(path, options = {}) {
  const resp = await fetch(`${baseUrl()}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  const data = await resp.json();
  if (!data.success) {
    throw new Error(data.message || ZH.requestFailed);
  }
  return data;
}

function renderQueueInfo(data) {
  if (!data || !data.active) {
    queueInfoView.innerHTML = `<div>${ZH.noActiveRequest}</div>`;
    return;
  }
  queueInfoView.innerHTML = `
    <div class="kvs">
      <div class="k">请求ID</div><div class="v">${data.requestId ?? "--"}</div>
      <div class="k">排队号</div><div class="v">${data.queueNumber ?? "--"}</div>
      <div class="k">充电模式</div><div class="v">${modeLabel(data.mode)}</div>
      <div class="k">当前状态</div><div class="v"><span class="tag">${statusLabel(data.status)}</span></div>
      <div class="k">所在区域</div><div class="v">${queueAreaLabel(data.queueArea)}</div>
      <div class="k">车辆电池容量</div><div class="v">${data.batteryCapacityKwh ?? 0} kWh</div>
      <div class="k">请求电量</div><div class="v">${data.requestKwh ?? 0} kWh</div>
      <div class="k">前车数量</div><div class="v">${data.frontCars ?? 0}</div>
      <div class="k">分配充电桩</div><div class="v">${data.pileId ?? "--"}</div>
      <div class="k">入队时间</div><div class="v">${formatDateTime(data.enqueueTime)}</div>
      <div class="k">开始充电</div><div class="v">${formatDateTime(data.startTime)}</div>
      <div class="k">预计完成</div><div class="v">${formatDateTime(data.expectedFinishTime)}</div>
    </div>
  `;
}

function renderBills(bills) {
  if (!bills || bills.length === 0) {
    billsView.innerHTML = `<div>${ZH.noBills}</div>`;
    return;
  }
  const rows = bills.map((b) => `
    <tr>
      <td>${b.billNo}</td>
      <td>${formatDateTime(b.generatedAt)}</td>
      <td>${b.pileId}</td>
      <td>${b.chargedKwh}</td>
      <td>${b.chargedHours}</td>
      <td>${formatDateTime(b.startTime)}</td>
      <td>${formatDateTime(b.stopTime)}</td>
      <td>${b.chargeFee}</td>
      <td>${b.serviceFee}</td>
      <td>${b.totalFee}</td>
    </tr>
  `).join("");
  billsView.innerHTML = `
    <table>
      <thead>
      <tr>
        <th>详单号</th><th>生成时间</th><th>充电桩</th><th>电量</th><th>时长</th>
        <th>开始时间</th><th>结束时间</th><th>充电费</th><th>服务费</th><th>总费用</th>
      </tr>
      </thead>
      <tbody>${rows}</tbody>
    </table>
  `;
}

function renderRequests(rows) {
  if (!rows || rows.length === 0) {
    requestsView.innerHTML = `<div>${ZH.noRequests}</div>`;
    clearQueueInfoWithHint();
    return;
  }
  const currentId = (byId("requestId")?.value || "").trim();
  const html = rows.map((r) => {
    const selectedClass = String(r.requestId) === currentId ? "selected-row" : "";
    return `
    <tr data-request-id="${r.requestId}" class="${selectedClass}">
      <td>${r.requestId}</td>
      <td>${r.queueNumber ?? "--"}</td>
      <td>${modeLabel(r.mode)}</td>
      <td><span class="tag">${statusLabel(r.status)}</span></td>
      <td>${queueAreaLabel(r.queueArea)}</td>
      <td>${r.batteryCapacityKwh ?? 0}</td>
      <td>${r.requestKwh}</td>
      <td>${r.frontCars ?? 0}</td>
      <td>${r.pileId ?? "--"}</td>
      <td>${formatDateTime(r.enqueueTime)}</td>
    </tr>
  `;
  }).join("");
  requestsView.innerHTML = `
    <table>
      <thead>
      <tr>
        <th>订单ID</th><th>排队号</th><th>模式</th><th>状态</th><th>区域</th>
        <th>电池容量(kWh)</th><th>电量(kWh)</th><th>前车</th><th>充电桩</th><th>入队时间</th>
      </tr>
      </thead>
      <tbody>${html}</tbody>
    </table>
  `;

  requestsView.querySelectorAll("tbody tr").forEach((tr) => {
    tr.style.cursor = "pointer";
    tr.addEventListener("click", async () => {
      const id = tr.getAttribute("data-request-id");
      byId("requestId").value = id;
      const row = rows.find((x) => String(x.requestId) === String(id));
      if (row) {
        if (row.mode) byId("mode").value = row.mode;
        if (row.requestKwh != null) byId("requestKwh").value = row.requestKwh;
        if (row.batteryCapacityKwh != null) byId("batteryCapacityKwh").value = row.batteryCapacityKwh;
      }
      setMessage(userMessage, `${ZH.requestSelected}${id}`);
      await refreshQueueInfo();
      await refreshRequests(false);
    });
  });
}

function renderPiles(piles) {
  if (!piles || piles.length === 0) {
    pileView.innerHTML = `<div>${ZH.noPileData}</div>`;
    return;
  }
  const rows = piles.map((p) => `
    <tr>
      <td>${p.pileId}</td>
      <td>${modeLabel(p.mode)}</td>
      <td><span class="tag">${pileStateLabel(p.state)}</span></td>
      <td>${p.totalChargeCount}</td>
      <td>${p.totalChargeHours}</td>
      <td>${p.totalChargeKwh}</td>
      <td>${(p.queueCars || []).length}</td>
      <td>${(p.queueCars || []).map((c) => `
        <div>
          请求${c.requestId}（${c.queueNumber}）<br/>
          用户${c.userId}，电池${c.batteryCapacityKwh}kWh，请求${c.requestKwh}kWh<br/>
          状态：${statusLabel(c.status)}，已排队${c.queuedMinutes}分钟
        </div>
      `).join("<hr/>") || "--"}</td>
    </tr>
  `).join("");
  pileView.innerHTML = `
    <table>
      <thead>
      <tr>
        <th>桩编号</th><th>类型</th><th>状态</th><th>累计次数</th>
        <th>累计时长(h)</th><th>累计电量(kWh)</th><th>排队数</th><th>等候服务车辆信息</th>
      </tr>
      </thead>
      <tbody>${rows}</tbody>
    </table>
  `;
}

function renderReport(rows) {
  if (!rows || rows.length === 0) {
    reportView.innerHTML = `<div>${ZH.noReportData}</div>`;
    return;
  }
  const html = rows.map((r) => `
    <tr>
      <td>${periodLabel(r.period)}</td>
      <td>${r.pileId}</td>
      <td>${r.totalChargeCount}</td>
      <td>${r.totalChargeHours}</td>
      <td>${r.totalChargeKwh}</td>
      <td>${r.totalChargeFee}</td>
      <td>${r.totalServiceFee}</td>
      <td>${r.totalFee}</td>
    </tr>
  `).join("");
  reportView.innerHTML = `
    <table>
      <thead>
      <tr>
        <th>统计周期</th><th>充电桩</th><th>累计次数</th><th>累计时长(h)</th>
        <th>累计电量(kWh)</th><th>累计充电费</th><th>累计服务费</th><th>累计总费用</th>
      </tr>
      </thead>
      <tbody>${html}</tbody>
    </table>
  `;
}

async function refreshSystemTime() {
  const res = await request("/api/admin/time");
  systemTimeText.textContent = formatDateTime(res.data.systemTime);
}

async function advanceMinutes(minutes) {
  const res = await request("/api/admin/time/advance", {
    method: "POST",
    body: JSON.stringify({ minutes }),
  });
  systemTimeText.textContent = formatDateTime(res.data.systemTime);
  setMessage(adminMessage, `${ZH.advanced}${minutes}${ZH.advancedSuffix}`);
  await refreshPiles();
  await refreshUserPanelsIfLoggedIn();
}

async function refreshQueueInfo() {
  const reqId = requestIdValue();
  if (reqId == null) {
    clearQueueInfoWithHint();
    return;
  }
  const url = `/api/user/queue-info?userId=${userIdValue()}&requestId=${reqId}`;
  const res = await request(url);
  renderQueueInfo(res.data);
}

async function refreshRequests(includeFinished = false) {
  const res = await request(`/api/user/requests?userId=${userIdValue()}&includeFinished=${includeFinished}`);
  renderRequests(res.data);
  return res.data || [];
}

async function refreshBills() {
  const res = await request(`/api/user/bills?userId=${userIdValue()}`);
  renderBills(res.data);
}

async function refreshPiles() {
  const res = await request("/api/admin/piles");
  renderPiles(res.data);
}

async function refreshReport() {
  const period = byId("reportPeriod").value;
  const res = await request(`/api/admin/report?period=${period}`);
  renderReport(res.data);
}

async function refreshUserPanelsIfLoggedIn() {
  const userId = (byId("userId")?.value || "").trim();
  if (!userId) return;
  await refreshRequests(false);
  await Promise.all([refreshQueueInfo(), refreshBills()]);
}

function bind(id, fn, scope = "user") {
  byId(id).addEventListener("click", async () => {
    try {
      await fn();
    } catch (e) {
      setMessage(scope === "admin" ? adminMessage : userMessage, e.message, true);
    }
  });
}

bind("btnRegister", async () => {
  await request("/api/auth/register", {
    method: "POST",
    body: JSON.stringify({
      username: byId("regUsername").value.trim(),
      password: byId("regPassword").value.trim(),
    }),
  });
  setMessage(userMessage, ZH.registerOk);
});

bind("btnLogin", async () => {
  const res = await request("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({
      username: byId("loginUsername").value.trim(),
      password: byId("loginPassword").value.trim(),
    }),
  });
  byId("userId").value = res.data.userId;
  setMessage(userMessage, `${ZH.loginOk}${res.data.userId}`);
  await Promise.all([refreshRequests(false), refreshBills()]);
  clearQueueInfoWithHint();
});

bind("btnSubmitReq", async () => {
  const res = await request("/api/user/request", {
    method: "POST",
    body: JSON.stringify({
      userId: userIdValue(),
      mode: byId("mode").value,
      batteryCapacityKwh: Number(byId("batteryCapacityKwh").value),
      requestKwh: Number(byId("requestKwh").value),
    }),
  });
  if (res?.data?.requestId) {
    byId("requestId").value = res.data.requestId;
  }
  setMessage(userMessage, ZH.submitOk);
  await Promise.all([refreshRequests(false), refreshQueueInfo()]);
});

bind("btnModifyReq", async () => {
  const payload = {
    userId: userIdValue(),
    mode: byId("mode").value,
    batteryCapacityKwh: Number(byId("batteryCapacityKwh").value),
    requestKwh: Number(byId("requestKwh").value),
  };
  const reqId = requestIdValue();
  if (reqId != null) payload.requestId = reqId;
  await request("/api/user/request", {
    method: "PUT",
    body: JSON.stringify(payload),
  });
  setMessage(userMessage, ZH.modifyOk);
  await Promise.all([refreshRequests(false), refreshQueueInfo()]);
});

bind("btnCancelReq", async () => {
  const reqId = requestIdValue();
  const url = reqId == null
    ? `/api/user/request?userId=${userIdValue()}`
    : `/api/user/request?userId=${userIdValue()}&requestId=${reqId}`;
  await request(url, { method: "DELETE" });
  setMessage(userMessage, ZH.cancelOk);
  await Promise.all([refreshRequests(false), refreshQueueInfo(), refreshBills(), refreshPiles()]);
});

bind("btnEndCharge", async () => {
  const payload = { userId: userIdValue() };
  const reqId = requestIdValue();
  if (reqId != null) payload.requestId = reqId;
  await request("/api/user/end", {
    method: "POST",
    body: JSON.stringify(payload),
  });
  setMessage(userMessage, ZH.endOk);
  await Promise.all([refreshRequests(false), refreshQueueInfo(), refreshBills(), refreshPiles()]);
});

bind("btnUserRefresh", async () => {
  await refreshUserPanelsIfLoggedIn();
  setMessage(userMessage, ZH.userRefreshed);
});

bind("btnPiles", async () => {
  await refreshPiles();
  setMessage(adminMessage, ZH.pilesRefreshed);
}, "admin");

bind("btnSetStrategy", async () => {
  await request("/api/admin/fault-strategy", {
    method: "POST",
    body: JSON.stringify({ strategy: byId("strategy").value }),
  });
  setMessage(adminMessage, ZH.strategyOk);
}, "admin");

bind("btnChangePileState", async () => {
  await request("/api/admin/pile-state", {
    method: "POST",
    body: JSON.stringify({
      pileId: byId("pileId").value.trim(),
      state: byId("pileState").value,
    }),
  });
  setMessage(adminMessage, ZH.pileStateOk);
  await refreshPiles();
  await refreshUserPanelsIfLoggedIn();
}, "admin");

bind("btnUpdateConfig", async () => {
  const payload = {};
  if (byId("waitingAreaSize").value.trim()) {
    payload.waitingAreaSize = Number(byId("waitingAreaSize").value);
  }
  if (byId("chargingQueueLen").value.trim()) {
    payload.chargingQueueLen = Number(byId("chargingQueueLen").value);
  }
  if (byId("fastChargingPileNum").value.trim()) {
    payload.fastChargingPileNum = Number(byId("fastChargingPileNum").value);
  }
  if (byId("slowChargingPileNum").value.trim()) {
    payload.slowChargingPileNum = Number(byId("slowChargingPileNum").value);
  }
  if (byId("fastPower").value.trim()) {
    payload.fastPower = Number(byId("fastPower").value);
  }
  if (byId("slowPower").value.trim()) {
    payload.slowPower = Number(byId("slowPower").value);
  }
  await request("/api/admin/config", {
    method: "POST",
    body: JSON.stringify(payload),
  });
  setMessage(adminMessage, ZH.configOk);
}, "admin");

bind("btnReport", async () => {
  await refreshReport();
  setMessage(adminMessage, ZH.reportRefreshed);
}, "admin");

bind("btnTime10", () => advanceMinutes(10), "admin");
bind("btnTime30", () => advanceMinutes(30), "admin");
bind("btnTime60", () => advanceMinutes(60), "admin");
bind("btnTime360", () => advanceMinutes(360), "admin");
bind("btnTime1440", () => advanceMinutes(1440), "admin");

bind("btnAdvanceCustom", async () => {
  const minutes = Number(byId("customMinutes").value);
  if (!minutes || minutes < 1) {
    throw new Error(ZH.badMinutes);
  }
  await advanceMinutes(minutes);
}, "admin");

bind("btnRefreshAll", async () => {
  await Promise.all([refreshSystemTime(), refreshPiles(), refreshReport()]);
  await refreshUserPanelsIfLoggedIn();
  setMessage(adminMessage, ZH.overviewRefreshed);
}, "admin");

byId("requestId").addEventListener("change", async () => {
  try {
    await refreshQueueInfo();
    await refreshRequests(false);
  } catch (e) {
    setMessage(userMessage, e.message, true);
  }
});

async function bootstrap() {
  try {
    await Promise.all([refreshSystemTime(), refreshPiles(), refreshReport()]);
    clearQueueInfoWithHint();
  } catch (e) {
    setMessage(adminMessage, e.message, true);
  }
}

bootstrap();
