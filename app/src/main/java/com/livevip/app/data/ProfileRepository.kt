package com.livevip.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File

/**
 * Persists stream profiles to app private storage so they survive app close,
 * activity recreation and reboot. Stream keys live in SecureKeyStore only.
 */
class ProfileRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "stream_profiles.json")
    val secureKeys = SecureKeyStore(appContext)

    private val _profiles = MutableStateFlow<List<StreamProfile>>(emptyList())
    val profiles: StateFlow<List<StreamProfile>> = _profiles

    init {
        _profiles.value = load()
    }

    private fun load(): List<StreamProfile> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { StreamProfile.fromJson(arr.getJSONObject(it)) }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun persist(list: List<StreamProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
        _profiles.value = list
    }

    fun get(id: String?): StreamProfile? = _profiles.value.firstOrNull { it.id == id }

    fun upsert(profile: StreamProfile) {
        val updated = profile.copy(updatedAt = System.currentTimeMillis())
        val list = _profiles.value.toMutableList()
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) list[idx] = updated else list.add(updated)
        persist(list)
    }

    fun markUsed(id: String) {
        get(id)?.let { upsert(it.copy(lastUsedAt = System.currentTimeMillis())) }
    }

    fun delete(id: String) {
        secureKeys.removeStreamKey(id)
        persist(_profiles.value.filterNot { it.id == id })
    }

    fun duplicate(id: String): StreamProfile? {
        val src = get(id) ?: return null
        val copy = src.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${src.name} copy",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            lastUsedAt = 0L
        )
        secureKeys.setStreamKey(copy.id, secureKeys.getStreamKey(src.id))
        upsert(copy)
        return copy
    }

    fun streamKey(id: String): String = secureKeys.getStreamKey(id)

    fun setStreamKey(id: String, key: String) = secureKeys.setStreamKey(id, key)

    companion object {
        @Volatile private var instance: ProfileRepository? = null
        fun get(context: Context): ProfileRepository =
            instance ?: synchronized(this) {
                instance ?: ProfileRepository(context).also { instance = it }
            }
    }
}
