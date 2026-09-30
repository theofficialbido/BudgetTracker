# Budget Tracker

An Android expense and income tracker that syncs through a small laptop helper into an Excel budget workbook (`Budget.xlsx`).

```
Phone app (Room DB, offline first)  --HTTP on home Wi-Fi-->  Laptop helper  -->  Budget.xlsx
```

- `app/`: Android app (Kotlin, Jetpack Compose, Room, WorkManager). Add expenses and income, review bank SMS, see the Tracker.
- `helper/`: laptop sync helper (Kotlin/JVM, Apache POI, JDK `HttpServer` on port 8765). Writes expenses to the Log sheet and income to the Income sheet, idempotently.
- `9pm-checkin-prompt.txt`: prompt for a daily check-in routine that reads the same workbook.

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

The helper only listens on your local network over plain HTTP and requires the pairing token on every request.
