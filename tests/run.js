/* Makrologg – faste tester av kjerneflyten. Kjøres i GitHub Actions før en ny versjon signeres og publiseres.
   Lokalt:  node tests/run.js   (krever playwright med chromium) */
const path = require("path");
const assert = require("assert");
const { chromium } = require("playwright");

const FILE = "file://" + path.resolve(__dirname, "../app/src/main/assets/index.html");
const today = () => { const d = new Date(); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); };

const results = [];
async function test(name, fn) {
  try { await fn(); results.push(["ok", name]); console.log("✓ " + name); }
  catch (e) { results.push(["fail", name]); console.log("✗ " + name + "\n   " + (e && e.stack || e)); }
}

(async () => {
  const browser = await chromium.launch();
  const SB = { hh: {}, seq: 0 };

  async function phone(seed, width = 412) {
    const ctx = await browser.newContext({ viewport: { width, height: 900 }, locale: "nb-NO" });
    await ctx.route(/test\.supabase\.co/, async r => {
      const fn = r.request().url().split("/").pop(), b = JSON.parse(r.request().postData() || "{}"), H = SB.hh[b.p_h];
      const send = (st, o) => r.fulfill({ status: st, headers: { "access-control-allow-origin": "*" }, contentType: "application/json", body: JSON.stringify(o) });
      if (fn === "ml_create") { if (!H) SB.hh[b.p_h] = { s: b.p_s, docs: {} }; return send(200, SB.hh[b.p_h].s === b.p_s); }
      if (!H || H.s !== b.p_s) return send(400, { message: "denied" });
      if (fn === "ml_pull") { const docs = Object.entries(H.docs).filter(([k, v]) => v.seq > b.p_since).sort((x, y) => x[1].seq - y[1].seq).map(([k, v]) => ({ k, d: v.d, seq: v.seq })); return send(200, { docs, max: docs.length ? docs[docs.length - 1].seq : b.p_since }); }
      if (fn === "ml_push") { const ok = [], conflict = []; for (const it of b.p_docs) { const cur = H.docs[it.k]; if (cur && cur.seq > (it.base || 0)) conflict.push({ k: it.k, d: cur.d, seq: cur.seq }); else { const seq = ++SB.seq; H.docs[it.k] = { d: it.d, seq }; ok.push({ k: it.k, seq }); } } return send(200, { ok, conflict }); }
      return send(404, {});
    });
    await ctx.route(/^https?:\/\/(?!test\.supabase)/, r => r.fulfill({ status: 404, body: "" }));
    const pg = await ctx.newPage();
    pg.errors = [];
    pg.on("pageerror", e => pg.errors.push(e.message));
    if (seed) await pg.addInitScript(s => { if (!sessionStorage.getItem("seeded")) { sessionStorage.setItem("seeded", "1"); localStorage.setItem("makrologg.v1", JSON.stringify(s)); } }, seed);
    await pg.goto(FILE);
    await pg.waitForFunction(() => typeof S !== "undefined" && S.ready);
    return pg;
  }
  const click = async (pg, sel) => { await pg.click(sel); await pg.waitForTimeout(250); };
  const entries = (pg, pid, k) => pg.evaluate(([pid, k]) => withPerson(pid, () => (dayDoc(k).entries || []).map(e => e.name)), [pid, k]);

  await test("Appen starter uten feil, også helt tom", async () => {
    const pg = await phone(null);
    assert.ok(await pg.$(".summary"), "Dag-siden mangler");
    for (const t of ["meny", "hist", "mal"]) await click(pg, `[data-act="tab"][data-v="${t}"]`);
    await click(pg, '[data-act="settings"]');
    assert.deepStrictEqual(pg.errors, []);
    await pg.context().close();
  });

  await test("Logge mat og angre", async () => {
    const pg = await phone(null);
    await click(pg, '[data-act="add"][data-v="frokost"]');
    await pg.fill("#q", "egg"); await pg.waitForTimeout(500);
    await click(pg, '.sheet .list button[data-act="pickFood"], .sheet .list button');
    await click(pg, '[data-act="saveAmount"]');
    const after = await entries(pg, "p0", today());
    assert.ok(after.length === 1, "Ingen ting ble logget: " + JSON.stringify(after));
    const kcal = await pg.evaluate(() => totals(dayDoc(todayKey())).kcal);
    assert.ok(kcal > 0, "Ingen kalorier");
    await click(pg, ".toast button");
    assert.deepStrictEqual(await entries(pg, "p0", today()), [], "Angre virket ikke");
    assert.deepStrictEqual(pg.errors, []);
    await pg.context().close();
  });

  const seed = (() => {
    const k = today();
    return {
      people: { kind: "people", list: [{ id: "p0", name: "Meg", color: 0 }, { id: "pk", name: "Kari", color: 1 }], active: "p0" },
      ["day-" + k]: { kind: "day", date: k, entries: [{ id: "a1", meal: "lunsj", name: "Ris", g: 200, per: { kcal: 130, p: 2.7, c: 28, f: 0.3 }, kcal: 260, p: 5.4, c: 56, f: 0.6 }] }
    };
  })();

  await test("Kopiere et måltid til en annen dag og til en annen person", async () => {
    const pg = await phone(seed);
    await pg.evaluate(() => { const k = todayKey(); addToDay("p0", addDays(k, 1), Object.assign({}, dayDoc(k).entries[0], { id: "x" })); });
    const tm = await pg.evaluate(() => addDays(todayKey(), 1));
    assert.deepStrictEqual(await entries(pg, "p0", tm), ["Ris"]);
    await click(pg, '[data-act="mealOpen"][data-v="lunsj"]');
    await click(pg, '.sheet [data-act="copyMeal"][data-v="lunsj"]:not([data-k])');
    await click(pg, '[data-act="cmPct"][data-v="50"]');
    await click(pg, '[data-act="copyMealDo"]');
    const k = await pg.evaluate(() => withPerson("pk", () => dayDoc(todayKey()).entries.map(e => Math.round(e.g))));
    assert.deepStrictEqual(k, [100], "50 % til Kari ble feil: " + JSON.stringify(k));
    assert.deepStrictEqual(pg.errors, []);
    await pg.context().close();
  });

  await test("Mealprep fordeles og forsvinner når den er brukt opp", async () => {
    const pg = await phone(seed);
    await click(pg, '[data-act="tab"][data-v="meny"]');
    await click(pg, '[data-act="newPrep"]');
    await pg.fill("#tplName", "Testgryte");
    await pg.evaluate(() => { UI.tplDraft.items.push({ name: "Kjøttdeig", g: 400, per: { kcal: 200, p: 18, c: 0, f: 14 } }, { name: "Ris", g: 400, per: { kcal: 130, p: 2.7, c: 28, f: 0.3 } }); renderSheet(); });
    await pg.fill("#dishServ", "4"); await pg.dispatchEvent("#dishServ", "input");
    await click(pg, '[data-act="prepNext"]');
    await click(pg, '[data-act="prepRowAdd"]');
    await click(pg, '[data-act="prepDo"]');
    const left = await pg.evaluate(() => Object.keys((S.docs.prep || {}).items || {}).length);
    assert.strictEqual(left, 0, "Gryta ble ikke brukt opp");
    const n = await pg.evaluate(() => people().list.reduce((a, p) => a + withPerson(p.id, () => (dayDoc(addDays(todayKey(), 1)).entries || []).filter(e => e.name === "Testgryte").length), 0));
    assert.strictEqual(n, 2, "I morgen skulle hatt 2 porsjoner");
    assert.deepStrictEqual(pg.errors, []);
    await pg.context().close();
  });

  await test("Synk mellom to telefoner, med samtidige endringer og sletting", async () => {
    const A = await phone(seed), B = await phone(null);
    const code = "MLS1." + Buffer.from(JSON.stringify({ u: "https://test.supabase.co", k: "sb_publishable_test" })).toString("base64").replace(/=+$/, "");
    await click(A, '[data-act="settings"]'); await click(A, '[data-act="sset"][data-v="sync"]');
    await A.fill("#hhCode", code); await click(A, '[data-act="hhConnect"]'); await click(A, '[data-act="hhConnect"]');
    await A.waitForTimeout(800); await A.evaluate(() => hhSync()); await A.waitForTimeout(500);
    const share = await A.$eval("#hhShare", e => e.value);
    await click(B, '[data-act="settings"]'); await click(B, '[data-act="sset"][data-v="sync"]');
    await B.fill("#hhCode", share); await click(B, '[data-act="hhConnect"]'); await B.waitForTimeout(800);
    await click(B, '[data-act="hhMe"][data-v="pk"]'); await B.evaluate(() => hhSync()); await B.waitForTimeout(500);
    assert.strictEqual(await B.evaluate(() => CUR), "pk");
    await A.evaluate(() => addToDay("pk", todayKey(), { id: "fa", meal: "middag", name: "Fra A", g: 100, per: { kcal: 100, p: 1, c: 1, f: 1 }, kcal: 100, p: 1, c: 1, f: 1 }));
    await B.evaluate(() => addToDay("pk", todayKey(), { id: "fb", meal: "lunsj", name: "Fra B", g: 100, per: { kcal: 100, p: 1, c: 1, f: 1 }, kcal: 100, p: 1, c: 1, f: 1 }));
    for (const p of [A, B, A]) { await p.evaluate(() => hhSync()); await p.waitForTimeout(400); }
    const a = (await entries(A, "pk", today())).sort(), b = (await entries(B, "pk", today())).sort();
    assert.deepStrictEqual(a, ["Fra A", "Fra B"]); assert.deepStrictEqual(b, a);
    await A.evaluate(() => withPerson("pk", () => { const k = todayKey(), d = clone(dayDoc(k)); d.entries = d.entries.filter(e => e.id !== "fb"); writeDoc(pk("day-") + k, d); }));
    await A.evaluate(() => hhSync()); await A.waitForTimeout(300); await B.evaluate(() => hhSync()); await B.waitForTimeout(300);
    assert.deepStrictEqual(await entries(B, "pk", today()), ["Fra A"], "Sletting kom ikke fram");
    assert.deepStrictEqual(A.errors.concat(B.errors), []);
    await A.context().close(); await B.context().close();
  });

  await test("Ingen sideveis scrolling på smal skjerm", async () => {
    const pg = await phone(seed, 320);
    for (const t of ["dag", "meny", "hist", "mal"]) {
      await click(pg, `[data-act="tab"][data-v="${t}"]`);
      const w = await pg.evaluate(() => document.documentElement.scrollWidth);
      assert.ok(w <= 320, `${t}: siden er ${w}px bred`);
    }
    await pg.context().close();
  });

  await browser.close();
  const failed = results.filter(r => r[0] === "fail");
  console.log(`\n${results.length - failed.length} av ${results.length} tester bestått`);
  process.exit(failed.length ? 1 : 0);
})();
