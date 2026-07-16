package com.powerforge.core.physics

/** Real-world physical constants used by the simulation (SI units throughout). */
object PhysicsConstants {
    const val GRAVITY_M_PER_S2 = 9.81
    const val WATER_SPECIFIC_HEAT_J_PER_KG_K = 4186.0
    const val WATER_LATENT_HEAT_VAPORIZATION_J_PER_KG = 2_257_000.0
    const val STEAM_SPECIFIC_GAS_CONSTANT_J_PER_KG_K = 461.5
    const val WATER_DENSITY_KG_PER_M3 = 1000.0
    const val ATMOSPHERIC_PRESSURE_PA = 101_325.0
    const val REFERENCE_BOILING_POINT_K = 373.15
    const val AMBIENT_TEMPERATURE_K = 293.15

    /** Static friction always exceeds kinetic friction for real bearings. */
    const val STATIC_FRICTION_MULTIPLIER = 1.6

    // --- Air, for windage/vacuum drag on parts moving through the surrounding atmosphere ---
    const val AIR_DENSITY_KG_PER_M3 = 1.204
    const val AIR_KINEMATIC_VISCOSITY_M2_PER_S = 1.516e-5

    // --- Cast iron: the flywheel's material. Real published values for grey cast iron
    // (e.g. ASTM A48 class 30) - notably weak in tension, which is exactly the stress a
    // spinning rim is under, so this is what actually determines a real burst speed. ---
    const val CAST_IRON_DENSITY_KG_PER_M3 = 7200.0
    const val CAST_IRON_TENSILE_STRENGTH_PA = 200e6
    const val CAST_IRON_YOUNGS_MODULUS_PA = 110e9

    /**
     * A first, unrefined sand-cast flywheel isn't graded foundry stock - real castings
     * with uncontrolled cooling carry shrinkage porosity and slag inclusions that
     * foundry literature documents derating as-cast tensile strength well below a
     * standard grade's nominal minimum (often to roughly half, for badly-flawed
     * castings), which is exactly the historical failure mode behind real 19th-century
     * cast iron fractures. This is that flawed-casting floor, well under even ASTM A48's
     * lowest standard commercial grade (class 20, ~138 MPa) - not an invented weak
     * number, a real documented consequence of poor casting quality.
     */
    const val FLAWED_CAST_IRON_TENSILE_STRENGTH_PA = 65e6

    /**
     * Each flywheel level represents investing in better casting quality control, closing
     * the gap from a flawed first casting up toward clean, standard-grade iron - real
     * foundry improvement (tighter cooling control, degassing, fewer inclusions) tracks
     * roughly linearly with process investment, not a curve fit to a target RPM.
     */
    const val CAST_IRON_GRADE_IMPROVEMENT_PA_PER_LEVEL = 15e6

    // --- Mild steel: the boiler shell and the piston/rod. Real published values. ---
    const val STEEL_DENSITY_KG_PER_M3 = 7850.0
    const val STEEL_YIELD_STRENGTH_PA = 250e6
    const val STEEL_ULTIMATE_TENSILE_STRENGTH_PA = 400e6
    const val STEEL_LINEAR_EXPANSION_COEFF_PER_K = 12e-6

    /**
     * Historic boiler codes (e.g. ASME's original 1915 rules for riveted boilers)
     * required a safety factor of about 4:1 against ultimate tensile strength - the real
     * engineering margin between a boiler's design pressure and the pressure that
     * actually bursts the shell, not an assumed number.
     */
    const val BOILER_DESIGN_SAFETY_FACTOR = 4.0

    // --- Silicon/electrical steel core-loss (Steinmetz) coefficients, for the eddy
    // current and hysteresis drag in the generator's iron core. Typical published values
    // for non-oriented electrical steel (loss ~1-3 W/kg at 1.5 T, 50 Hz is the usual
    // datasheet reference point these reproduce). ---
    const val STEINMETZ_HYSTERESIS_COEFF = 0.02
    const val STEINMETZ_EDDY_CURRENT_COEFF = 0.0001
    const val STEINMETZ_HYSTERESIS_EXPONENT = 1.6
    const val RATED_MAGNETIC_FLUX_DENSITY_T = 1.2

    // --- Combustion flue gas: mostly N2 (from air) plus CO2 and H2O, real average molar
    // mass for a hydrocarbon-burner's exhaust is close to N2's own (~28-30 g/mol). Real
    // practical flame temperature (adiabatic flame temp for hydrocarbons is ~2000-2260K;
    // real burners with normal excess air and real heat loss run cooler, ~1700-1900K is a
    // commonly cited practical figure) - not the same number as the boiler's own
    // temperature, which is what the flame particles heat up via real collisions. ---
    const val FLUE_GAS_MOLECULE_MASS_KG = 4.816e-26
    const val FLAME_TEMPERATURE_K = 1800.0

    /** Real order-of-magnitude dwell time for combustion gas passing through a small boiler's heat-exchange surfaces before it exits up the stack. */
    const val FLUE_GAS_RESIDENCE_TIME_SECONDS = 1.0
}
