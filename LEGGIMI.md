# Indici Meteo — app Android

App con gli indici meteorologici sempre aggiornati, scaricati direttamente dalle fonti ufficiali a ogni apertura (e, se vuoi, controllati in background con notifica quando esce un nuovo dato).

## Cosa contiene

**Teleconnessioni e indici climatici**
- NAO, AO, PNA, AAO giornalieri (ultimi 120 giorni) con previsione GEFS a 15 giorni (media, spread min–max, GFS) — NOAA CPC
- MJO: fase, ampiezza e diagramma di Wheeler-Hendon — Bureau of Meteorology australiano
- ENSO: ONI e anomalie settimanali Niño 1+2, 3, 3.4, 4 — NOAA CPC
- Pattern mensili: EA, SCA (Scandinavo), EA/WR, WP, EP/NP, POL, TNH — NOAA CPC
- QBO (30 hPa) e IOD (DMI) — NOAA PSL
- Stratosfera / vortice polare: collegamenti ai grafici CPC, NASA e FU Berlin

**Instabilità e temporali** (per qualsiasi località: ricerca o GPS)
- CAPE, CIN, Lifted Index, zero termico dal modello scelto (ICON, ICON-2I Italia, ECMWF, GFS, Météo-France, UKMO)
- K-Index, Total Totals, Showalter e shear calcolati dall'app
- Livello di rischio temporali giorno per giorno e panoramica di 20 città italiane
- Dati: Open-Meteo.com, aggiornati a ogni nuova corsa del modello

**Sole e meteo spaziale** — NOAA SWPC, NASA SDO, SOHO
- Kp osservato e previsto (scala G1–G5), vento solare (velocità, densità, Bz), raggi X / brillamenti
- Ciclo solare: macchie (SSN), F10.7 mensile e giornaliero, previsione NOAA
- Immagini del Sole in tempo reale

## Come ottenere l'APK (gratis, con GitHub)

1. Crea un account su https://github.com (se non ce l'hai).
2. In alto a destra **+ → New repository**. Dagli un nome (es. `indici-meteo`), lascialo **Private** e premi **Create repository**.
3. Nella pagina del repository vuoto clicca **uploading an existing file**.
4. Estrai lo zip sul computer e trascina **tutto il contenuto** della cartella `IndiciMeteo` nella pagina (non la cartella stessa, ma quello che c'è dentro). Premi **Commit changes**.
5. Apri la scheda **Actions**: parte da sola la compilazione “Compila APK” (circa 3–5 minuti). Se ti chiede di abilitare le Actions, conferma.
6. Quando diventa verde, apri la pagina principale del repository → **Releases** (colonna a destra) → scarica **IndiciMeteo.apk**.
7. Dal telefono apri il file APK e conferma l'installazione (la prima volta Android chiede di consentire l'installazione da “origini sconosciute” per il browser o per l'app File).

> **Se la scheda Actions resta vuota:** su Mac e su alcuni sistemi la cartella nascosta `.github` non viene caricata col trascinamento. In quel caso, nel repository: **Add file → Create new file**, scrivi come nome `.github/workflows/build-apk.yml`, incolla il contenuto del file con lo stesso nome che trovi nello zip e premi **Commit changes**.

Per aggiornare l'app in futuro basta caricare i file modificati: GitHub ricompila e pubblica una nuova Release, che si installa sopra la versione precedente.

## Note

- La chiave di firma (`app/indicimeteo.keystore`) è inclusa apposta, così gli aggiornamenti si installano senza disinstallare. Va bene per uso personale; se un giorno vorrai pubblicare l'app sul Play Store servirà una chiave privata nuova.
- Le notifiche si attivano da **Impostazioni** nell'app. Android esegue i controlli in background con una certa elasticità, quindi una notifica può arrivare con qualche decina di minuti di ritardo rispetto alla pubblicazione.
- Gli indirizzi delle fonti sono raccolti in `app/src/main/assets/www/js/data.js` (in alto, `SRC`): se un ente cambia un indirizzo basta correggerlo lì.
- Strumento informativo: per le allerte ufficiali fai riferimento alla Protezione Civile e al Servizio Meteorologico.
