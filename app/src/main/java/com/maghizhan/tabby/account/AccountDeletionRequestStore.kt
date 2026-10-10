package com.maghizhan.tabby.account

import android.content.Context

/** Durable across the browser round-trip used to re-consent a Google account. */
class AccountDeletionRequestStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isPending(): Boolean = preferences.getBoolean(KEY, false)
    fun begin() = preferences.edit().putBoolean(KEY, true).apply()
    fun clear() = preferences.edit().clear().apply()

    private companion object {
        const val FILE = "tabby_account_deletion"
        const val KEY = "pending_google_reauth"
    }
}
