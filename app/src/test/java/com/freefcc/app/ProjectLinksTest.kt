package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProjectLinksTest {

    @Test
    fun updaterTargetsForkReleases() {
        assertEquals("danusha2345/FreeFCC", ProjectLinks.REPOSITORY)
        assertEquals(
            "https://api.github.com/repos/danusha2345/FreeFCC/releases/latest",
            ProjectLinks.LATEST_RELEASE_API
        )
        assertFalse(ProjectLinks.LATEST_RELEASE_API.contains("doesthings/FreeFCC"))
    }
}
