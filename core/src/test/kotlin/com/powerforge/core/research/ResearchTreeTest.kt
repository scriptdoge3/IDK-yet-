package com.powerforge.core.research

import com.powerforge.core.physics.PartKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `boiler level 4 cannot be researched before level 3`() {
        assertFalse(ResearchTree.canResearch("BOILER_LV4", emptySet()))
        assertTrue(ResearchTree.canResearch("BOILER_LV2", emptySet()))
        assertTrue(ResearchTree.canResearch("BOILER_LV3", setOf("BOILER_LV2")))
        assertTrue(ResearchTree.canResearch("BOILER_LV4", setOf("BOILER_LV2", "BOILER_LV3")))
    }

    @Test
    fun `next node id follows the current level`() {
        assertEquals("BOILER_LV2", ResearchTree.nextNodeIdFor(PartKind.BOILER, 1))
        assertEquals("BOILER_LV5", ResearchTree.nextNodeIdFor(PartKind.BOILER, 4))
        assertNull(ResearchTree.nextNodeIdFor(PartKind.BOILER, ResearchTree.MAX_LEVEL))
    }

    @Test
    fun `already researched nodes cannot be researched again`() {
        assertFalse(ResearchTree.canResearch("BOILER_LV2", setOf("BOILER_LV2")))
    }

    @Test
    fun `costs rise with level`() {
        val cheap = ResearchTree.byId.getValue("BOILER_LV2").costRp
        val pricier = ResearchTree.byId.getValue("BOILER_LV10").costRp
        assertTrue(pricier > cheap)
    }

    @Test
    fun `every part has a full chain from level 2 to max level`() {
        PartKind.entries.forEach { kind ->
            for (level in 2..ResearchTree.MAX_LEVEL) {
                assertTrue("missing node for $kind level $level", "${kind.name}_LV$level" in ResearchTree.byId)
            }
        }
    }
}
