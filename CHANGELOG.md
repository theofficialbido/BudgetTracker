# Changelog

## 1.6

### Changed
- **Left to spend is now a running balance worked out entirely in the app:** all income declared in the app minus all spending declared in the app, counted from the last month you chose to start at 0. It needs no connection; the laptop only keeps a copy for redundancy and for the briefs.
- **Month-end decision.** When a month ends with money left, Home asks: carry it into the new month, or invest / treat yourself and start the new month at 0 (a slider splits the leftover between investing and treating yourself). An overspent month asks whether to carry the shortfall or start at 0. Until you decide, the money carries over. The decision is kept on the phone and copied to a new "Month closing" sheet.
- The daily allowance is now what is left to spend divided by the days left, not the unused part of the plan.
- The widget says "left to spend" and shows the same running balance.

### Added
- **Plans are edited in the app** (open a category, Change plan). They apply at once, drive progress and alerts, and are copied to the workbook on the next sync: for the Tracker categories into the Plan sheet's unit cost (keeping times per month), for your own categories into the More categories sheet.
- **Tracker row "Left to spend (running balance)"** (Tracker!A14:C14) in the workbook, calculated the same way, so the morning and evening briefs can quote the same number as the app. The balance start date is kept in 'Month closing'!I2.

### Fixed
- Two workbook backups taken in the same second no longer collide on their file name.

## 1.5

### Added
- **Edit and delete saved entries.** Tap any entry to change its amount, description, category or date, or delete it. Entries still waiting to sync change on the phone; entries already in Budget.xlsx are changed there through the laptop. The helper refuses a change if the row was edited in Excel meanwhile (the phone sends what it saw), deleting twice is harmless, and the Tracker numbers follow.
- **Quick add.** Entries you repeat (same category, description and amount, at least twice in 90 days) appear as one-tap chips on Home and as "Your usual ones" on the Add screen. A quick add is held for six seconds so Undo can take it back before it is sent.
- **Home-screen widget and launcher shortcuts.** The widget shows what is left after spending (worked out on the phone) with + Expense and + Income buttons. Long-press the app icon for the same shortcuts.
- **Daily allowance and month-end forecast.** "About X EGP a day is safe" from what is left of the plan and the days remaining, and "at this pace you'll spend Y of Z", plus categories heading over their plan before they get there. A single big entry, like a subscription, is treated as a one-off, not a daily habit.
- **Alerts and reminders** (Settings, both off by default): a notification when a category reaches WATCH or OVER (once per step up), and a 9pm reminder if nothing was logged today. They work without the laptop.
- **History and trends.** A six-month bar chart of spending with income marks, the chosen month compared with the one before, and a category breakdown that opens each category for that month.
- **Automatic daily backups** of Budget.xlsx to `%LOCALAPPDATA%\BudgetSync\backups\daily` (outside OneDrive), taken when the workbook has changed since the last one; the newest 30 are kept.

### Fixed
- **Dates were one day early on any time zone east of UTC** (Cairo included): the helper turned Excel dates into days through UTC. Dates now come straight from Excel's serial number. This could put entries on the wrong day, and around month ends in the wrong month.
- A backup is dated when it was made, not when the workbook was last edited.

## 1.4

### Changed
- The app is now a proper release build (not debuggable), named `BudgetTracker.apk`. It is signed with the same key as before, so it installs over the current app and keeps its data. The package name is unchanged on purpose: changing it would make Android treat it as a different app and lose all data.

### Fixed
- **Amounts:** typing `1,200` was read as 1.2. Thousands separators, decimal commas and Arabic digits are now understood everywhere an amount is typed.
- **"Saved and synced" message** could never appear: the save did not wait for the sync before checking. It now waits and reports the real result.
- **Sync spinner** turned off as soon as the first of several overlapping syncs finished.
- **Month rollover:** the home screen kept showing last month's numbers if the app stayed open across midnight on the 1st.
- **A failed refresh after a successful send** was reported as a failed sync. It is now reported as synced, and the entries stay counted from the phone until fresh numbers arrive.
- **Slow retries:** the update check repeated the full slow search for the laptop right after a sync had failed to find it, delaying saves; it now skips for 20 seconds.
- **Retried entries:** a retry could be mistaken for a different entry with the same amount; the date is now compared too.
- **SMS:** a credit notice that mentioned a purchase limit was read as spending. A message about money coming in now needs a clear debit word.
- **Bank message review** defaulted to the most recently added category instead of "Other".
- **Pre-filled amounts** in bank message review could appear in scientific notation.
- **Update button** did not refresh after granting "install unknown apps".
- The helper now refuses request bodies over 1 MB, and sync cancellation is no longer swallowed.

## 1.3

### Fixed
- **Updates lost data.** Every build had versionCode 1, so Android could refuse to install over the existing app and the app had to be uninstalled, wiping settings and queued entries. The build number now comes from the clock and the signing key stays the same, so each build installs in place. The app can fetch and install updates from the laptop (Settings, App updates).
- **Bank SMS read twice.** The broadcast receiver and the inbox scan recorded one message with different timestamps, so duplicates slipped through. A message is now identified by sender and text within 10 minutes, checked and saved in one transaction. Duplicates already saved are removed by the database migration.
- **Sync stopped after switching networks.** The app used one saved laptop address. The helper now announces itself over mDNS and the app finds it automatically, falling back to the last working address and then the manual one.
- **Stale helper rejected the pairing token** after a restart; fixed by restarting on the current build.
- **Plan and Tracker income** were fixed typed figures. Income is logged on its own Income sheet and the Tracker and Plan follow what was actually received this month.

### Added
- Totals, statuses, income and "left after actual spending" are worked out on the phone, so the app works without the laptop.
- Custom categories, kept on a "More categories" sheet that feeds the Tracker and Plan totals.
- Tapping a category shows where the money went (grouped by description, then every entry), with month navigation.
- Income entry, and an expense/income quick-add menu.
- Automatic workbook upgrade with a backup before any change.

### Notes
- Plans and alert levels come from the laptop and are remembered after the first sync.
- Debug keystore backup: `C:\Users\Abdul\Projects\BudgetSync-keys` (keep a copy off this PC).
