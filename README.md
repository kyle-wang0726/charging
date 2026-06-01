# 智能充电桩调度计费系统（前后端分离）

基于需求文档 `智能充电桩调度计费系统详细需求-20260423.docx` 实现：

- 后端：Java 17 + Spring Boot 3（REST API）
- 前端：独立静态页面（`frontend`），通过 `fetch` 调用后端

## 目录结构

```text
backend/   # Java 后端
frontend/  # 前端静态页面
```

## 已实现核心功能

1. 用户端
2. 注册、登录
3. 提交充电请求（快/慢充、请求电量）
4. 修改请求（等候区可改模式/电量；模式变更重排队号）
5. 取消请求（等候区、充电区均支持）
6. 结束充电
7. 查询排队信息（排队号、前车数量、状态）
8. 查询充电详单（编号、时间、桩编号、电量、时长、费用）

9. 管理端
10. 查看所有充电桩状态与队列车辆信息
11. 启动/关闭/故障切换充电桩
12. 故障调度策略切换（优先级调度、时间顺序调度）
13. 查看报表（日/周/月）
14. 更新系统参数（等候区容量N、队列长度M）
15. 手动推进系统时间（用于演示调度与计费变化）

16. 调度和计费
17. 快慢充分队排号（F/T）
18. 调度目标：同模式下选择完成时长最短的充电桩队列
19. 分时电价计费（峰/平/谷）+ 服务费
20. 故障与恢复后的重调度逻辑

## 启动后端

```bash
cd backend
mvn spring-boot:run
```

默认端口：`8080`

## 启动前端

前端为静态页面，可直接打开：

- `frontend/index.html`

页面顶部可配置后端地址（默认 `http://localhost:8080`）。

## 主要接口

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/user/request`
- `PUT /api/user/request` (supports `requestId`)
- `DELETE /api/user/request?userId=...&requestId=...`
- `POST /api/user/end` (supports `requestId`)
- `GET /api/user/queue-info?userId=...&requestId=...`
- `GET /api/user/requests?userId=...&includeFinished=false`
- `GET /api/user/bills?userId=...`
- `GET /api/admin/piles`
- `POST /api/admin/pile-state`
- `POST /api/admin/fault-strategy`
- `GET /api/admin/fault-strategy`
- `POST /api/admin/config`
- `GET /api/admin/config`
- `GET /api/admin/report?period=day|week|month`
- `GET /api/admin/time`
- `POST /api/admin/time/advance`

## 说明

当前实现为内存版（无数据库），用于课程设计/验收演示和逻辑验证。若要接入生产，可继续扩展：

- 持久化（MySQL + JPA/MyBatis）
- 鉴权（JWT + Spring Security）
- 并发一致性（分布式锁/消息队列）
- 更精细的调度仿真与审计日志
