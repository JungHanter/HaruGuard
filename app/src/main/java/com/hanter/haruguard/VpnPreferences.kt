package com.hanter.haruguard

import android.content.Context
import android.os.UserManager
import java.io.IOException

/** 부모가 선택한 필터 요청만 저장한다. 이 값으로 실제 VPN 실행 여부를 판단하지 않는다. */
internal class VpnPreferences(context: Context) {
    private val preferences = context.createDeviceProtectedStorageContext().let { storage ->
        val existing = storage.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        // 잠금 해제 후 기존 선택을 한 번 옮긴다. 재부팅 직후에는 이 비민감 값만 읽는다.
        if (!existing.getBoolean("migration_complete", false) &&
            context.getSystemService(UserManager::class.java).isUserUnlocked) {
            storage.moveSharedPreferencesFrom(context, NAME)
            storage.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putBoolean("migration_complete", true).commit()
        }
        storage.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    var filteringRequested: Boolean
        get() = preferences.getBoolean("filtering_requested", false)
        set(value) {
            // 서비스가 죽기 전에도 부모의 중지 선택을 디스크에 확정한다.
            if (!preferences.edit().putBoolean("filtering_requested", value).commit()) {
                throw IOException("VPN 선택 저장 실패")
            }
        }

    private companion object {
        const val NAME = "vpn_filter"
    }
}
