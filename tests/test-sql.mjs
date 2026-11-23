/*
 * Tut supabase/bildschirmzeit.sql, was Web-Apps und Widget erwarten?
 *
 *     node tests/test-sql.mjs
 *
 * Ohne Supabase und ohne Netz: PGlite ist ein echtes Postgres als WebAssembly.
 * Vorher bekommt es, was Supabase mitbringt – die Rollen anon, authenticated
 * und service_role und die Voreinstellung, dass jede neue Tabelle und Funktion
 * im Schema public gleich alle Rechte für sie hat (sonst bewiese das Wegnehmen
 * der Rechte in der Datei nichts). Eingespielt wird als Rolle „besitzer“ ohne
 * Superuser-Rechte, aufgerufen wird als anon, so wie PostgREST mit dem
 * öffentlichen Schlüssel – mit Namen. Gleiches Vorgehen wie
 * Finanzdienste/Freizeit tests/test-sql.mjs.
 */
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual as gleich } from 'node:util';
import { PGlite } from '@electric-sql/pglite';

const HIER = path.dirname(fileURLToPath(import.meta.url));
const SQL = readFileSync(path.resolve(HIER, '..', 'supabase', 'bildschirmzeit.sql'), 'utf8');

let fails = 0;
let oks = 0;
const check = (c, m) => {
  console.log(`${c ? 'OK  ' : 'FAIL'} ${m}`);
  if (c) oks++; else fails++;
};

const db = await PGlite.create();

const ROLLEN = `
  create role anon nologin noinherit;
  create role authenticated nologin noinherit;
  create role service_role nologin noinherit bypassrls;
  create role besitzer nologin;
  grant usage, create on schema public to besitzer;
  grant usage on schema public to anon, authenticated, service_role;
  alter default privileges for role besitzer in schema public grant all on tables to anon, authenticated, service_role;
  alter default privileges for role besitzer in schema public grant all on functions to anon, authenticated, service_role;
  alter default privileges for role besitzer in schema public grant all on sequences to anon, authenticated, service_role;
`;
await db.exec(ROLLEN);

async function einspielen() {
  await db.exec('set role besitzer');
  try { await db.exec(SQL); } finally { await db.exec('reset role'); }
}

async function als(rolle, sql, werte = []) {
  if (rolle) await db.exec(`set role ${rolle}`);
  try { return (await db.query(sql, werte)).rows; } finally { if (rolle) await db.exec('reset role'); }
}
const su = (sql, werte) => als(null, sql, werte);

async function fehler(p) {
  try { await p; return null; } catch (e) { return e; }
}

const K1 = 'A'.repeat(20) + 'b_-c'.repeat(5) + 'xyz'; // 43 Zeichen, base64url
const K2 = 'Z'.repeat(43);
const K3 = 'q'.repeat(43);
const sha = (k) => createHash('sha256').update(k, 'utf8').digest('hex');

const anmelden = (k) => als('anon', 'select bildschirmzeit_anmelden(p_kennung => $1) as r', [k]).then((r) => r[0].r);
const melden = (k, app, tage) => als('anon', 'select bildschirmzeit_melden(p_kennung => $1, p_app => $2, p_tage => $3::text::jsonb) as r',
  [k, app, typeof tage === 'string' ? tage : JSON.stringify(tage)]).then((r) => r[0].r);
