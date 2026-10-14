# Budget Tracker

An Android expense and income tracker that syncs into an Excel budget workbook (`Budget.xlsx`) through **BaseCamp**, the Windows app that also serves the ToDo app. One port (8765) and one pairing code serve both phone apps; this app's routes live under `/api/budget`.

```
Phone app (Room DB, offline first)  --signed HTTP on home Wi-Fi-->  BaseCamp (Windows)  -->  Budget.xlsx
```

- `app/`: Android app (Kotlin, Jetpack Compose, Room, WorkManager). Add expenses and income, review bank SMS, see the Tracker.
- `contract/`: the snapshot format shared with BaseCamp.
- `helper/`: the **retired** standalone helper, kept for reference only. It uses the old routes and plain-code auth, so this app cannot sync with it. Run BaseCamp instead.
- `9pm-checkin-prompt.txt`: prompt for a daily check-in routine that reads the same workbook.

## What it does

- Log expenses and income in a couple of taps, offline first; entries sync to the workbook when the laptop is reachable.
- Totals, statuses, daily allowance and forecast are worked out on the phone, so the app is useful without the laptop.
- Edit or delete any entry, add your own categories, see where each category's money went, and browse past months.
- Quick-add chips, a home-screen widget, launcher shortcuts, optional alerts and a 9pm reminder.
- BaseCamp keeps daily backups of the workbook outside OneDrive and serves app updates to the phone.

## How the phone and the laptop talk

The phone is the source of truth and pushes **one versioned snapshot** to BaseCamp.

```
Phone (Budget Tracker)  --POST /api/budget/sync, one JSON snapshot-->  BaseCamp  -->  Budget.xlsx
                        <--receipt: what was taken + the workbook's numbers--   -->  snapshot.json, briefs\budget.md / .json
```

- **Auth:** every request is signed with the pairing code (HMAC-SHA256 over method, path, time, a one-time nonce and the body); the code itself is never sent. BaseCamp's reply carries a proof that it knows the code too, so another device on the network can neither read the code nor pose as the laptop. Replayed requests and phone clocks more than 10 minutes off are refused. The signer is `app/src/main/kotlin/com/bido/budgetsync/data/RequestSigner.kt`, kept identical in BaseCamp and ToDo.
- **Privacy:** the data itself is not encrypted, so anyone on the same Wi-Fi who captures the traffic can read the entries. Use it on a network you trust.
- **Reach:** only private, loopback, link-local and Tailscale callers are answered; bodies up to 16 MB.
- **Idempotent:** BaseCamp applies only what the snapshot marks as not synced, and its ledger remembers every id, so a retry never duplicates.
- **Room to grow:** when the Log or Income sheet fills (1000 rows at first), BaseCamp backs up the workbook and widens every formula over those sheets by 4000 rows. The "More categories" sheet grows the same way, 50 rows at a time.
- **Contract:** `contract/src/main/kotlin/com/bido/budgetsync/sync/SyncModels.kt`, with `BudgetSnapshot.VERSION` and `SnapshotJsonTest`. Change it there and in BaseCamp's copy; raise `VERSION` for changes older readers cannot handle.
- **For the briefs:** after every sync BaseCamp rewrites `%LOCALAPPDATA%\BudgetSync\briefs\budget.md` (and `budget.json`) with the phone's own numbers.

## Build

Needs JDK 17 and the Android SDK (platform 35). Set `sdk.dir` in `local.properties`.

```
gradlew :app:testDebugUnitTest
gradlew :app:assembleDebug
```

## Pairing

In BaseCamp, Settings shows the address, port and a 16-letter pairing code (shown as four groups; dashes and case don't matter). Enter the code in this app's Settings. Making a new code in BaseCamp, or updating from a BaseCamp with the old 8-letter codes, means entering the new code on both phones.

## Updating the phone app without losing data

Android installs a new APK over the old app, keeping all its data, only if the package name and signing key are the same and the build number is higher. Every build is signed with the debug keystore (`%USERPROFILE%\.android\debug.keystore`, keep a backup) and gets a build number from the clock, so each build is newer than the last.

1. Build and publish: `gradlew :app:publishUpdate` (copies the APK and `version.json` to `%LOCALAPPDATA%\BudgetSync\update`, where BaseCamp serves them).
2. On the phone, with the laptop reachable: Settings, App updates, Check for update, then Download and install. The first time, Android asks to allow installs from this app.

BaseCamp serves `GET /api/budget/app/version` and `GET /api/budget/app/apk`, both signed like every other request.
