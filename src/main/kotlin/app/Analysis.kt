package app

import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * “开关损耗镜”分析管线，三层决定严格分离、各自可追溯：
 *  1. 对齐层 Align：传感器延时 + 手动 deskew 把不同采样率通道映射到明确器件时间轴，
 *     并应用探头极性（不按数组索引相乘）；
 *  2. 基线层 Baseline：零线窗估计零点偏置，窗与估计值一并保留；
 *  3. 积分窗层 Window：交叉阈值或手工锨点定义端点，在明确时间轴上求 p=v·i 后积分。
 */
object Analysis {

    data class AlignedChannel(
        val meta: Channel,
        val spec: ChannelAlign,
        /** 与原始样本一一对应；t 为器件时间，v 为极性+基线修正后的物理量。 */
        val points: List<TPoint>,
        /** 每个原始样本物理量的保守取值区间（OK 时 lo==hi）。 */
        val envelopes: List<Envelope>
    )

    data class TPoint(val t: Double, val v: Double, val f: Flag)
    data class Envelope(val t: Double, val lo: Double, val hi: Double, val f: Flag)

    // ---------- 第一层：对齐；第二层：基线（估计在零线窗内完成） ----------

    fun estimateZeroOffset(channel: Channel, zw: ZeroWindow?): Double {
        if (zw == null) return 0.0
        val inWin = channel.samples.filter { it.f == Flag.OK && it.t >= zw.t0 && it.t <= zw.t1 }
        return if (inWin.isEmpty()) 0.0 else inWin.sumOf { it.v } / inWin.size
    }

    fun align(channel: Channel, spec: ChannelAlign): AlignedChannel {
        val offset = if (spec.zeroWindow != null) estimateZeroOffset(channel, spec.zeroWindow) else spec.zeroOffset
        val resolved = spec.copy(zeroOffset = offset)
        val shift = channel.sensorDelayS + resolved.deskewS
        val pts = ArrayList<TPoint>(channel.samples.size)
        val env = ArrayList<Envelope>(channel.samples.size)
        for (s in channel.samples) {
            val tDev = s.t - shift
            when (s.f) {
                Flag.OK -> {
                    val v = channel.polarity * (s.v - offset)
                    pts.add(TPoint(tDev, v, Flag.OK))
                    env.add(Envelope(tDev, v, v, Flag.OK))
                }
                Flag.SAT_HIGH -> {
                    val rail = channel.polarity * (s.v - offset)
                    val bound = channel.vFullScale?.let { abs(it) }
                    pts.add(TPoint(tDev, rail, Flag.SAT_HIGH))
                    env.add(Envelope(tDev, min(rail, bound ?: rail), max(rail, bound ?: rail), Flag.SAT_HIGH))
                }
                Flag.SAT_LOW -> {
                    val rail = channel.polarity * (s.v - offset)
                    val bound = channel.vFullScale?.let { -abs(it) }
                    pts.add(TPoint(tDev, rail, Flag.SAT_LOW))
                    env.add(Envelope(tDev, min(rail, bound ?: rail), max(rail, bound ?: rail), Flag.SAT_LOW))
                }
                Flag.SAT_CLIP -> {
                    val rail = channel.polarity * (s.v - offset)
                    val b = channel.vFullScale?.let { abs(it) }
                    pts.add(TPoint(tDev, rail, Flag.SAT_CLIP))
                    env.add(Envelope(tDev, if (b == null) rail else -b, if (b == null) rail else b, Flag.SAT_CLIP))
                }
                Flag.MISSING -> {
                    pts.add(TPoint(tDev, 0.0, Flag.MISSING))
                    val b = channel.vFullScale?.let { abs(it) }
                    env.add(Envelope(tDev, if (b == null) Double.NaN else -b, if (b == null) Double.NaN else b, Flag.MISSING))
                }
            }
        }
        pts.sortBy { it.t }
        env.sortBy { it.t }
        return AlignedChannel(channel, resolved, pts, env)
    }

    // ---------- 公共时间轴重采样（线性插值；坏区间不补线，返回包络界） ----------

