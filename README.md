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

Appen signeres alltid med samme nøkkel, så nye versjoner installeres over den gamle
uten at dataene forsvinner. Nøkkelen ligger aldri i repoet, men i to GitHub Secrets
(Settings → Secrets and variables → Actions):

- `KEYSTORE_BASE64`: signeringsnøkkelen, base64-kodet
- `KEYSTORE_PASSWORD`: passordet til nøkkelen

Mister du nøkkelen, kan nye versjoner ikke installeres over den gamle. Ta vare på en kopi.

## Oppdateringer

- **Innhold** (`app/src/main/assets/index.html`): appen henter nyeste versjon fra `main`
  ved oppstart og bruker den med en gang. Ingen ny APK trengs. Øk `web-version` ved endringer.
  Krever endringen nye funksjoner i Android-delen, økes `min-native` og `NATIVE_API` i `MainActivity.kt`.
- **Android-delen**: endringer utenfor `assets` bygger en ny APK under Releases. Appen viser
  da «Ny versjon er klar» med en knapp som laster ned og installerer.

Repoet må være offentlig for at appen skal finne oppdateringene.
