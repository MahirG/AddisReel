package et.addisreel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDesignSystemTest {
    @Test fun semanticTextMeetsWcagAaInBothThemes() {
        for (p in listOf(AddisPalettes.Dark, AddisPalettes.Light)) {
            for (background in listOf(p.background,p.surface,p.elevated)) {
                assertTrue("primary text must be AA on $background",
                    AddisContrast.normalText(p.textPrimary,background))
                assertTrue("secondary text must be AA on $background",
                    AddisContrast.normalText(p.textSecondary,background))
                assertTrue("muted text must be AA on $background",
                    AddisContrast.normalText(p.textMuted,background))
                assertTrue("accent text must be AA on $background",
                    AddisContrast.normalText(p.accent,background))
            }
            assertTrue("brand button must be AA",AddisContrast.normalText(p.onAccent,p.accent))
            assertTrue("selected accent label must be AA",AddisContrast.normalText(p.accent,p.selected))
            assertTrue("control outline must be 3:1",AddisContrast.largeText(p.border,p.surface))
            assertTrue("error messages must be AA",AddisContrast.normalText(p.error,p.elevated))
            assertTrue("success messages must be AA",AddisContrast.normalText(p.success,p.elevated))
            assertTrue("warning messages must be AA",AddisContrast.normalText(p.warning,p.elevated))
        }
    }

    @Test fun scriptDetectionRespectsEthiopicAndLatin() {
        assertTrue(AddisScript.hasEthiopic("አዲስ ሪል"))
        assertTrue(AddisScript.hasEthiopic("Welcome ሰላም!"))
        assertFalse(AddisScript.hasEthiopic("Afaan Oromoo"))
        assertFalse(AddisScript.hasEthiopic("Hello world 123"))
        assertEquals(25.52f,AddisScript.lineHeightSp(22f,"ሰላም"),0.001f)
        assertEquals(22f,AddisScript.lineHeightSp(22f,"Hello"),0.001f)
        assertEquals(0f,AddisScript.letterSpacingSp(0.3f,"አዲስ"),0.001f)
        assertEquals(0.3f,AddisScript.letterSpacingSp(0.3f,"Oromoo"),0.001f)
    }

    @Test fun motionPreferenceDoesNotAddUnnecessaryAnimation() {
        assertEquals(0,AddisMotion.duration(AddisMotion.quickMs,true))
        assertEquals(140,AddisMotion.duration(AddisMotion.quickMs,false))
    }

    @Test fun designIsNotAnInvertedPalette() {
        assertFalse(AddisPalettes.Dark.background == AddisPalettes.Light.background)
        assertFalse(AddisPalettes.Dark.accent == AddisPalettes.Light.accent)
        assertEquals(48f,AddisSpacing.touch.value,0.001f)
        assertTrue(AddisSpacing.standard < AddisSpacing.section)
    }
}
