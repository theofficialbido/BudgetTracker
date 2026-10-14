package com.bido.budgetsync.data

import android.content.Context

/** Small settings store: laptop address, pairing token, SMS options and the last state fetched from the laptop. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("budgetsync", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", DEFAULT_HOST) ?: DEFAULT_HOST
        set(v) = sp.edit().putString("host", v.trim()).apply()

    /**
     * Whether to sync with the laptop at all. The app works fully either way; syncing keeps a copy on the laptop and
     * feeds the morning and evening briefs.
     */
    var syncEnabled: Boolean
        get() = sp.getBoolean("syncEnabled", true)
        set(v) = sp.edit().putBoolean("syncEnabled", v).apply()

    /** Look for the laptop on the current network instead of relying on one saved address. */
    var autoDiscover: Boolean
        get() = sp.getBoolean("autoDiscover", true)
        set(v) = sp.edit().putBoolean("autoDiscover", v).apply()

    /** The address that last worked, tried first next time. */
    var lastHost: String
        get() = sp.getString("lastHost", "") ?: ""
        set(v) = sp.edit().putString("lastHost", v).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var smsEnabled: Boolean
        get() = sp.getBoolean("smsEnabled", false)
        set(v) = sp.edit().putBoolean("smsEnabled", v).apply()

    var senders: Set<String>
        get() = sp.getStringSet("senders", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("senders", v).apply()

    /** Notify when a category reaches WATCH or OVER. */
    var alertsCategory: Boolean
        get() = sp.getBoolean("alertsCategory", false)
        set(v) = sp.edit().putBoolean("alertsCategory", v).apply()

    /** The 9pm reminder to log the day's expenses. On unless switched off; it stays silent until notifications are allowed. */
    var alertsReminder: Boolean
        get() = sp.getBoolean("alertsReminder", true)
        set(v) = sp.edit().putBoolean("alertsReminder", v).apply()

    /** The hour of the evening reminder, 9pm unless changed in Settings. */
    var reminderHour: Int
        get() = sp.getInt("reminderHour", 21)
        set(v) = sp.edit().putInt("reminderHour", v).apply()

    /** The one-time "allow notifications?" question has been shown. */
    var askedNotifications: Boolean
        get() = sp.getBoolean("askedNotifications", false)
        set(v) = sp.edit().putBoolean("askedNotifications", v).apply()

    /** Last status notified per month and category, so each step up (OK, WATCH, OVER) is announced once. Stored as lines "key=STATUS". */
    var alertState: Map<String, String>
        get() = (sp.getString("alertState", "") ?: "").lines().mapNotNull { l -> l.lastIndexOf('=').takeIf { it > 0 }?.let { i -> l.substring(0, i) to l.substring(i + 1) } }.toMap()
        set(v) = sp.edit().putString("alertState", v.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()

    var lastReminderDate: String
        get() = sp.getString("lastReminderDate", "") ?: ""
        set(v) = sp.edit().putString("lastReminderDate", v).apply()

    var lastScanMs: Long
        get() = sp.getLong("lastScanMs", 0L)
        set(v) = sp.edit().putLong("lastScanMs", v).apply()

    var cachedState: String?
        get() = sp.getString("cachedState", null)
        set(v) = sp.edit().putString("cachedState", v).apply()

    var lastSyncMs: Long
        get() = sp.getLong("lastSyncMs", 0L)
        set(v) = sp.edit().putLong("lastSyncMs", v).apply()

    var lastSyncMessage: String
        get() = sp.getString("lastSyncMessage", "") ?: ""
        set(v) = sp.edit().putString("lastSyncMessage", v).apply()

    companion object {
        const val DEFAULT_HOST = "Bidos-Laptop.local:8765"
    }
}
