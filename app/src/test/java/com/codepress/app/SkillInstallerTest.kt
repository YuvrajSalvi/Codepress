package com.codepress.app

import com.codepress.app.data.BUILT_IN_SKILLS
import com.codepress.app.data.parseSkillRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillInstallerTest {

    @Test
    fun parsesHttpsLinkAndShowsOnlyTheName() {
        val r = parseSkillRepo("https://github.com/YuvrajSalvi/imgbb.js.git")!!
        assertEquals("YuvrajSalvi", r.owner)
        assertEquals("imgbb.js", r.repo)
        assertEquals("imgbb.js", r.title)
    }

    @Test
    fun dashesBecomeSpaces() {
        assertEquals("awesome design md", parseSkillRepo("https://github.com/VoltAgent/awesome-design-md.git")!!.title)
        assertEquals("html game skill", parseSkillRepo("https://github.com/kawaicutout/html-game-skill.git")!!.title)
    }

    @Test
    fun acceptsOtherGithubForms() {
        assertEquals("rea", parseSkillRepo("git@github.com:morluto/rea.git")!!.repo)
        assertEquals("rea", parseSkillRepo("morluto/rea")!!.repo)
        assertEquals("rea", parseSkillRepo("https://github.com/morluto/rea/")!!.repo)
        assertEquals("dev", parseSkillRepo("https://github.com/a/b/tree/dev")!!.ref)
    }

    @Test
    fun rejectsOtherLinks() {
        assertNull(parseSkillRepo("hello"))
        assertNull(parseSkillRepo("https://example.com/a/b"))
        assertNull(parseSkillRepo(""))
    }

    @Test
    fun libraryHasTheFiveSkills() {
        assertEquals(
            listOf("imgbb.js", "rea", "awesome design md", "freellmapi", "html game skill"),
            BUILT_IN_SKILLS.map { it.title },
        )
    }
}
