# Budget Tracker

> **The laptop helper has moved into BaseCamp.** One helper, one port (8765) and one code now serve both this app and the ToDo app. The `helper/` folder here is the retired standalone helper, kept for reference; run BaseCamp instead. This app reaches it under `/api/budget`.


An Android expense and income tracker that syncs through a small laptop helper into an Excel budget workbook (`Budget.xlsx`).

```
Phone app (Room DB, offline first)  --HTTP on home Wi-Fi-->  Laptop helper  -->  Budget.xlsx
```

- `app/`: Android app (Kotlin, Jetpack Compose, Room, WorkManager). Add expenses and income, review bank SMS, see the Tracker.
- `helper/`: laptop sync helper (Kotlin/JVM, Apache POI, JDK `HttpServer` on port 8765). Writes expenses to the Log sheet and income to the Income sheet, idempotently.
- `9pm-checkin-prompt.txt`: prompt for a daily check-in routine that reads the same workbook.

## What it does

- Log expenses and income in a couple of taps, offline first; entries sync to the workbook when the laptop is reachable.
- Totals, statuses, daily allowance and forecast are worked out on the phone, so the app is useful without the laptop.
- Edit or delete any entry, add your own categories, see where each category's money went, and browse past months.
- Quick-add chips, a home-screen widget, launcher shortcuts, optional alerts and a 9pm reminder.
- The helper keeps daily backups of the workbook outside OneDrive and serves app updates to the phone.

## How the phone and the laptop talk

Modelled on the ToDo app's laptop sync: the phone is the source of truth and pushes **one versioned snapshot** to the helper.

```
Phone (Budget Tracker)  --POST /api/sync, one JSON snapshot-->  Helper (Windows)  -->  Budget.xlsx
                        <--receipt: what was taken + the workbook's numbers--       -->  snapshot.json, briefs\budget.md / .json
```

- **Auth:** the pairing code as `Authorization: Bearer <code>` (or `X-Token`), compared in constant time; a wrong code is answered after a delay.
- **Reach:** only private, loopback, link-local and Tailscale callers are answered; bodies up to 16 MB. `GET /api/ping` checks the code.
- **Idempotent:** the helper applies only what the snapshot marks as not synced, and its ledger remembers every id, so a retry never duplicates.
- **Contract:** `contract/src/main/kotlin/com/bido/budgetsync/sync/SyncModels.kt`, used by both the app and the helper, with `BudgetSnapshot.VERSION` and `SnapshotJsonTest`. Change it there; raise `VERSION` for changes older readers cannot handle.
- **For the briefs:** after every sync the helper rewrites `%LOCALAPPDATA%\BudgetSync\briefs\budget.md` (and `budget.json`) with the phone's own numbers.

## Build

Needs JDK 17 and the Android SDK (platform 35). Set `sdk.dir` in `local.properties`.

```
gradlew :helper:test :app:testDebugUnitTest
gradlew :helper:jar :app:assembleDebug
```

## Run the helper

```
java -jar helper/build/libs/budget-sync-helper.jar
```

First run prints a pairing token; enter it in the app's Settings. Options: `--workbook <path> --port <n> --data-dir <dir> --bind <addr>`.

One-time workbook upgrade (adds the Income sheet and links Tracker and Plan income to it; makes a backup first): stop the helper, close Excel, then run with `--upgrade`.

## Updating the phone app without losing data

Android installs a new APK over the old app, keeping all its data, only if the package name and signing key are the same and the build number is higher. Every build is signed with the debug keystore (`%USERPROFILE%\.android\debug.keystore`, keep a backup) and gets a build number from the clock, so each build is newer than the last.

1. Build and publish: `gradlew :helper:jar :app:publishUpdate` (copies the APK and `version.json` to `%LOCALAPPDATA%\BudgetSync\update`).
2. Restart the helper if the helper code changed.
3. On the phone, with the laptop reachable: Settings, App updates, Check for update, then Download and install. The first time, Android asks to allow installs from this app.

The helper serves `GET /app/version` and `GET /app/apk`, both behind the pairing token.

The helper only listens on your local network over plain HTTP and requires the pairing token on every request.
