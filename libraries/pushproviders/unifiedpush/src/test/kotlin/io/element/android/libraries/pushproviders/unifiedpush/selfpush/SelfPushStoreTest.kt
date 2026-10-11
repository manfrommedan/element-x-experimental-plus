/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelfPushStoreTest {
    private val store = SharedPreferencesSelfPushStore(InMemorySharedPreferences())

    @Test
    fun `registration round trip`() {
        val registration = SelfPushRegistration(server = "https://ntfy.example.org", topic = "up1234abcd")
        store.storeRegistration("secret1", registration)

        assertThat(store.getRegistration("secret1")).isEqualTo(registration)
        assertThat(store.getRegistration("unknown")).isNull()
    }

    @Test
    fun `removeRegistration drops both fields`() {
        store.storeRegistration("secret1", SelfPushRegistration("https://ntfy.example.org", "up1"))
        store.removeRegistration("secret1")

        assertThat(store.getRegistration("secret1")).isNull()
    }

    @Test
    fun `partial registration reads as absent`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("SELF_PUSH_TOPIC_secret1", "up1").apply()
        val store = SharedPreferencesSelfPushStore(prefs)

        assertThat(store.getRegistration("secret1")).isNull()
    }

    @Test
    fun `getAllRegistrations returns every stored secret`() {
        store.storeRegistration("secret1", SelfPushRegistration("https://ntfy.example.org", "up1"))
        store.storeRegistration("secret2", SelfPushRegistration("https://other.example.org", "up2"))
        store.setCustomServer("https://custom.example.org")

        val all = store.getAllRegistrations()
        assertThat(all).hasSize(2)
        assertThat(all["secret1"]?.topic).isEqualTo("up1")
        assertThat(all["secret2"]?.server).isEqualTo("https://other.example.org")
    }

    @Test
    fun `custom server and read token round trip and clear`() {
        assertThat(store.getCustomServer()).isNull()
        assertThat(store.getReadToken()).isNull()

        store.setCustomServer("https://ntfy.example.org")
        store.setReadToken("tk_read")

        assertThat(store.getCustomServer()).isEqualTo("https://ntfy.example.org")
        assertThat(store.getReadToken()).isEqualTo("tk_read")

        store.setCustomServer(null)
        store.setReadToken(null)

        assertThat(store.getCustomServer()).isNull()
        assertThat(store.getReadToken()).isNull()
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, Any?> = data.toMap()

    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = data[key] as? Set<String> ?: defValues

    override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { data[key] = value }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { data[key] = values }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { data[key] = value }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { data[key] = value }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { data[key] = value }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { data[key] = value }

        override fun remove(key: String): SharedPreferences.Editor = apply { data.remove(key) }

        override fun clear(): SharedPreferences.Editor = apply { data.clear() }

        override fun commit(): Boolean = true

        override fun apply() = Unit
    }
}
