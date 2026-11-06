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

    @Test fun `uk premium 09 refused - when it is not a JP mobile shape`() {
        // M12: 11-digit 090… numbers are EXACTLY Japanese mobiles
        // (090-XXXX-XXXX) and are now accepted — see the JP test below.
        // UK premium that is NOT JP-shaped is still refused:
        assertNotNull(DialGuard.validate("09123456789"))   // 091… not a JP prefix
        assertNotNull(DialGuard.validate("0912345678"))    // 10 digits, not a JP shape
        assertNotNull(DialGuard.validate("09876543210"))   // 098… not a JP prefix
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

    // -------------------------------------------------- M12: JP mobiles

    @Test fun `japanese 090 080 070 mobiles are accepted`() {
        // The exact false positive from the code review: an ordinary
        // Japanese mobile was refused as premium-rate.
        assertNull(DialGuard.validate("090-1234-5678"))
        assertNull(DialGuard.validate("09012345678"))
        assertNull(DialGuard.validate("08012345678"))
        assertNull(DialGuard.validate("07012345678"))
    }

    @Test fun `japanese mobiles in international format are accepted`() {
        assertNull(DialGuard.validate("+81 90 1234 5678"))
        assertNull(DialGuard.validate("+819012345678"))
    }

    @Test fun `ambiguous 090 shape - JP mobile interpretation wins`() {
        // 09001234567: a valid UK premium shape AND a valid JP mobile
        // (090-0123-4567). M12 resolves the collision in favour of the JP
        // mobile; the user-confirmation dialog is the backstop for dials.
        assertNull(DialGuard.validate("09001234567"))
    }

    @Test fun `german 0900 premium-rate still refused`() {
        // The M12-corrected pattern: 0900 with fewer than 8 trailing digits
        // is not a JP mobile and stays blocked.
        assertNotNull(DialGuard.validate("0900123456"))
        assertNotNull(DialGuard.validate("09001234567".dropLast(1))) // 10 digits, not JP-shaped
    }
}
