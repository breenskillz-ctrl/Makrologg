# Makrologg

Android-app for å logge kalorier, protein, karbohydrater og fett.

- Strekkodeskanner med oppslag i Open Food Facts
- Søk i vanlige norske matvarer, egne varer og Open Food Facts
- Daglige mål med kalkulator, historikk, vektlogg og vannteller
- Alt lagres lokalt på telefonen, med backup via deling

## Installere

Hver gang noe legges til i `main`, bygger GitHub Actions en ny APK.
Gå til **Releases** i repoet fra telefonen, last ned `Makrologg.apk` og åpne den.
Android spør om du vil tillate installasjon fra nettleseren første gang.

Appen signeres alltid med samme nøkkel (`app/makrologg.keystore`), så nye versjoner
installeres over den gamle uten at dataene forsvinner.
