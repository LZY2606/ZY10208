"use strict";

const state = {
  runs: [], run: null, defaultConfig: null,
  A: { config: null, result: null, pick: null },
  B: { config: null, result: null, pick: null }
};

async function api(path, opts) {
  const res = await fetch(path, opts);
  if (!res.ok) throw new Error(path + " -> " + res.status);
  return res.json();
}

async function loadRuns(selectId) {
  const data = await api("/api/runs");
  state.runs = data.runs;
  const sel = document.getElementById("runSelect");
  sel.innerHTML = "";
  data.runs.forEach(r => {
    const o = document.createElement("option");
    o.value = r.id; o.textContent = r.id + " — " + r.name;
    sel.appendChild(o);
  });
  if (data.runs.length) await selectRun(data.runs[0].id);
  else { state.run = null; document.getElementById("runDesc").textContent = "数据库为空，请先导入 fixture"; }
}

async function selectRun(id) {
  state.run = await api("/api/runs/" + id);
  state.defaultConfig = await api("/api/runs/" + id + "/default-config");
  const r = state.runs.find(x => x.id === id);
  document.getElementById("runDesc").textContent = r ? r.description : "";
  if (!state.A.config || state.A.config.runId !== id) state.A.config = clone(state.defaultConfig);
  if (!state.B.config || state.B.config.runId !== id) state.B.config = clone(state.defaultConfig);
  renderConfig("A"); renderConfig("B");
  await runAnalyze("A"); await runAnalyze("B");
  loadHistory();
}

function clone(x) { return JSON.parse(JSON.stringify(x)); }

function renderConfig(side) {
  const cfg = state[side].config;
  document.getElementById(side + "_gridStep").value = cfg.gridStepS;
  const alignBox = document.getElementById(side + "_align");
  alignBox.innerHTML = "";
  cfg.align.forEach((ca, idx) => {
    const d = document.createElement("div"); d.className = "field";
    d.innerHTML =
      "<label>" + ca.name + " · polarity</label><input type='number' step='0.1' data-k='align' data-i='" + idx + "' data-f='polarity' value='" + ca.polarity + "'>" +
      "<label>sensorDelay (s)</label><input type='number' step='1e-9' data-k='align' data-i='" + idx + "' data-f='sensorDelayS' value='" + ca.sensorDelayS + "'>" +
      "<label>手动 deskew (s)</label><input type='number' step='1e-9' data-k='align' data-i='" + idx + "' data-f='deskewS' value='" + ca.deskewS + "'>" +
      "<label>零线窗 t0 / t1 (s)</label><div><input type='number' step='1e-9' data-k='zero0' data-i='" + idx + "' value='" + (ca.zeroWindow ? ca.zeroWindow.t0 : "") + "'>" +
      "<input type='number' step='1e-9' data-k='zero1' data-i='" + idx + "' value='" + (ca.zeroWindow ? ca.zeroWindow.t1 : "") + "'></div>";
    alignBox.appendChild(d);
  });
  const winBox = document.getElementById(side + "_windows");
  winBox.innerHTML = "";
  cfg.windows.forEach((w, idx) => {
    const d = document.createElement("div"); d.className = "winBox";
    d.innerHTML =
      "<h3>窗口 " + w.name + " · 模式 " + w.mode + "</h3><div class='winGrid'>" +
      "<div class='field'><label>v 通道</label><input data-k='win' data-i='" + idx + "' data-f='vChannel' value='" + w.vChannel + "'></div>" +
      "<div class='field'><label>i 通道</label><input data-k='win' data-i='" + idx + "' data-f='iChannel' value='" + w.iChannel + "'></div>" +
      "<div class='field'><label>v 阈值</label><input type='number' data-k='win' data-i='" + idx + "' data-f='vThreshold' value='" + nz(w.vThreshold) + "'></div>" +
      "<div class='field'><label>i 阈值</label><input type='number' data-k='win' data-i='" + idx + "' data-f='iThreshold' value='" + nz(w.iThreshold) + "'></div>" +
      "<div class='field'><label>方向(on/off)</label><input data-k='win' data-i='" + idx + "' data-f='crossingDir' value='" + w.crossingDir + "'></div>" +
      "<div class='field'><label>手工 t0</label><input type='number' step='1e-9' data-k='win' data-i='" + idx + "' data-f='t0' value='" + nz(w.t0) + "'></div>" +
      "<div class='field'><label>手工 t1</label><input type='number' step='1e-9' data-k='win' data-i='" + idx + "' data-f='t1' value='" + nz(w.t1) + "'></div>" +
      "<div class='field'><label>操作</label><span><button type='button' data-pick='t0' data-i='" + idx + "'>取 t0</button> <button type='button' data-pick='t1' data-i='" + idx + "'>取 t1</button></span></div>" +
      "</div>";
    winBox.appendChild(d);
  });
  wireInputs(side);
}

