package com.hanter.haruguard

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockNavigationTest {
    @Test fun backPrecedesHomeAndDuplicateWindowEventsDoNotPopMultiplePages() {
        val navigation = BlockNavigation()
        assertEquals(BlockNavigation.Action.BACK, navigation.blocked(0))
        assertEquals(BlockNavigation.Action.WAIT, navigation.blocked(250))
        assertEquals(BlockNavigation.Action.HOME, navigation.blocked(600))
        assertEquals(BlockNavigation.Action.WAIT, navigation.blocked(700))
    }

    @Test fun returningToASafePageMakesTheNextAttemptStartWithBackAgain() {
        val navigation = BlockNavigation()
        navigation.blocked(0)
        navigation.clear()
        assertEquals(BlockNavigation.Action.BACK, navigation.blocked(300))
    }
}