const lesen = (k, von, bis) => als('anon', 'select * from bildschirmzeit_lesen(p_kennung => $1, p_von => $2::date, p_bis => $3::date)', [k, von, bis]);
const loeschen = (k) => als('anon', 'select bildschirmzeit_loeschen(p_kennung => $1) as r', [k]).then((r) => r[0].r);
const anrufeMelden = (k, liste) => als('anon', 'select bildschirmzeit_anrufe_melden(p_kennung => $1, p_anrufe => $2::text::jsonb) as r', [k, JSON.stringify(liste)]).then((r) => r[0].r);
const anrufeLoeschen = (k) => als('anon', 'select bildschirmzeit_anrufe_loeschen(p_kennung => $1) as r', [k]).then((r) => r[0].r);
const anrufeLesen = (k, seit) => als('anon', 'select * from bildschirmzeit_anrufe_lesen(p_kennung => $1, p_seit => $2::timestamptz)', [k, seit]);
const kontakteSetzen = (k, liste) => als('anon', 'select bildschirmzeit_kontakte_setzen(p_kennung => $1, p_kontakte => $2::text::jsonb) as r', [k, JSON.stringify(liste)]).then((r) => r[0].r);
const kontakteLesen = (k) => als('anon', 'select * from bildschirmzeit_kontakte_lesen(p_kennung => $1)', [k]);
const anrufe = (k) => su('select nummer, name, art, sekunden, zeit from bildschirmzeit_anruf where geraet = $1 order by zeit', [sha(k)]);

const tag = (versatz) => {
  const [r] = [new Date(Date.now() + versatz * 86400000)];
  return r.toISOString().slice(0, 10);
};
const HEUTE = tag(0);
const GESTERN = tag(-1);

await einspielen();
// Zweimal hintereinander – so wie man sie im SQL Editor noch einmal einspielt.
await einspielen();
check(true, 'die Datei lässt sich zweimal hintereinander als Besitzer einspielen');

// --- Rechte ---
const fn = await su(`select p.proname as name, has_function_privilege('anon', p.oid, 'execute') as anon
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public' and p.proname like 'bildschirmzeit%'`);
const anonDarf = fn.filter((f) => f.anon).map((f) => f.name).sort();
check(gleich(anonDarf, ['bildschirmzeit_anmelden', 'bildschirmzeit_anrufe_lesen', 'bildschirmzeit_anrufe_loeschen', 'bildschirmzeit_anrufe_melden', 'bildschirmzeit_kontakte_lesen', 'bildschirmzeit_kontakte_setzen', 'bildschirmzeit_lesen', 'bildschirmzeit_loeschen', 'bildschirmzeit_melden']),
  `anon darf genau die neun Funktionen ausführen (${anonDarf.join(', ')})`);
check((await fehler(als('anon', "select bildschirmzeit_hash('x')")))?.code === '42501', 'anon darf bildschirmzeit_hash nicht aufrufen');
for (const t of ['bildschirmzeit', 'bildschirmzeit_geraet', 'bildschirmzeit_anruf', 'bildschirmzeit_kontakt']) {
  for (const rolle of ['anon', 'authenticated']) {
    const e = await fehler(als(rolle, `select * from ${t}`));
    check(e?.code === '42501', `${rolle}: select * from ${t} → permission denied`);
  }
  const rls = await su('select relrowsecurity as r from pg_class where relname = $1', [t]);
  check(rls[0].r === true, `${t}: Row Level Security ist an`);
}
const definer = await su(`select p.proname as name, p.prosecdef as d, array_to_string(p.proconfig, ',') as c
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public' and p.proname like 'bildschirmzeit%'`);
check(definer.every((f) => f.c === 'search_path=""'), 'jede Funktion hat search_path = \'\'');

// --- Anmelden ---
check(await anmelden(K1) === true, 'anmelden → true');
check(await anmelden(K1) === true, 'noch einmal anmelden → true (mehrfach ist egal)');
const geraete = await su('select geraet from bildschirmzeit_geraet');
check(geraete.length === 1 && geraete[0].geraet === sha(K1), 'gespeichert ist nur der SHA-256 der Kennung, einmal');
for (const schlecht of ['', 'kurz', 'a'.repeat(31), 'a'.repeat(129), 'ä'.repeat(40), `${'a'.repeat(40)} x`, null]) {
  const e = await fehler(anmelden(schlecht));
  check(e?.message === 'Kennung ungültig', `anmelden(${JSON.stringify(schlecht)?.slice(0, 20)}) → „Kennung ungültig“`);
}

