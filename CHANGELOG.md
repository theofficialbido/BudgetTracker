# Changelog

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
