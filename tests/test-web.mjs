/*
 * Das Mess-Modul der Web-Apps (web/bildschirmzeit.js) ohne Browser:
 *
 *     node tests/test-web.mjs
 *
 * window, document, location, history, localStorage, fetch und die Uhr sind
 * hier nachgebaut – gerade so viel, wie das Modul benutzt. Die Uhr steht,
 * bis der Test sie weiterdreht; setInterval und setTimeout laufen nur, wenn
 * der Test sie auslöst. Die Browsertests in Boxenstopp, Freizeit und Workout
 * prüfen dieselbe Datei noch einmal im echten Chromium.
 */
import { isDeepStrictEqual as gleich } from 'node:util';

let fails = 0;
let oks = 0;
const check = (c, m) => {
  console.log(`${c ? 'OK  ' : 'FAIL'} ${m}`);
  if (c) oks++; else fails++;
};

// --- Nachbau ---
let jetzt = new Date(2026, 10, 14, 20, 0, 0).getTime(); // 14.11.2026, 20:00 Ortszeit
const echtesNow = Date.now;
Date.now = () => jetzt;
const weiter = (ms) => { jetzt += ms; };

const lauscher = { window: {}, document: {} };
const an = (wo) => (typ, f) => { (lauscher[wo][typ] ||= []).push(f); };
const feuern = (wo, typ) => { for (const f of lauscher[wo][typ] || []) f({ type: typ }); };
const ereignisse = [];
globalThis.window = {
  addEventListener: an('window'),
  dispatchEvent: (e) => { ereignisse.push(e); return true; },
};
globalThis.CustomEvent = class { constructor(type, init) { this.type = type; this.detail = init?.detail; } };
globalThis.document = { visibilityState: 'visible', addEventListener: an('document') };
globalThis.location = { hash: '', pathname: '/Workout/', search: '' };
const ersetzt = [];
globalThis.history = { state: { x: 1 }, replaceState: (state, _t, url) => { ersetzt.push({ state, url }); location.hash = ''; } };
const speicher = new Map();
globalThis.localStorage = {
  getItem: (k) => (speicher.has(k) ? speicher.get(k) : null),
  setItem: (k, v) => { speicher.set(k, String(v)); },
  removeItem: (k) => { speicher.delete(k); },
};
let intervalle = [];
globalThis.setInterval = (f) => { intervalle.push(f); return intervalle.length; };
globalThis.clearInterval = (id) => { intervalle[id - 1] = null; };
let wecker = [];
globalThis.setTimeout = (f, ms) => { wecker.push({ f, ms }); return wecker.length; };
globalThis.clearTimeout = (id) => { if (wecker[id - 1]) wecker[id - 1].f = null; };
const sofortige = () => { for (const w of wecker) if (w.f && w.ms === 0) { const f = w.f; w.f = null; f(); } };
const takt = () => { for (const f of intervalle) if (f) f(); };
const laeuftUhr = () => intervalle.some(Boolean);

const anfragen = [];
let antwort = () => ({ ok: true, status: 200, json: async () => true });
globalThis.fetch = async (url, init) => {
  anfragen.push({ url, init, body: JSON.parse(init.body) });
  return antwort();
};
const ruhe = () => new Promise((r) => setImmediate(r));

const bz = await import('../web/bildschirmzeit.js');
const K = 'Kx9_-'.repeat(8) + 'abc'; // 43 Zeichen
const K2 = 'Zz'.repeat(22);
const zustand = () => JSON.parse(speicher.get('bildschirmzeit.workout') || 'null');
const HEUTE = bz.tagVon(jetzt);