    private fun interpEnv(aligned: AlignedChannel, t: Double): Pair<Double, Double> {
        val env = aligned.envelopes
        if (env.isEmpty() || t < env.first().t || t > env.last().t) return Double.NaN to Double.NaN
        var lo = 0; var hi = env.size - 1
        if (env[hi].t == t) return env[hi].lo to env[hi].hi
        if (env[lo].t == t) return env[lo].lo to env[lo].hi
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (env[mid].t <= t) lo = mid else hi = mid
        }
        val a = env[lo]; val b = env[hi]
        if (t == a.t) return a.lo to a.hi
        if (t == b.t) return b.lo to b.hi
        // 饱和（已知方向）锚点自带满量程界；缺测锚点才是 NaN。坏点之间绝不线性补线，
        // 而是退化为两端包络的保守并集。
        if (a.f != Flag.OK || b.f != Flag.OK) {
            val loV = if (a.lo.isNaN() || b.lo.isNaN()) Double.NaN else min(a.lo, b.lo)
            val hiV = if (a.hi.isNaN() || b.hi.isNaN()) Double.NaN else max(a.hi, b.hi)
            return loV to hiV
        }
        val f = (t - a.t) / (b.t - a.t)
        val lv = a.lo + (b.lo - a.lo) * f
        val hv = a.hi + (b.hi - a.hi) * f
        return min(lv, hv) to max(lv, hv)
    }

    private fun interpPoint(aligned: AlignedChannel, t: Double): TPoint? {
        val pts = aligned.points
        if (pts.isEmpty() || t < pts.first().t || t > pts.last().t) return null
        var lo = 0; var hi = pts.size - 1
        if (pts[hi].t == t) return pts[hi]
        if (pts[lo].t == t) return pts[lo]
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (pts[mid].t <= t) lo = mid else hi = mid
        }
        val a = pts[lo]; val b = pts[hi]
        val f = (t - a.t) / (b.t - a.t)
        val flag = when {
            a.f == Flag.OK && b.f == Flag.OK -> Flag.OK
            a.f == b.f -> a.f
            else -> Flag.MISSING
        }
        return TPoint(t, if (flag == Flag.OK) a.v + (b.v - a.v) * f else 0.0, flag)
    }

    fun resample(aligned: AlignedChannel, t0: Double, t1: Double, step: Double): List<GridPoint> {
        val n = ((t1 - t0) / step).toInt() + 1
        val out = ArrayList<GridPoint>(n + 1)
        for (k in 0..n) {
            val t = t0 + k * step
            val mid = interpPoint(aligned, t)
            val (lo, hi) = interpEnv(aligned, t)
            if (mid == null) {
                out.add(GridPoint(t, Double.NaN, Double.NaN, Double.NaN, Flag.MISSING))
            } else {
                val v = when {
                    mid.f == Flag.OK -> mid.v
                    lo.isNaN() || hi.isNaN() -> Double.NaN
                    else -> (lo + hi) / 2
                }
                out.add(GridPoint(t, v, lo, hi, mid.f))
            }
        }
        return out
    }

    // ---------- 第三层：积分窗（交叉阈值 / 手工锨点） ----------

    /**
     * 交叉判定。OK 样本线性插值；已知方向的饱和点（SAT_HIGH/SAT_LOW）可安全参与：
     * 上升沿遇到 SAT_HIGH（值已达限幅且真值更高）且阈值不低于限幅读数时，交叉点落在该样本上；
     * 下降沿遇到 SAT_LOW 同理。未知方向（SAT_CLIP）或缺测（MISSING）不参与，不跨缺口补线。
     */
    private fun crossing(pts: List<TPoint>, threshold: Double, rising: Boolean, s0: Double, s1: Double): Double? {
        var prev: TPoint? = null
        for (p in pts) {
            if (p.t < s0) { prev = p; continue }
            if (p.t > s1) break
            val a = prev
            if (a != null) {
                val usableA = a.f == Flag.OK || (!rising && a.f == Flag.SAT_LOW) || (rising && a.f == Flag.SAT_HIGH)
                val usableB = p.f == Flag.OK || (!rising && p.f == Flag.SAT_LOW) || (rising && p.f == Flag.SAT_HIGH)
                if (usableA && usableB && p.t != a.t) {
                    val crossed = if (rising) a.v < threshold && p.v >= threshold
                    else a.v >= threshold && p.v < threshold
                    if (crossed) {
                        if (p.v == a.v) return p.t
                        val f = (threshold - a.v) / (p.v - a.v)
                        return a.t + f.coerceIn(0.0, 1.0) * (p.t - a.t)
                    }
                }
            }
            prev = p
        }
        return null
    }

    fun windowBounds(spec: WindowSpec, vPts: List<TPoint>, iPts: List<TPoint>): Pair<Double?, Double?> {
        if (spec.mode == "manual") return spec.t0 to spec.t1
        val s0 = spec.searchT0 ?: Double.NEGATIVE_INFINITY
        val s1 = spec.searchT1 ?: Double.POSITIVE_INFINITY
        val (vRise, iRise) = when (spec.crossingDir) {
            "on" -> false to true
            "off" -> true to false
            else -> false to true
        }
        val tv = spec.vThreshold?.let { crossing(vPts, it, vRise, s0, s1) }
        val ti = spec.iThreshold?.let { crossing(iPts, it, iRise, s0, s1) }
        // 开通窗：电流上升交叉 → 电压下降交叉；关断窗：电压上升交叉 → 电流下降交叉
        return if (spec.crossingDir == "off") (tv to ti) else (ti to tv)
    }

    data class Integral(val energy: Double?, val low: Double?, val high: Double?, val status: String, val notes: List<String>)

    private fun powerCorner(vlo: Double, vhi: Double, ilo: Double, ihi: Double): Pair<Double, Double> {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (vv in doubleArrayOf(vlo, vhi)) for (ii in doubleArrayOf(ilo, ihi)) {
            val p = vv * ii
            if (p < lo) lo = p
            if (p > hi) hi = p
        }
        return lo to hi
    }

    /**
     * 在 [t0,t1] 内对 p=v·i 积分。
     * 锚点 = 窗端点 ∪ 两通道各自的原始器件时间样本；每个锚点取两通道包络，
     * 功率界 = 四组 vlo/vhi × ilo/ihi 乘积的最小/最大值；相邻锚点做梯形求和。
     * 任何锚点含 NaN（缺测且无满量程界）→ 该窗 INVALID；
     * 含饱和但有界 → BOUNDED，给出上下界，不给虚假单点能量；不跨缺口补线。
     */
    fun integrate(v: AlignedChannel, i: AlignedChannel, t0: Double, t1: Double): Integral {
        val notes = mutableListOf<String>()
        val times = sortedSetOf(t0, t1)
        for (e in v.envelopes) if (e.t in t0..t1) times.add(e.t)
        for (e in i.envelopes) if (e.t in t0..t1) times.add(e.t)
        val anchors = times.toList()

        var sumLo = 0.0
        var sumHi = 0.0
        var sumMid = 0.0
        var allOK = true
        var invalid = false

        data class A(val vlo: Double, val vhi: Double, val ilo: Double, val ihi: Double, val mid: Double, val wide: Boolean)
        val rows = ArrayList<A>(anchors.size)
        for (t in anchors) {
            val (vlo, vhi) = interpEnv(v, t)
            val (ilo, ihi) = interpEnv(i, t)
            if (vlo.isNaN() || vhi.isNaN() || ilo.isNaN() || ihi.isNaN()) {
                invalid = true
                notes.add("t=%.3g s 处含缺测样本（无满量程界），该窗结论无效，不跨缺口补线".format(t))
                rows.add(A(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, true))
                continue
            }
            val wide = vlo != vhi || ilo != ihi
            if (wide) allOK = false
            rows.add(A(vlo, vhi, ilo, ihi, ((vlo + vhi) / 2) * ((ilo + ihi) / 2), wide))
        }
        if (!invalid) {
            for (k in 0 until anchors.size - 1) {
                val dt = anchors[k + 1] - anchors[k]
                if (dt <= 0) continue
                val a = rows[k]; val b = rows[k + 1]
                val exactSeg = a.vlo == a.vhi && b.vlo == b.vhi && a.ilo == a.ihi && b.ilo == b.ihi
                if (exactSeg) {
                    // 干净段：v(t)、i(t) 均为线性，p=v·i 的积分有解析解
                    val va = a.vlo; val vb = b.vlo; val ia = a.ilo; val ib = b.ilo
                    val T = dt
                    val exact = va * ia * T + (va * (ib - ia) + ia * (vb - va)) * T * T / 2.0 +
                        (vb - va) * (ib - ia) * T * T * T / 3.0
                    sumLo += exact; sumHi += exact; sumMid += exact
                } else {
                    // 坏段：段内 v、i 均不超出两端包络的并集，给常数保守界，不跨缺口补线
                    val segVlo = min(a.vlo, b.vlo); val segVhi = max(a.vhi, b.vhi)
                    val segIlo = min(a.ilo, b.ilo); val segIhi = max(a.ihi, b.ihi)
                    val (segPLo, segPHi) = powerCorner(segVlo, segVhi, segIlo, segIhi)
                    sumLo += dt * segPLo
                    sumHi += dt * segPHi
                    sumMid += dt * (a.mid + b.mid) / 2.0
                }
            }
        }
        val status = when {
            invalid -> "INVALID"
            allOK -> "OK"
            else -> "BOUNDED"
        }
        if (status == "BOUNDED") notes.add("积分窗含饱和样本：energy 为包络中值估计，energyLow/energyHigh 为保守界限")
        return Integral(
            if (invalid) null else sumMid,
            if (invalid) null else sumLo,
            if (invalid) null else sumHi,
            status, notes
        )
    }

    // ---------- 顶层分析：三层决定 → 结果（含公共网格与敏感性） ----------

    private const val GRID_T0 = 0.0
    private const val GRID_T1 = 10e-6
    private const val SHIFT_STEP_FRACTION = 0.5

    fun analyze(config: AnalysisConfig, run: Run, id: String = UUID.randomUUID().toString()): AnalysisResult {
        // 第一层 + 第二层
        val aligned = config.align.associate { ca ->
            val ch = run.channels.first { it.name == ca.name }
            ca.name to align(ch, ca)
        }
        // 明确的公共时间轴：所有通道映射到同一组网格时刻，不按数组索引相乘
        val g0 = max(GRID_T0, aligned.values.maxOf { it.envelopes.first().t })
        val g1 = min(GRID_T1, aligned.values.minOf { it.envelopes.last().t })
        val grid = HashMap<String, List<GridPoint>>()
        for ((name, al) in aligned) {
            grid[name] = resample(al, g0, g1, config.gridStepS)
        }

        // 第三层
        val results = config.windows.map { w ->
            val vAl = aligned[w.vChannel]!!
            val iAl = aligned[w.iChannel]!!
            val (t0, t1) = windowBounds(w, vAl.points, iAl.points)
            val notes = mutableListOf<String>()
            if (t0 == null || t1 == null) {
                notes.add("交叉阈值未找到，窗口无效")
                WindowResult(w.name, t0, t1, null, null, null, "INVALID", notes)
            } else if (t1 <= t0) {
                notes.add("积分端点顺序非法（t1<=t0）")
                WindowResult(w.name, t0, t1, null, null, null, "INVALID", notes)
            } else {
                notes.add("积分端点：t0=%.4g s, t1=%.4g s（模式 %s）".format(t0, t1, w.mode))
                val r = integrate(vAl, iAl, t0, t1)
                WindowResult(w.name, t0, t1, r.energy, r.low, r.high, r.status, notes + r.notes)
            }
        }

        // 敏感性：对齐量相对当前值移动 0、±1/2、±1 个 vce 采样间隔（作用于电流通道）
        val vceFs = run.channels.first { it.name == "vce" }.sampleRateHz
        val half = SHIFT_STEP_FRACTION / vceFs
        val shifts = listOf(-2 * half, -half, 0.0, half, 2 * half)
        val iNames = config.windows.map { it.iChannel }.distinct()
        val sensitivity = LinkedHashMap<String, List<SensitivityPoint>>()
        for (w in config.windows) {
            sensitivity[w.name] = shifts.map { sh ->
                val cfg2 = config.copy(align = config.align.map { ca ->
                    if (ca.name in iNames) ca.copy(deskewS = ca.deskewS + sh) else ca
                })
                val a2 = cfg2.align.associate { ca ->
                    ca.name to align(run.channels.first { it.name == ca.name }, ca)
                }
                val vAl = a2[w.vChannel]!!
                val iAl = a2[w.iChannel]!!
                val (t0, t1) = windowBounds(w, vAl.points, iAl.points)
                if (t0 == null || t1 == null || t1 <= t0)
                    SensitivityPoint(sh, null, null, null, "INVALID")
                else {
                    val r = integrate(vAl, iAl, t0, t1)
                    SensitivityPoint(sh, r.energy, r.low, r.high, r.status)
                }
            }
        }

        val resolvedConfig = config.copy(align = config.align.map { ca -> aligned[ca.name]!!.spec })
        return AnalysisResult(
            id = id,
            runId = run.id,
            name = "分析 ${id.take(8)}",
            createdAt = Instant.now().toString(),
            config = resolvedConfig,
            windows = results,
            sensitivity = sensitivity,
            grid = grid
        )
    }
}
