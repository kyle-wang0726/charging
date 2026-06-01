const byId = (id) => document.getElementById(id);

const userPanel = byId("userPanel");
const adminPanel = byId("adminPanel");
const btnShowUser = byId("btnShowUser");
const btnShowAdmin = byId("btnShowAdmin");
const userAuthCard = byId("userAuthCard");
const userWorkspace = byId("userWorkspace");
const userIdInput = byId("userId");
const userIdText = byId("userIdText");

const userMessage = byId("userMessage");
const adminMessage = byId("adminMessage");
const queueInfoView = byId("queueInfoView");
const billsView = byId("billsView");
const requestsView = byId("requestsView");
const pileView = byId("pileView");
const reportView = byId("reportView");
const systemTimeText = byId("systemTimeText");

let currentView = "user";
let loggedInUserId = null;

const ZH = {
  needLogin: "请先登录用户端账号。",
  requestFailed: "请求失败",
  noActiveRequest: "当前没有进行中的充电请求。",
  noRequests: "暂无订单记录。",
  selectRequestHint: "请先在订单列表中选择一条订单。",
  noBills: "暂无充电详单。",
  noPileData: "暂无充电桩数据。",
  noReportData: "暂无报表数据。",
  registerOk: "注册成功，请使用新账号登录。",
  loginOk: "登录成功，已进入用户操作界面。",
  logoutOk: "已退出登录。",
  submitOk: "请求已提交。",
  modifyOk: "请求已修改。",
  cancelOk: "请求已取消。",
  endOk: "已结束充电。",
  requestSelected: "已选中订单 requestId=",
  userRefreshed: "用户端数据已刷新。",
  pilesRefreshed: "充电桩状态已刷新。",
  strategyOk: "调度策略已更新。",
  pileStateOk: "充电桩状态已更新。",
  configOk: "系统配置已更新。",
  reportRefreshed: "报表已刷新。",
  overviewRefreshed: "总览已刷新。",
  advanced: "系统时间已推进 ",
  advancedSuffix: " 分钟。",
  badMinutes: "分钟数必须大于 0。",
};

function baseUrl() {
  return byId("baseUrl").value.trim().replace(/\/$/, "");
}

function toChineseError(message) {
  const raw = String(message || "").trim();
  if (!raw) return ZH.requestFailed;
  const map = [
    ["username cannot be empty", "用户名不能为空。"],
    ["password cannot be empty", "密码不能为空。"],
    ["username already exists", "用户名已存在。"],
    ["invalid username or password", "用户名或密码错误。"],
    ["request kwh cannot exceed battery capacity", "请求电量不能超过车辆电池容量。"],
    ["battery capacity must be greater than 0", "车辆电池容量必须大于 0。"],
    ["request kwh must be greater than 0", "请求电量必须大于 0。"],
    ["waiting area is full, cannot create request", "等候区已满，无法创建订单。"],
    ["request not found for user", "未找到该用户的订单。"],
    ["request is not active", "该订单不在进行中。"],
    ["no active request", "当前没有进行中的订单。"],
    ["multiple active requests, requestId is required", "当前有多个进行中订单，请填写 requestId。"],
    ["no waiting-area request to modify", "当前没有可修改的等候区订单。"],
    ["multiple waiting-area requests, requestId is required", "存在多个可修改订单，请填写 requestId。"],
    ["no charging request to end", "当前没有正在充电的订单。"],
    ["request is not charging", "该订单当前不在充电中。"],
    ["user not found", "用户不存在。"],
    ["pile not found", "充电桩不存在。"],
    ["cannot change pile count while requests are active", "存在进行中的订单时，不可修改充电桩数量。"],
    ["minutes must be greater than 0", "推进分钟数必须大于 0。"],
  ];
  for (const [en, zh] of map) {
    if (raw.includes(en)) return zh;
  }
  return raw;
}

function setMessage(el, text, type = "info") {
  el.textContent = text;
  el.classList.remove("msg-success", "msg-error", "msg-warn");
  if (type === "success") el.classList.add("msg-success");
  if (type === "error") el.classList.add("msg-error");
  if (type === "warn") el.classList.add("msg-warn");
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
  if (!loggedInUserId) throw new Error(ZH.needLogin);
  return Number(loggedInUserId);
}

function requestIdValue() {
  const val = (byId("requestId")?.value || "").trim();
  if (!val) return null;
  const num = Number(val);
  if (!Number.isFinite(num) || num <= 0) throw new Error("requestId 必须是正整数。");
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
    throw new Error(toChineseError(data.message || ZH.requestFailed));
  }
  return data;
}

function setView(view) {
  currentView = view;
  const showUser = view === "user";
  userPanel.classList.toggle("hidden", !showUser);
  adminPanel.classList.toggle("hidden", showUser);
  btnShowUser.classList.toggle("active", showUser);
  btnShowAdmin.classList.toggle("active", !showUser);
}

