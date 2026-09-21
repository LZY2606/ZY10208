package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalysisTest {

    private fun analyzed(extraDeskewIc: Double = 0.0): Pair<Run, AnalysisResult> {
        val run = Fixture.build()
        val cfg = Fixture.defaultConfig(run).copy(
            align = Fixture.defaultConfig(run).align.map {
                if (it.name == "ic") it.copy(deskewS = it.deskewS + extraDeskewIc) else it
            }
        )
        return run to Analysis.analyze(cfg, run)
    }

    private fun byName(r: AnalysisResult, n: String) = r.windows.first { it.name == n }

    @Test
    fun `channel metadata carries distinct sample rates`() {
        val run = Fixture.build()
        val fs = run.channels.associate { it.name to it.sampleRateHz }
        assertEquals(20e6, fs["vge"]!!, 0.1)
        assertEquals(10e6, fs["vce"]!!, 0.1)
        assertEquals(5e6, fs["ic"]!!, 0.1)
        assertEquals(2e6, fs["trig"]!!, 0.1)
    }

    @Test
    fun `layer 1 polarity reversal is corrected on device time axis`() {
        val run = Fixture.build()
        val ic = run.channels.first { it.name == "ic" }
        assertEquals(-1.0, ic.polarity, 1e-12)
        val cfg = Fixture.defaultConfig(run)
        val aligned = Analysis.align(ic, cfg.align.first { it.name == "ic" })
        // 通态 3.5 µs 附近真值约 210 A；反接+偏置修正后必须为正（正向吸收）
        val near = aligned.points.filter { it.t in 3.4e-6..3.6e-6 && it.f == Flag.OK }
        assertTrue(near.all { it.v > 180.0 }, "反接电流修正后应为正向大电流，实际=${near.take(3)}")
        // 对齐后的器件时间戳 = 采集时间 - (传感器延时 + deskew)
        val shift = ic.sensorDelayS
        assertEquals(ic.samples[10].t - shift, aligned.points[10].t, 1e-15)
    }

    @Test
    fun `layer 2 zero offset is estimated from the zero window`() {
        val run = Fixture.build()
        val cfg = Fixture.defaultConfig(run)
        val vce = run.channels.first { it.name == "vce" }
        val off = Analysis.estimateZeroOffset(vce, cfg.align.first { it.name == "vce" }.zeroWindow)
        assertEquals(-1.5, off, 1e-9)
        val ic = run.channels.first { it.name == "ic" }
        val offIc = Analysis.estimateZeroOffset(ic, cfg.align.first { it.name == "ic" }.zeroWindow)
        assertEquals(0.8, offIc, 1e-9)
    }

    @Test
    fun `turn-on energy positive and endpoints from time-axis crossings`() {
        val (_, res) = analyzed()
        val eon = byName(res, "eon")
        assertEquals("OK", eon.status)
        assertNotNull(eon.t0); assertNotNull(eon.t1)
        val t0 = eon.t0!!; val t1 = eon.t1!!
        assertTrue(t0 in 2.3e-6..2.5e-6) { "t0=$t0" }
        assertTrue(t1 in 2.5e-6..2.75e-6) { "t1=$t1" }
        assertTrue(eon.energyJ!! > 0.0) { "开通能量应为正，实际=${eon.energyJ}" }
        assertEquals(0.0071399, eon.energyJ!!, 2e-4)
        assertEquals(requireNotNull(eon.energyLowJ), requireNotNull(eon.energyHighJ), 1e-12)
    }

    @Test
    fun `negative regen energy is retained, never absolute-valued`() {
        val (_, res) = analyzed()
        val regen = byName(res, "regen")
        assertEquals("OK", regen.status)
        assertTrue(regen.energyJ!! < 0.0) { "再生能量必须按负能量保留，实际=${regen.energyJ}" }
    }

    @Test
    fun `saturation gap crossing turn-off window yields bounded result not interpolation`() {
        val (run, res) = analyzed()
        val eoff = byName(res, "eoff")
        assertEquals("BOUNDED", eoff.status)
        assertNotNull(eoff.energyLowJ); assertNotNull(eoff.energyHighJ)
        assertTrue(eoff.energyLowJ!! <= eoff.energyHighJ!!)
        // 缺口必须真正落在积分窗内
        assertTrue(eoff.t0!! < 7.42e-6 && eoff.t1!! > 7.68e-6)
        // vce 饱和样本在原始数据中保留 SAT_HIGH 标志
        val vce = run.channels.first { it.name == "vce" }
        assertTrue(vce.samples.any { it.f == Flag.SAT_HIGH })
        // 界限与中值不同（证明没有跨缺口补出单值）
        assertTrue(eoff.energyHighJ!! - eoff.energyLowJ!! > 0.0)
    }

    @Test
    fun `missing samples without full-scale reference invalidate the window`() {
        val run = Fixture.build().let { r ->
            r.copy(channels = r.channels.map { c ->
                if (c.name != "vce") c else c.copy(vFullScale = null, samples = c.samples.map { s ->
                    val tDev = s.t - c.sensorDelayS
                    if (tDev in 7.50e-6..7.60e-6) Sample(s.t, s.v, Flag.MISSING) else s
                })
            })
        }
        val cfg = Fixture.defaultConfig(run)
        val res = Analysis.analyze(cfg, run)
        val eoff = res.windows.first { it.name == "eoff" }
        assertEquals("INVALID", eoff.status)
        assertNull(eoff.energyJ)
        assertNull(eoff.energyLowJ)
    }

    @Test
    fun `half-sample alignment shift changes energy (sensitivity traceable)`() {
        val (_, base) = analyzed(0.0)
        val (_, shifted) = analyzed(50e-9) // vce 10MHz 的半个采样间隔
        val e0 = base.windows.first { it.name == "eon" }.energyJ!!
        val e1 = shifted.windows.first { it.name == "eon" }.energyJ!!
        assertTrue(kotlin.math.abs(e1 - e0) > 1e-9) { "移动半采样间隔能量应发生变化：$e0 vs $e1" }
        // 敏感性表自身记录了 -100..100ns 五档
        val sens = base.sensitivity["eon"]!!
        assertEquals(listOf(-1e-7, -5e-8, 0.0, 5e-8, 1e-7), sens.map { it.deskewShiftS })
    }

    @Test
    fun `config records sample rate polarity offsets zero window and endpoints`() {
        val (_, res) = analyzed()
        val cfg = res.config
        assertEquals(10e-9, cfg.gridStepS, 1e-15)
        val ic = cfg.align.first { it.name == "ic" }
        assertEquals(-1.0, ic.polarity, 1e-12)
        assertEquals(0.8, ic.zeroOffset, 1e-9)
        assertNotNull(ic.zeroWindow)
        val eon = cfg.windows.first { it.name == "eon" }
        assertEquals(30.0, eon.vThreshold!!, 1e-9)
        assertEquals(20.0, eon.iThreshold!!, 1e-9)
    }
}
