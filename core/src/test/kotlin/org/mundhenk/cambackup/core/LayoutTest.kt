package org.mundhenk.cambackup.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LayoutTest {
    private val s = File.separator

    @Test
    fun presetsPatternsAndFlat() {
        assertEquals("", Layout.subfolder(null, "2026-10-27"))                    // default: flat
        assertEquals("", Layout.subfolder("flat", "2026-10-27"))
        assertEquals("", Layout.subfolder("none", "2026-10-27"))
        assertEquals("", Layout.subfolder("  ", "2026-10-27"))
        assertEquals("2026-10-27", Layout.subfolder("day", "2026-10-27"))
        assertEquals("2026${s}10", Layout.subfolder("month", "2026-10-27"))
        assertEquals("2026", Layout.subfolder("YEAR", "2026-10-27"))
        assertEquals("2026${s}10${s}27", Layout.subfolder("{yyyy}/{MM}/{dd}", "2026-10-27"))
        assertEquals("2026-10", Layout.subfolder("{yyyy}-{MM}", "2026-10-27"))
        assertEquals("pics${s}2026", Layout.subfolder("pics\\{yyyy}", "2026-10-27"))
        // odd camera dates degrade to placeholders, never to a crash or an empty segment
        assertEquals("unknown-00-00", Layout.subfolder("day", ""))
        assertEquals("2026-00-00", Layout.subfolder("day", "2026"))
        assertEquals("2026${s}1", Layout.subfolder("month", "2026/1/5"))
        assertEquals("unknown-date-00", Layout.subfolder("day", "unknown-date"))
        assertEquals("a_b", Layout.subfolder("a:b", "2026-10-27"))                 // segments are sanitised
        assertEquals(Layout.DAY, Layout.normalize("day")); assertEquals("", Layout.normalize("-")); assertEquals("{yyyy}", Layout.normalize(" {yyyy} "))
        assertEquals(File("/root", "SAM_1.JPG").path, Layout.target(File("/root"), "flat", "2026-10-27", "SAM_1.JPG").path)
        assertEquals(File(File("/root", "2026-10-27"), "a_b.JPG").path, Layout.target(File("/root"), "day", "2026-10-27", "a b.JPG").path)
    }
}
