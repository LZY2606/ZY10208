# 开关损耗镜（Switching Loss Mirror）

双脉冲试验开关损耗的本地复核工具：门极、器件电压、电流、触发四通道**异采样率**采集，
将分析拆成三层可追溯决定，页面可叠加原始/对齐波形与瞬时功率，支持两种解释并排复核。

- 语言/框架：Kotlin + Ktor（CIO，内嵌服务器）
- 存储：SQLite（`org.xerial:sqlite-jdbc`），默认库文件 `data/loss-mirror.db`
- 前端：零依赖单页（Canvas 自绘），随服务由 classpath 提供
- 数据：内置**确定性**双脉冲 fixture（无随机数），清空数据库可重新导入并复现同一能量

## 快速开始

```bash
# 安装/打包
mvn -q -DskipTests package

# 演示：自动化测试 + 启动（首次空库会自动导入固定 fixture）
mvn -q test && mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5548'
```

打开 <http://127.0.0.1:5548>，页面标题为 **开关损耗镜**。
可用参数：`--port 5548`、`--db path/to/file.db`（`:memory:` 为内存库）。

## 数据口径（务必先读）

1. **时间轴，不按数组索引相乘。**
   每个通道保存自己的 `sampleRateHz` 与带时间戳的样本。第一层把采集时间换算为器件时间：

   `t_device = t_acquired − (sensorDelayS + deskewS)`

   其中 `sensorDelayS` 是传感器/探头链路由检定得到的传播延时（正值表示采集时刻晚于器件时刻），
   `deskewS` 是用户在检定值之外追加的手动对齐量。所有通道随后重采样到**同一公共网格**
   （默认步长 10 ns），电压与电流只在网格时刻相乘。

2. **极性决定能量符号，禁止绝对值。**
   物理量统一为 `physical = polarity × (acquired − zeroOffset)`。fixture 中一次电流探头
   反接（`polarity = −1`），由第一层修正；再生工况 `p = v·i < 0`，能量按**负数**保留，
   界面与 CSV 均不取绝对值。

3. **基线（第二层）。** 零线窗 `ZeroWindow[t0,t1]` 内取合格（`OK`）样本均值作为零点偏置；
   零线窗、估计出的 `zeroOffset` 与极性、延时、deskew 一并写入每次分析的决定集。

4. **积分窗（第三层）。**
   - `cross`：在搜索区间内找电压、电流的交叉阈值点（线性插值到亚采样时刻）。
     开通窗端点 = 电流上升交叉 → 电压下降交叉；关断窗 = 电压上升交叉 → 电流下降交叉。
   - `manual`：直接给 `t0/t1`，或在图上“取 t0/取 t1”后单击画布手工锨点。
   - 已知方向的饱和点可安全参与其同向交叉判定；缺测/未知方向削波不参与，不跨缺口补线。

5. **缺测与饱和：只给界限或无效。**
   样本标志 `OK / MISSING / SAT_LOW / SAT_HIGH / SAT_CLIP`：
   - `SAT_HIGH/LOW`：已知饱和方向，限幅读数为一界，通道 `vFullScale` 为另一界；
   - `SAT_CLIP`：双向削波，界为 `[−fullScale, +fullScale]`；
   - `MISSING`：无满量程参考时包络为未知（NaN）。

   积分把窗端点与两通道原始器件时间样本并集为锚点：干净段用 v(t)、i(t) 线性乘积的
   **解析积分**（p 为二次函数）；任一锚点落入饱和/缺测段时该段给保守常数功率界，
   绝不跨缺口插值补线。窗状态：
   - `OK`：全部干净，`energy = energyLow = energyHigh`；
   - `BOUNDED`：含已知方向饱和，`energy` 仅为包络中值的并列估计，以 `energyLow/High` 为准；
   - `INVALID`：含缺测且无满量程界，能量给 `null`，备注说明位置。

## 内置 fixture（固定可重放）

合成双脉冲，时间轴（器件时间）：

