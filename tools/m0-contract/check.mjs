// Prüft die Referenzfälle aus docs/architecture/0002-m0-contract.md (Abschnitt 11).
// Keine Abhängigkeiten, nur Node.js. Aufruf: node tools/m0-contract/check.mjs
// Die Werte in den Erwartungen stammen aus dem ADR; dieses Skript ist die ausführbare Fassung der Regeln.

import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

export const STEP = 1000; // ms
export const U = 1000; // Untereinheiten je Einheit
export const CAP_START = 1200 * U;
export const CAP_UPGRADE = 1200 * U;
export const EXPAND_COST = { H: 120 * U, B: 40 * U };
const STORAGE_COST = { H: { H: 40 * U }, B: { H: 60 * U } };
const PLOT_RULE = { P1: 'any', P2: 'SAW', P3: 'any', P4: 'any', E1: 'any' };
const KIND = {
  FOREST: { outRes: 'H', inRes: null, ratio: 1, outRates: [2000, 3000], build: { H: 40 * U }, upgrade: { H: 60 * U }, max: 3 },
  SAW: { outRes: 'B', inRes: 'H', ratio: 4, outRates: [4000, 8000], build: { H: 60 * U }, upgrade: { H: 80 * U, B: 20 * U }, max: 2 },
};
const PURCHASE = {
  SAW: { cmd: { type: 'build', kind: 'SAW', plot: 'P2' }, cost: KIND.SAW.build },
  F2: { cmd: { type: 'build', kind: 'FOREST', plot: 'P3' }, cost: KIND.FOREST.build },
  E1: { cmd: { type: 'expand' }, cost: EXPAND_COST },
};

export function fresh({ epoch = 0, stock = {}, cap = {}, buildings = [], unlocked = ['P1', 'P2', 'P3', 'P4'] } = {}) {
  return {
    epoch,
    t: epoch, // Beginn des nächsten nicht berechneten Schritts
    stock: { H: 0, B: 0, ...stock },
    cap: { H: CAP_START, B: CAP_START, ...cap },
    buildings: buildings.map((b, i) => ({ id: i + 1, stage: 1, paused: false, pendingStage: null, pendingFrom: null, ...b })),
    nextId: buildings.length + 1,
    unlocked: new Set(unlocked),
    pending: [],
    seq: 1,
    log: [],
  };
}

// Befehl zur Systemzeit now: wirkt am ersten Schrittbeginn ≥ now (gerechnet ab der Epoche).
export function command(st, now, cmd) {
  const b = st.epoch + Math.ceil((now - st.epoch) / STEP) * STEP;
  st.pending.push({ b: Math.max(b, st.t), seq: st.seq++, cmd }); // vor dem Anker wirkt am Anker
}

export function advanceTo(st, now) {
  while (st.t + STEP <= now) step(st);
}

function pay(st, cost) {
  if (Object.keys(cost).some((r) => st.stock[r] < cost[r])) return false;
  for (const r of Object.keys(cost)) st.stock[r] -= cost[r];
  return true;
}

function apply(st, c, b) {
  const bd = st.buildings.find((x) => x.id === c.id);
  switch (c.type) {
    case 'build': {
      const k = KIND[c.kind];
      if (!st.unlocked.has(c.plot)) return 'REJECT Bauplatz gesperrt';
      if (PLOT_RULE[c.plot] !== 'any' && PLOT_RULE[c.plot] !== c.kind) return 'REJECT Bauplatz reserviert';
      if (st.buildings.some((x) => x.plot === c.plot)) return 'REJECT Bauplatz belegt';
      if (st.buildings.filter((x) => x.kind === c.kind).length >= k.max) return 'REJECT Maximalzahl';
      if (!pay(st, k.build)) return 'REJECT Kosten';
      st.buildings.push({ id: st.nextId++, kind: c.kind, plot: c.plot, stage: 1, paused: false, activeFrom: b + STEP, pendingStage: null, pendingFrom: null });
      return `BAU ${c.kind} auf ${c.plot}`;
    }
    case 'upgrade': {
      if (!bd || bd.stage >= 2 || bd.pendingStage) return 'REJECT Stufe';
      if (!pay(st, KIND[bd.kind].upgrade)) return 'REJECT Kosten';
      bd.pendingStage = 2;
      bd.pendingFrom = b + STEP;
      return `UPGRADE #${bd.id} ab ${b + STEP}`;
    }
    case 'pause': bd.paused = true; return `PAUSE #${bd.id}`;
    case 'resume': bd.paused = false; return `FORTSETZEN #${bd.id}`;
    case 'storage': {
      if (!pay(st, STORAGE_COST[c.res])) return 'REJECT Kosten';
      st.cap[c.res] += CAP_UPGRADE;
      return `LAGER ${c.res}`;
    }
    case 'expand': {
      if (st.unlocked.has('E1')) return 'REJECT bereits erschlossen';
      if (!pay(st, EXPAND_COST)) return 'REJECT Kosten';
      st.unlocked.add('E1');
      return 'ERSCHLIESSUNG E1';
    }
    default: throw new Error(`unbekannter Befehl ${c.type}`);
  }
}

