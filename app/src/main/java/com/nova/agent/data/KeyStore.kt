package com.nova.agent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.novaPrefs by preferencesDataStore(name = "nova_prefs")

/**
 * Locally persisted secrets for NOVA.
 * Backed by Jetpack DataStore — values never leave the device, never go to GitHub.
 */
class KeyStore(private val context: Context) {

    val apiKey: Flow<String> = context.novaPrefs.data.map { it[API_KEY] ?: "" }

    suspend fun setApiKey(key: String) {
        context.novaPrefs.edit { it[API_KEY] = key.trim() }
    }

    companion object {
        private val API_KEY = stringPreferencesKey("zai_api_key")
    }
}
