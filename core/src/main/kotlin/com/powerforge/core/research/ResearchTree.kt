package com.powerforge.core.research

import com.powerforge.core.physics.PartKind
import com.powerforge.core.physics.exponentialCost

enum class ResearchBranch { BOILER, PISTON, FLYWHEEL, ROTOR, FRAME }

/**
 * A single node in a part's research chain. Researching it directly bumps that
 * part from its current level to [toLevel] - there is no separate purchase step,
 * the tree *is* the upgrade path. Each part is its own strictly linear branch:
 * you can't research level 5 before level 4.
 */
data class ResearchNode(
    val id: String,
    val branch: ResearchBranch,
    val label: String,
    val description: String,
    val costRp: Long,
    val prerequisiteIds: List<String>,
    val partKind: PartKind,
    val toLevel: Int,
)

object ResearchTree {

    const val MAX_LEVEL = 20

    private val branchMeta: List<Pair<PartKind, Pair<String, ResearchBranch>>> = listOf(
        PartKind.BOILER to ("Boiler" to ResearchBranch.BOILER),
        PartKind.PISTON to ("Piston & Crank" to ResearchBranch.PISTON),
        PartKind.FLYWHEEL to ("Flywheel" to ResearchBranch.FLYWHEEL),
        PartKind.ROTOR to ("Generator Rotor" to ResearchBranch.ROTOR),
        PartKind.FRAME to ("Frame & Bearings" to ResearchBranch.FRAME),
    )

    private val baseCostByKind: Map<PartKind, Int> = mapOf(
        PartKind.BOILER to 24,
        PartKind.PISTON to 28,
        PartKind.FLYWHEEL to 18,
        PartKind.ROTOR to 32,
        PartKind.FRAME to 36,
    )

    val nodes: List<ResearchNode> = buildList {
        branchMeta.forEach { (kind, meta) ->
            val (label, branch) = meta
            var previousId: String? = null
            for (level in 2..MAX_LEVEL) {
                val id = nodeId(kind, level)
                add(
                    ResearchNode(
                        id = id,
                        branch = branch,
                        label = "$label Mk.$level",
                        description = "Upgrade the $label to level $level.",
                        costRp = exponentialCost(baseCostByKind.getValue(kind), level - 1, growth = 1.28),
                        prerequisiteIds = previousId?.let { listOf(it) } ?: emptyList(),
                        partKind = kind,
                        toLevel = level,
                    )
                )
                previousId = id
            }
        }
    }

    val byId: Map<String, ResearchNode> = nodes.associateBy { it.id }

    private fun nodeId(kind: PartKind, level: Int) = "${kind.name}_LV$level"

    fun canResearch(nodeId: String, researchedIds: Set<String>): Boolean {
        val node = byId[nodeId] ?: return false
        if (node.id in researchedIds) return false
        return node.prerequisiteIds.all { it in researchedIds }
    }

    /** The node id for going from [currentLevel] to the next level of [partKind], or null if maxed. */
    fun nextNodeIdFor(partKind: PartKind, currentLevel: Int): String? {
        val nextLevel = currentLevel + 1
        if (nextLevel > MAX_LEVEL) return null
        return nodeId(partKind, nextLevel)
    }
}
