# Bildschirmzeit

> „Ich will ein eigenes Bildschirmzeit-Widget für meinen Startbildschirm
> (Samsung Galaxy S24), weil Androids Bildschirmzeit meine Web-Apps
> Boxenstopp, Freizeit und Workout nur als „Chrome“ zählt.“
>
> „Push, Daten und alles andere in den drei Apps müssen weiter funktionieren
> wie bisher.“ – Tobi

Eine kleine Android-App mit Widget: Ring mit der Bildschirmzeit von heute,
daneben die Top 3 – und Boxenstopp, Freizeit und Workout stehen dort unter
eigenem Namen und mit eigenem Symbol statt als „Chrome“.

![Widget 4×2, hell](docs/vorschau-gross-hell.png)
![Widget 4×2, dunkel](docs/vorschau-gross-dunkel.png)

Weitere Vorschauen (mit erfundenen Zahlen, von `VorschauTest` und
`AppVorschauTest` gezeichnet): `docs/vorschau-klein-*.png` (2×2, nur der
Ring), `vorschau-lang-hell.png` (3×2, lange Zeiten, ohne Netz),
`vorschau-flach-hell.png`, `vorschau-einrichten-hell.png` (ohne Erlaubnis),
`vorschau-app-hell.png` / `-dunkel.png` (der App-Bildschirm).

## Tobis Entscheidungen

- Repo öffentlich, deshalb liegt auch der Signaturschlüssel offen im Repo
  (Risiko ist erklärt, unten „Signaturschlüssel“).
- Der Ring zeigt die Anteile der Apps: voller Ring = Gesamtzeit heute, Top 3
  farbig (blau, grün, orange), der Rest grau.
- Verbunden wird aus der Widget-App (je App ein Knopf „Verbinden“); die
  Web-Apps zeigen nur „verbunden · Trennen“.

## Einrichten auf dem S24

Einmal, alles am Handy:

1. In Chrome diesen Link öffnen:
   https://github.com/Finanzdienste/Bildschirmzeit/releases/latest/download/bildschirmzeit.apk
   → „Herunterladen“ (fragt Chrome „Datei trotzdem herunterladen?“, auch
   „Herunterladen“).
2. Unten „Öffnen“ antippen (oder die Mitteilung „Download abgeschlossen“).
   Beim ersten Mal kommt „Aus Sicherheitsgründen … unbekannte Apps …“ →
   „Einstellungen“ → „Diese Quelle zulassen“ für Chrome einschalten →
   zurück → „Installieren“.
3. Lässt One UI gar nicht installieren („Automatische Sperre“): Einstellungen
   → Sicherheit und Datenschutz → Automatische Sperre → aus, installieren,
   danach wieder einschalten.
4. Warnt Play Protect („Unsichere App blockiert“ o. Ä.): „Weitere Details“ →
   „Trotzdem installieren“. Fragt es nach einem Scan, ist beides in Ordnung.
5. „Bildschirmzeit“ öffnen. **Schritt 1** „Erlauben“ → in der Liste
   „Bildschirmzeit“ → Schalter ein → zurück. Ist der Schalter grau: in der
   App „App-Info öffnen“ → oben rechts ⋮ → „Eingeschränkte Einstellungen
   zulassen“ → dann noch einmal „Erlauben“.
6. **Schritt 2** – dreimal: bei Boxenstopp „Verbinden“ → die Web-App öffnet
   sich und meldet „Bildschirmzeit verbunden …“ → zurückwischen zur
   Bildschirmzeit-App. Dasselbe für Freizeit und Workout. Danach steht dort
   „heute … Min., zuletzt 21:40“.
7. **Schritt 3** „Widget hinzufügen“ → „Hinzufügen“. Von Hand:
   Startbildschirm lange drücken → „Widgets“ → „Bildschirmzeit“ → antippen →
   „Hinzufügen“. Größe ändern: Widget lange drücken, an den Rändern ziehen
   (2×2 zeigt nur den Ring).
8. Empfohlen, damit es alle 15 Minuten neu rechnet: Einstellungen → Apps →
   Bildschirmzeit → Akku → „Nicht eingeschränkt“. Antippen des Widgets
   rechnet immer sofort.
9. **Updates:** Gibt es eine neue Fassung, steht in der App unten „Neue
   Version verfügbar“ → „Herunterladen“ → Datei öffnen → „Aktualisieren“.
   Einstellungen, Verbindung und Widget bleiben.

## Wie gerechnet wird

**Android** (`Nutzung.kt`, getestet in `NutzungTest`): Vordergrundzeit heute,
von lokaler Mitternacht bis jetzt, aus `UsageStatsManager.queryEvents`.

- Abgefragt wird ab 8 Stunden vor Mitternacht, damit eine App, die über
  Mitternacht offen war, ab Mitternacht zählt; gezählt wird nur [Mitternacht,
  jetzt].
