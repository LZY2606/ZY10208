package app

import kotlinx.serialization.Serializable

/** 样本质量标志：缺测 / 饱和（已知方向）/ 饱和（方向未知）。 */
@Serializable
enum class Flag { OK, MISSING, SAT_LOW, SAT_HIGH, SAT_CLIP }

/** 原始样本：t 为采集设备自报时间（秒），v 为采集值（未做基线/极性修正）。 */
@Serializable
data class Sample(val t: Double, val v: Double, val f: Flag = Flag.OK)

/**
 * 通道描述。
 * - sampleRateHz 为该通道自己的采样率；
 * - polarity 为探头接线极性：+1 正向，-1 反接（fixture 的一次电流为 -1）；
 * - sensorDelayS 为传感器链路由检定得到的传播延时，校正方向约定见 README；
 * - vFullScale 为该 ADC/探头组合的满量程物理限幅，用于饱和缺口的保守界限。
 */
@Serializable
data class Channel(
    val name: String,
    val role: String,
    val unit: String,
    val sampleRateHz: Double,
    val polarity: Double = 1.0,
    val sensorDelayS: Double = 0.0,
    val vFullScale: Double? = null,
    val samples: List<Sample>
)

@Serializable
data class Run(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: String,
    val channels: List<Channel>
)

/** 零线窗：在该时间区间内信号应为零，取均值作为零点偏置（单位与原始采集值相同）。 */
@Serializable
data class ZeroWindow(val t0: Double, val t1: Double)

/** 单通道对齐参数。deskewS 为用户在传感器延时之外追加的手动时间偏移。 */
@Serializable
data class ChannelAlign(
    val name: String,
    val polarity: Double,
    val sensorDelayS: Double,
    val deskewS: Double = 0.0,
    val zeroWindow: ZeroWindow? = null,
    val zeroOffset: Double = 0.0
)

/** 积分端点：交叉阈值（cross）或手工锨点（manual）。 */
@Serializable
data class WindowSpec(
    val name: String,
    val mode: String,
    val vChannel: String,
    val iChannel: String,
    val vThreshold: Double? = null,
    val iThreshold: Double? = null,
    val crossingDir: String = "falling-rise",
    val t0: Double? = null,
    val t1: Double? = null,
    val searchT0: Double? = null,
    val searchT1: Double? = null
)

/** 一次分析的完整决定集：可追溯三层（对齐 / 基线 / 积分窗）。 */
@Serializable
data class AnalysisConfig(
    val runId: String,
    val gridStepS: Double,
    val align: List<ChannelAlign>,
    val windows: List<WindowSpec>
)

/** 公共时间轴上的网格点。lo/hi 为该点物理量的保守取值区间。 */
@Serializable
data class GridPoint(val t: Double, val v: Double, val lo: Double, val hi: Double, val f: Flag)

@Serializable
data class WindowResult(
    val name: String,
    val t0: Double?,
    val t1: Double?,
    val energyJ: Double?,
    val energyLowJ: Double?,
    val energyHighJ: Double?,
    val status: String,
    val notes: List<String>
)

@Serializable
data class SensitivityPoint(val deskewShiftS: Double, val energyJ: Double?, val energyLowJ: Double?, val energyHighJ: Double?, val status: String)

@Serializable
data class AnalysisResult(
    val id: String,
    val runId: String,
    val name: String,
    val createdAt: String,
    val config: AnalysisConfig,
    val windows: List<WindowResult>,
    val sensitivity: Map<String, List<SensitivityPoint>>,
    val grid: Map<String, List<GridPoint>>
)
