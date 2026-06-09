# 验收用例接口驱动程序

`acceptance_input.csv` 是从仓库根目录的 `test.xlsx` 提取出的 42 条实际输入。
可以直接修改或替换该 CSV 后复用程序，也可以直接使用 `test.xlsx`。

字段映射：

- `A,<车辆>,F/T,<电量>`：车辆提交快充/慢充请求
- `A,<车辆>,O,0`：车辆取消请求或结束充电
- `B,<充电桩>,O,0/1`：充电桩故障/恢复
- `C,<车辆>,O,<电量>`：修改车辆请求电量

CSV 最少只需要两个字段：

```csv
event_time,raw_event
06:00,"(A,V1,T,40)"
06:05,"(B,T1,O,0)"
```

当前 CSV 中的 `sequence,event_type,target,operation,value` 是方便查看的可选字段。
程序始终从 `raw_event` 解析事件，因此只修改 `event_time` 和 `raw_event` 即可。
`event_time` 支持 `HH:mm`、`HH:mm:ss`，也支持 Excel 导出的日内时间小数。

仓库中的 `minimal_input_example.csv` 是可以直接修改使用的最小输入示例。

XLSX 输入需要存在标题为 `时刻` 和 `事件` 的两列。程序会自动查找工作表，
忽略其他结果列和空白行，因此可以直接接受当前 `test.xlsx` 的格式。

程序只调用现有 REST API，不重新实现调度和计费逻辑。运行前请启动一个全新的后端进程：

```powershell
cd backend
mvn spring-boot:run
```

然后在项目根目录运行：

```powershell
python acceptance/run_acceptance.py
```

直接运行 `test.xlsx`：

```powershell
python acceptance/run_acceptance.py --input test.xlsx
```

若工作簿中有多个用例工作表，可以指定名称：

```powershell
python acceptance/run_acceptance.py --input 作业验收用例.xlsx --sheet 测试用例
```

系统参数可通过命令行修改，例如：

```powershell
python acceptance/run_acceptance.py --input my_test.csv --waiting-area-size 20 --queue-length 6
```

执行结果写入 `acceptance/results/acceptance_results.csv` 和
`acceptance/results/acceptance_results.jsonl`。后端必须从系统初始时间
`2026-06-01T06:00:00` 开始运行；需要重跑时请先重启后端。若现有业务逻辑
拒绝某条事件，程序会在结果中记录失败原因，并继续执行剩余事件。结果 CSV
分别输出充电桩、普通等候区和故障优先调度区快照。