// --- Melden ---
check(await melden(K2, 'workout', { [HEUTE]: 60 }) === false, 'melden mit nicht angemeldeter Kennung → false');
check((await su('select count(*)::int as n from bildschirmzeit'))[0].n === 0, '… und es wird nichts gespeichert');

check(await melden(K1, 'workout', { [HEUTE]: 125.6, [GESTERN]: 3600 }) === true, 'melden: zwei Tage → true');
let zeilen = await lesen(K1, GESTERN, HEUTE);
check(gleich(zeilen.map((z) => [z.app, z.tag.toISOString?.().slice(0, 10) ?? z.tag, z.sekunden]),
  [['workout', GESTERN, 3600], ['workout', HEUTE, 126]]), 'lesen: beide Tage, gerundet, nach Tag sortiert');

check(await melden(K1, 'workout', { [HEUTE]: 100 }) === true, 'melden: kleinerer Wert für heute');
zeilen = await lesen(K1, HEUTE, HEUTE);
check(zeilen.length === 1 && zeilen[0].sekunden === 126, 'der größere Wert bleibt (126, nicht 100)');
await melden(K1, 'workout', { [HEUTE]: 300 });
zeilen = await lesen(K1, HEUTE, HEUTE);
check(zeilen[0].sekunden === 300, 'ein größerer Wert ersetzt ihn (300)');

await melden(K1, 'boxenstopp', { [HEUTE]: 90000 });
await melden(K1, 'freizeit', { [HEUTE]: -5 });
zeilen = await lesen(K1, HEUTE, HEUTE);
const je = Object.fromEntries(zeilen.map((z) => [z.app, z.sekunden]));
check(je.boxenstopp === 86400 && je.freizeit === 0 && je.workout === 300, `Werte auf 0 … 86400 begrenzt (${JSON.stringify(je)})`);

// Die Bewerbungszentrale kam am 19.11.2026 dazu.
check(await melden(K1, 'bewerbungszentrale', { [HEUTE]: 42 }) === true, 'melden: die Bewerbungszentrale → true');
zeilen = await lesen(K1, HEUTE, HEUTE);
check(zeilen.some((z) => z.app === 'bewerbungszentrale' && z.sekunden === 42), 'lesen: die Bewerbungszentrale mit ihren Sekunden');
const pruefungen = await su(`select pg_get_constraintdef(oid) as d from pg_constraint
  where conrelid = 'public.bildschirmzeit'::regclass and contype = 'c' and pg_get_constraintdef(oid) like '%app%'`);
check(pruefungen.length === 1 && pruefungen[0].d.includes('bewerbungszentrale'), `genau eine Prüfung der Apps an der Tabelle (${pruefungen.map((p) => p.d).join(' | ')})`);

const vorher = (await su('select count(*)::int as n from bildschirmzeit'))[0].n;
await melden(K1, 'freizeit', { [tag(-30)]: 10, [tag(5)]: 10, '2026-02-30': 10, nichtdatum: 10, [GESTERN]: 'viel', [tag(-2)]: null });
check((await su('select count(*)::int as n from bildschirmzeit'))[0].n === vorher,
  'Tage außerhalb des Fensters, kaputte Daten und Werte, die keine Zahl sind, fallen still weg');
await melden(K1, 'freizeit', { [tag(-8)]: 11, [tag(1)]: 12 });
check((await su('select count(*)::int as n from bildschirmzeit'))[0].n === vorher + 2, 'heute − 8 und heute + 1 werden noch angenommen');

for (const [app, tage, m] of [
  ['chrome', { [HEUTE]: 1 }, 'App unbekannt'],
  [null, { [HEUTE]: 1 }, 'App unbekannt'],
  ['workout', '[1,2]', 'Tage fehlen'],
  ['workout', 'null', 'Tage fehlen'],
  ['workout', Object.fromEntries(Array.from({ length: 11 }, (_, i) => [tag(-i), 1])), 'Zu viele Tage'],
]) {
  const e = await fehler(melden(K1, app, tage));
  check(e?.message === m, `melden(${app}, ${JSON.stringify(tage).slice(0, 30)}) → „${m}“`);
}

