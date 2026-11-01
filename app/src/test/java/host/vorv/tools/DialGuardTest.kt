package host.vorv.tools

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DialGuardTest {

    @Test fun `normal numbers accepted`() {
        assertNull(DialGuard.validate("+1 555 123 4567"))
        assertNull(DialGuard.validate("5551234567"))
        assertNull(DialGuard.validate("+44 20 7946 0958"))
        assertNull(DialGuard.validate("(555) 123-4567"))
    }

    @Test fun `us premium 900 refused`() {
        assertNotNull(DialGuard.validate("1-900-555-0199"))
    }

    @Test fun `us premium 976 refused`() {
        assertNotNull(DialGuard.validate("19765551234"))
    }

    @Test fun `uk premium 09 refused`() {
        assertNotNull(DialGuard.validate("09001234567"))
        assertNotNull(DialGuard.validate("0912345678"))
    }

    @Test fun `italian 89x refused`() {
        assertNotNull(DialGuard.validate("899123456"))
    }

    @Test fun `australian 190x refused`() {
        assertNotNull(DialGuard.validate("1900123456"))
    }

    @Test fun `too short refused`() {
        assertNotNull(DialGuard.validate("123"))
    }

    @Test fun `too long refused`() {
        assertNotNull(DialGuard.validate("+1" + "9".repeat(20)))
    }

    @Test fun `invalid characters refused`() {
        assertNotNull(DialGuard.validate("555;rm -rf"))
        assertNotNull(DialGuard.validate("tel:5551234567"))
    }

    @Test fun `empty refused`() {
        assertNotNull(DialGuard.validate("   "))
    }

    @Test fun `uk directory 118 refused`() {
        assertNotNull(DialGuard.validate("118118"))
    }
}
