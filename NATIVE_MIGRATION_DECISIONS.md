# Dev-1-new native migration decisions

Updated: 2026-10-04

This file records the current user-confirmed behavior and overrides older planning text where the older text says that this project is diagnostic-only. The implementation target is the complete commute app, not a Flet/Python port and not a diagnostics-only prototype.

## User-confirmed manual/automatic policy

- **Commute:** The user manually selects the initial `514` bus boarding plan. Bus boarding/alighting and the subsequent KTX and final `동구4-1` segment advance automatically. The subway is the only subsequent leg that needs an explicit manual boarding confirmation; its train selection is fixed at that confirmation, then timetable progress and alighting continue automatically.
- **Return:** The user manually selects the initial `동구4` boarding plan. Subsequent subway, KTX, and final `514` segments advance automatically except for the explicit manual subway boarding confirmation.
- **Hospital:** Outbound and return are fully separate journeys. The user manually starts the boarding plan for the chosen direction; bus boarding detection, GPS stop progress, and destination alighting then run automatically only within that direction. Completing one direction never starts the opposite direction; it requires its own manual plan.
- A plan is not the same thing as being on board. A planned bus starts native foreground location tracking immediately; the location service alone commits automatic boarding when the configured GPS criteria are met.
- Cancellation and deliberate recovery/reset controls remain available so a mistaken plan or corrupted trip can be corrected; ordinary downstream boarding/alighting controls are not a second parallel state path.
- **KTX data entry (confirmed 2026-10-04):** Keep manual entry of the train identifier, departure/arrival time, platform, car, and seat for the current implementation. API- or timetable-based lookup is a future change and must not be introduced until the user requests/defines it. No platform default is inferred from Dev-1; a blank platform remains visibly unconfirmed rather than guessed.

## Route progression

| Direction | Ordered legs | Manual actions |
| --- | --- | --- |
| Commute | Home → 514 bus → KTX (Daejeon–Dongdaegu) → subway Line 1 (Dongdaegu–Ansim) → `동구4-1` bus | Initial 514 plan; subway boarding only |
| Return | `동구4` bus → subway Line 1 (Ansim–Dongdaegu) → KTX (Dongdaegu–Daejeon) → 514 bus | Initial `동구4` plan; subway boarding only |
| Hospital, outbound | `동구4` from 대구경북지방병무청 to 동호육교1 | Initial plan only |
| Hospital, return | `동구4-1` from 동호육교2 to 한국교육학술정보원앞 | Initial plan only |

Every completed leg uses one state transition path to clear the prior leg and create the next plan. Commute/return trips retain their original start date through all legs and reset the active chain when the local service date changes; this is implemented in the reducer and covered by a regression test.

## Single native architecture

- Android UI: Kotlin + Jetpack Compose. No Flet, Flutter runtime, Python UI, or WebView in the app.
- One canonical persisted journey state (Room/SQLite repository) is shared by Compose, the location foreground service, and notification rendering. Views render repository state; they do not independently decide progress or post competing notifications.
- One native foreground service owns bus/KTX GPS, background state transitions, subway schedule ticks, and the ongoing transit notification. Notification content is rendered from the same persisted snapshot used by the UI.
- Bus and KTX location/progression evaluation lives in the service/domain layer. Subway progress uses the selected, immutable train timetable and does not use GPS.
- API credentials will be kept in app-private encrypted storage; never hard-code or log an API key. Dev-1's `bus_api_key.py` is intentionally ignored and was not opened.
- Build, install, release signing, and APK creation remain out of scope until the user explicitly requests them, as required by the project rules.

## Known intentional changes from Dev-1

