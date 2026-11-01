package host.vorv.tools

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleValidatorTest {

    @Test fun `valid minutes pass`() {
        assertNull(ScheduleValidator.checkMinutes(1.0))
        assertNull(ScheduleValidator.checkMinutes(5.0))
        assertNull(ScheduleValidator.checkMinutes(60.0))
        assertNull(ScheduleValidator.checkMinutes(10080.0)) // exactly 7 days
    }

    @Test fun `below one minute refused`() {
        assertNotNull(ScheduleValidator.checkMinutes(0.5))
        assertNotNull(ScheduleValidator.checkMinutes(0.0))
        assertNotNull(ScheduleValidator.checkMinutes(-5.0))
    }

    @Test fun `above seven days refused`() {
        assertNotNull(ScheduleValidator.checkMinutes(10081.0))
        assertNotNull(ScheduleValidator.checkMinutes(1e10))
    }

    @Test fun `non finite refused`() {
        assertNotNull(ScheduleValidator.checkMinutes(Double.NaN))
        assertNotNull(ScheduleValidator.checkMinutes(Double.POSITIVE_INFINITY))
    }
}
