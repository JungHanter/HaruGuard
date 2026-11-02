package com.hanter.haruguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

data class DpcState(
    val isDeviceOwner: Boolean,
    val isAdminActive: Boolean,
    val ownDnsLock: Boolean,
    val effectiveDnsLock: Boolean,
    val privateDnsMode: Int?,
    val ownVpnLock: Boolean,
    val effectiveVpnLock: Boolean,
    val managedVpnPackage: String?,
)

class DpcController(context: Context) {
    private val policies = context.getSystemService(DevicePolicyManager::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val packageName = context.packageName
    private val admin = ComponentName(context, HaruGuardDeviceAdminReceiver::class.java)

    fun readState(): DpcState {
        val owner = policies.isDeviceOwnerApp(packageName)
        return DpcState(
            isDeviceOwner = owner,
            isAdminActive = policies.isAdminActive(admin),
            ownDnsLock = owner && policies.getUserRestrictions(admin)
                .getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS),
            effectiveDnsLock = users.hasUserRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS),
            privateDnsMode = if (owner) policies.getGlobalPrivateDnsMode(admin) else null,
            ownVpnLock = owner && policies.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONFIG_VPN),
            effectiveVpnLock = users.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN),
            managedVpnPackage = if (owner) policies.getAlwaysOnVpnPackage(admin) else null,
        )
    }

    fun setDnsLocked(locked: Boolean): DpcState {
        check(policies.isDeviceOwnerApp(packageName)) { "Device Owner 등록이 필요합니다." }
        if (locked) {
            policies.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        } else {
            policies.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        }
        // 요청값을 성공으로 간주하지 않고 실제 적용된 정책을 다시 읽는다.
        return readState().also { check(it.ownDnsLock == locked) { "DNS 잠금 상태가 반영되지 않았습니다." } }
    }

    fun setVpnLocked(locked: Boolean): DpcState {
        check(policies.isDeviceOwnerApp(packageName)) { "Device Owner 등록이 필요합니다." }
        val before = readState()
        check(before.managedVpnPackage == null || before.managedVpnPackage == packageName) {
            "다른 always-on VPN이 지정되어 있습니다. 기존 VPN은 변경하지 않았습니다."
        }
        val previousLockdown = policies.isAlwaysOnVpnLockdownEnabled(admin)
        try {
            if (locked) {
                // Android 12+에서는 이 제한이 일반 VPN의 연결 권한을 회수한다.
                // HaruGuard를 관리자 VPN 예외로 먼저 등록한 뒤 사용자 변경을 제한한다.
                // https://developer.android.com/reference/android/os/UserManager#DISALLOW_CONFIG_VPN
                // 공식 YouTube 앱과 일반 통신의 직접 연결을 유지하므로 lockdown은 사용하지 않는다.
                policies.setAlwaysOnVpnPackage(admin, packageName, false)
                policies.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
            } else {
                policies.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
                if (before.managedVpnPackage == packageName) {
                    policies.setAlwaysOnVpnPackage(admin, null, false)
                }
            }
            return readState().also {
                check(it.ownVpnLock == locked && (it.managedVpnPackage == packageName) == locked) {
                    "VPN 변경 잠금 상태가 반영되지 않았습니다."
                }
            }
        } catch (error: Exception) {
            // 관리자 VPN 지정 또는 변경 제한 중 하나라도 실패하면 기존 자체 정책을 복구한다.
            runCatching {
                if (!before.ownVpnLock) policies.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
                policies.setAlwaysOnVpnPackage(admin, before.managedVpnPackage, previousLockdown)
                if (before.ownVpnLock) policies.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    companion object {
        const val ADMIN_COMPONENT = "com.hanter.haruguard/.HaruGuardDeviceAdminReceiver"
    }
}