function nz(v) { return v === null || v === undefined ? "" : v; }
function numOrNull(el) { const s = el.value.trim(); return s === "" ? null : Number(s); }

function wireInputs(side) {
  document.querySelectorAll("#pane" + side + " input[data-k]").forEach(el => {
    el.onchange = () => {
      const cfg = state[side].config; const i = Number(el.dataset.i);
      if (el.dataset.k === "align") cfg.align[i][el.dataset.f] = Number(el.value);
      else if (el.dataset.k === "zero0") { const a = cfg.align[i]; a.zeroWindow = { t0: Number(el.value), t1: a.zeroWindow ? a.zeroWindow.t1 : 0 }; }
      else if (el.dataset.k === "zero1") { const a = cfg.align[i]; a.zeroWindow = { t0: a.zeroWindow ? a.zeroWindow.t0 : 0, t1: Number(el.value) }; }
      else if (el.dataset.k === "win") {
        const f = el.dataset.f;
        if (f === "vChannel" || f === "iChannel" || f === "crossingDir") cfg.windows[i][f] = el.value;
        else cfg.windows[i][f] = numOrNull(el);
      }
    };
  });
  document.querySelectorAll("#pane" + side + " button[data-pick]").forEach(b => {
    b.onclick = () => { state[side].pick = { field: b.dataset.pick, winIdx: Number(b.dataset.i) }; };
  });
  document.getElementById(side + "_gridStep").onchange = (e) => { state[side].config.gridStepS = Number(e.target.value); };
}

async function runAnalyze(side) {
  if (!state.run || !state[side].config) return;
  try {
    const res = await api("/api/analyze", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ config: state[side].config, save: true })
    });
    state[side].result = res;
    renderResults(side);
    draw(side);
    loadHistory();
  } catch (e) {
    document.getElementById(side + "_results").textContent = "分析失败：" + e.message;
  }
}

function fmtU(x) { return x === null || x === undefined ? "—" : (Math.abs(x) >= 1e-3 ? x.toExponential(3) : x.toExponential(3)); }

function renderResults(side) {
  const res = state[side].result; const box = document.getElementById(side + "_results");
  let html = "<table><tr><th>窗</th><th>t0(µs)</th><th>t1(µs)</th><th>E(J)</th><th>E低(J)</th><th>E高(J)</th><th>状态</th></tr>";
  res.windows.forEach(w => {
    html += "<tr><td>" + w.name + "</td><td>" + (w.t0 ? (w.t0 * 1e6).toFixed(3) : "—") + "</td><td>" +
      (w.t1 ? (w.t1 * 1e6).toFixed(3) : "—") + "</td><td class='" + (w.energyJ < 0 ? "neg" : "") + "'>" + fmtU(w.energyJ) +
      "</td><td>" + fmtU(w.energyLowJ) + "</td><td>" + fmtU(w.energyHighJ) + "</td><td><span class='tag " + w.status + "'>" + w.status + "</span></td></tr>";
    if (w.notes && w.notes.length) html += "<tr><td colspan='7'><div class='notes'>" + w.notes.join("\n") + "</div></td></tr>";
  });
  html += "</table>";
  html += "<div class='sens'><b>对齐敏感性（电流通道 deskew 扫描，步长=½ vce 采样间隔 50ns）：</b><br>";
  Object.entries(res.sensitivity).forEach(([name, pts]) => {
    html += name + ": " + pts.map(p => (p.deskewShiftS * 1e9).toFixed(0) + "ns→" +
      (p.energyJ === null ? p.status : p.energyJ.toExponential(3) + "J")).join("；") + "<br>";
  });
  html += "</div>";
  box.innerHTML = html;
  const csv = document.getElementById(side + "_csv");
  csv.style.display = "inline"; csv.href = "/api/analyses/" + res.id + "/export.csv";
}

const COLORS = { vce: "#c53030", ic: "#2b6cb0", vge: "#805ad5", trig: "#718096" };

