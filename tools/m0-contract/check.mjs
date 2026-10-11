// Prüft die Referenzfälle aus docs/architecture/0002-m0-contract.md (Abschnitt 11).
// Keine Abhängigkeiten, nur Node.js. Aufruf: node tools/m0-contract/check.mjs
// Das Modell bildet Wirtschaft, Zeit und Befehle nach. Blöcke der Welt und Darstellung sind nicht enthalten.

import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

export const STEP = 1000; // ms
export const U = 1000; // Untereinheiten je Einheit
export const CAP_START = 1200 * U;
export const CAP_UPGRADE = 1200 * U;
export const CAP_MAX = 1000000000; // U, Abschnitt 2
export const MAX_ABSENCE = 8 * 3600 * 1000; // ms, Abschnitt 4.3
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
const STATE_KEYS = ['t', 'stock', 'cap', 'buildings', 'pending', 'seq', 'nextId', 'storageDone'];

export function fresh({ epoch = 0, stock = {}, cap = {}, buildings = [], unlocked = ['P1', 'P2', 'P3', 'P4'] } = {}) {
  return {
    epoch,
    t: epoch, // Beginn des nächsten nicht berechneten Schritts (Zeitanker)
    stock: { H: 0, B: 0, ...stock },
    cap: { H: CAP_START, B: CAP_START, ...cap },
    buildings: buildings.map((b, i) => ({ id: i + 1, stage: 1, paused: false, pendingStage: null, pendingFrom: null, ...b })),
    nextId: buildings.length + 1,
    unlocked: new Set(unlocked),
    storageDone: { H: false, B: false },
    pending: [],
    seq: 1,
    log: [],
  };
}

// Befehl zur Systemzeit now: wirkt am ersten Schrittbeginn ≥ now, gerechnet ab der Epoche.
export function command(st, now, cmd) {
  const b = st.epoch + Math.ceil((now - st.epoch) / STEP) * STEP;
  st.pending.push({ b: Math.max(b, st.t), seq: st.seq++, cmd }); // vor dem Anker wirkt am Anker
}

// Abschnitt 4.3: höchstens acht Stunden Nachberechnung. Älterer Zeitraum wird verworfen,
// ausstehende Befehle aus diesem Zeitraum wirken am neuen Anker (Abschnitt 4.5).
// Liegt die Systemzeit vor dem Anker, entstehen keine Schritte (Abschnitt 4.4).
export function advanceTo(st, now) {
  const earliest = st.epoch + Math.ceil((now - MAX_ABSENCE - st.epoch) / STEP) * STEP;
  if (st.t < earliest) {
    st.t = earliest;
    for (const p of st.pending) p.b = Math.max(p.b, st.t);
  }
  while (st.t + STEP <= now) step(st);
}

function pay(st, cost) {
  if (Object.keys(cost).some((r) => st.stock[r] < cost[r])) return false;
  for (const r of Object.keys(cost)) st.stock[r] -= cost[r];
  return true;
}

const find = (st, id) => st.buildings.find((x) => x.id === id);

