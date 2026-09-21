package app

import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * 确定性双脉冲 fixture（不使用随机数，任何机器重放结果一致）。
 *
 * 时间轴（器件时间，秒）：
 *  0.10–0.85 µs 合成探头校零段：vge/vce/ic 真值均为 0（零线窗取于此）；
 *  0.85–2.30 µs 断态：vce=600 V；
 *  2.00 µs      触发上升、门极开始开通；
 *  2.30–2.70 µs 开通过渡：vce 600→2.2 V，ic 0→200 A；
 *  2.70–7.30 µs 通态：ic 200→230 A；其中 5.00–5.20 µs 为合成再生段
 *               （vce=60 V、ic=-30 A，瞬时功率为负，用于验证负能量保留）；
 *  7.30–7.80 µs 关断过渡：vce 2.2→约 690→600 V，ic 230→0 A；
 *  7.42–7.68 µs vce ADC 饱和缺口（SAT_HIGH，满量程限幅 720 V），跨默认关断积分窗。
 *
 * 通道各自采样率：vge 20 MHz、vce 10 MHz、ic 5 MHz、trig 2 MHz。
 * ic 探头极性反接（polarity=-1），各通道带有传感器延时和零点偏置。
 */
object Fixture {

    const val RUN_ID = "dp-2pulse-demo"
    private const val T_END = 10e-6

    private const val V_BUS = 600.0
    private const val V_ON = 2.2
    private const val V_GAP_T0 = 7.42e-6
    private const val V_GAP_T1 = 7.68e-6
    private const val V_CLIP = 680.0

    private fun clamp(x: Double, a: Double, b: Double) = max(a, min(b, x))
    private fun smooth(u: Double) = u * u * (3.0 - 2.0 * u)
    private fun seg(t: Double, t0: Double, t1: Double, a: Double, b: Double): Double =
        when {
            t <= t0 -> a
            t >= t1 -> b
            else -> a + (b - a) * smooth((t - t0) / (t1 - t0))
        }

    /** vge 真值（V），含 -5 V 负压关断。 */
    private fun vge(t: Double): Double {
        if (t < 0.85e-6) return 0.0
        val on = seg(t, 2.00e-6, 2.25e-6, 0.0, 15.0)
        if (t < 6.95e-6) return on
        return seg(t, 6.95e-6, 7.25e-6, 15.0, -5.0)
    }

    /** vce 真值（V）。 */
    private fun vce(t: Double): Double {
        if (t < 0.85e-6) return 0.0
        if (t < 1.00e-6) return seg(t, 0.85e-6, 1.00e-6, 0.0, V_BUS)
        if (t < 2.30e-6) return V_BUS
        if (t < 2.70e-6) return seg(t, 2.30e-6, 2.70e-6, V_BUS, V_ON)
        if (t < 4.95e-6) return V_ON
        if (t < 5.25e-6) { // 合成再生段
            return when {
                t < 5.00e-6 -> seg(t, 4.95e-6, 5.00e-6, V_ON, 60.0)
                t < 5.20e-6 -> 60.0
                else -> seg(t, 5.20e-6, 5.25e-6, 60.0, V_ON)
            }
        }
        if (t < 7.30e-6) return V_ON
        if (t < 7.50e-6) return seg(t, 7.30e-6, 7.50e-6, V_ON, 690.0)
        if (t < 7.65e-6) return 690.0
        if (t < 7.85e-6) return seg(t, 7.65e-6, 7.85e-6, 690.0, V_BUS)
        return V_BUS
    }

    /** ic 真值（A），正方向为器件正向（集电极流入）。 */
    private fun ic(t: Double): Double {
        if (t < 2.30e-6) return 0.0
        if (t < 2.70e-6) return seg(t, 2.30e-6, 2.70e-6, 0.0, 200.0)
        if (t < 4.95e-6) return 200.0 + 30.0 * clamp((t - 2.70e-6) / 2.25e-6, 0.0, 1.0)
        if (t < 5.25e-6) { // 合成再生段：电流反向回灌
            return when {
                t < 5.00e-6 -> seg(t, 4.95e-6, 5.00e-6, 230.0, -30.0)
                t < 5.20e-6 -> -30.0
                else -> seg(t, 5.20e-6, 5.25e-6, -30.0, 218.0)
            }
        }
        if (t < 7.30e-6) return 218.0 + 12.0 * clamp((t - 5.25e-6) / 2.05e-6, 0.0, 1.0)
        if (t < 7.80e-6) return seg(t, 7.30e-6, 7.80e-6, 230.0, 0.0)
        return 0.0
    }