// --- Reine Helfer ---
check(HEUTE === '2026-11-14', `tagVon: Ortszeit (${HEUTE})`);
const mn = new Date(2026, 10, 14, 23, 59, 30).getTime();
check(gleich(bz.verteilen(mn, mn + 60000), { '2026-11-14': 30, '2026-11-15': 30 }), 'verteilen teilt an Mitternacht');
check(gleich(bz.verteilen(mn, mn), {}), 'verteilen: nichts von a bis a');
const sommer = new Date(2026, 2, 28, 23, 0, 0).getTime(); // Nacht der Zeitumstellung (29.3.)
const zwei = bz.verteilen(sommer, sommer + 3 * 3600000);
check(zwei['2026-03-28'] === 3600 && zwei['2026-03-29'] === 7200, `verteilen über die Zeitumstellung (${JSON.stringify(zwei)})`);
check(bz.kennungAus(`#bildschirmzeit=${K}`) === K, 'kennungAus: #bildschirmzeit=…');
check(bz.kennungAus(`#a=1&bildschirmzeit=${K}&b=2`) === K, 'kennungAus: mitten zwischen anderen Teilen');
check(bz.kennungAus('#bildschirmzeit=kurz') === '', 'kennungAus: zu kurz → leer');
check(bz.kennungAus(`#bildschirmzeit=${K}!`) === '', 'kennungAus: fremdes Zeichen am Ende → leer');
check(bz.kennungAus('') === '' && bz.kennungAus(undefined) === '', 'kennungAus: leer bleibt leer');
check(bz.kennungAus(`#xbildschirmzeit=${K}`) === '', 'kennungAus: nur der ganze Name zählt');
check([0, 59, 60, 61 * 60, 3600, 3660, 2 * 3600 + 15 * 60].map(bz.dauerText).join(' | ')
  === 'unter 1 Min. | unter 1 Min. | 1 Min. | 1 Std. 1 Min. | 1 Std. | 1 Std. 1 Min. | 2 Std. 15 Min.', 'dauerText wie das Widget');

// --- Ohne Link bleibt alles still ---
const meldungen = [];
const meldung = (t) => meldungen.push(t);
check(bz.bildschirmzeitStarten('workout', { meldung }) === false, 'ohne Link: Starten → false');
weiter(60000); takt(); feuern('document', 'visibilitychange');
document.visibilityState = 'hidden'; feuern('document', 'visibilitychange'); feuern('window', 'pagehide');
document.visibilityState = 'visible'; feuern('document', 'visibilitychange');
await ruhe();
check(anfragen.length === 0, 'ohne Link: keine einzige Anfrage');
check(speicher.size === 0, 'ohne Link: nichts im Speicher');
check(!laeuftUhr(), 'ohne Link: keine Uhr läuft');
check(bz.bildschirmzeitStand() === null, 'ohne Link: Stand → null');
let fehlerGeworfen = false;
try { bz.bildschirmzeitStarten('chrome'); } catch { fehlerGeworfen = true; }
check(fehlerGeworfen, 'unbekannte App → Fehler beim Starten');
bz.bildschirmzeitStarten('workout', { meldung });

// --- Verbinden über hashchange ---
location.hash = `#bildschirmzeit=${K}`;
feuern('window', 'hashchange');
await ruhe();
check(location.hash === '' && ersetzt.at(-1)?.url === '/Workout/' && gleich(ersetzt.at(-1).state, { x: 1 }),
  'der Link verschwindet aus der Adresse, history.state bleibt');
let z = zustand();
check(z?.kennung === K && z.v === 1 && typeof z.seit === 'string', 'Kennung gespeichert unter bildschirmzeit.workout');
check(anfragen.length === 1, 'gleich nach dem Verbinden geht eine Meldung los');
const erste = anfragen[0];
check(erste.url === 'https://vjyohppohmvhhxgwouti.supabase.co/rest/v1/rpc/bildschirmzeit_melden', 'an bildschirmzeit_melden');
check(gleich(erste.body, { p_kennung: K, p_app: 'workout', p_tage: { [HEUTE]: 0 } }), `mit heute = 0 (${JSON.stringify(erste.body.p_tage)})`);
check(erste.init.headers.apikey.startsWith('sb_publishable_') && erste.init.headers['Content-Type'] === 'application/json' && !erste.init.headers.Authorization,
  'Kopf: apikey und Content-Type, kein Authorization');
check(!erste.init.keepalive, 'ohne keepalive, solange die App offen ist');
check(meldungen.length === 0, 'die Meldung kommt nicht im selben Takt …');
sofortige();
check(/verbunden/.test(meldungen[0] || ''), `… sondern gleich danach („${meldungen[0]}“)`);
z = zustand();
check(z.gemeldet[HEUTE] === 0 && z.zuletzt && z.fehler === '', 'bestätigt: gemeldet heute = 0, zuletzt gesetzt');
check(laeuftUhr(), 'die Uhr läuft, weil die Seite sichtbar ist');
check(ereignisse.some((e) => e.type === 'bildschirmzeit' && e.detail?.heute === 0), 'Ereignis „bildschirmzeit“ mit dem Stand');

