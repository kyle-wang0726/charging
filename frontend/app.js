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
  needLogin: "\u8bf7\u5148\u767b\u5f55",
  requestFailed: "\u8bf7\u6c42\u5931\u8d25",
  noActiveRequest: "\u5f53\u524d\u6ca1\u6709\u8fdb\u884c\u4e2d\u7684\u5145\u7535\u8bf7\u6c42\u3002",
  noRequests: "\u6682\u65e0\u8ba2\u5355\u8bb0\u5f55\u3002",
  selectRequestHint: "\u8bf7\u5148\u5728\u300c\u6211\u7684\u8ba2\u5355\u300d\u4e2d\u9009\u62e9\u4e00\u6761\u8ba2\u5355\u3002",
  noBills: "\u6682\u65e0\u5145\u7535\u8be6\u5355\u3002",
  noPileData: "\u6682\u65e0\u5145\u7535\u6869\u6570\u636e\u3002",
  noReportData: "\u6682\u65e0\u62a5\u8868\u6570\u636e\u3002",
  registerOk: "\u6ce8\u518c\u6210\u529f",
  loginOk: "\u767b\u5f55\u6210\u529f\uff0c\u5f53\u524d\u7528\u6237ID\uff1a",
  submitOk: "\u8bf7\u6c42\u5df2\u63d0\u4ea4",
  modifyOk: "\u8bf7\u6c42\u5df2\u4fee\u6539",
  cancelOk: "\u8bf7\u6c42\u5df2\u53d6\u6d88",
  endOk: "\u5df2\u7ed3\u675f\u5145\u7535",
  queueRefreshed: "\u6392\u961f\u4fe1\u606f\u5df2\u5237\u65b0",
  requestsRefreshed: "\u8ba2\u5355\u5217\u8868\u5df2\u5237\u65b0",
  requestSelected: "\u5df2\u9009\u4e2d\u8ba2\u5355 requestId=",
  billRefreshed: "\u8be6\u5355\u5df2\u5237\u65b0",
  pilesRefreshed: "\u5145\u7535\u6869\u72b6\u6001\u5df2\u5237\u65b0",
  strategyOk: "\u8c03\u5ea6\u7b56\u7565\u5df2\u66f4\u65b0",
  pileStateOk: "\u5145\u7535\u6869\u72b6\u6001\u5df2\u66f4\u65b0",
  configOk: "\u7cfb\u7edf\u914d\u7f6e\u5df2\u66f4\u65b0",
  reportRefreshed: "\u62a5\u8868\u5df2\u5237\u65b0",
  overviewRefreshed: "\u603b\u89c8\u5df2\u5237\u65b0",
  advanced: "\u7cfb\u7edf\u65f6\u95f4\u5df2\u63a8\u8fdb ",
  advancedSuffix: " \u5206\u949f",
  badMinutes: "\u5206\u949f\u6570\u5fc5\u987b\u5927\u4e8e0",
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
  if (mode === "FAST") return "\u5feb\u5145";
  if (mode === "SLOW") return "\u6162\u5145";
  return mode || "--";
}

function statusLabel(status) {
  const map = {
    WAITING_AREA: "\u7b49\u5019\u533a",
    QUEUED: "\u5145\u7535\u533a\u6392\u961f",
    CHARGING: "\u5145\u7535\u4e2d",
    COMPLETED: "\u5df2\u5b8c\u6210",
    CANCELED: "\u5df2\u53d6\u6d88",
  };
  return map[status] || status || "--";
}

function pileStateLabel(state) {
  const map = {
    WORKING: "\u5de5\u4f5c\u4e2d",
    SHUTDOWN: "\u5df2\u5173\u95ed",
    FAULT: "\u6545\u969c",
  };
  return map[state] || state || "--";
}

