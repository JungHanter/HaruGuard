package com.hanter.haruguard

import android.content.Context
import com.hanter.haruguard.vpn.FilterSettings
import com.hanter.haruguard.vpn.FilterSettingsStore
import com.hanter.haruguard.vpn.PublicSuffixes

/** 차단 정책만 DP 저장소에 둬 잠금 해제 전 VPN 복구에도 동일한 규칙을 사용한다. */
internal class FilterPreferences(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.createDeviceProtectedStorageContext()
        .getSharedPreferences("domain_filter_policy", Context.MODE_PRIVATE)
    private val store = FilterSettingsStore(
        read = { preferences.getString("snapshot", null) },
        write = { value ->
            val previous = preferences.getString("snapshot", null)
            val saved = preferences.edit().putString("snapshot", value).commit()
            // commit 실패 시 메모리에서만 새 값이 보이는 것도 되돌린다.
            if (!saved) preferences.edit().putString("snapshot", previous).commit()
            saved
        },
        validateDomain = ::validateDomain,
    )

    fun load(): FilterSettings = synchronized(storageLock) { store.load() }
    fun save(settings: FilterSettings) = synchronized(storageLock) { store.save(settings) }
    fun validateDomain(input: String): String = suffixes(app).validate(input)

    private companion object {
        val storageLock = Any()
        @Volatile var publicSuffixes: PublicSuffixes? = null

        fun suffixes(context: Context): PublicSuffixes = publicSuffixes ?: synchronized(storageLock) {
            publicSuffixes ?: context.assets.open("public_suffix_list.dat").bufferedReader().use { reader ->
                PublicSuffixes(reader.lineSequence()).also { publicSuffixes = it }
            }
        }
    }
}
