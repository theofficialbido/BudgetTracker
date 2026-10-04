# Changelog

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
