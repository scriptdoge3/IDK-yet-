package com.powerforge.core.research

import com.powerforge.core.physics.PartKind

enum class ResearchBranch { BOILER, PISTON, FLYWHEEL, ROTOR, FRAME, OPERATIONS }

enum class ControlUnlock { THROTTLE, IGNITION, GENERATOR_CLUTCH, SAFETY_VALVE, LUBRICATION }

sealed interface ResearchEffect {
    data class RaisePartLevelCap(val partKind: PartKind, val newMaxLevel: Int) : ResearchEffect
    data class UnlockControl(val control: ControlUnlock) : ResearchEffect
}

data class ResearchNode(
    val id: String,
    val branch: ResearchBranch,
    val label: String,
    val description: String,
    val costRp: Long,
    val prerequisiteIds: List<String>,
    val effect: ResearchEffect,
)

/**
 * The whole tech tree, as a flat static list of nodes. Part branches gate which
 * upgrade levels are purchasable with credits; the Operations branch gates which
 * physical controls on [com.powerforge.core.physics.SteamEnginePlant] actually exist
 * yet. Everyone starts able to buy levels 1-2 of every part with no research at all.
 */
object ResearchTree {

    const val BASELINE_MAX_LEVEL = 2

    val nodes: List<ResearchNode> = buildList {
        val partBranches = listOf(
            PartKind.BOILER to ("Boiler" to ResearchBranch.BOILER),
            PartKind.PISTON to ("Piston & Crank" to ResearchBranch.PISTON),
            PartKind.FLYWHEEL to ("Flywheel" to ResearchBranch.FLYWHEEL),
            PartKind.ROTOR to ("Generator Rotor" to ResearchBranch.ROTOR),
            PartKind.FRAME to ("Frame & Bearings" to ResearchBranch.FRAME),
        )

        partBranches.forEach { (kind, meta) ->
            val (label, branch) = meta
            val tier1Id = "${kind.name}_T1"
            val tier2Id = "${kind.name}_T2"
            val tier3Id = "${kind.name}_T3"

            add(
                ResearchNode(
                    id = tier1Id,
                    branch = branch,
                    label = "$label Fundamentals",
                    description = "Unlocks $label levels up to 4.",
                    costRp = 30,
                    prerequisiteIds = emptyList(),
                    effect = ResearchEffect.RaisePartLevelCap(kind, 4),
                )
            )
            add(
                ResearchNode(
                    id = tier2Id,
                    branch = branch,
                    label = "$label Engineering",
                    description = "Unlocks $label levels up to 8.",
                    costRp = 90,
                    prerequisiteIds = listOf(tier1Id),
                    effect = ResearchEffect.RaisePartLevelCap(kind, 8),
                )
            )
            add(
                ResearchNode(
                    id = tier3Id,
                    branch = branch,
                    label = "Advanced $label",
                    description = "Unlocks $label levels up to 15.",
                    costRp = 220,
                    prerequisiteIds = listOf(tier2Id),
                    effect = ResearchEffect.RaisePartLevelCap(kind, 15),
                )
            )
        }

        add(
            ResearchNode(
                id = "OPS_THROTTLE",
                branch = ResearchBranch.OPERATIONS,
                label = "Throttle Valve",
                description = "Manually restrict steam admission instead of always running wide open.",
                costRp = 20,
                prerequisiteIds = emptyList(),
                effect = ResearchEffect.UnlockControl(ControlUnlock.THROTTLE),
            )
        )
        add(
            ResearchNode(
                id = "OPS_IGNITION",
                branch = ResearchBranch.OPERATIONS,
                label = "Ignition System",
                description = "Light and kill the burner on demand instead of it always running.",
                costRp = 20,
                prerequisiteIds = emptyList(),
                effect = ResearchEffect.UnlockControl(ControlUnlock.IGNITION),
            )
        )
        add(
            ResearchNode(
                id = "OPS_CLUTCH",
                branch = ResearchBranch.OPERATIONS,
                label = "Generator Clutch",
                description = "Disengage the generator to spin the flywheel up freely before loading it.",
                costRp = 35,
                prerequisiteIds = emptyList(),
                effect = ResearchEffect.UnlockControl(ControlUnlock.GENERATOR_CLUTCH),
            )
        )
        add(
            ResearchNode(
                id = "OPS_SAFETY",
                branch = ResearchBranch.OPERATIONS,
                label = "Manual Relief Valve",
                description = "Bleed pressure on demand instead of relying only on the automatic relief valve.",
                costRp = 35,
                prerequisiteIds = listOf("OPS_THROTTLE"),
                effect = ResearchEffect.UnlockControl(ControlUnlock.SAFETY_VALVE),
            )
        )
        add(
            ResearchNode(
                id = "OPS_LUBE",
                branch = ResearchBranch.OPERATIONS,
                label = "Lubrication System",
                description = "Model bearing wear from oil starvation, and let you service it.",
                costRp = 50,
                prerequisiteIds = listOf("OPS_CLUTCH"),
                effect = ResearchEffect.UnlockControl(ControlUnlock.LUBRICATION),
            )
        )
    }

    val byId: Map<String, ResearchNode> = nodes.associateBy { it.id }

    fun canResearch(nodeId: String, researchedIds: Set<String>): Boolean {
        val node = byId[nodeId] ?: return false
        if (node.id in researchedIds) return false
        return node.prerequisiteIds.all { it in researchedIds }
    }

    fun maxLevelFor(partKind: PartKind, researchedIds: Set<String>): Int {
        var maxLevel = BASELINE_MAX_LEVEL
        for (node in nodes) {
            val effect = node.effect
            if (effect is ResearchEffect.RaisePartLevelCap && effect.partKind == partKind && node.id in researchedIds) {
                maxLevel = maxOf(maxLevel, effect.newMaxLevel)
            }
        }
        return maxLevel
    }

    fun isControlUnlocked(control: ControlUnlock, researchedIds: Set<String>): Boolean =
        nodes.any { node ->
            val effect = node.effect
            effect is ResearchEffect.UnlockControl && effect.control == control && node.id in researchedIds
        }
}
