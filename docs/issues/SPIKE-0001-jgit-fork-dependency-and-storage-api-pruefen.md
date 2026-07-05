# SPIKE-0001: JGit-Fork-Abhängigkeit und Storage-API prüfen

## Ziel

Prüfen, ob `jgit-storage-hibernate` als eigenständiges Modul gegen eine veröffentlichte JGit-Version gebaut werden kann, ohne den kompletten `carstenartur/jgit`-Fork als Laufzeitbasis zu übernehmen.

## Hintergrund

Die bisherige Hibernate-Storage-Arbeit liegt im JGit-Fork und nutzt DFS-/Reftable-Internals. Dieses Repository soll diese Arbeit konsolidieren und hinter einer stabilen öffentlichen API kapseln.

Konsumierende Anwendungen wie Audio Analyzer, Taxonomy und Sandbox dürfen nicht direkt von `org.eclipse.jgit.internal.*` abhängen.

## Aufgaben

- [ ] Minimalen H2-basierten Hibernate-Store implementieren oder aus dem Fork extrahieren.
- [ ] Gegen eine veröffentlichte JGit-Version bauen.
- [ ] Alle Importe aus `org.eclipse.jgit.internal.*` dokumentieren.
- [ ] Repository öffnen/anlegen.
- [ ] Blob, Tree und Commit über JGit API schreiben.
- [ ] Branch-Ref atomar aktualisieren.
- [ ] Repository schließen und erneut öffnen.
- [ ] Commit und Blob wieder lesen.
- [ ] Reflog-Verhalten prüfen.
- [ ] Ergebnis als Entscheidung dokumentieren: upstream JGit reicht / minimaler Fork nötig.

## Akzeptanzkriterien

- `mvn verify` läuft lokal und in CI.
- Die öffentliche API exponiert keine JGit-Internal-Typen.
- Ein H2-Integrationstest beweist den grundlegenden Repository-Lebenszyklus.
- Es gibt eine klare Liste der verbleibenden technischen Risiken.

## Nicht-Ziele

- Kein Audio-Workflow-Modell.
- Kein Web-Editor.
- Keine vollständige Datenbank-Matrix.
- Kein Git-Server.
