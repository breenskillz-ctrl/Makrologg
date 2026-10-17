/**
 * Makrologg + Loadlog → Google Sheets
 *
 * Tar imot poster fra Makrologg og Loadlog og legger dem i disse fanene:
 *   Dager      – Makrologg: én rad per person og dag (kalorier, makroer, mål, trening, hva som ble spist)
 *   Vekt       – Makrologg: én rad per person og dag
 *   Garmin     – Makrologg: én rad per person og dag (søvnscore, body battery, hvilepuls, HRV …)
 *   Økter      – Loadlog: én rad per treningsdag (varighet, antall sett, volum, sammendrag)
 *   Sett       – Loadlog: én rad per gjennomført sett (øvelse, vekt, reps, nivå, variant, e1RM)
 *   Kroppsmål  – Loadlog: én rad per måling (vekt, bryst, liv, armer, lår)
 * Endres en dag i en app, oppdateres samme rad i stedet for å lage en ny.
 * Makrologg sender med GET (?d=…), Loadlog med POST (JSON i kroppen). Begge går til samme adresse.
 *
 * Oppsett:
 *   1. Lag et nytt Google-regneark, åpne Utvidelser → Apps Script.
 *   2. Slett alt i Kode.gs og lim inn denne filen. Lagre.
 *   3. Distribuer → Ny distribusjon → Type: Nettapp.
 *      Kjør som: Meg. Hvem har tilgang: Alle. Distribuer og godkjenn tilgangen.
 *   4. Kopier nettapp-adressen (slutter på /exec) og lim den inn i begge appene:
 *      Makrologg: «Mål og vekt» → «Coach (Google Sheets)».
 *      Loadlog:   Progress → Settings → «Coach (Google Sheets)».
 *
 * Adressen er hemmelig nok til at bare den som har den kan skrive. Ikke del den.
 * Endrer du skriptet senere: Distribuer → Administrer distribusjoner → rediger → Ny versjon,
 * så beholder du samme adresse.
 */

var TABS = {
  day: {
    name: 'Dager',
    cols: ['Dato', 'Person', 'Kcal', 'Protein', 'Karbo', 'Fett', 'Mål kcal', 'Mål protein', 'Mål karbo', 'Mål fett',
           'Vann (glass)', 'Trening', 'Våknet', 'Antall matvarer', 'Mat', 'Person-ID', 'Oppdatert'],
    row: function (r) {
      return [r.date, r.person, r.kcal, r.p, r.c, r.f, r.gk, r.gp, r.gc, r.gf,
              r.water, r.train || '', r.wake || '', r.n, r.food || '', r.pid, new Date()];
    }
  },
  weight: {
    name: 'Vekt',
    cols: ['Dato', 'Person', 'Kg', 'Person-ID', 'Oppdatert'],
    row: function (r) { return [r.date, r.person, r.kg, r.pid, new Date()]; }
  },
  body: {
    name: 'Kroppsmål',
    cols: ['Dato', 'Person', 'Vekt', 'Bryst', 'Liv', 'Arm V', 'Arm H', 'Lår V', 'Lår H', 'Enhet', 'Person-ID', 'Oppdatert'],
    row: function (r) {
      return [r.date, r.person, v(r.weight), v(r.chest), v(r.waist), v(r.armL), v(r.armR), v(r.thighL), v(r.thighR), r.unit || '', r.pid, new Date()];
    }
  },
  workout: {
    name: 'Økter',
    cols: ['Dato', 'Person', 'Tittel', 'Minutter', 'Sett', 'Volum', 'Planlagt, ikke gjort', 'Øvelser', 'Person-ID', 'Oppdatert'],
    row: function (r) {
      var vol = 0, byEx = {}, order = [];
      (r.sets || []).forEach(function (s) {
        var w = Number(s[2]) || 0, reps = Number(s[3]) || 0;
        if (s[6] !== 'warmup') vol += w * reps;
        if (!byEx[s[0]]) { byEx[s[0]] = []; order.push(s[0]); }
        byEx[s[0]].push((s[2] !== '' ? s[2] + '×' : '') + (s[3] !== '' ? s[3] : (s[4] !== '' ? s[4] + 's' : s[5] + 'km')) + (s[6] && s[6] !== 'normal' ? ' ' + s[6] : ''));
      });
      var summary = order.map(function (n) { return n + ': ' + byEx[n].join(', '); }).join(' | ');
      return [r.date, r.person, r.title || '', v(r.min), (r.sets || []).length, Math.round(vol), r.planned || 0, summary, r.pid, new Date()];
    },
    // Each set also gets its own row in «Sett»
    after: function (r) {
      var cols = ['Dato', 'Person', 'Øvelse', 'Muskelgruppe', 'Sett nr', 'Vekt', 'Reps', 'Tid (s)', 'Distanse (km)', 'Nivå', 'Variant', 'Kommentar', 'e1RM', 'Enhet', 'Person-ID'];
      var sh = sheetFor({ name: 'Sett', cols: cols });
      removeRows(sh, r.date, r.pid, cols.indexOf('Person-ID') + 1);
      var n = {}, rows = (r.sets || []).map(function (s) {
        n[s[0]] = (n[s[0]] || 0) + 1;
        return [r.date, r.person, s[0], s[1], n[s[0]], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9], r.unit || '', r.pid];
      });
      if (rows.length) sh.getRange(sh.getLastRow() + 1, 1, rows.length, cols.length).setValues(rows.map(asText));
    }
  },
  morning: {
    name: 'Garmin',
    cols: ['Dato', 'Person', 'Søvnscore', 'Body battery', 'Hvilepuls', 'HRV', 'Søvn (t)', 'Stress', 'Notat', 'Person-ID', 'Oppdatert'],
    row: function (r) {
      return [r.date, r.person, v(r.sleep), v(r.bb), v(r.rhr), v(r.hrv), v(r.sleepH), v(r.stress), r.note || '', r.pid, new Date()];
    }
  }
};