// --- Messen ---
for (let i = 0; i < 8; i++) { weiter(15000); takt(); }
z = zustand();
check(Math.round(z.tage[HEUTE]) === 120, `acht Takte à 15 s → 120 s (${z.tage[HEUTE]})`);
check(anfragen.length === 1, 'nach zwei Minuten noch keine weitere Meldung (alle fünf Minuten)');
weiter(10 * 60000); takt();
z = zustand();
check(Math.round(z.tage[HEUTE]) === 180, `eine Lücke von zehn Minuten zählt höchstens 60 s (${z.tage[HEUTE]})`);
await ruhe();
check(anfragen.length === 2 && anfragen[1].body.p_tage[HEUTE] === 180, 'nach fünf Minuten geht der Stand von selbst los (180)');
check(bz.bildschirmzeitStand().heute === 180 && bz.bildschirmzeitStand().gemeldet === 180, 'Stand: heute 180, gemeldet 180');

// --- Wegschalten ---
weiter(15000); takt(); weiter(5000);
document.visibilityState = 'hidden';
feuern('document', 'visibilitychange');
feuern('window', 'pagehide');
await ruhe();
check(!laeuftUhr(), 'versteckt: die Uhr steht');
check(anfragen.length === 3, `versteckt: genau eine Meldung, obwohl visibilitychange und pagehide kamen (${anfragen.length - 2})`);
check(anfragen[2].init.keepalive === true && anfragen[2].body.p_tage[HEUTE] === 200, 'mit keepalive und dem Stand bis zum Wegschalten (200)');
weiter(3600000);
document.visibilityState = 'visible';
feuern('document', 'visibilitychange');
weiter(15000); takt();
await ruhe(); // der Takt hat nach einer Stunde Pause gleich gemeldet
z = zustand();
check(Math.round(z.tage[HEUTE]) === 215 && z.gemeldet[HEUTE] === 215, `die Stunde im Hintergrund zählt nicht (${z.tage[HEUTE]})`);

// --- Fehler vom Server ---
antwort = () => ({ ok: false, status: 503, json: async () => ({}) });
weiter(15000); takt();
const r1 = await bz.bildschirmzeitSenden();
check(r1.ok === false && r1.grund === 'Fehler 503', 'Server 503 → { ok: false, grund: „Fehler 503“ }');
check(zustand().fehler === 'Fehler 503' && zustand().gemeldet[HEUTE] === 215, 'der Fehler steht im Stand, gemeldet bleibt beim alten Wert (215)');
antwort = () => { throw new TypeError('Failed to fetch'); };
weiter(15000); takt();
const r2 = await bz.bildschirmzeitSenden();
check(r2.grund === 'kein Netz' && zustand().fehler === 'kein Netz', 'ohne Netz → „kein Netz“');
antwort = () => ({ ok: true, status: 200, json: async () => true });
weiter(15000); takt();
const r3 = await bz.bildschirmzeitSenden();
check(r3.ok && zustand().fehler === '' && zustand().gemeldet[HEUTE] > 200, 'beim nächsten Mal geht es durch, der Fehler ist weg');
weiter(1000); takt();
check((await bz.bildschirmzeitSenden()).grund === 'gerade erst', 'höchstens alle zehn Sekunden, außer beim Wegschalten');

// Eine späte Antwort überschreibt nicht, was eine neuere Anfrage ergab.
let spaet;
antwort = () => new Promise((r) => { spaet = r; });
weiter(15000); takt(); weiter(15000); takt();
const langsam = bz.bildschirmzeitSenden({ keepalive: true });
antwort = () => ({ ok: false, status: 500, json: async () => ({}) });
weiter(15000); takt();
await bz.bildschirmzeitSenden({ keepalive: true });
spaet({ ok: true, status: 200, json: async () => true });
await langsam;
check(zustand().fehler === 'Fehler 500', `eine späte Erfolgsmeldung löscht den neueren Fehler nicht (${zustand().fehler})`);
check(zustand().gemeldet[HEUTE] >= 245, '… übernimmt aber, was sie bestätigt hat');
antwort = () => ({ ok: true, status: 200, json: async () => true });