// Schritt mit Beginn st.t: Befehle, Stufenwechsel, Verteilung nach ID, Bilanz.
export function step(st) {
  const b = st.t;
  const due = st.pending.filter((p) => p.b === b).sort((x, y) => x.seq - y.seq);
  st.pending = st.pending.filter((p) => p.b !== b);
  const msgs = due.map((p) => apply(st, p.cmd, b));
  for (const bd of st.buildings) {
    if (bd.pendingFrom !== null && bd.pendingFrom <= b) {
      bd.stage = bd.pendingStage;
      bd.pendingStage = null;
      bd.pendingFrom = null;
    }
  }
  // Eingangspool und freier Ausgang: Werte zu Schrittbeginn, danach von jedem Betrieb verringert.
  const avail = { ...st.stock };
  const free = { H: st.cap.H - st.stock.H, B: st.cap.B - st.stock.B };
  const delta = { H: 0, B: 0 };
  const rows = [];
  for (const bd of [...st.buildings].sort((a, c) => a.id - c.id)) {
    const k = KIND[bd.kind];
    const outRate = k.outRates[bd.stage - 1];
    const row = { id: bd.id, kind: bd.kind, status: 'RUNNING', phase: null, produced: 0, consumed: 0 };
    if (bd.paused) {
      row.status = 'PAUSED';
    } else if (b < bd.activeFrom) {
      row.phase = 'ANLAUF';
    } else if (!k.inRes) {
      row.produced = Math.min(outRate, free[k.outRes]);
      free[k.outRes] -= row.produced;
      delta[k.outRes] += row.produced;
      if (row.produced === 0) row.status = 'OUTPUT_FULL';
    } else {
      // Der Betrieb nimmt zuerst Eingang, dann Ausgang; was er entnimmt, fehlt den folgenden Betrieben.
      row.consumed = Math.min(outRate / k.ratio, avail[k.inRes], Math.floor(free[k.outRes] / k.ratio));
      row.produced = row.consumed * k.ratio;
      avail[k.inRes] -= row.consumed;
      delta[k.inRes] -= row.consumed;
      free[k.outRes] -= row.produced;
      delta[k.outRes] += row.produced;
      if (row.produced === 0) row.status = free[k.outRes] < k.ratio ? 'OUTPUT_FULL' : 'MISSING_INPUT';
    }
    rows.push(row);
  }
  st.stock.H += delta.H;
  st.stock.B += delta.B;
  for (const r of ['H', 'B']) {
    if (st.stock[r] < 0 || st.stock[r] > st.cap[r]) throw new Error(`Vorrat ${r} außerhalb der Grenzen`);
  }
  st.log.push({ b, msgs, rows, stock: { ...st.stock } });
  st.t += STEP;
}

function expectSteps(st, expected) {
  for (const e of expected) {
    const entry = st.log.find((x) => x.b === e.b);
    assert.ok(entry, `Schritt ${e.b} fehlt`);
    if (e.statuses) assert.deepEqual(entry.rows.map((r) => r.status), e.statuses, `Status bei ${e.b}`);
    if (e.phases) assert.deepEqual(entry.rows.map((r) => r.phase), e.phases, `Phase bei ${e.b}`);
    if (e.H !== undefined) assert.equal(entry.stock.H, e.H, `Holz bei ${e.b}`);
    if (e.B !== undefined) assert.equal(entry.stock.B, e.B, `Bretter bei ${e.b}`);
  }
}