function applyUserSessionState() {
  const loggedIn = !!loggedInUserId;
  userAuthCard.classList.toggle("hidden", loggedIn);
  userWorkspace.classList.toggle("hidden", !loggedIn);
  userIdInput.value = loggedIn ? String(loggedInUserId) : "";
  userIdText.textContent = loggedIn ? String(loggedInUserId) : "--";
  if (!loggedIn) {
    requestsView.innerHTML = "<div>登录后可查看订单信息。</div>";
    billsView.innerHTML = "<div>登录后可查看充电详单。</div>";
    clearQueueInfoWithHint();
  }
}

function renderQueueInfo(data) {
  if (!data || !data.active) {
    queueInfoView.innerHTML = `<div>${ZH.noActiveRequest}</div>`;
    return;
  }
  queueInfoView.innerHTML = `
    <div class="kvs">
      <div class="k">请求ID</div><div class="v">${data.requestId ?? "--"}</div>
      <div class="k">排队号（队列编号）</div><div class="v">${data.queueNumber ?? "--"}</div>
      <div class="k">充电模式</div><div class="v">${modeLabel(data.mode)}</div>
      <div class="k">当前状态</div><div class="v"><span class="tag">${statusLabel(data.status)}</span></div>
      <div class="k">所在区域</div><div class="v">${queueAreaLabel(data.queueArea)}</div>
      <div class="k">车辆电池容量</div><div class="v">${data.batteryCapacityKwh ?? 0} kWh</div>
      <div class="k">请求电量</div><div class="v">${data.requestKwh ?? 0} kWh</div>
      <div class="k">前车数量</div><div class="v">${data.frontCars ?? 0}</div>
      <div class="k">充电桩编号</div><div class="v">${data.pileId ?? "--"}</div>
      <div class="k">入队时间</div><div class="v">${formatDateTime(data.enqueueTime)}</div>
      <div class="k">开始充电时间</div><div class="v">${formatDateTime(data.startTime)}</div>
      <div class="k">预计完成时间</div><div class="v">${formatDateTime(data.expectedFinishTime)}</div>
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
        <th>订单ID</th><th>排队号（队列）</th><th>模式</th><th>状态</th><th>区域</th>
        <th>电池容量(kWh)</th><th>电量(kWh)</th><th>前车</th><th>充电桩编号</th><th>入队时间</th>
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
      setMessage(userMessage, `${ZH.requestSelected}${id}`, "info");
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
          请求${c.requestId}（排队号 ${c.queueNumber}）<br/>
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
        <th>充电桩编号</th><th>类型</th><th>状态</th><th>累计次数</th>
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
        <th>统计周期</th><th>充电桩编号</th><th>累计次数</th><th>累计时长(h)</th>
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
  setMessage(adminMessage, `${ZH.advanced}${minutes}${ZH.advancedSuffix}`, "success");
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
  if (!loggedInUserId) return;
  await refreshRequests(false);
  await Promise.all([refreshQueueInfo(), refreshBills()]);
}

function bind(id, fn, scope = "user") {
  byId(id).addEventListener("click", async () => {
    try {
      await fn();
    } catch (e) {
      const msg = toChineseError(e.message);
      setMessage(scope === "admin" ? adminMessage : userMessage, msg, "error");
    }
  });
}

bind("btnShowUser", async () => {
  setView("user");
  if (!loggedInUserId) {
    setMessage(userMessage, "请先登录后再操作订单。", "warn");
  }
});

bind("btnShowAdmin", async () => {
  setView("admin");
});

bind("btnRegister", async () => {
  await request("/api/auth/register", {
    method: "POST",
    body: JSON.stringify({
      username: byId("regUsername").value.trim(),
      password: byId("regPassword").value.trim(),
    }),
  });
  setMessage(userMessage, ZH.registerOk, "success");
}, "user");

bind("btnLogin", async () => {
  const res = await request("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({
      username: byId("loginUsername").value.trim(),
      password: byId("loginPassword").value.trim(),
    }),
  });
  loggedInUserId = res.data.userId;
  applyUserSessionState();
  setView("user");
  setMessage(userMessage, `${ZH.loginOk} 用户ID：${res.data.userId}`, "success");
  await Promise.all([refreshRequests(false), refreshBills()]);
  clearQueueInfoWithHint();
}, "user");

bind("btnLogout", async () => {
  loggedInUserId = null;
  applyUserSessionState();
  setMessage(userMessage, ZH.logoutOk, "warn");
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
  if (res?.data?.requestId) byId("requestId").value = res.data.requestId;
  setMessage(userMessage, ZH.submitOk, "success");
  await Promise.all([refreshRequests(false), refreshQueueInfo(), refreshPiles()]);
}, "user");

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
  setMessage(userMessage, ZH.modifyOk, "success");
  await Promise.all([refreshRequests(false), refreshQueueInfo(), refreshPiles()]);
}, "user");

bind("btnCancelReq", async () => {
  const reqId = requestIdValue();
  const url = reqId == null
    ? `/api/user/request?userId=${userIdValue()}`
    : `/api/user/request?userId=${userIdValue()}&requestId=${reqId}`;
  await request(url, { method: "DELETE" });
  setMessage(userMessage, ZH.cancelOk, "success");
  await Promise.all([refreshRequests(false), refreshQueueInfo(), refreshBills(), refreshPiles()]);
}, "user");

bind("btnEndCharge", async () => {
  const payload = { userId: userIdValue() };
  const reqId = requestIdValue();
  if (reqId != null) payload.requestId = reqId;
  await request("/api/user/end", {
    method: "POST",
    body: JSON.stringify(payload),
  });
  setMessage(userMessage, ZH.endOk, "success");
  await Promise.all([refreshRequests(false), refreshQueueInfo(), refreshBills(), refreshPiles()]);
}, "user");

bind("btnUserRefresh", async () => {
  await refreshUserPanelsIfLoggedIn();
  setMessage(userMessage, ZH.userRefreshed, "success");
}, "user");

bind("btnPiles", async () => {
  await refreshPiles();
  setMessage(adminMessage, ZH.pilesRefreshed, "success");
}, "admin");

bind("btnSetStrategy", async () => {
  await request("/api/admin/fault-strategy", {
    method: "POST",
    body: JSON.stringify({ strategy: byId("strategy").value }),
  });
  setMessage(adminMessage, ZH.strategyOk, "success");
}, "admin");

bind("btnChangePileState", async () => {
  await request("/api/admin/pile-state", {
    method: "POST",
    body: JSON.stringify({
      pileId: byId("pileId").value.trim(),
      state: byId("pileState").value,
    }),
  });
  setMessage(adminMessage, ZH.pileStateOk, "success");
  await refreshPiles();
  await refreshUserPanelsIfLoggedIn();
}, "admin");

bind("btnUpdateConfig", async () => {
  const payload = {};
  if (byId("waitingAreaSize").value.trim()) payload.waitingAreaSize = Number(byId("waitingAreaSize").value);
  if (byId("chargingQueueLen").value.trim()) payload.chargingQueueLen = Number(byId("chargingQueueLen").value);
  if (byId("fastChargingPileNum").value.trim()) payload.fastChargingPileNum = Number(byId("fastChargingPileNum").value);
  if (byId("slowChargingPileNum").value.trim()) payload.slowChargingPileNum = Number(byId("slowChargingPileNum").value);
  if (byId("fastPower").value.trim()) payload.fastPower = Number(byId("fastPower").value);
  if (byId("slowPower").value.trim()) payload.slowPower = Number(byId("slowPower").value);
  await request("/api/admin/config", {
    method: "POST",
    body: JSON.stringify(payload),
  });
  setMessage(adminMessage, ZH.configOk, "success");
}, "admin");

bind("btnReport", async () => {
  await refreshReport();
  setMessage(adminMessage, ZH.reportRefreshed, "success");
}, "admin");

bind("btnTime10", () => advanceMinutes(10), "admin");
bind("btnTime30", () => advanceMinutes(30), "admin");
bind("btnTime60", () => advanceMinutes(60), "admin");
bind("btnTime360", () => advanceMinutes(360), "admin");
bind("btnTime1440", () => advanceMinutes(1440), "admin");

bind("btnAdvanceCustom", async () => {
  const minutes = Number(byId("customMinutes").value);
  if (!minutes || minutes < 1) throw new Error(ZH.badMinutes);
  await advanceMinutes(minutes);
}, "admin");

bind("btnRefreshAll", async () => {
  await Promise.all([refreshSystemTime(), refreshPiles(), refreshReport()]);
  await refreshUserPanelsIfLoggedIn();
  setMessage(adminMessage, ZH.overviewRefreshed, "success");
}, "admin");

byId("requestId").addEventListener("change", async () => {
  try {
    await refreshQueueInfo();
    await refreshRequests(false);
  } catch (e) {
    setMessage(userMessage, toChineseError(e.message), "error");
  }
});

async function bootstrap() {
  applyUserSessionState();
  setView(currentView);
  try {
    await Promise.all([refreshSystemTime(), refreshPiles(), refreshReport()]);
    clearQueueInfoWithHint();
    setMessage(userMessage, "请先登录后再提交或修改订单。", "warn");
  } catch (e) {
    setMessage(adminMessage, toChineseError(e.message), "error");
  }
}

bootstrap();