    /** 触发通道真值（V）：2.00 µs 上升、7.00 µs 下降。 */
    private fun trig(t: Double): Double =
        if (t in 2.00e-6..7.00e-6) 3.3 else 0.0

    private data class ChSpec(
        val name: String, val role: String, val unit: String,
        val fs: Double, val polarity: Double, val delay: Double,
        val offset: Double, val fullScale: Double?, val truth: (Double) -> Double,
        val gap: Pair<Double, Double>? = null, val clipRail: Double = Double.NaN
    )

    fun build(): Run {
        val specs = listOf(
            ChSpec("vge", "gate", "V", 20e6, 1.0, 60e-9, 0.12, null, ::vge),
            ChSpec("vce", "voltage", "V", 10e6, 1.0, 120e-9, -1.5, 720.0, ::vce,
                gap = V_GAP_T0 to V_GAP_T1, clipRail = V_CLIP),
            ChSpec("ic", "current", "A", 5e6, -1.0, 300e-9, 0.8, 400.0, ::ic),
            ChSpec("trig", "trigger", "V", 2e6, 1.0, 0.0, 0.02, null, ::trig)
        )
        val channels = specs.map { s ->
            val dt = 1.0 / s.fs
            val n = (T_END / dt).toInt() + 1
            val samples = ArrayList<Sample>(n)
            for (k in 0 until n) {
                val tRaw = k * dt
                val tDev = tRaw - s.delay // 采集到该样本的器件时刻
                val inGap = s.gap != null && tDev >= s.gap.first && tDev <= s.gap.second
                if (inGap) {
                    val acqRail = s.polarity * s.clipRail + s.offset
                    samples.add(Sample(tRaw, acqRail, Flag.SAT_HIGH))
                } else {
                    val acq = s.polarity * s.truth(tDev) + s.offset
                    samples.add(Sample(tRaw, acq, Flag.OK))
                }
            }
            Channel(s.name, s.role, s.unit, s.fs, s.polarity, s.delay, s.fullScale, samples)
        }
        return Run(
            id = RUN_ID,
            name = "双脉冲演示记录（合成 fixture）",
            description = "异采样率四通道；ic 探头反接；vce 在 7.42–7.68 µs 饱和缺口，跨关断积分窗；5.00–5.20 µs 为合成再生段。",
            createdAt = Instant.parse("2026-09-22T00:00:00Z").toString(),
            channels = channels
        )
    }

    /** 基于 run 中通道元数据生成默认分析决定（三层）。 */
    fun defaultConfig(run: Run): AnalysisConfig {
        fun ca(name: String, zw: ZeroWindow?) = run.channels.first { it.name == name }.let { c ->
            ChannelAlign(c.name, c.polarity, c.sensorDelayS, 0.0, zw, 0.0)
        }
        val cal = ZeroWindow(0.10e-6, 0.85e-6)
        val align = listOf(
            ca("vge", cal),
            ca("vce", cal),
            ca("ic", ZeroWindow(0.15e-6, 0.85e-6)),
            ca("trig", cal)
        )
        val windows = listOf(
            WindowSpec(
                "eon", "cross", "vce", "ic",
                vThreshold = 30.0, iThreshold = 20.0, crossingDir = "on",
                searchT0 = 2.1e-6, searchT1 = 3.0e-6
            ),
            WindowSpec(
                "eoff", "cross", "vce", "ic",
                vThreshold = 300.0, iThreshold = 23.0, crossingDir = "off",
                searchT0 = 7.1e-6, searchT1 = 8.1e-6
            ),
            WindowSpec(
                "regen", "manual", "vce", "ic",
                t0 = 5.08e-6, t1 = 5.12e-6
            )
        )
        return AnalysisConfig(run.id, gridStepS = 10e-9, align = align, windows = windows)
    }
}