1. **Remove Flet/native notification split.** Dev-1 shares state between a Python/Flet foreground notification and a Java Android service. Dev-1-new has no Flet owner: the native app/service will be the sole publisher. Keep one notification ID/owner, one persisted state, and the same independently generated `title`, `shortCriticalText`, and `body` fields. This is required by the no-Flet request and prevents competing stale cards.
2. **KTX boarding becomes automatic after the preceding leg.** Dev-1 exposes manual KTX boarding controls even though its native service can evaluate a planned KTX. That conflicts with the user's rule that only the initial bus plan and subway boarding are manual. Keep the route/time/speed/accuracy confirmation logic, but create the KTX plan automatically after the previous segment ends and let native GPS confirm boarding.
3. **Do not apply a one-minute-per-stop ETA floor.** Dev-1's current `format_daejeon_arrival_pairs()` still executes `minutes = max(minutes, stops)` when both fields exist. This treats each remaining stop as at least one minute, despite no verified route-specific conversion and the user's report that the arrival time was inaccurate. Dev-1-new will show the provider ETA and that same vehicle's remaining-stop count as separate paired facts; it will not alter the API ETA using stop count. Zero-minute records may normalize their remaining-stop count to zero per the existing behavior requirement.
4. **Hospital start behavior.** The user explicitly confirmed that each standalone hospital route starts with one manual plan selection and then proceeds automatically.
5. **Short return Donggu4 alighting exception.** The three-point return Donggu4 segment advances after one valid fix only when GPS is past the final-segment midpoint and within 140 m of Songjeong Triangle 3 or Ansim Exit 1. This preserves Dev-1's transfer-specific rule; ordinary bus destinations still require two accurate consecutive fixes under the shared project rule.
6. **Commute subway transfer-car label.** Dev-1's current Flet route text says `3-4`, while the shared D-drive Samsung behavior rule says `6-4` and restricts it to the in-app subway card. Dev-1-new follows the latter project rule and displays `6-4` only in the app, never in notification/Now Bar text.

The root `D:\ddd\AGENTS.md` still governs shared Samsung notification field formatting and current bus GPS thresholds. The Flet-specific ownership sentence in that file is not applicable to this Flet-free app; the single-owner principle is retained with ownership moved into the native app/service.

## Subway timetable source and freshness

- The full CSVs copied from Dev-1 are `line1_down_20241007.csv` and `line1_up.csv`. The official data.go.kr records identify both files as the **2024-10-07 timetable plan**: down dataset `15138731`, up dataset `15065526`. Both catalog pages show a metadata modification date of 2026-07-07, but their dataset names and descriptions still explicitly identify the timetable content as 20241007 / 2024-10-07; a metadata edit date is not evidence of a newer service plan.
- Both copied files were checked using CP949-aware import: each has 204 data rows, 148 train-number columns, every station in its corresponding commute/return route, and weekday origin departures. This verifies structure, not that 2026 service times remain current.
- The native implementation intentionally selects from the same complete CSV by absolute difference from the user's subway-board tap time, pins the train-number column and its route-station times, and does not silently switch to a different train. Upcoming planned departures exclude times already passed.
- The current official DTRO page (last updated 2026-09-16) contains updated first/last trains, service intervals, and links to station-level schedules. Its station-time endpoint returns each station/direction/day's list of times, but the examined response contains no train identifier or correlated full-route train row. Replacing the CSV with those lists would require inferred train matching, which risks breaking the user's manually selected/fixed-train behavior.
- **Decision following the user's instruction:** because no newer official train-number-by-station file was found, retain Dev-1's existing 2024 CSVs for exact same-train route progression. Label both directions with the 2024-10-07 source date; do not call them current or real-time. Replace them if a newer full train-by-station timetable becomes available.

## Samsung/Android notification contract to preserve

Use separate values; never copy `body` into `shortCriticalText` just because Android names them similarly.

| State | `title` | `shortCriticalText` | `body` |
| --- | --- | --- | --- |
| Bus planned | Bus number (without `번`) + destination | Arrival + paired vehicle ETA/stop count | Same as short line |
| Bus on board | Bus number + destination | Stops remaining + current + next stop | Same as short line |
| Subway planned | Next station + terminal direction | Arrival information | Same as short line |
| Subway on board | Destination time + destination station | Stops remaining/current/next; append the exit door **only at the destination** | Stops remaining/current/next, no exit door |
| KTX planned | Train identifier + departure time | Platform + car + seat | Same as short line |
| KTX on board | Arrival time + destination | Remaining route positions + current + next | Same as short line |

Android 16's progress-centric `Notification.ProgressStyle` and promoted-ongoing request will be used where supported; older OS versions get a normal ongoing notification fallback. Promotion depends on OS/device/user settings and must not be represented as guaranteed when the platform declines it.

## Current ETA evidence