function periodLabel(period) {
  const map = {
    DAY: "\u65e5\u62a5",
    WEEK: "\u5468\u62a5",
    MONTH: "\u6708\u62a5",
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
    throw new Error("requestId must be a positive number");
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
      <div class="k">\u8bf7\u6c42ID</div><div class="v">${data.requestId ?? "--"}</div>
      <div class="k">\u6392\u961f\u53f7</div><div class="v">${data.queueNumber ?? "--"}</div>
      <div class="k">\u5145\u7535\u6a21\u5f0f</div><div class="v">${modeLabel(data.mode)}</div>
      <div class="k">\u5f53\u524d\u72b6\u6001</div><div class="v"><span class="tag">${statusLabel(data.status)}</span></div>
      <div class="k">\u8bf7\u6c42\u7535\u91cf</div><div class="v">${data.requestKwh ?? 0} kWh</div>
      <div class="k">\u524d\u8f66\u6570\u91cf</div><div class="v">${data.frontCars ?? 0}</div>
      <div class="k">\u5206\u914d\u5145\u7535\u6869</div><div class="v">${data.pileId ?? "--"}</div>
      <div class="k">\u5165\u961f\u65f6\u95f4</div><div class="v">${formatDateTime(data.enqueueTime)}</div>
      <div class="k">\u5f00\u59cb\u5145\u7535</div><div class="v">${formatDateTime(data.startTime)}</div>
      <div class="k">\u9884\u8ba1\u5b8c\u6210</div><div class="v">${formatDateTime(data.expectedFinishTime)}</div>
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
        <th>\u8be6\u5355\u53f7</th><th>\u751f\u6210\u65f6\u95f4</th><th>\u5145\u7535\u6869</th><th>\u7535\u91cf</th><th>\u65f6\u957f</th>
        <th>\u5f00\u59cb\u65f6\u95f4</th><th>\u7ed3\u675f\u65f6\u95f4</th><th>\u5145\u7535\u8d39</th><th>\u670d\u52a1\u8d39</th><th>\u603b\u8d39\u7528</th>
      </tr>
      </thead>
      <tbody>${rows}</tbody>
    </table>
  `;
}

function renderRequests(rows) {
  if (!rows || rows.length === 0) {
    requestsView.innerHTML = `<div>${ZH.noRequests}</div>`;
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
        <th>订单ID</th><th>排队号</th><th>模式</th><th>状态</th><th>电量(kWh)</th><th>前车</th><th>充电桩</th><th>入队时间</th>
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
      <td>${(p.queueCars || []).map(c => `${c.queueNumber} / \u7528\u6237${c.userId} / ${statusLabel(c.status)}`).join("<br/>") || "--"}</td>
    </tr>
  `).join("");
  pileView.innerHTML = `
    <table>
      <thead>
      <tr>
        <th>\u6869\u7f16\u53f7</th><th>\u7c7b\u578b</th><th>\u72b6\u6001</th><th>\u7d2f\u8ba1\u6b21\u6570</th>
        <th>\u7d2f\u8ba1\u65f6\u957f(h)</th><th>\u7d2f\u8ba1\u7535\u91cf(kWh)</th><th>\u6392\u961f\u6570</th><th>\u6392\u961f\u8be6\u60c5</th>
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
        <th>\u7edf\u8ba1\u5468\u671f</th><th>\u5145\u7535\u6869</th><th>\u7d2f\u8ba1\u6b21\u6570</th><th>\u7d2f\u8ba1\u65f6\u957f(h)</th>
        <th>\u7d2f\u8ba1\u7535\u91cf(kWh)</th><th>\u7d2f\u8ba1\u5145\u7535\u8d39</th><th>\u7d2f\u8ba1\u670d\u52a1\u8d39</th><th>\u7d2f\u8ba1\u603b\u8d39\u7528</th>
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
  try {
    await refreshQueueInfo();
  } catch (e) {
    // ignore if user not logged in
  }
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
      batteryCapacityKwh: Number(byId("regBattery").value || 60),
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
  const reqId = requestIdValue();
  const payload = {
    userId: userIdValue(),
    mode: byId("mode").value,
    requestKwh: Number(byId("requestKwh").value),
  };
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
  await Promise.all([refreshRequests(false), refreshQueueInfo()]);
});

bind("btnEndCharge", async () => {
  const reqId = requestIdValue();
  const payload = { userId: userIdValue() };
  if (reqId != null) payload.requestId = reqId;
  await request("/api/user/end", {
    method: "POST",
    body: JSON.stringify(payload),
  });
  setMessage(userMessage, ZH.endOk);
  await Promise.all([refreshRequests(false), refreshQueueInfo(), refreshBills(), refreshPiles()]);
});

bind("btnQueueInfo", async () => {
  await refreshQueueInfo();
  setMessage(userMessage, ZH.queueRefreshed);
});

bind("btnRequests", async () => {
  await refreshRequests(false);
  setMessage(userMessage, ZH.requestsRefreshed);
});

bind("btnBills", async () => {
  await refreshBills();
  setMessage(userMessage, ZH.billRefreshed);
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
}, "admin");

bind("btnUpdateConfig", async () => {
  const payload = {};
  if (byId("waitingAreaSize").value.trim()) {
    payload.waitingAreaSize = Number(byId("waitingAreaSize").value);
  }
  if (byId("chargingQueueLen").value.trim()) {
    payload.chargingQueueLen = Number(byId("chargingQueueLen").value);
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