// Befehle prüfen zuerst und ändern den Zustand erst nach bestandener Prüfung.
function apply(st, c, b) {
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
      const bd = find(st, c.id);
      if (!bd) return 'REJECT unbekannter Betrieb';
      if (bd.stage >= 2 || bd.pendingStage) return 'REJECT Stufe';
      if (!pay(st, KIND[bd.kind].upgrade)) return 'REJECT Kosten';
      bd.pendingStage = 2;
      bd.pendingFrom = b + STEP;
      return `UPGRADE #${bd.id} ab ${b + STEP}`;
    }
    case 'pause':
    case 'resume': {
      const bd = find(st, c.id);
      if (!bd) return 'REJECT unbekannter Betrieb';
      if (c.type === 'resume' && !bd.paused) return 'REJECT Betrieb nicht pausiert';
      bd.paused = c.type === 'pause';
      return `${c.type === 'pause' ? 'PAUSE' : 'FORTSETZEN'} #${bd.id}`;
    }
    case 'move': {
      // Versetzen: kein Kostenpunkt, Betrieb und Pausenzustand bleiben erhalten.
      const bd = find(st, c.id);
      if (!bd) return 'REJECT unbekannter Betrieb';
      if (!st.unlocked.has(c.plot)) return 'REJECT Bauplatz gesperrt';
      if (PLOT_RULE[c.plot] !== 'any' && PLOT_RULE[c.plot] !== bd.kind) return 'REJECT Bauplatz reserviert';
      if (st.buildings.some((x) => x.plot === c.plot)) return 'REJECT Bauplatz belegt';
      bd.plot = c.plot;
      bd.activeFrom = b + STEP;
      return `VERSETZT #${bd.id} auf ${c.plot}`;
    }
    case 'storage': {
      if (st.storageDone[c.res]) return 'REJECT Lager bereits ausgebaut';
      if (st.cap[c.res] + CAP_UPGRADE > CAP_MAX) return 'REJECT Kapazitätsgrenze';
      if (!pay(st, STORAGE_COST[c.res])) return 'REJECT Kosten';
      st.storageDone[c.res] = true;
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

// Speicherstand (Abschnitt 10): alles außer dem Protokoll der Schritte.
export function serialize(st) {
  return JSON.stringify({
    epoch: st.epoch, t: st.t, stock: st.stock, cap: st.cap, buildings: st.buildings,
    unlocked: [...st.unlocked], storageDone: st.storageDone, pending: st.pending, seq: st.seq, nextId: st.nextId,
  });
}

export function deserialize(text) {
  const d = JSON.parse(text);
  return { ...d, unlocked: new Set(d.unlocked), log: [] };
}

function snapshot(st) {
  const copy = Object.fromEntries(STATE_KEYS.map((k) => [k, structuredClone(st[k])]));
  return { ...copy, unlocked: [...st.unlocked] };
}

function restore(st, snap) {
  for (const k of STATE_KEYS) st[k] = snap[k];
  st.unlocked = new Set(snap.unlocked);
}

// Schrittbeginn st.t: Befehle, Stufenwechsel, Verteilung nach ID, Bilanz.
// Schlägt ein Schritt fehl, wird der Zustand auf den Stand vor dem Schritt zurückgesetzt (Abschnitt 2).
export function step(st) {
  const before = snapshot(st);
  try {
    stepInPlace(st);
  } catch (err) {
    restore(st, before);
    throw err;
  }
}

function stepInPlace(st) {
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
  // Eingangspool und freier Ausgang: Werte zu Schrittbeginn; jeder Betrieb verringert sie für die folgenden.
  const avail = { ...st.stock };
  const free = { H: st.cap.H - st.stock.H, B: st.cap.B - st.stock.B };
  for (const r of ['H', 'B']) {
    if (free[r] < 0) throw new Error(`Vorrat ${r} über der Kapazität`);
  }
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

// Eingeschränkte Suche für RF-9: nur die genannten Käufe, jeder zum frühestmöglichen Schritt.
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

function forestAndSaw() {
  return [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }, { kind: 'SAW', plot: 'P2', activeFrom: 0 }];
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

// RF-9 ist ein Beispielpfad mit drei Käufen. Die Suche umfasst nur diese Käufe, kein globales Minimum.
function rf9() {
  let best = null;
  for (const order of [['SAW', 'E1'], ['F2', 'SAW', 'E1'], ['SAW', 'F2', 'E1'], ['F2', 'E1', 'SAW'], ['SAW', 'E1', 'F2']]) {
    const plan = earliestPlan(order);
    if (!plan || plan.find((p) => p.name === 'E1') === undefined) continue;
    const e1 = plan.find((p) => p.name === 'E1').b;
    if (!best || e1 < best.e1) best = { order, plan, e1 };
  }
  assert.ok(best, 'kein Weg zur Erweiterung gefunden');
  const st = fresh({ buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  for (const p of best.plan) command(st, p.b, PURCHASE[p.name].cmd);
  advanceTo(st, best.e1 + STEP);
  assert.deepEqual(st.log.flatMap((e) => e.msgs).filter((m) => m.startsWith('REJECT')), [], 'abgelehnte Befehle auf dem Beispielpfad');
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

// Acht Stunden Obergrenze: 10 Stunden Abwesenheit ergeben genau 28.800 Schritte.
function rf11() {
  const st = fresh({ cap: { H: 1e9 }, buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  advanceTo(st, 10 * 3600 * 1000);
  assert.equal(st.log.length, 28800, 'Anzahl der Schritte');
  assert.equal(st.log[0].b, 7200000, 'erster berechneter Schritt');
  assert.equal(st.stock.H, 28800 * 2000, 'Holz nach acht Stunden');
}

// Negative Zeitdifferenz: kein Schritt, kein Zurücksetzen des Ankers.
function rf12() {
  const st = fresh({ buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  advanceTo(st, 5000);
  assert.equal(st.t, 5000);
  advanceTo(st, 3000);
  assert.equal(st.t, 5000, 'Anker bleibt');
  assert.equal(st.log.length, 5, 'keine neuen Schritte');
  advanceTo(st, 6000);
  assert.equal(st.stock.H, 12000, 'Schritt 5.000 ms wird nach Erreichen des Ankers berechnet');
}

// Ausstehender Befehl vor dem alten Zeitraum wirkt am neuen Anker, geht nicht verloren.
function rf13() {
  const st = fresh({ buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  command(st, 1000, { type: 'pause', id: 1 });
  advanceTo(st, 10 * 3600 * 1000);
  assert.equal(st.log[0].b, 7200000);
  assert.ok(st.log[0].msgs.includes('PAUSE #1'), 'Pause wirkt am neuen Anker');
  assert.equal(st.stock.H, 0, 'kein Holz während der Pause');
  assert.equal(st.pending.length, 0, 'Befehl ist verarbeitet');
}

// Befehlsinvarianten: abgelehnte Befehle ändern nichts; Lagerausbau höchstens einmal.
function rf14() {
  const st = fresh({ stock: { H: 500 * U }, cap: { H: 5000 * U }, buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  command(st, 0, { type: 'storage', res: 'H' });
  command(st, 0, { type: 'storage', res: 'H' });
  command(st, 0, { type: 'pause', id: 99 });
  command(st, 0, { type: 'build', kind: 'SAW', plot: 'P1' });
  command(st, 0, { type: 'build', kind: 'FOREST', plot: 'P2' });
  command(st, 0, { type: 'resume', id: 1 });
  advanceTo(st, 1000);
  assert.equal(st.cap.H, 5000 * U + CAP_UPGRADE, 'Lagerausbau nur einmal');
  assert.deepEqual(st.log[0].msgs.map((m) => m.split(' ')[0]), ['LAGER', 'REJECT', 'REJECT', 'REJECT', 'REJECT', 'REJECT']);
  assert.equal(st.log[0].stock.H, 500000 - 40000 + 2000, 'Holz nach einmaligem Ausbau und einem Forstschritt');
}

// Speichern und Wiederherstellen: Ausbauzustand und Sequenz bleiben erhalten, ausstehende Befehle wirken weiter.
function rf16() {
  const st = fresh({ stock: { H: 100 * U }, buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  command(st, 0, { type: 'storage', res: 'H' });
  advanceTo(st, 1000);
  command(st, 1000, { type: 'pause', id: 1 });
  const loaded = deserialize(serialize(st));
  command(loaded, 1000, { type: 'storage', res: 'H' });
  command(loaded, 1000, { type: 'resume', id: 1 });
  advanceTo(loaded, 2000);
  const msgs = loaded.log[0].msgs;
  assert.deepEqual(msgs.map((m) => m.split(' ')[0]), ['PAUSE', 'REJECT', 'FORTSETZEN'], 'Reihenfolge nach dem Laden');
  assert.equal(loaded.cap.H, 2 * CAP_START, 'Ausbau nur einmal, auch nach dem Laden');
  assert.equal(loaded.buildings[0].paused, false, 'Fortsetzen nach der Pause wirkt');
}

// Schrittfehler: der Zustand bleibt vor dem Schritt erhalten.
function rf15() {
  const st = fresh({ stock: { H: 2000 }, cap: { H: 1000 }, buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  assert.throws(() => advanceTo(st, 1000), /über der Kapazität/);
  assert.equal(st.t, 0, 'Anker unverändert');
  assert.equal(st.stock.H, 2000, 'Vorrat unverändert');
  assert.equal(st.log.length, 0, 'kein Schritt protokolliert');
}

// Kapazitätsgrenze: ein Lagerausbau, der über 1.000.000.000 U hinausginge, wird vor der Zustandsänderung abgewiesen.
function rf17() {
  const st = fresh({ stock: { H: 500 * U }, cap: { H: CAP_MAX }, buildings: [{ kind: 'FOREST', plot: 'P1', activeFrom: 0 }] });
  command(st, 0, { type: 'storage', res: 'H' });
  advanceTo(st, 1000);
  assert.equal(st.log[0].msgs[0], 'REJECT Kapazitätsgrenze');
  assert.equal(st.cap.H, CAP_MAX, 'Kapazität unverändert');
  assert.equal(st.stock.H, 500000 + 2000, 'Kosten nicht abgebucht');
  assert.equal(st.storageDone.H, false, 'Ausbau nicht als erledigt markiert');
}

export const CASES = {
  'RF-1': rf1, 'RF-2': rf2, 'RF-3': rf3, 'RF-4': rf4, 'RF-5': rf5, 'RF-6': rf6, 'RF-7': rf7, 'RF-8': rf8,
  'RF-9': rf9, 'RF-10': rf10, 'RF-11': rf11, 'RF-12': rf12, 'RF-13': rf13, 'RF-14': rf14, 'RF-15': rf15, 'RF-16': rf16, 'RF-17': rf17,
};

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