function draw(side) {
  const res = state[side].result;
  const canvas = document.getElementById(side + "_canvas");
  const ctx = canvas.getContext("2d");
  const W = canvas.width, H = canvas.height, padL = 56, padR = 16, padT = 14, padB = 26;
  ctx.clearRect(0, 0, W, H);
  if (!res) return;
  const showRaw = document.getElementById(side + "_raw").checked;
  const showAl = document.getElementById(side + "_aligned").checked;
  const showP = document.getElementById(side + "_power").checked;

  const tMin = 0.5e-6, tMax = 8.5e-6;
  const xs = (t) => padL + (t - tMin) / (tMax - tMin) * (W - padL - padR);

  // 左轴：V，右轴：A，底部副轴：功率
  let vMin = -50, vMax = 800, iMin = -60, iMax = 260, pMin = 1e18, pMax = -1e18;
  res.windows.forEach(w => {
    if (w.t0 !== null && w.t1 !== null) { /* range markers */ }
  });
  const vGrid = res.grid["vce"] || [];
  const iGrid = res.grid["ic"] || [];
  const powerPts = [];
  for (let k = 0; k < Math.min(vGrid.length, iGrid.length); k++) {
    const gv = vGrid[k], gi = iGrid[k];
    if (Math.abs(gv.t - gi.t) > 1e-12) continue;
    if (gv.f === "OK" && gi.f === "OK" && isFinite(gv.v) && isFinite(gi.v)) {
      const p = gv.v * gi.v;
      powerPts.push({ t: gv.t, p });
      if (p < pMin) pMin = p; if (p > pMax) pMax = p;
    }
  }
  if (!isFinite(pMin)) { pMin = 0; pMax = 1; }
  pMin = Math.min(pMin, 0); pMax *= 1.1;

  const yV = (v) => padT + (1 - (v - vMin) / (vMax - vMin)) * (H - padT - padB);
  const yI = (i) => padT + (1 - (i - iMin) / (iMax - iMin)) * (H - padT - padB);
  const yP = (p) => padT + (1 - (p - pMin) / (pMax - pMin)) * (H - padT - padB);

  // 网格与刻度
  ctx.strokeStyle = "#edf2f7"; ctx.fillStyle = "#4a5568"; ctx.font = "10px sans-serif"; ctx.beginPath();
  for (let tv = 0; tv <= 800; tv += 200) { const y = yV(tv); ctx.moveTo(padL, y); ctx.lineTo(W - padR, y); ctx.fillText(tv + "V", 6, y + 3); }
  for (let t = 1; t <= 8; t++) { const x = xs(t * 1e-6); ctx.moveTo(x, padT); ctx.lineTo(x, H - padB); ctx.fillText(t + "µs", x - 8, H - 10); }
  for (let iv = 0; iv >= -50; iv -= 50) { ctx.fillText(iv + "A", W - padR - 26, yI(iv) + 3); }
  ctx.stroke();

  // 积分窗阴影
  res.windows.forEach(w => {
    if (w.t0 === null || w.t1 === null) return;
    ctx.fillStyle = w.status === "OK" ? "rgba(47,133,90,.08)" : w.status === "BOUNDED" ? "rgba(183,121,31,.10)" : "rgba(197,48,48,.10)";
    ctx.fillRect(xs(w.t0), padT, xs(w.t1) - xs(w.t0), H - padT - padB);
    ctx.fillStyle = "#333"; ctx.fillText(w.name, xs(w.t0) + 3, padT + 11);
  });

  const run = state.run;
  const shiftOf = (name) => {
    const ca = res.config.align.find(a => a.name === name);
    const ch = run.channels.find(c => c.name === name);
    return ch.sensorDelayS + ca.deskewS;
  };
  const offsetOf = (name) => res.config.align.find(a => a.name === name).zeroOffset;

  function drawSeries(points, xFn, yFn, color, width) {
    ctx.strokeStyle = color; ctx.lineWidth = width; ctx.beginPath();
    let pen = false;
    points.forEach(pt => {
      const x = xFn(pt), y = yFn(pt);
      if (!isFinite(y)) { pen = false; return; }
      if (!pen) { ctx.moveTo(x, y); pen = true; } else ctx.lineTo(x, y);
    });
    ctx.stroke();
  }

  // 原始波形（设备自报时间，仅做极性/偏置换算后用于叠加对比；仍按各自时间戳画）
  if (showRaw) {
    run.channels.forEach(ch => {
      if (ch.role === "trigger") return;
      const isV = ch.role === "voltage" || ch.role === "gate";
      const off = offsetOf(ch.name);
      drawSeries(ch.samples.filter(s => s.f === "OK"),
        s => xs(s.t),
        s => isV ? yV(ch.polarity * (s.v - off)) : yI(ch.polarity * (s.v - off)),
        COLORS[ch.name] || "#888", 1);
    });
  }

  // 对齐波形（器件时间，网格重采样）
  if (showAl) {
    drawSeries(vGrid.filter(g => g.f === "OK"), g => xs(g.t), g => yV(g.v), "#c53030", 2);
    drawSeries(iGrid.filter(g => g.f === "OK"), g => xs(g.t), g => yI(g.v), "#2b6cb0", 2);
    const gGrid = res.grid["vge"] || [];
    drawSeries(gGrid.filter(g => g.f === "OK"), g => xs(g.t), g => yV(g.v), "#805ad5", 1.5);
    // 饱和/缺测段用横杠标出
    markBad(ctx, vGrid, xs, yV);
  }

  // 瞬时功率
  if (showP) {
    ctx.strokeStyle = "#dd6b20"; ctx.lineWidth = 1.5; ctx.beginPath();
    let pen = false;
    powerPts.forEach(pt => {
      const x = xs(pt.t), y = yP(pt.p);
      if (!pen) { ctx.moveTo(x, y); pen = true; } else ctx.lineTo(x, y);
    });
    ctx.stroke();
    ctx.fillStyle = "#dd6b20"; ctx.fillText("功率 (W, 橙)", padL + 6, padT + 24);
    ctx.fillText("Vce 红 / Ic 蓝 / Vge 紫", padL + 6, padT + 38);
  }

  ctx.strokeStyle = "#999"; ctx.strokeRect(padL, padT, W - padL - padR, H - padT - padB);
}