// Kürzeste Kaufwege: alle Reihenfolgen der Käufe, jeder Kauf zum frühestmöglichen Schritt.
export function earliestPlan(order) {
  const st = fresh({ buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  const plan = [];
  let i = 0;
  for (let b = 0; i < order.length; b += STEP) {
    if (b > 600 * 1000) return null;
    advanceTo(st, b);
    const spent = { H: 0, B: 0 };
    while (i < order.length) {
      const p = PURCHASE[order[i]];
      if (!Object.keys(p.cost).every((r) => st.stock[r] - spent[r] >= p.cost[r])) break;
      for (const r of Object.keys(p.cost)) spent[r] += p.cost[r];
      command(st, b, p.cmd);
      plan.push({ b, name: order[i] });
      i++;
    }
  }
  return plan;
}

function rf1() {
  const st = fresh({ buildings: [
    { kind: 'SAW', plot: 'P2', activeFrom: 0 },
    { kind: 'FOREST', plot: 'P1', activeFrom: 0, paused: true },
  ] });
  command(st, 3000, { type: 'resume', id: 2 });
  advanceTo(st, 5000);
  expectSteps(st, [
    { b: 0, statuses: ['MISSING_INPUT', 'PAUSED'], H: 0, B: 0 },
    { b: 1000, statuses: ['MISSING_INPUT', 'PAUSED'], H: 0, B: 0 },
    { b: 2000, statuses: ['MISSING_INPUT', 'PAUSED'], H: 0, B: 0 },
    { b: 3000, statuses: ['MISSING_INPUT', 'RUNNING'], H: 2000, B: 0 },
    { b: 4000, statuses: ['RUNNING', 'RUNNING'], H: 3000, B: 4000 },
  ]);
}

function rf2() {
  const st = fresh({ stock: { H: 5 * U, B: CAP_START }, buildings: [{ kind: 'SAW', plot: 'P2', activeFrom: 0 }] });
  advanceTo(st, 3000);
  expectSteps(st, [0, 1000, 2000].map((b) => ({ b, statuses: ['OUTPUT_FULL'], H: 5000, B: 1200000 })));
}

function rf3() {
  const st = fresh({ stock: { H: 10 * U }, buildings: [{ kind: 'SAW', plot: 'P2', activeFrom: 0 }] });
  command(st, 1500, { type: 'pause', id: 1 });
  command(st, 4200, { type: 'resume', id: 1 });
  advanceTo(st, 6000);
  expectSteps(st, [
    { b: 0, statuses: ['RUNNING'], H: 9000, B: 4000 },
    { b: 1000, statuses: ['RUNNING'], H: 8000, B: 8000 },
    { b: 2000, statuses: ['PAUSED'], H: 8000, B: 8000 },
    { b: 3000, statuses: ['PAUSED'], H: 8000, B: 8000 },
    { b: 4000, statuses: ['PAUSED'], H: 8000, B: 8000 },
    { b: 5000, statuses: ['RUNNING'], H: 7000, B: 12000 },
  ]);
}

function rf4() {
  const st = fresh({ stock: { H: 1500 }, buildings: [
    { kind: 'SAW', plot: 'P2', activeFrom: 0 },
    { kind: 'SAW', plot: 'P4', activeFrom: 0 },
  ] });
  advanceTo(st, 2000);
  expectSteps(st, [
    { b: 0, statuses: ['RUNNING', 'RUNNING'], H: 0, B: 6000 },
    { b: 1000, statuses: ['MISSING_INPUT', 'MISSING_INPUT'], H: 0, B: 6000 },
  ]);
}

function rf5() {
  const st = fresh({ stock: { H: 1199 * U }, buildings: [
    { kind: 'FOREST', plot: 'P1', activeFrom: 0 },
    { kind: 'FOREST', plot: 'P3', activeFrom: 0 },
  ] });
  advanceTo(st, 2000);
  expectSteps(st, [
    { b: 0, statuses: ['RUNNING', 'OUTPUT_FULL'], H: 1200000, B: 0 },
    { b: 1000, statuses: ['OUTPUT_FULL', 'OUTPUT_FULL'], H: 1200000, B: 0 },
  ]);
}

function rf6() {
  const END = 600 * 1000;
  const reference = fresh({ buildings: forestAndSaw() });
  advanceTo(reference, END);
  // Feste Folge von Zufallszahlen: 200 Zerlegungen desselben Zeitraums.
  let s = 1;
  const rnd = () => (s = (Math.imul(s, 1664525) + 1013904223) >>> 0) / 2 ** 32;
  for (let trial = 0; trial < 200; trial++) {
    const st = fresh({ buildings: forestAndSaw() });
    let now = 0;
    while (now < END) {
      now = Math.min(END, now + 1 + Math.floor(rnd() * 7919));
      advanceTo(st, now);
    }
    assert.equal(st.stock.H, reference.stock.H, `Zerlegung ${trial}: Holz`);
    assert.equal(st.stock.B, reference.stock.B, `Zerlegung ${trial}: Bretter`);
  }
  const short = fresh({ buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  advanceTo(short, 4000);
  const split = fresh({ buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  advanceTo(split, 2500);
  advanceTo(split, 4000);
  assert.equal(short.stock.H, 8000);
  assert.equal(split.stock.H, 8000);
  assert.equal(split.log.length, short.log.length);
}

function forestAndSaw() {
  return [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }, { kind: 'SAW', plot: 'P2', activeFrom: 0 }];
}

function rf7() {
  const st = fresh({ stock: { H: 500 * U }, buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  advanceTo(st, 2000);
  command(st, 2300, { type: 'pause', id: 1 });
  command(st, 2700, { type: 'upgrade', id: 1 });
  command(st, 2900, { type: 'build', kind: 'FOREST', plot: 'P3' });
  command(st, 5000, { type: 'resume', id: 1 });
  advanceTo(st, 7000);
  expectSteps(st, [
    { b: 2000, statuses: ['RUNNING'], H: 506000 },
    { b: 3000, statuses: ['PAUSED', 'RUNNING'], phases: [null, 'ANLAUF'], H: 406000 },
    { b: 4000, statuses: ['PAUSED', 'RUNNING'], phases: [null, null], H: 408000 },
    { b: 5000, statuses: ['RUNNING', 'RUNNING'], H: 413000 },
    { b: 6000, statuses: ['RUNNING', 'RUNNING'], H: 418000 },
  ]);
}

function rf8() {
  const st = fresh({ buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  advanceTo(st, 50000);
  command(st, 50000, { type: 'build', kind: 'FOREST', plot: 'P3' });
  advanceTo(st, 53000);
  expectSteps(st, [
    { b: 49000, statuses: ['RUNNING'], H: 100000 },
    { b: 50000, statuses: ['RUNNING', 'RUNNING'], phases: [null, 'ANLAUF'], H: 62000 },
    { b: 51000, statuses: ['RUNNING', 'RUNNING'], H: 66000 },
    { b: 52000, statuses: ['RUNNING', 'RUNNING'], H: 70000 },
  ]);
}

// RF-9: Kürzester Weg zur ersten Erweiterung über alle Reihenfolgen der drei Käufe.
function rf9() {
  let best = null;
  for (const order of [['SAW', 'E1'], ['F2', 'SAW', 'E1'], ['SAW', 'F2', 'E1'], ['F2', 'E1', 'SAW'], ['SAW', 'E1', 'F2']]) {
    const plan = earliestPlan(order);
    if (!plan || plan.find((p) => p.name === 'E1') === undefined) continue;
    const e1 = plan.find((p) => p.name === 'E1').b;
    if (!best || e1 < best.e1) best = { order, plan, e1 };
  }
  assert.ok(best, 'kein Weg zur Erweiterung gefunden');
  // Der Weg muss sich ohne abgelehnten Befehl ausführen lassen.
  const st = fresh({ buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  for (const p of best.plan) command(st, p.b, PURCHASE[p.name].cmd);
  advanceTo(st, best.e1 + STEP);
  const rejected = st.log.flatMap((e) => e.msgs).filter((m) => m.startsWith('REJECT'));
  assert.deepEqual(rejected, [], 'abgelehnte Befehle auf dem besten Weg');
  assert.deepEqual(best.plan.map((p) => `${p.name}@${p.b}`), ['F2@20000', 'SAW@36000', 'E1@75000']);
  return best;
}

function rf10() {
  const st = fresh({ epoch: 250, buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  command(st, 1501, { type: 'pause', id: 1 });
  assert.equal(st.pending[0].b, 2250, 'Befehl bei 1.501 ms muss bei 2.250 ms wirken');
  advanceTo(st, 5000);
  expectSteps(st, [
    { b: 250, statuses: ['RUNNING'], H: 2000 },
    { b: 1250, statuses: ['RUNNING'], H: 4000 },
    { b: 2250, statuses: ['PAUSED'], H: 4000 },
    { b: 3250, statuses: ['PAUSED'], H: 4000 },
  ]);
}

export const CASES = { 'RF-1': rf1, 'RF-2': rf2, 'RF-3': rf3, 'RF-4': rf4, 'RF-5': rf5, 'RF-6': rf6, 'RF-7': rf7, 'RF-8': rf8, 'RF-9': rf9, 'RF-10': rf10 };

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  let failed = 0;
  for (const [name, run] of Object.entries(CASES)) {
    try {
      run();
      console.log(`PASS ${name}`);
    } catch (err) {
      failed++;
      console.log(`FAIL ${name}: ${err.message}`);
    }
  }
  process.exitCode = failed === 0 ? 0 : 1;
}