- Eine App ist vorn, solange eine ihrer Activities `ACTIVITY_RESUMED` hat und
  noch kein `PAUSED`/`STOPPED`. Zwei Activities derselben App zählen einmal.
- `SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN` und `DEVICE_SHUTDOWN` schließen
  alles. Nach `DEVICE_STARTUP` ohne vorheriges Ausschalten (Akku leer) zählt
  das Offene nicht – wie lange es lief, weiß niemand.
- Nicht gezählt: jede HOME-Activity (Startbildschirm, Übersicht, auch der
  Notfall-Startbildschirm der Einstellungen), `com.android.systemui` und Pakete
  ohne Eintrag im App-Drawer. Die Bildschirmzeit-App selbst zählt mit. Sichtbar
  sind die Pakete über `<queries>` (MAIN/LAUNCHER, MAIN/HOME), nicht über
  `QUERIES_ALL_PACKAGES`.
- Installierte Web-Apps (Pakete `org.chromium.webapk.*`) sind nur ein
  Startknopf für Chrome; ihre Sekunden zählen zu Chrome.

**Chrome kürzen** (`Aufteilung.kt`, `AufteilungTest`): Die Widget-App liest
mit `bildschirmzeit_lesen` die letzten acht Tage und nimmt für heute, was jede
Web-App gemeldet hat. Das wird von Chrome abgezogen und als „Boxenstopp“,
„Freizeit“, „Workout“ gezeigt. Haben die drei zusammen mehr gemeldet, als
Chrome lief, werden sie anteilig auf Chromes Zeit gekürzt (Rest nach größtem
Bruchteil) – die Gesamtzeit ist also immer die von Android. Was von Chrome
übrig bleibt, steht als „Chrome“ da.

**Ohne Netz:** Es gilt, was zuletzt von Supabase kam (SharedPreferences), und
das Widget schreibt leise „Stand 21:40 · ohne Netz“ (nur wenn mindestens eine
Web-App verbunden ist).

**Wann:** WorkManager alle 15 Minuten (unique, KEEP; eingeplant beim ersten
Widget, beim App-Start; nach einem Neustart plant WorkManager selbst neu),
dazu `updatePeriodMillis` 30 Minuten als Rückfallebene. Antippen schickt
einen Broadcast an das Widget, das gleich dort rechnet (`goAsync`, eigener
Faden): erst ohne Netz zeichnen, dann mit. Eine Sperre verhindert zwei
Rechnungen zugleich; „Alle Daten löschen“ läuft unter derselben Sperre.

**Warum Samsungs Zahl um Minuten abweichen kann:** Samsungs Digital Wellbeing
rechnet selbst und anders (eigene Zusammenfassung der Nutzungsdaten, zählt
z. B. Telefonate oder Apps ohne Symbol im App-Drawer, die hier fehlen, und
lässt dafür anderes weg; geteilter Bildschirm und Bild-im-Bild werden anders
gewertet). Das Widget zeigt den Stand seiner letzten Rechnung (bis zu 15
Minuten alt, Antippen holt nach). Die Web-Apps messen nur, solange sie
sichtbar sind, und melden alle 5 Minuten und beim Wegschalten – was seit der
letzten Meldung dazukam, steht bis dahin noch bei Chrome.

## Der Vertrag zwischen Widget-App, Supabase und Web-Apps

