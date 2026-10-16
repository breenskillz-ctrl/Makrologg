/**
 * Makrologg → Google Sheets
 *
 * Tar imot poster fra Makrologg-appen og legger dem i tre faner:
 *   Dager   – én rad per person og dag (kalorier, makroer, mål, trening, hva som ble spist)
 *   Vekt    – én rad per person og dag
 *   Garmin  – én rad per person og dag (søvnscore, body battery, hvilepuls, HRV …)
 * Endres en dag i appen, oppdateres samme rad i stedet for å lage en ny.
 *
 * Oppsett:
 *   1. Lag et nytt Google-regneark, åpne Utvidelser → Apps Script.
 *   2. Slett alt i Kode.gs og lim inn denne filen. Lagre.
 *   3. Distribuer → Ny distribusjon → Type: Nettapp.
 *      Kjør som: Meg. Hvem har tilgang: Alle. Distribuer og godkjenn tilgangen.
 *   4. Kopier nettapp-adressen (slutter på /exec) og lim den inn i Makrologg under
 *      «Mål og vekt» → «Coach (Google Sheets)».
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

function upsert(tab, r) {
  var sh = sheetFor(tab);
  var row = tab.row(r);
  var pidCol = tab.cols.indexOf('Person-ID') + 1;
  var last = sh.getLastRow();
  if (last > 1) {
    var dates = sh.getRange(2, 1, last - 1, 1).getValues();
    var pids = sh.getRange(2, pidCol, last - 1, 1).getValues();
    for (var i = 0; i < dates.length; i++) {
      if (String(dates[i][0]) === r.date && String(pids[i][0]) === r.pid) {
        sh.getRange(i + 2, 1, 1, row.length).setValues([row]);
        return 'oppdatert';
      }
    }
  }
  sh.appendRow(row);
  return 'ny';
}

function doGet(e) {
  var p = (e && e.parameter) || {};
  if (p.ping) return out({ ok: true, ping: true });
  if (!p.d) return out({ ok: false, error: 'mangler data' });
  var r;
  try { r = JSON.parse(p.d); } catch (err) { return out({ ok: false, error: 'ugyldig JSON' }); }
  var tab = TABS[r && r.t];
  if (!tab) return out({ ok: false, error: 'ukjent type' });
  if (!/^\d{4}-\d\d-\d\d$/.test(String(r.date || ''))) return out({ ok: false, error: 'ugyldig dato' });
  r.pid = String(r.pid || 'p0');

  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    return out({ ok: true, result: upsert(tab, r) });
  } finally {
    lock.releaseLock();
  }
}
