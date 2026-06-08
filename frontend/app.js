const { createApp } = Vue;

const ZH = {
  needLogin: "请先登录用户端账号。",
  requestFailed: "请求失败",
  noActiveRequest: "当前没有进行中的充电请求。",
  selectRequestHint: "请先在订单列表中选择一条订单。",
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
  dispatchStrategyOk: "扩展调度策略已更新。",
  pileStateOk: "充电桩状态已更新。",
  configOk: "系统配置已更新。",
  reportRefreshed: "报表已刷新。",
  overviewRefreshed: "总览已刷新。",
  advanced: "系统时间已推进 ",
  advancedSuffix: " 分钟。",
  badMinutes: "分钟数必须大于 0。",
};

const DEFAULT_BASE_URL = "http://localhost:8080";

createApp({
  data() {
    return {
      baseUrl: DEFAULT_BASE_URL,
      currentView: "user",
      activeTab: "panel",
      authTab: "login",
      autoTimer: null,
      showPileControl: true,
      showQueueModal: false,
      userNav: [
        { id: "panel", label: "充电面板", icon: "⚡" },
        { id: "bills", label: "充电详单", icon: "🧾" },
      ],
      adminNav: [
        { id: "piles", label: "充电桩管理", icon: "🧭" },
        { id: "config", label: "系统配置", icon: "⚙" },
        { id: "report", label: "运营报表", icon: "📊" },
      ],
      auth: {
        regUsername: "",
        regPassword: "",
        loginUsername: "",
        loginPassword: "",
      },
      user: {
        loggedIn: false,
        id: null,
        form: {
          mode: "FAST",
          batteryCapacityKwh: 60,
          requestKwh: 30,
          vehicleNumber: "",
          requestId: "",
        },
      },
      admin: {
        strategy: "PRIORITY",
        dispatchStrategy: "NORMAL",
        pileId: "",
        pileState: "WORKING",
        waitingAreaSize: null,
        chargingQueueLen: null,
        fastChargingPileNum: null,
        slowChargingPileNum: null,
        fastPower: null,
        slowPower: null,
        reportPeriod: "day",
        customMinutes: 15,
      },
      userMessage: { text: "就绪", type: "info" },
      adminMessage: { text: "就绪", type: "info" },
      requests: [],
      bills: [],
      queueInfo: null,
      piles: [],
      report: [],
      systemTimeText: "--",
    };
  },
  computed: {
    activeNav() {
      return this.currentView === "admin" ? this.adminNav : this.userNav;
    },
    currentMessage() {
      return this.currentView === "admin" ? this.adminMessage : this.userMessage;
    },
    currentTitle() {
      const map = {
        panel: "充电面板",
        bills: "充电详单",
        piles: "充电桩管理",
        config: "系统配置",
        report: "运营报表",
      };
      return map[this.activeTab] || "控制台";
    },
    currentSubtitle() {
      const map = {
        panel: "提交与管理充电请求，并查看订单列表。",
        bills: "查看充电详单与费用。",
        piles: "启动、关闭充电桩并查看排队车辆。",
        config: "更新调度策略、系统容量、功率配置。",
        report: "查看日、周、月度报表。",
      };
      return map[this.activeTab] || "";
    },
    queueInfoHint() {
      if (!this.user.form.requestId) return ZH.selectRequestHint;
      return ZH.noActiveRequest;
    },
    showMessage() {
      return !(this.currentView === "user" && this.activeTab === "bills");
    },
    footerProfile() {
      if (this.currentView === "admin") {
        return { name: "管理员", desc: "系统管理员", initial: "管" };
      }
      if (!this.user.loggedIn) {
        return { name: "访客", desc: "尚未登录", initial: "访" };
      }
      const name = this.auth.loginUsername || `用户 ${this.user.id}`;
      const initial = String(name).trim().charAt(0) || "U";
      return { name, desc: `用户ID ${this.user.id}`, initial };
    },
  },
  methods: {
    messageClass(type) {
      return {
        success: type === "success",
        error: type === "error",
        warn: type === "warn",
      };
    },
    toChineseError(message) {
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
        ["vehicle number cannot be empty", "车辆编号不能为空。"],
        ["user not found", "用户不存在。"],
        ["pile not found", "充电桩不存在。"],
        ["cannot change pile count while requests are active", "存在进行中的订单时，不可修改充电桩数量。"],
        ["minutes must be greater than 0", "推进分钟数必须大于 0。"],
      ];
      for (const [en, zh] of map) {
        if (raw.includes(en)) return zh;
      }
      return raw;
    },
    setMessage(scope, text, type = "info") {
      if (scope === "admin") {
        this.adminMessage = { text, type };
      } else {
        this.userMessage = { text, type };
      }
    },
    formatDateTime(value) {
      if (!value) return "--";
      return String(value).replace("T", " ");
    },
    modeLabel(mode) {
      if (mode === "FAST") return "快充";
      if (mode === "SLOW") return "慢充";
      return mode || "--";
    },
    statusLabel(status) {
      const map = {
        WAITING_AREA: "等候区",
        QUEUED: "充电区排队",
        CHARGING: "充电中",
        COMPLETED: "已完成",
        CANCELED: "已取消",
      };
      return map[status] || status || "--";
    },
    queueAreaLabel(area) {
      const map = {
        WAITING_AREA: "等候区",
        FAULT_WAITING: "故障等候队列",
        CHARGING_AREA: "充电区",
        NONE: "--",
      };
      return map[area] || area || "--";
    },
    pileStateLabel(state) {
      const map = {
        WORKING: "工作中",
        SHUTDOWN: "已关闭",
        FAULT: "故障",
      };
      return map[state] || state || "--";
    },
    periodLabel(period) {
      const map = {
        DAY: "日报",
        WEEK: "周报",
        MONTH: "月报",
      };
      return map[period] || period || "--";
    },
    baseUrlValue() {
      return this.baseUrl.trim().replace(/\/$/, "");
    },
    async request(path, options = {}) {
      const resp = await fetch(`${this.baseUrlValue()}${path}`, {
        headers: { "Content-Type": "application/json" },
        ...options,
      });
      const data = await resp.json();
      if (!data.success) {
        throw new Error(this.toChineseError(data.message || ZH.requestFailed));
      }
      return data;
    },
    async safeRun(scope, fn) {
      try {
        await fn();
      } catch (e) {
        this.setMessage(scope, this.toChineseError(e.message), "error");
      }
    },
    setView(view) {
      this.currentView = view;
      this.activeTab = view === "admin" ? "piles" : "panel";
      if (view === "user" && !this.user.loggedIn) {
        this.setMessage("user", "请先登录后再操作订单。", "warn");
      }
    },
    userIdValue() {
      if (!this.user.loggedIn || !this.user.id) throw new Error(ZH.needLogin);
      return Number(this.user.id);
    },
    requestIdValue() {
      const raw = String(this.user.form.requestId || "").trim();
      if (!raw) return null;
      const num = Number(raw);
      if (!Number.isFinite(num) || num <= 0) throw new Error("requestId 必须是正整数。");
      return num;
    },
    async registerUser() {
      await this.safeRun("user", async () => {
        await this.request("/api/auth/register", {
          method: "POST",
          body: JSON.stringify({
            username: this.auth.regUsername.trim(),
            password: this.auth.regPassword.trim(),
          }),
        });
        this.authTab = "login";
        this.setMessage("user", ZH.registerOk, "success");
      });
    },
    async login() {
      await this.safeRun("user", async () => {
        const res = await this.request("/api/auth/login", {
          method: "POST",
          body: JSON.stringify({
            username: this.auth.loginUsername.trim(),
            password: this.auth.loginPassword.trim(),
          }),
        });
        this.user.loggedIn = true;
        this.user.id = res.data.userId;
        this.setMessage("user", `${ZH.loginOk} 用户ID：${res.data.userId}`, "success");
        await Promise.all([this.refreshRequests(), this.refreshBills()]);
        this.queueInfo = null;
      });
    },
    handleLogout() {
      if (this.currentView === "admin") {
        this.setView("user");
        return;
      }
      this.logout();
    },
    logout() {
      this.user.loggedIn = false;
      this.user.id = null;
      this.requests = [];
      this.bills = [];
      this.queueInfo = null;
      this.setMessage("user", ZH.logoutOk, "warn");
    },
    async submitRequest() {
      await this.safeRun("user", async () => {
        const res = await this.request("/api/user/request", {
          method: "POST",
          body: JSON.stringify({
            userId: this.userIdValue(),
            mode: this.user.form.mode,
            batteryCapacityKwh: Number(this.user.form.batteryCapacityKwh),
            requestKwh: Number(this.user.form.requestKwh),
            vehicleNumber: String(this.user.form.vehicleNumber).trim(),
          }),
        });
        if (res?.data?.requestId) this.user.form.requestId = String(res.data.requestId);
        this.setMessage("user", ZH.submitOk, "success");
        await Promise.all([this.refreshRequests(), this.refreshQueueInfo(), this.refreshPiles()]);
      });
    },
    async modifyRequest() {
      await this.safeRun("user", async () => {
        const payload = {
          userId: this.userIdValue(),
          mode: this.user.form.mode,
          batteryCapacityKwh: Number(this.user.form.batteryCapacityKwh),
          requestKwh: Number(this.user.form.requestKwh),
        };
        const reqId = this.requestIdValue();
        if (reqId != null) payload.requestId = reqId;
        await this.request("/api/user/request", {
          method: "PUT",
          body: JSON.stringify(payload),
        });
        this.setMessage("user", ZH.modifyOk, "success");
        await Promise.all([this.refreshRequests(), this.refreshQueueInfo(), this.refreshPiles()]);
      });
    },
    async cancelRequest() {
      await this.safeRun("user", async () => {
        const reqId = this.requestIdValue();
        const url = reqId == null
          ? `/api/user/request?userId=${this.userIdValue()}`
          : `/api/user/request?userId=${this.userIdValue()}&requestId=${reqId}`;
        await this.request(url, { method: "DELETE" });
        this.setMessage("user", ZH.cancelOk, "success");
        await Promise.all([this.refreshRequests(), this.refreshQueueInfo(), this.refreshBills(), this.refreshPiles()]);
      });
    },
    async endCharge() {
      await this.safeRun("user", async () => {
        const payload = { userId: this.userIdValue() };
        const reqId = this.requestIdValue();
        if (reqId != null) payload.requestId = reqId;
        await this.request("/api/user/end", {
          method: "POST",
          body: JSON.stringify(payload),
        });
        this.setMessage("user", ZH.endOk, "success");
        await Promise.all([this.refreshRequests(), this.refreshQueueInfo(), this.refreshBills(), this.refreshPiles()]);
      });
    },
    async refreshQueueInfo(openModal = false) {
      await this.safeRun("user", async () => {
        const reqId = this.requestIdValue();
        if (reqId == null) {
          this.queueInfo = null;
          if (openModal) this.showQueueModal = true;
          return;
        }
        const url = `/api/user/queue-info?userId=${this.userIdValue()}&requestId=${reqId}`;
        const res = await this.request(url);
        this.queueInfo = res.data;
        if (openModal) this.showQueueModal = true;
      });
    },
    async refreshRequests() {
      await this.safeRun("user", async () => {
        const res = await this.request(`/api/user/requests?userId=${this.userIdValue()}&includeFinished=false`);
        this.requests = res.data || [];
      });
    },
    async refreshBills() {
      await this.safeRun("user", async () => {
        const res = await this.request(`/api/user/bills?userId=${this.userIdValue()}`);
        this.bills = res.data || [];
      });
    },
    async refreshUserPanels() {
      await this.safeRun("user", async () => {
        if (!this.user.loggedIn) return;
        await Promise.all([this.refreshRequests(), this.refreshQueueInfo(), this.refreshBills()]);
        this.setMessage("user", ZH.userRefreshed, "success");
      });
    },
    async selectRequest(row) {
      this.user.form.requestId = String(row.requestId);
      if (row.mode) this.user.form.mode = row.mode;
      if (row.requestKwh != null) this.user.form.requestKwh = row.requestKwh;
      if (row.batteryCapacityKwh != null) this.user.form.batteryCapacityKwh = row.batteryCapacityKwh;
      this.setMessage("user", `${ZH.requestSelected}${row.requestId}`, "info");
      await this.refreshQueueInfo(true);
    },
    closeQueueModal() {
      this.showQueueModal = false;
    },
    async refreshSystemTime() {
      await this.safeRun("admin", async () => {
        const res = await this.request("/api/admin/time");
        this.systemTimeText = this.formatDateTime(res.data.systemTime);
      });
    },
    async advanceMinutes(minutes) {
      await this.safeRun("admin", async () => {
        const res = await this.request("/api/admin/time/advance", {
          method: "POST",
          body: JSON.stringify({ minutes }),
        });
        this.systemTimeText = this.formatDateTime(res.data.systemTime);
        this.setMessage("admin", `${ZH.advanced}${minutes}${ZH.advancedSuffix}`, "success");
        await Promise.all([this.refreshPiles(), this.refreshReport()]);
        await this.refreshUserPanels();
      });
    },
    async advanceCustom() {
      const minutes = Number(this.admin.customMinutes);
      if (!minutes || minutes < 1) {
        this.setMessage("admin", ZH.badMinutes, "error");
        return;
      }
      await this.advanceMinutes(minutes);
    },
    async refreshPiles() {
      await this.safeRun("admin", async () => {
        const res = await this.request("/api/admin/piles");
        this.piles = res.data || [];
      });
    },
    async setStrategy() {
      await this.safeRun("admin", async () => {
        await this.request("/api/admin/fault-strategy", {
          method: "POST",
          body: JSON.stringify({ strategy: this.admin.strategy }),
        });
        this.setMessage("admin", ZH.strategyOk, "success");
      });
    },
    async setDispatchStrategy() {
      await this.safeRun("admin", async () => {
        await this.request("/api/admin/dispatch-strategy", {
          method: "POST",
          body: JSON.stringify({ strategy: this.admin.dispatchStrategy }),
        });
        this.setMessage("admin", ZH.dispatchStrategyOk, "success");
        await Promise.all([this.refreshPiles(), this.refreshUserPanels()]);
      });
    },
    async changePileState() {
      await this.safeRun("admin", async () => {
        await this.request("/api/admin/pile-state", {
          method: "POST",
          body: JSON.stringify({
            pileId: this.admin.pileId.trim(),
            state: this.admin.pileState,
          }),
        });
        this.setMessage("admin", ZH.pileStateOk, "success");
        await this.refreshPiles();
        await this.refreshUserPanels();
      });
    },
    async updateConfig() {
      await this.safeRun("admin", async () => {
        const payload = {};
        if (this.admin.waitingAreaSize) payload.waitingAreaSize = Number(this.admin.waitingAreaSize);
        if (this.admin.chargingQueueLen) payload.chargingQueueLen = Number(this.admin.chargingQueueLen);
        if (this.admin.fastChargingPileNum) payload.fastChargingPileNum = Number(this.admin.fastChargingPileNum);
        if (this.admin.slowChargingPileNum) payload.slowChargingPileNum = Number(this.admin.slowChargingPileNum);
        if (this.admin.fastPower) payload.fastPower = Number(this.admin.fastPower);
        if (this.admin.slowPower) payload.slowPower = Number(this.admin.slowPower);
        await this.request("/api/admin/config", {
          method: "POST",
          body: JSON.stringify(payload),
        });
        this.setMessage("admin", ZH.configOk, "success");
      });
    },
    async refreshReport() {
      await this.safeRun("admin", async () => {
        const res = await this.request(`/api/admin/report?period=${this.admin.reportPeriod}`);
        this.report = res.data || [];
        this.setMessage("admin", ZH.reportRefreshed, "success");
      });
    },
    async refreshAll() {
      await this.safeRun("admin", async () => {
        await Promise.all([this.refreshSystemTime(), this.refreshPiles(), this.refreshReport()]);
        await this.refreshUserPanels();
      });
    },
    togglePileControl() {
      this.showPileControl = !this.showPileControl;
    },
    startAutoRefresh() {
      if (this.autoTimer) clearInterval(this.autoTimer);
      this.autoTimer = setInterval(() => {
        this.refreshSystemTime();
        if (this.currentView === "admin") {
          this.refreshPiles();
          if (this.activeTab === "report") this.refreshReport();
        }
        if (this.currentView === "user" && this.user.loggedIn) {
          this.refreshRequests();
          if (this.showQueueModal) this.refreshQueueInfo();
          if (this.activeTab === "bills") this.refreshBills();
        }
      }, 8000);
    },
  },
  mounted() {
    this.refreshAll();
    this.setMessage("user", "请先登录后再操作订单。", "warn");
    this.startAutoRefresh();
  },
  beforeUnmount() {
    if (this.autoTimer) clearInterval(this.autoTimer);
  },
}).mount("#app");