**Supabase** – Projekt `vjyohppohmvhhxgwouti` („Finanzdienste's Project“),
Quelle `supabase/bildschirmzeit.sql` (live als Migration „bildschirmzeit“),
geprüft von `tests/test-sql.mjs` (PGlite). URL
`https://vjyohppohmvhhxgwouti.supabase.co`, öffentlicher Schlüssel
`sb_publishable_wTUNShIKMFjCOmZzQuuN-A_vFHPkKUI` – nur als Kopf `apikey`,
kein `Authorization` (es ist kein JWT).

| Funktion (`POST /rest/v1/rpc/…`) | Wer | Eingabe | Antwort |
|---|---|---|---|
| `bildschirmzeit_anmelden` | Widget-App | `p_kennung` | `true` |
| `bildschirmzeit_melden` | Web-Apps | `p_kennung`, `p_app` (`boxenstopp`/`freizeit`/`workout`), `p_tage` `{"JJJJ-MM-TT": sekunden}` | `true`; `false` = Kennung nicht (mehr) angemeldet |
| `bildschirmzeit_lesen` | Widget-App | `p_kennung`, `p_von`, `p_bis` (≤ 62 Tage) | `[{app, tag, sekunden, aktualisiert}]` |
| `bildschirmzeit_loeschen` | Widget-App | `p_kennung` | `true`/`false`; danach meldet `melden` `false` |

Je Tag gilt der größere Wert (Tagesstand, kein Zuwachs). Tage außerhalb
heute − 8 … heute + 1 (UTC) fallen still weg, mehr als 10 Tage auf einmal
sind ein Fehler. Gespeichert wird nur der SHA-256 der Kennung.

**Kennung:** 32 zufällige Byte (SecureRandom) als base64url ohne „=“, 43
Zeichen (`^[A-Za-z0-9_-]{32,128}$`), einmal von der Widget-App erzeugt und in
ihren SharedPreferences gehalten. Geheim: nie loggen, nie in Commits oder
Tests mit echten Werten. Sie geht weder in die Cloud-Sicherung noch beim
Handywechsel mit (`datenregeln.xml`) – auf einem neuen Handy einfach neu
verbinden.

**Verbinden-Link** (öffnet die Web-App, die sich dann an die Kennung bindet):
`https://finanzdienste.github.io/Boxenstopp/#bildschirmzeit=<Kennung>`,
dasselbe mit `/Freizeit/` und `/Workout/`. Alle drei liegen auf
`finanzdienste.github.io` und teilen sich den localStorage. Vor dem Öffnen
stellt die App sicher, dass `bildschirmzeit_anmelden` durch ist.

**Mess-Modul der Web-Apps:** `web/bildschirmzeit.js`, geprüft von
`tests/test-web.mjs` (ohne Browser). Boxenstopp, Freizeit und Workout haben je
eine **byte-gleiche** Kopie als `js/bildschirmzeit.js` – geändert wird nur
hier, dann in alle drei kopiert (dort jeweils die Service-Worker-Version
hochzählen und die Browsertests laufen lassen). Ohne Link ist es völlig
still. Mit Link misst es nur sichtbare Zeit (Takt 15 s, Lücke höchstens
60 s), teilt an Mitternacht, schickt alle 5 Minuten, beim Zurückkommen und
beim Wegschalten (keepalive), gleich nach dem Verbinden auch heute = 0 –
daran sieht die Widget-App „verbunden“. Antwortet Supabase `false`, trennt es
sich selbst („Bildschirmzeit getrennt: …“). API: `bildschirmzeitStarten(name,
{ meldung, verstecken })`, `bildschirmzeitStand()`, `bildschirmzeitTrennen()`,
`bildschirmzeitSenden({ keepalive })`, `bildschirmzeitVerstecken()`,
`dauerText(sek)` (wortgleich mit `dauerText` in `Texte.kt`), `BZ_EREIGNIS`.

**„Alle Daten löschen“** (unten in der App, mit Rückfrage):
`bildschirmzeit_loeschen`, dann eine neue Kennung (gleich angemeldet). Die
Web-Apps bekommen beim nächsten Melden `false` und trennen sich von selbst;
danach kann man sie neu verbinden. „verbunden“ heißt in der App: in den
letzten acht Tagen gemeldet – wer in einer Web-App „Trennen“ drückt, steht
deshalb noch bis zu acht Tage als verbunden da (die Zahlen bleiben bei
Supabase).

## Signaturschlüssel – offen im Repo

`android/signatur/bildschirmzeit.p12` (PKCS12, Alias `bildschirmzeit`,
Passwort `bildschirmzeit-oeffentlich`, RSA 4096, gültig bis 2126,
SHA-256 `BC:6F:91:B9:8B:B1:76:7F:93:D5:F1:4F:9C:EA:F8:6C:A6:35:5F:61:59:5C:CE:62:1E:65:10:3C:3B:AA:75:9E`).
Passwort und Pfad stehen in `android/app/build.gradle.kts`.

**Warum:** Android installiert ein Update nur über die alte App, wenn beide
mit demselben Schlüssel signiert sind. Tobi hat keinen PC; gebaut wird in
GitHub Actions, und so braucht der Bau keine Geheimnisse (keine Secrets, die
verloren gehen oder in einer neuen Sitzung fehlen) – jede Fassung lässt sich
über die vorige installieren.

**Was das bedeutet:** Jeder kann eine APK bauen, die Android als „Update“ von
Bildschirmzeit annimmt. Schaden kann sie nur, wenn Tobi sie selbst
installiert – deshalb nur über den Link oben oder „Neue Version verfügbar“
in der App aktualisieren (beide zeigen auf die Releases dieses Repos). Die
App hat nur Nutzungsdaten und Internet; eine untergeschobene Fassung könnte
diese Nutzungsdaten lesen und die Kennung (also die Minuten der drei
Web-Apps) kennen. Wird der Schlüssel je getauscht, muss Tobi die App einmal
deinstallieren und neu installieren (neue Kennung, neu verbinden).

## Aufbau

```
gradlew, gradle/wrapper/     Gradle-Wrapper (9.9.1) – immer mit -p android
android/                     Gradle-Projekt (Kotlin DSL)
  gradle/libs.versions.toml  alle Versionen an einer Stelle
  signatur/bildschirmzeit.p12  der offene Signaturschlüssel
  app/src/main/kotlin/de/finanzdienste/bildschirmzeit/
    Nutzung.kt               die Rechnung über Androids Ereignisse (rein)
    Aufteilung.kt            Chrome kürzen, die drei Web-Apps (rein)
    Texte.kt                 Zeiten, Kennung (rein)
    Ring.kt                  Ring-Geometrie und -Bild
    Nutzungsdaten.kt         UsageStatsManager, Launcher, HOME, Erlaubnis
    Netz.kt                  HttpURLConnection + org.json, Supabase, Zeilen
    Speicher.kt              SharedPreferences: Kennung, Zwischenspeicher
    Rechner.kt               setzt alles zu einem Ergebnis zusammen
    WidgetAnsicht.kt         RemoteViews: 4×2, 2×2, „Tippen zum Einrichten“
    Aktualisierung.kt        WorkManager, Widget-Empfänger, Sperre
    NeueVersion.kt           GitHub releases/latest
    MainActivity.kt          der App-Bildschirm
  app/src/test/…             JUnit + Robolectric (Vorschau mit nativer Grafik)
docs/vorschau-*.png          gezeichnet von den Tests
supabase/bildschirmzeit.sql  die vier Funktionen
web/bildschirmzeit.js        das Mess-Modul der drei Web-Apps
tests/                       test-sql.mjs (PGlite), test-web.mjs
.github/workflows/apk.yml    pruefen → apk → Release v<Laufnummer>
```

Versionen: Android-Gradle-Plugin 9.5.1 (mit eingebautem Kotlin), Kotlin
2.4.21, Gradle 9.9.1, compileSdk 37, targetSdk 36 (Android 16, was das S24
hat; 37 erst nach einer Prüfung auf dem Gerät), minSdk 29, JDK 21 (Ziel 17).
Abhängigkeiten: androidx.core 1.19.1, androidx.work 2.12.0; Tests JUnit
4.13.2, Robolectric 4.18. Die Release-APK ist mit R8 verkleinert (rund
0,75 MB).

## Bauen und testen

GitHub Actions (`apk.yml`, bei jedem Push auf `main` und von Hand): Job
`pruefen` (`npm ci`, `node tests/test-sql.mjs`, `node tests/test-web.mjs`),
dann `apk` (`./gradlew -p android testReleaseUnitTest
assembleRelease`, `apksigner verify`, Artefakt `bildschirmzeit-apk`, Release
`v<Laufnummer>` mit `bildschirmzeit.apk` als „latest“; dazu das Artefakt
`vorschau` mit den neu gezeichneten Bildern und Testberichten). Die
Laufnummer ist der `versionCode`, `versionName` ist `1.<Laufnummer>`.

Lokal (oder in einer Cloud-Sitzung):

```sh
npm ci && node tests/test-sql.mjs && node tests/test-web.mjs

# einmal: Android-SDK nach ~/.android-sdk
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
mkdir -p ~/.android-sdk/cmdline-tools && unzip -q commandlinetools-*.zip -d ~/.android-sdk/cmdline-tools
mv ~/.android-sdk/cmdline-tools/cmdline-tools ~/.android-sdk/cmdline-tools/latest
yes | ~/.android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
~/.android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.0" "build-tools;36.0.0"
echo "sdk.dir=$HOME/.android-sdk" > android/local.properties

./gradlew -p android testReleaseUnitTest assembleRelease
~/.android-sdk/build-tools/36.0.0/apksigner verify --verbose android/app/build/outputs/apk/release/app-release.apk
```

Die Tests schreiben die Vorschau neu nach `docs/` – nach einer Änderung am
Widget die Bilder ansehen und mit committen. Die Bilder für die
Widget-Auswahl (`res/drawable-nodpi/widget_vorschau.png`, `-night-`) kommen
aus `android/app/build/vorschau/` und werden von Hand kopiert. Kein Test
spricht mit dem echten Supabase oder GitHub (`Netz.gesperrt`).

## Stand

- 14.11.2026, Fassung 1.1 (lokal gebaut): App, Widget, Tests (66 JUnit-/
  Robolectric-Prüfungen, 47 SQL, 61 Web-Modul), Workflow. Supabase ist live
  eingespielt und geprüft. Das Repo liegt noch nicht auf GitHub – der erste
  Push auf `main` baut Release v1; vorher führt der APK-Link oben ins Leere.
- Noch nicht auf dem S24 ausprobiert: Größen des Startbildschirms, Akku-
  Verhalten von One UI, ob „Nutzungsdatenzugriff“ direkt die Seite der App
  öffnet.