// Alte Zeilen verschwinden beim nächsten Melden.
await su(`insert into bildschirmzeit (geraet, app, tag, sekunden) values ($1, 'workout', current_date - 100, 5)`, [sha(K1)]);
await melden(K1, 'workout', { [HEUTE]: 301 });
check((await su("select count(*)::int as n from bildschirmzeit where tag < current_date - 62"))[0].n === 0, 'Zeilen älter als 62 Tage verschwinden beim Melden');

// --- Lesen ---
check((await lesen(K2, HEUTE, HEUTE)).length === 0, 'lesen mit fremder Kennung → nichts');
await anmelden(K2);
await melden(K2, 'workout', { [HEUTE]: 77 });
const z1 = await lesen(K1, HEUTE, HEUTE);
const z2 = await lesen(K2, HEUTE, HEUTE);
check(z1.find((z) => z.app === 'workout').sekunden === 301 && z2.length === 1 && z2[0].sekunden === 77, 'jede Kennung sieht nur ihre Zahlen');
check((await fehler(lesen(K1, HEUTE, GESTERN)))?.message === 'Zeitraum ungültig', 'lesen: bis vor von → „Zeitraum ungültig“');
check((await fehler(lesen(K1, tag(-63), HEUTE)))?.message === 'Zeitraum ungültig', 'lesen: mehr als 62 Tage → „Zeitraum ungültig“');
const gelesen = await su('select gelesen from bildschirmzeit_geraet where geraet = $1', [sha(K1)]);
check(gelesen[0].gelesen instanceof Date, 'lesen merkt sich, wann das Widget zuletzt las');
const spalten = Object.keys(z1[0]).sort();
check(gleich(spalten, ['aktualisiert', 'app', 'sekunden', 'tag']), `lesen liefert app, tag, sekunden, aktualisiert (${spalten.join(', ')})`);

