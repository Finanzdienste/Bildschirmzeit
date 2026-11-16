# Hinweise für Claude

## Ultracode-Empfehlung
Bevor du mit einer Aufgabe anfängst: Wenn sie sich für einen Workflow eignet
(dieselbe Arbeit über viele Dateien, eine Prüfung über das ganze Projekt oder
eine Aufgabe, die zu groß für einen einzelnen Agenten ist), schlag mir in einem
Satz vor, sie mit „ultracode“ laufen zu lassen, und warte auf meine Antwort.
Bei normalen Aufgaben erwähnst du Ultracode nicht. Änderungen an Widget und
App sind normale Aufgaben: Am 15.11. liefen dafür Workflows, Tobi fragte
zweimal „Sicher dass wir ultracode hier noch brauchen?“ – zu teuer und zu
langsam für diese Größe. Ist Ultracode an, obwohl es nicht nötig ist, sag es
und arbeite ohne Workflows.

## Modell und Aufwand – immer konkret
Tobi, 15.11.: „Sowas sollst du immer konkret mit angeben“. Was läuft, zeigt
`get_session` (`model`, `effort_level`, `flag_settings.ultracode`).
- Am Ende jeder Antwort, mit der du eine Aufgabe abschließt (nicht bei
  Zwischenständen), ein Satz: was lief und ob es passte, etwa „Opus 5.5 mit
  `/effort max`, passt – nichts umstellen“. Tobi, 16.11.: „So eine nachricht
  gibst du mir irgendwie nur in diesen chat, nicht in den anderen“ – die Regel
  stand nur hier; jetzt gleich in Workout, Boxenstopp und Freizeit.
- Rätst du von etwas ab (Ultracode, ein anderes Modell), sag in einem Satz,
  was stattdessen gilt, mit dem genauen Befehl, z. B. „kein Ultracode – Opus
  mit `/effort max` reicht, läuft schon“.
- Mehr (vorschlagen, auf Antwort warten): `/model fable` mit `/effort high`
  nur, wenn eine Ursache nach dem ersten Durchgang unklar bleibt oder ein
  Fehler nach einer Korrektur wiederkommt. Fable kostet Nutzungsguthaben
  („Requires usage credits“), das gehört in den Vorschlag.
- Weniger (erledigen, am Ende ein Satz): Für reine Texte, README und Zahlen
  reicht `/effort high`; für eine einzelne schwere Frage dann „ultrathink“ in
  der Nachricht.
- Lieber den Aufwand senken als das Modell wechseln, und nicht hin und her.

## Das Projekt
Tobis Bildschirmzeit-Widget für sein Samsung Galaxy S24: eine kleine
Android-App (Kotlin, `android/`), die Androids Nutzungsdaten liest und die Zeit
von Boxenstopp, Freizeit und Workout von Chrome abzieht. Die drei Web-Apps
melden ihre Minuten an Supabase (`supabase/bildschirmzeit.sql`), über das
Modul `web/bildschirmzeit.js`. Alles Weitere – Rechnung, Vertrag, Einrichten,
Signatur, Bauen – steht in README.md: vor einer Änderung den passenden
Abschnitt lesen.

## Mit Tobi arbeiten
- Deutsch, knapp und direkt. Er hat keinen PC: alles läuft über GitHub
  Actions, er installiert die APK am Handy. Was er selbst tun muss, als genaue
  Klickfolge sagen.
- Öffentliches Repo: keine echten Namen anderer Leute, keine echte Kennung,
  keine Geheimnisse. Der Signaturschlüssel liegt mit Absicht offen im Repo
  (README „Signaturschlüssel“) – nie tauschen, sonst muss Tobi neu
  installieren und neu verbinden.

## Ändern, prüfen, ausliefern
1. Node-Teil: `npm ci && node tests/test-sql.mjs && node tests/test-web.mjs`.
   Android: `./gradlew -p android testReleaseUnitTest assembleRelease`
   (Android-SDK nach README „Bauen und testen“). Neues Verhalten bekommt einen
   Test, der gegen die alte Fassung rot ist.
2. Nach Änderungen am Widget die Bilder in `docs/` ansehen und mit
   committen; die Bilder für die Widget-Auswahl von Hand aus
   `android/app/build/vorschau/` kopieren.
3. `web/bildschirmzeit.js` ist die Quelle: nur hier ändern, dann byte-gleich
   als `js/bildschirmzeit.js` nach Boxenstopp, Freizeit und Workout kopieren –
   dort jeweils nach deren CLAUDE.md (Service-Worker-Version, Tests, README).
4. SQL: `supabase/bildschirmzeit.sql` ist die Quelle; nach grünem
   test-sql nur die geänderten Teile als Migration über den
   Supabase-Connector einspielen (Projekt vjyohppohmvhhxgwouti).
5. Push auf `main` baut Release `v<Laufnummer>` (der Link im README zeigt
   immer auf das neueste). Reine Doku-Commits mit „[skip ci]“, sonst
   installiert die App das Release ohne Änderung von selbst. Sonst darf die
   Zeichenfolge in keiner Commit-Nachricht stehen, auch nicht zitiert – GitHub
   baut dann nicht (so bei 1124751; dann „APK“ von Hand starten,
   workflow_dispatch). Danach prüfen: Lauf „APK“ grün, Release mit
   `bildschirmzeit.apk`.
6. Commit-Nachrichten deutsch; README „Stand“ nachziehen.