// --- Über Mitternacht ---
jetzt = new Date(2026, 10, 14, 23, 59, 50).getTime();
document.visibilityState = 'hidden'; feuern('document', 'visibilitychange');
document.visibilityState = 'visible'; feuern('document', 'visibilitychange');
weiter(15000); takt();
await ruhe();
z = zustand();
check(Math.round(z.tage['2026-11-15']) === 5, `nach Mitternacht zählt der neue Tag (${z.tage['2026-11-15']})`);
document.visibilityState = 'hidden'; feuern('document', 'visibilitychange');
await ruhe();
const letzte = anfragen.at(-1).body.p_tage;
check(Object.keys(letzte).sort().join() === '2026-11-14,2026-11-15', `beide Tage gehen mit (${JSON.stringify(letzte)})`);

// --- Alte Tage ---
const alt = zustand();
alt.tage['2026-11-01'] = 99;
alt.gemeldet['2026-11-01'] = 99;
speicher.set('bildschirmzeit.workout', JSON.stringify(alt));
document.visibilityState = 'visible'; feuern('document', 'visibilitychange');
weiter(15000); takt();
check(!('2026-11-01' in zustand().tage) && !('2026-11-01' in zustand().gemeldet), 'Tage älter als eine Woche verschwinden');

// --- Noch einmal derselbe Link ---
location.hash = `#bildschirmzeit=${K}`;
const vorLink = anfragen.length;
feuern('window', 'hashchange');
await ruhe();
check(zustand().kennung === K && anfragen.length === vorLink + 1, 'derselbe Link noch einmal: bleibt verbunden, meldet heute neu');

// --- Neue Kennung (Widget-App neu installiert) ---
const heuteVorher = Math.round(zustand().tage['2026-11-15']);
location.hash = `#bildschirmzeit=${K2}`;
feuern('window', 'hashchange');
await ruhe();
z = zustand();
check(z.kennung === K2 && Math.round(z.tage['2026-11-15']) === heuteVorher, 'neue Kennung: die schon gemessene Zeit von heute bleibt');
check(anfragen.at(-1).body.p_kennung === K2 && Object.keys(anfragen.at(-1).body.p_tage).length >= 2,
  '… und geht komplett an das neue Gerät');

// --- Server kennt die Kennung nicht mehr ---
antwort = () => ({ ok: true, status: 200, json: async () => false });
weiter(20000); takt();
meldungen.length = 0;
const r4 = await bz.bildschirmzeitSenden({ keepalive: true });
check(r4.grund === 'unbekannt' && zustand() === null, 'Antwort false → die App trennt sich, der Speicher ist leer');
check(/getrennt/.test(meldungen[0] || ''), `… und sagt es („${meldungen[0]}“)`);
check(!laeuftUhr(), '… und misst nicht mehr');
const nachher = anfragen.length;
weiter(60000);
document.visibilityState = 'hidden'; feuern('document', 'visibilitychange');
document.visibilityState = 'visible'; feuern('document', 'visibilitychange');
await ruhe();
check(anfragen.length === nachher, 'danach geht nichts mehr los');

// --- Start mit gespeicherter Verbindung (App neu geöffnet) ---
antwort = () => ({ ok: true, status: 200, json: async () => true });
speicher.set('bildschirmzeit.workout', JSON.stringify({ v: 1, kennung: K, seit: 'x', tage: { [bz.tagVon(jetzt)]: 42 }, gemeldet: {}, zuletzt: '', fehler: '' }));
weiter(20000);
check(bz.bildschirmzeitStarten('workout', { meldung }) === false, 'Start ohne Link, aber verbunden → false');
await ruhe();
check(anfragen.at(-1).body.p_tage[bz.tagVon(jetzt)] === 42, 'schickt gleich beim Start, was noch offen ist');
check(laeuftUhr(), '… und misst');

// --- Trennen ---
bz.bildschirmzeitTrennen();
check(zustand() === null && !laeuftUhr() && bz.bildschirmzeitStand() === null, 'Trennen: Speicher leer, Uhr steht, Stand null');

// --- Kaputter Speicher ---
speicher.set('bildschirmzeit.workout', '{kaputt');
check(bz.bildschirmzeitStand() === null, 'kaputter Speicher zählt als nicht verbunden');
speicher.set('bildschirmzeit.workout', JSON.stringify({ kennung: 'kurz' }));
check(bz.bildschirmzeitStand() === null, 'kaputte Kennung zählt als nicht verbunden');

Date.now = echtesNow;
console.log(`\n${oks} OK, ${fails} FAIL`);
process.exit(fails ? 1 : 0);
