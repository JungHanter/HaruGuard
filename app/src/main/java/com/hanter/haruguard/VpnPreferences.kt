package com.hanter.haruguard

import android.content.Context

/** 부모가 선택한 필터 요청만 저장한다. 이 값으로 실제 VPN 실행 여부를 판단하지 않는다. */
internal class VpnPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("vpn_filter", Context.MODE_PRIVATE)

    var filteringRequested: Boolean
        get() = preferences.getBoolean("filtering_requested", false)
        set(value) { preferences.edit().putBoolean("filtering_requested", value).apply() }
}
