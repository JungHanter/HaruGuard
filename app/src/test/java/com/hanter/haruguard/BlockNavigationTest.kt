package com.hanter.haruguard

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockNavigationTest {
    @Test fun backPrecedesHomeAndDuplicateWindowEventsDoNotPopMultiplePages() {
        val navigation = BlockNavigation()
        assertEquals(BlockNavigation.Action.BACK, navigation.blocked(0))
        assertEquals(BlockNavigation.Action.WAIT, navigation.blocked(179))
        assertEquals(BlockNavigation.Action.HOME, navigation.blocked(180))
        assertEquals(BlockNavigation.Action.WAIT, navigation.blocked(200))
    }

    @Test fun returningToASafePageMakesTheNextAttemptStartWithBackAgain() {
        val navigation = BlockNavigation()
        navigation.blocked(0)
        navigation.clear()
        assertEquals(BlockNavigation.Action.BACK, navigation.blocked(300))
    }

    @Test fun fastNavigationToAnotherProtectedAreaDoesNotWaitForThePreviousPage() {
        val navigation = BlockNavigation()
        val info = BlockNavigation.Target(7, SettingsScreenPolicy.Area.APP, 1)
        val battery = BlockNavigation.Target(7, SettingsScreenPolicy.Area.BACKGROUND, 2)
        assertEquals(BlockNavigation.Action.BACK, navigation.blocked(0, info))
        assertEquals(BlockNavigation.Action.BACK, navigation.blocked(20, battery))
        assertEquals(BlockNavigation.Action.WAIT, navigation.blocked(40, battery))
        assertEquals(BlockNavigation.Action.HOME, navigation.blocked(200, battery))
    }

    @Test fun storageAndAppInfoInTheSameWindowAreDifferentProtectedPages() {
        val navigation = BlockNavigation()
        val info = BlockNavigation.Target(7, SettingsScreenPolicy.Area.APP, 1)
        val storage = info.copy(pageIdentity = 2)
        assertEquals(BlockNavigation.Action.BACK, navigation.blocked(0, info))
        assertEquals(BlockNavigation.Action.BACK, navigation.blocked(10, storage))
    }
}