// --- Anrufe (Widget-App, seit 23.11.2026) ---
{
  const vor = (min) => new Date(Date.now() - min * 60000).toISOString();
  check(await anrufeMelden(K3, []) === false, 'anrufe_melden mit nicht angemeldeter Kennung → false');
  const liste = [
    { zeit: vor(30), nummer: '+4940123456', name: null, art: 'verpasst', sekunden: 0 },
    { zeit: vor(90), nummer: '017612345678', name: 'Erika Beispiel', art: 'eingehend', sekunden: 312.4 },
    { zeit: vor(200), nummer: 'unbekannt', name: '', art: 'verpasst', sekunden: 0 },
  ];
  check(await anrufeMelden(K1, liste) === true, 'anrufe_melden: drei Anrufe → true');
  let a = await anrufe(K1);
  check(a.length === 3 && a.find((x) => x.nummer === '017612345678').sekunden === 312 && a.find((x) => x.nummer === 'unbekannt').name === null,
    'gespeichert: drei Anrufe, Sekunden gerundet, leerer Name als null');
  check(await anrufeMelden(K1, liste) === true && (await anrufe(K1)).length === 3, 'dieselbe Liste noch einmal: nichts doppelt');
  await anrufeMelden(K1, [{ ...liste[0], art: 'eingehend', sekunden: 60 }]);
  a = await anrufe(K1);
  check(a.find((x) => x.nummer === '+4940123456').art === 'eingehend', 'gleiche Zeit und Nummer: der neue Stand gilt');
  await anrufeMelden(K1, [
    { zeit: vor(60 * 24 * 40), nummer: '040111', art: 'verpasst', sekunden: 0 },
    { zeit: 'gestern', nummer: '040222', art: 'verpasst', sekunden: 0 },
    { zeit: vor(5), nummer: '040 333', art: 'verpasst', sekunden: 0 },
    { zeit: vor(5), nummer: '040444', art: 'konferenz', sekunden: 0 },
    { zeit: vor(5), nummer: '040555', art: 'verpasst', sekunden: 'lang' },
    'kein Objekt',
  ]);
  check((await anrufe(K1)).length === 3, 'zu alt, falsche Zeit, Leerzeichen in der Nummer, unbekannte Art, keine Zahl: fällt still weg');
  check((await fehler(anrufeMelden(K1, Array.from({ length: 501 }, () => liste[0]))))?.message === 'Zu viele Anrufe', 'mehr als 500 → „Zu viele Anrufe“');
  check((await fehler(anrufeMelden(K1, { a: 1 })))?.message === 'Anrufe fehlen', 'kein Array → „Anrufe fehlen“');
  const gelesen = await anrufeLesen(K1, vor(60 * 24 * 14));
  check(gelesen.length === 3 && gleich(Object.keys(gelesen[0]).sort(), ['art', 'name', 'nummer', 'sekunden', 'zeit']) && gelesen[0].zeit < gelesen[2].zeit,
    'anrufe_lesen: die drei Anrufe, älteste zuerst, mit zeit, nummer, name, art, sekunden');
  check((await anrufeLesen(K1, vor(60))).length === 1, 'anrufe_lesen ab p_seit');
  check((await anrufeLesen(K2, vor(60 * 24 * 14))).length === 0, 'anrufe_lesen mit anderer Kennung → nichts');
  check((await fehler(anrufeLesen(K1, null)))?.message === 'Zeitraum ungültig', 'anrufe_lesen ohne p_seit → „Zeitraum ungültig“');
  await anmelden(K3);
  await anrufeMelden(K3, [liste[1]]);
  check(await anrufeLoeschen(K1) === true && (await anrufe(K1)).length === 0, 'anrufe_loeschen: alle Anrufe dieser Kennung weg');
  check((await anrufe(K3)).length === 1, '… die einer anderen Kennung bleiben');
  check((await su('select count(*)::int as n from bildschirmzeit where geraet = $1', [sha(K1)]))[0].n > 0, '… und die Minuten der Web-Apps auch');
  await anrufeMelden(K1, [liste[1]]);
}

// --- Kontakte (Bewerbungszentrale setzt, Widget-App liest, seit 23.11.2026) ---
{
  check(await kontakteSetzen('w'.repeat(43), []) === false, 'kontakte_setzen mit nicht angemeldeter Kennung → false');
  const liste = [
    { nummer: '+4940123456', name: 'Erika Beispiel (Beispiel GmbH)' },
    { nummer: '+49301112', name: '  Muster AG\n ' },
  ];
  check(await kontakteSetzen(K1, liste) === true, 'kontakte_setzen: zwei Namen → true');
  let k = await kontakteLesen(K1);
  check(gleich(k.map((x) => [x.nummer, x.name]), [['+49301112', 'Muster AG'], ['+4940123456', 'Erika Beispiel (Beispiel GmbH)']]),
    `kontakte_lesen: nach Nummer, Name ohne Steuerzeichen und Rand (${JSON.stringify(k)})`);
  check(gleich(Object.keys(k[0]).sort(), ['name', 'nummer']), 'kontakte_lesen liefert nur nummer und name');
  await kontakteSetzen(K1, [
    { nummer: '+49301112', name: 'Muster AG' },
    { nummer: '+49301112', name: 'Muster Neu AG' },
    { nummer: '040 333', name: 'Leerzeichen' },
    { nummer: '+4940999', name: '   ' },
    { nummer: 4940999, name: 'Zahl' },
    'kein Objekt',
  ]);
  k = await kontakteLesen(K1);
  check(k.length === 1 && k[0].name === 'Muster Neu AG', 'die neue Liste ersetzt die alte; doppelte Nummer: der letzte Name; Unpassendes fällt still weg');
  check((await kontakteLesen(K2)).length === 0 && (await kontakteLesen('w'.repeat(43))).length === 0, 'kontakte_lesen mit anderer oder unbekannter Kennung → nichts');
  check((await fehler(kontakteSetzen(K1, Array.from({ length: 501 }, () => liste[0]))))?.message === 'Zu viele Kontakte', 'mehr als 500 → „Zu viele Kontakte“');
  check((await fehler(kontakteSetzen(K1, { a: 1 })))?.message === 'Kontakte fehlen', 'kein Array → „Kontakte fehlen“');
  check((await kontakteLesen(K1)).length === 1, '… und die Liste bleibt dabei, wie sie war');
}