| 时刻 (µs) | 事件 |
| --- | --- |
| 0.10–0.85 | 合成校零段（vge/vce/ic 真值为 0，零线窗取于此） |
| 0.85–2.30 | 断态，vce = 600 V |
| 2.30–2.70 | 开通过渡，vce 600→2.2 V，ic 0→200 A |
| 2.70–7.30 | 通态 |
| 5.00–5.20 | 合成**再生段**（vce≈60 V，ic=−30 A，p<0） |
| 7.30–7.85 | 关断过渡（vce 过冲约 690 V） |
| **7.42–7.68** | **vce ADC 饱和缺口（SAT_HIGH，满量程 720 V），跨默认关断积分窗** |

通道：vge 20 MHz、vce 10 MHz、ic 5 MHz（**极性反接**）、trig 2 MHz；
另带传感器延时（60/120/300/0 ns）与零点偏置（0.12 V / −1.5 V / 0.8 A / 0.02 V）。

默认三个窗：`eon`（交叉阈值）、`eoff`（交叉阈值，跨饱和缺口 → `BOUNDED`）、
`regen`（手工窗 → 负能量）。

## 页面用法

- 顶部可**重新导入 fixture**、**清空数据库**；运行记录下拉切换。
- A / B 两栏各自保存一套三层决定，可对同一记录做两种解释并排复核。
- 勾选叠加：原始波形（设备自报时间，仅做极性/偏置展示）、对齐波形（公共时间轴）、瞬时功率。
- 红色横杠标出饱和/缺测段；背景色按窗状态着色（绿=OK，黄=BOUNDED，红=INVALID）。
- 每栏给出能量表、窗端点、状态备注与**对齐敏感性**：电流通道 deskew 取
  `−100, −50, 0, +50, +100 ns`（步长 = vce 10 MHz 的**半个采样间隔** 50 ns），
  可直接看到移动半采样间隔对各窗能量的影响。
- “导出 CSV”下载该次分析的窗端点、能量与界限；历史分析可载入任一栏复核。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/runs` `/api/runs/{id}` | 运行记录列表/明细（含全部带时间戳样本） |
| POST | `/api/runs/import-fixture` | 导入（或覆盖）固定 fixture |
| GET | `/api/runs/{id}/default-config` | 该记录的默认三层决定 |
| POST | `/api/analyze` | body `{config, save}`，返回结果并落库 |
| GET | `/api/analyses?runId=` `/api/analyses/{id}` | 历史分析 |
| GET | `/api/analyses/{id}/export.csv` | 运行记录导出 CSV |
| POST | `/api/admin/clear` | 清空 runs / analyses |

每次分析持久化：采样率、探头极性、传感器延时、手动 deskew、零线窗与估计偏置、
公共网格步长、积分窗模式/阈值/搜索区间/手工端点，以及窗端点、能量、界限、状态与备注。

## 清空后重新导入复核

```bash
curl -X POST http://127.0.0.1:5548/api/admin/clear
curl -X POST http://127.0.0.1:5548/api/runs/import-fixture
# 再用 /api/runs/{id}/default-config 调 /api/analyze，能量与清空前一致（确定性 fixture）
```

## 测试

```bash
mvn -q test
```

覆盖：异采样率对齐到时间轴、反接极性修正、零线偏置估计、交叉阈值端点、
开通正能/再生负能保留、饱和缺口给 `BOUNDED` 界限、无满量程缺测判 `INVALID`、
半采样间隔 deskew 的能量敏感性、决定集字段留存、清空重导可复现，以及 HTTP 全链路。

## 目录

- `src/main/kotlin/app/Models.kt`：带时间戳样本、标志、三层决定与结果的数据模型
- `src/main/kotlin/app/Fixture.kt`：确定性双脉冲 fixture 与默认决定
- `src/main/kotlin/app/Analysis.kt`：对齐/基线/积分窗三层管线、界限积分、敏感性
- `src/main/kotlin/app/Database.kt`：SQLite 持久化
- `src/main/kotlin/app/Server.kt`、`Main.kt`：Ktor 路由与入口
- `src/main/resources/web/`：单页 UI
- `src/test/kotlin/app/`：自动化测试