function v(x) { return x === undefined || x === null ? '' : x; }

function out(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

function sheetFor(tab) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sh = ss.getSheetByName(tab.name);
  if (!sh) {
    sh = ss.insertSheet(tab.name);
    sh.getRange(1, 1, 1, tab.cols.length).setValues([tab.cols]).setFontWeight('bold');
    sh.setFrozenRows(1);
    sh.getRange('A:A').setNumberFormat('@'); // datoer som tekst (ÅÅÅÅ-MM-DD), ikke tolket
  }
  return sh;
}

// Google Sheets kan gjøre «2026-10-16» om til en dato selv om kolonnen er tekst.
// Sammenlign derfor alltid som ÅÅÅÅ-MM-DD, uansett om cellen er tekst eller dato.
function dkey(x) {
  if (x instanceof Date) return Utilities.formatDate(x, Session.getScriptTimeZone(), 'yyyy-MM-dd');
  return String(x).trim().slice(0, 10);
}

// Skriver datoen som ren tekst, så den ikke tolkes
function asText(row) {
  row = row.slice();
  row[0] = "'" + row[0];
  return row;
}

// Removes every row for this date and person (used to replace a day's sets)
function removeRows(sh, date, pid, pidCol) {
  var last = sh.getLastRow();
  if (last < 2) return;
  var dates = sh.getRange(2, 1, last - 1, 1).getValues();
  var pids = sh.getRange(2, pidCol, last - 1, 1).getValues();
  for (var i = dates.length - 1; i >= 0; i--) {
    if (dkey(dates[i][0]) === date && String(pids[i][0]) === pid) sh.deleteRow(i + 2);
  }
}

function upsert(tab, r) {
  var sh = sheetFor(tab);
  var row = tab.row(r);
  var pidCol = tab.cols.indexOf('Person-ID') + 1;
  var last = sh.getLastRow();
  if (last > 1) {
    var dates = sh.getRange(2, 1, last - 1, 1).getValues();
    var pids = sh.getRange(2, pidCol, last - 1, 1).getValues();
    for (var i = 0; i < dates.length; i++) {
      if (dkey(dates[i][0]) === r.date && String(pids[i][0]) === r.pid) {
        sh.getRange(i + 2, 1, 1, row.length).setValues([asText(row)]);
        return 'oppdatert';
      }
    }
  }
  sh.appendRow(asText(row));
  return 'ny';
}

function doGet(e) {
  var p = (e && e.parameter) || {};
  if (p.ping) return out({ ok: true, ping: true });
  if (!p.d) return out({ ok: false, error: 'mangler data' });
  return handle(p.d);
}

function doPost(e) {
  return handle(e && e.postData ? e.postData.contents : '');
}

function handle(text) {
  var r;
  try { r = JSON.parse(text); } catch (err) { return out({ ok: false, error: 'ugyldig JSON' }); }
  var tab = TABS[r && r.t];
  if (!tab) return out({ ok: false, error: 'ukjent type' });
  if (!/^\d{4}-\d\d-\d\d$/.test(String(r.date || ''))) return out({ ok: false, error: 'ugyldig dato' });
  r.pid = String(r.pid || 'p0');

  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    var result = upsert(tab, r);
    if (tab.after) tab.after(r);
    return out({ ok: true, result: result });
  } finally {
    lock.releaseLock();
  }
}

/**
 * Kjør én gang fra Apps Script (velg «ryddDuplikater» i lista øverst og trykk Kjør).
 * Fjerner doble rader i Dager, Vekt, Garmin, Økter og Kroppsmål og beholder den nyeste
 * for hver dato og person. I «Sett» fjernes helt like rader.
 */
function ryddDuplikater() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  ['Dager', 'Vekt', 'Garmin', 'Økter', 'Kroppsmål'].forEach(function (name) {
    var sh = ss.getSheetByName(name);
    if (!sh || sh.getLastRow() < 3) return;
    var data = sh.getDataRange().getValues(), head = data[0], pidCol = head.indexOf('Person-ID');
    var seen = {}, keep = [];
    for (var i = data.length - 1; i >= 1; i--) {         // nyeste rader ligger nederst
      var k = dkey(data[i][0]) + '|' + data[i][pidCol];
      if (seen[k]) continue;
      seen[k] = true;
      var row = data[i].slice(); row[0] = dkey(row[0]); keep.unshift(asText(row));
    }
    keep.sort(function (a, b) { return a[0] < b[0] ? 1 : a[0] > b[0] ? -1 : 0; });
    sh.getRange(2, 1, sh.getLastRow() - 1, head.length).clearContent();
    sh.getRange(2, 1, keep.length, head.length).setValues(keep);
  });
  var st = ss.getSheetByName('Sett');
  if (st && st.getLastRow() > 2) {
    var d = st.getDataRange().getValues(), h = d[0], seen2 = {}, out2 = [];
    for (var j = 1; j < d.length; j++) {
      var r = d[j].slice(); r[0] = dkey(r[0]);
      var key = JSON.stringify(r);
      if (seen2[key]) continue;
      seen2[key] = true; out2.push(asText(r));
    }
    st.getRange(2, 1, st.getLastRow() - 1, h.length).clearContent();
    st.getRange(2, 1, out2.length, h.length).setValues(out2);
  }
}