// --- Löschen ---
check(await loeschen(K1) === true, 'loeschen → true');
check(await loeschen(K1) === false, 'noch einmal loeschen → false');
check((await su('select count(*)::int as n from bildschirmzeit where geraet = $1', [sha(K1)]))[0].n === 0, 'loeschen nimmt alle Zahlen dieser Kennung mit');
check((await anrufe(K1)).length === 0, 'loeschen nimmt auch die Anrufe mit');
check((await kontakteLesen(K1)).length === 0 && (await su('select count(*)::int as n from bildschirmzeit_kontakt where geraet = $1', [sha(K1)]))[0].n === 0, '… und die Kontakte');
check(await anrufeLoeschen(K1) === false, 'danach: anrufe_loeschen → false');
check(await melden(K1, 'workout', { [HEUTE]: 400 }) === false, 'nach dem Löschen: melden → false (die Web-App trennt sich dann)');
check((await lesen(K2, HEUTE, HEUTE)).length === 1, 'die andere Kennung behält ihre Zahlen');

// --- Eingespielt über den Stand vor dem 19.11.2026 (so wie live): die Tabelle schon da, mit der alten Liste ---
{
  const alt = await PGlite.create();
  await alt.exec(ROLLEN);
  await alt.exec(`set role besitzer;
    create table public.bildschirmzeit_geraet (geraet text primary key, angelegt timestamptz not null default now(), gelesen timestamptz);
    create table public.bildschirmzeit (
      geraet text not null references public.bildschirmzeit_geraet (geraet) on delete cascade,
      app text not null check (app in ('boxenstopp', 'freizeit', 'workout')),
      tag date not null,
      sekunden integer not null check (sekunden between 0 and 86400),
      aktualisiert timestamptz not null default now(),
      primary key (geraet, app, tag)
    );
    insert into public.bildschirmzeit_geraet (geraet) values ('${sha(K1)}');
    insert into public.bildschirmzeit (geraet, app, tag, sekunden) values ('${sha(K1)}', 'workout', current_date, 99);
    reset role;`);
  await alt.exec('set role besitzer');
  try { await alt.exec(SQL); } finally { await alt.exec('reset role'); }
  await alt.exec('set role anon');
  let r;
  try {
    r = (await alt.query('select bildschirmzeit_melden(p_kennung => $1, p_app => $2, p_tage => $3::text::jsonb) as r',
      [K1, 'bewerbungszentrale', JSON.stringify({ [HEUTE]: 7 })])).rows[0].r;
  } finally { await alt.exec('reset role'); }
  check(r === true, 'über die alte Tabelle eingespielt: die Bewerbungszentrale darf melden');
  const da = (await alt.query('select app, sekunden from public.bildschirmzeit order by app')).rows.map((z) => [z.app, z.sekunden]);
  check(gleich(da, [['bewerbungszentrale', 7], ['workout', 99]]), `… und die alten Zahlen bleiben (${JSON.stringify(da)})`);
  const p = (await alt.query(`select pg_get_constraintdef(oid) as d from pg_constraint
    where conrelid = 'public.bildschirmzeit'::regclass and contype = 'c' and pg_get_constraintdef(oid) like '%app%'`)).rows;
  check(p.length === 1 && p[0].d.includes('bewerbungszentrale'), '… mit genau einer Prüfung der Apps, der neuen');
}

console.log(`\n${oks} OK, ${fails} FAIL`);
process.exit(fails ? 1 : 0);
