package com.roomradar.core.traffic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultipleTestingTest {
    @Test
    fun benjaminiHochbergPreservesOrderAndCapsValues() {
        val adjusted = MultipleTesting.benjaminiHochberg(listOf(0.001, 0.01, 0.04, 0.9))

        assertEquals(4, adjusted.size)
        assertTrue(adjusted.all { it != null && it in 0.0..1.0 })
        // Raw order is preserved; the smallest p gets the smallest adjusted value.
        assertTrue(adjusted[0]!! < adjusted[3]!!)
        assertEquals(0.004, adjusted[0]!!, 0.000001)
        assertEquals(0.02, adjusted[1]!!, 0.000001)
    }

    @Test
    fun keepsNullsInPlace() {
        val adjusted = MultipleTesting.benjaminiHochberg(listOf(null, 0.02))
        assertNull(adjusted[0])
        assertEquals(0.02, adjusted[1]!!, 0.000001)
    }
}