function markBad(ctx, grid, xs, yScaled) {
  let i = 0;
  ctx.fillStyle = "rgba(197,48,48,.9)";
  while (i < grid.length) {
    if (grid[i].f !== "OK" && isFinite(grid[i].lo)) {
      let j = i;
      while (j < grid.length && grid[j].f !== "OK") j++;
      const t0 = grid[i].t, t1 = grid[Math.min(j, grid.length - 1)].t;
      const y = yScaled(Math.max(0, grid[i].lo));
      ctx.fillRect(xs(t0), y - 9, Math.max(2, xs(t1) - xs(t0)), 4);
      i = j;
    } else i++;
  }
  ctx.fillStyle = "#c53030"; ctx.font = "10px sans-serif";
  ctx.fillText("▲ 饱和/缺测段", xs(7.42e-6) - 46, yScaled(700) - 14);
}

// 画布点击 → 手工锨点
["A", "B"].forEach(side => {
  const canvas = document.getElementById(side + "_canvas");
  canvas.addEventListener("click", (e) => {
    const pick = state[side].pick;
    if (!pick) return;
    const rect = canvas.getBoundingClientRect();
    const x = (e.clientX - rect.left) / rect.width * canvas.width;
    const padL = 56, padR = 16, W = canvas.width;
    const tMin = 0.5e-6, tMax = 8.5e-6;
    const t = tMin + (x - padL) / (W - padL - padR) * (tMax - tMin);
    const w = state[side].config.windows[pick.winIdx];
    w.mode = "manual";
    w[pick.field] = t;
    state[side].pick = null;
    renderConfig(side);
  });
});

async function loadHistory() {
  const data = await api("/api/analyses?runId=" + encodeURIComponent(state.run ? state.run.id : ""));
  const box = document.getElementById("history");
  box.innerHTML = "";
  data.analyses.forEach(a => {
    const row = document.createElement("div");
    row.innerHTML = "<a href='#' class='loadA' data-id='" + a.id + "'>" + a.id.slice(0, 8) + "</a> · " +
      a.createdAt + " <button class='loadA2' data-id='" + a.id + "'>载入到 A</button> <button class='loadB2' data-id='" + a.id + "'>载入到 B</button>";
    box.appendChild(row);
  });
  box.querySelectorAll(".loadA2").forEach(b => b.onclick = () => loadAnalysis(b.dataset.id, "A"));
  box.querySelectorAll(".loadB2").forEach(b => b.onclick = () => loadAnalysis(b.dataset.id, "B"));
}

async function loadAnalysis(id, side) {
  const res = await api("/api/analyses/" + id);
  state[side].config = res.config; state[side].result = res;
  renderConfig(side); renderResults(side); draw(side);
}

// 顶部按钮与全局事件
document.getElementById("runSelect").onchange = (e) => selectRun(e.target.value);
document.getElementById("btnImport").onclick = async () => {
  await api("/api/runs/import-fixture", { method: "POST" });
  document.getElementById("dbState").textContent = "fixture 已导入";
  await loadRuns();
};
document.getElementById("btnClear").onclick = async () => {
  if (!confirm("确认清空 runs 与 analyses？可重新导入 fixture 复核。")) return;
  await api("/api/admin/clear", { method: "POST" });
  state.A.config = null; state.B.config = null;
  await loadRuns();
};
document.querySelectorAll(".btnAnalyze").forEach(b => b.onclick = () => runAnalyze(b.dataset.side));
document.querySelectorAll(".btnDefault").forEach(b => b.onclick = () => {
  state[b.dataset.side].config = clone(state.defaultConfig);
  renderConfig(b.dataset.side); runAnalyze(b.dataset.side);
});
["A_raw", "A_aligned", "A_power", "B_raw", "B_aligned", "B_power"].forEach(id =>
  document.getElementById(id).onchange = () => draw(id[0]));

loadRuns();