- The official national TAGO bus-arrival API page defines `arrtime` as an estimated arrival duration in **seconds**, and `arrprevstationcnt` as the estimated bus's remaining-stop count. They are separate fields and do not establish a universal minutes-per-stop conversion.
- The official Daegu API catalogue describes the arrival/vehicle-position endpoints as real-time and notes that arrival/vehicle information may be absent outside operating hours; its public summary page does not expose all per-field units.
- The fetched Daejeon API catalogue describes the feed as real-time but does not expose a complete response-element table on that page. Dev-1 currently parses `EXTIME_SEC`, then `EXTIME_MIN`, and keeps remaining stops from the same Daejeon item; these provider-specific details still require confirmation against the provider guide/sample before final API implementation.
- No stale timetable timestamp or stop count will be relabelled as a live ETA. The app must retain source time/freshness metadata internally and show a non-live/error state when a provider response is invalid.

Official references:

- [National TAGO bus arrival API](https://www.data.go.kr/data/15098530/openapi.do)
- [Daejeon bus location API catalogue](https://www.data.go.kr/data/15157881/openapi.do)
- [Daegu bus information system API catalogue](https://www.data.go.kr/data/15139134/openapi.do)
- [Daegu Metro Line 1 down timetable CSV (official dataset 15138731)](https://www.data.go.kr/data/15138731/fileData.do)
- [Daegu Metro Line 1 up timetable CSV (official dataset 15065526)](https://www.data.go.kr/data/15065526/fileData.do)
- [Current DTRO Line 1 timetable page](https://www.dtro.or.kr/index.do?menu_id=00000270)
- [DTRO station timetable endpoint example (Dongdaegu, weekday down)](https://www.dtro.or.kr/open_content_new/ko/OpenApi/stationTime.php?STT_NM=%EB%8F%99%EB%8C%80%EA%B5%AC%EC%97%AD&LINE_NO=1&SCHEDULE_METH=DOWN&SCHEDULE_TYPE=WEEKDAY)
- [Android progress-centric notifications](https://developer.android.com/about/versions/16/features/progress-centric-notifications)
- [Android Notification.Builder API](https://developer.android.com/reference/android/app/Notification.Builder)

## Environment/validation limits at this stage

- Native Kotlin/Compose source, bus/subway clients, service, notification renderer, and unit-test source now exist in the target; the project still has no Git repository.
- The journey-state, route, ETA, GPS, CSV timetable, and screen/service paths have not yet been compiled. The user clarified that `D:\ddd\nodelete` contains only `.venv`, `dev-2-run-venv`, `dev-3-test-venv`, and `dev-4-run-venv`; `D:\ddd\flutter-sdk-home` is a separate Flutter SDK location. The primary Android SDK is verified at `C:\Users\cobra\Android\sdk` with platforms `android-34`, `android-35`, `android-36` and Build-Tools `28.0.3`, `34.0.0`, `35.0.0`. Its `platform-tools\adb.exe` runs, but global `adb` and `gradle` commands are not on PATH; global Kotlin is also absent. The Flutter SDK cache separately contains wrapper scripts/JAR, but dev-1-new contains only `gradle-wrapper.properties`; the user also reports no `gradlew.bat` in Dev-1's current generated build folder. Dev-1's `D:\ddd\Dev-1\.build-venv` is a separate project-specific environment and has not been used for dev-1-new. No Build-Tools 36.0.0 is installed. The user confirms the signing key was not deleted and is stored at `C:\Users\cobra\OneDrive\dev3-private-signing`; the key contents were not accessed or copied, and its suitability for dev-1-new has not been verified. The unit tests are authored but have not been executed.
- No APK/build/install has been attempted. The project rules require an explicit user request for build/install. Real S26 lock-screen/Now Bar behavior cannot be claimed verified until an authorized build/install and device test.
- The SQLite diagnostic tables have been scaffolded, but diagnostic event/sample writing, issue-history UI, and share/export controls remain to be migrated from Dev-1. The user confirmed that log splitting is to prevent large files from deleting/truncating earlier records, not to cap total session retention; keep every prior part, set the per-part threshold during implementation before platform/file limits, and warn rather than auto-delete if storage is low.

## Remaining implementation questions

1. Replace the 2024 subway CSVs only if a newer full train-number-by-station dataset becomes available.
2. Confirm Daejeon and Daegu source-field units against provider samples/guides before relying on final ETA labels.
3. Migrate diagnostic event/sample writing, issue-history UI, and share/export controls.
4. Actual Galaxy S26 acceptance testing: permissions, background location survival, battery restrictions, and Now Bar promotion.
