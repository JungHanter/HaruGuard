package com.hanter.haruguard

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** 일반 관리자만 사용한다. DO/PO 등록이나 초기화·암호·카메라 정책은 요청하지 않는다. */
class HaruGuardAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)

    override fun onEnabled(context: Context, intent: Intent) {
        VpnRecovery.request(context)
    }

    companion object {
        fun component(context: Context) = ComponentName(context, HaruGuardAdminReceiver::class.java)
        fun isActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(context))
    }
}
