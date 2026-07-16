package com.powerforge.core.research

import com.powerforge.core.physics.PartKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchTreeTest {

    @Test
    fun `every prerequisite id actually exists in the tree`() {
        val ids = ResearchTree.nodes.map { it.id }.toSet()
        ResearchTree.nodes.forEach { node ->
            node.prerequisiteIds.forEach { prereq ->
                assertTrue("$prereq referenced by ${node.id} is not a real node", prereq in ids)
            }
        }
    }

    @Test
    fun `node ids are unique`() {
        val ids = ResearchTree.nodes.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `boiler tier 2 cannot be researched before tier 1`() {
        assertFalse(ResearchTree.canResearch("BOILER_T2", emptySet()))
        assertTrue(ResearchTree.canResearch("BOILER_T1", emptySet()))
        assertTrue(ResearchTree.canResearch("BOILER_T2", setOf("BOILER_T1")))
    }

    @Test
    fun `max level starts at baseline and rises with researched tiers`() {
        assertEquals(ResearchTree.BASELINE_MAX_LEVEL, ResearchTree.maxLevelFor(PartKind.BOILER, emptySet()))
        assertEquals(4, ResearchTree.maxLevelFor(PartKind.BOILER, setOf("BOILER_T1")))
        assertEquals(8, ResearchTree.maxLevelFor(PartKind.BOILER, setOf("BOILER_T1", "BOILER_T2")))
        assertEquals(15, ResearchTree.maxLevelFor(PartKind.BOILER, setOf("BOILER_T1", "BOILER_T2", "BOILER_T3")))
    }

    @Test
    fun `controls stay locked until their operations node is researched`() {
        assertFalse(ResearchTree.isControlUnlocked(ControlUnlock.THROTTLE, emptySet()))
        assertTrue(ResearchTree.isControlUnlocked(ControlUnlock.THROTTLE, setOf("OPS_THROTTLE")))
    }

    @Test
    fun `manual relief valve requires the throttle valve first`() {
        assertFalse(ResearchTree.canResearch("OPS_SAFETY", emptySet()))
        assertTrue(ResearchTree.canResearch("OPS_SAFETY", setOf("OPS_THROTTLE")))
    }

    @Test
    fun `already researched nodes cannot be researched again`() {
        assertFalse(ResearchTree.canResearch("OPS_THROTTLE", setOf("OPS_THROTTLE")))
    }
}
