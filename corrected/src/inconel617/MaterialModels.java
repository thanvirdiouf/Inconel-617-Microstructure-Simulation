package inconel617;

import java.util.Objects;

/**
 * Constitutive laws preserved from the supplied Java prototypes.
 *
 * <p>Temperatures are Kelvin, strain rates are s^-1, stress/modulus are Pa
 * unless an accessor explicitly says MPa, and dislocation densities are m^-2.
 * These are reproducible candidate calibrations, not independently validated
 * material data. The caller supplies the actual current temperature; heating,
 * spatial evolution, initial grain size, and time stepping belong to the engine.
 */
public final class MaterialModels {
    private MaterialModels() {}

    public enum Kind { CAC2, MAIN9, VARIANT, FINAL_CA }

    public record Parameters(
            double temperatureK,
            double strainRate,
            double shearModulusPa,
            double burgersVectorM,
            double alpha,
            double k1,
            double k2,
            double criticalDensityM2,
            double saturationDensityM2,
            double steadyFactor,
            double mobility,
            double nucleationRatePerM2S,
            double yieldDensityM2,
            double peakStressMPa,
            double boundaryEnergyJPerM2) {
        public Parameters {
            positive("temperatureK", temperatureK);
            positive("strainRate", strainRate);
            positive("shearModulusPa", shearModulusPa);
            positive("burgersVectorM", burgersVectorM);
            positive("alpha", alpha);
            positive("k1", k1);
            positive("k2", k2);
            positive("criticalDensityM2", criticalDensityM2);
            positive("saturationDensityM2", saturationDensityM2);
            nonnegative("steadyFactor", steadyFactor);
            nonnegative("mobility", mobility);
            nonnegative("nucleationRatePerM2S", nucleationRatePerM2S);
            nonnegative("yieldDensityM2", yieldDensityM2);
            positive("peakStressMPa", peakStressMPa);
            positive("boundaryEnergyJPerM2", boundaryEnergyJPerM2);
        }
    }

    // Original constants: R [J/(mol K)], k_B [J/K], b [m], alpha [-].
    private static final double GAS_CONSTANT = 8.314;
    private static final double BOLTZMANN_CONSTANT = 1.38064852e-23;
    private static final double BURGERS_VECTOR_M = 2.5e-10;
    private static final double ALPHA = 0.5;
    // The high-angle boundary energy returned by Cell.getGamma() in all sources.
    private static final double BOUNDARY_ENERGY_J_PER_M2 = 0.8;
    private static final double LOG_TWO = Math.log(2.0);

    /** Evaluate the chosen laws at the actual temperature and unmodified rate. */
    public static Parameters evaluate(Kind kind, double temperatureK, double strainRate) {
        Objects.requireNonNull(kind, "kind");
        positive("temperatureK", temperatureK);
        positive("strainRate", strainRate);
        return switch (kind) {
            case CAC2 -> cac2(temperatureK, strainRate);
            case MAIN9 -> main9(temperatureK, strainRate);
            case VARIANT -> variant(temperatureK, strainRate);
            case FINAL_CA -> finalCa(temperatureK, strainRate);
        };
    }

    /** Human-readable source and intentional corrections for run metadata. */
    public static String provenance(Kind kind) {
        Objects.requireNonNull(kind, "kind");
        return switch (kind) {
            case CAC2 -> "CAC_final (2).java: active constitutive laws, fixed shear modulus "
                    + "46.4 GPa; CAC2 high-temperature nucleation expression uses "
                    + "1423.0/1223.0 instead of the original integer division; "
                    + "heating is supplied separately by the caller.";
            case MAIN9 -> "Main (9).java: active constitutive laws; user strain rate is "
                    + "used without the original factor of two; per-grain random density "
                    + "modifiers are excluded; shear modulus uses actual supplied temperature; "
                    + "melting-temperature conversion uses 273.15 instead of 273; "
                    + "nominal yield stress Sp/10 is reported as density but the source "
                    + "does not impose it as a grain-density floor.";
            case VARIANT -> "Main_variant.java: active temperature fits, sfact=1 and "
                    + "nDot=8.76e10 m^-2 s^-1; mobility exponent retains the original "
                    + "450 J/mol coefficient (its intended unit is undocumented); "
                    + "nonpositive fitted peak stress or k2 is rejected; "
                    + "shear modulus uses actual supplied temperature; Celsius and "
                    + "melting-temperature conversions use 273.15 instead of 273.";
            case FINAL_CA -> "files654/final-CA-15-4/untitled/src/Main.java: active source "
                    + "constitutive laws, critical factor 0.95 and temperature-only np; "
                    + "user strain rate is used without the original factor of two; "
                    + "Celsius and melting-temperature conversions use 273.15 instead of 273; "
                    + "per-grain random density modifiers are excluded by the corrected engine; "
                    + "source peak-stress exponent 3.86 is retained although the final report "
                    + "gives 3.91342 (unresolved); legacy melting constant 1453 C is retained "
                    + "in the shear-modulus fit; Db prefactor units require confirmation for "
                    + "the mobility law; saved Main.class uses a different np expression "
                    + "and is not the calibration source; Sp/10 yield density is diagnostic only.";
        };
    }

    private static Parameters cac2(double temperatureK, double strainRate) {
        // CAC_final (2).java:141-157. Compute ln Z directly to avoid exp overflow.
        double logZ = Math.log(strainRate) + 482_319.715203914 / (GAS_CONSTANT * temperatureK);
        double k2 = Math.exp(8.32 - 0.11 * logZ);
        double peakStressMPa = 105.0 * asinhExp(
                (logZ - Math.log(9_722_017_481_736_390.0)) / 3.1);
        double steadyFactor = logZ < 40.0
                ? -0.0527 * logZ + 2.738
                : 0.0345 * logZ - 0.6481;

        // CAC_final (2).java:176-206. Q_m is kJ/mol in the prototype.
        double mobilityActivationKJPerMol;
        if (temperatureK <= 1250.0) {
            mobilityActivationKJPerMol = 263.0;
        } else if (temperatureK <= 1323.0) {
            mobilityActivationKJPerMol = 239.0;
        } else {
            mobilityActivationKJPerMol = Math.max(100.0, 239.0 - 0.35 * (temperatureK - 1323.0));
        }
        double beta = 1.0;
        if (strainRate > 0.1) {
            // Original c1 is capped at 0.5; cap its log exponent before exp too.
            double c1 = 0.5 * Math.exp(Math.min(0.0,
                    40_000.0 / GAS_CONSTANT * (1.0 / temperatureK - 1.0 / 1273.0)));
            beta = Math.exp(Math.min(LOG_TWO, c1 * (Math.log(strainRate) - Math.log(0.1))));
        }
        double diffusivityTimesBoundaryWidth = 6.1e-4 * 5e-10;
        double mobility = beta * diffusivityTimesBoundaryWidth * BURGERS_VECTOR_M
                / (BOLTZMANN_CONSTANT * temperatureK)
                * Math.exp(-mobilityActivationKJPerMol * 1000.0 / (GAS_CONSTANT * temperatureK));

        // CAC_final (2).java:209-224. Correct integer truncation in the >1423 K
        // branch: the source's (1423/1223) evaluates to 1, not 1.1635....
        // The (1223/1223) lower reference is exactly 1 in either representation.
        double fc = Math.min(0.8, 0.64 + Math.max(0.0, temperatureK - 1373.0) * 0.0032);
        double referenceNt = -1.0 + 4.8 * Math.exp(-102.0 * square(1.0 - 1.09));
        double clampedTemperatureK = Math.min(temperatureK, 1423.0);
        double nt = Math.max(referenceNt,
                -1.0 + 4.8 * Math.exp(-102.0 * square(clampedTemperatureK / 1223.0 - 1.09)));
        double nucleationRate = Math.exp(Math.log(2e9 * nt)
                + fc * (Math.log(strainRate) - Math.log(0.001)));
        return parameters(temperatureK, strainRate, 46.4e9, k2, peakStressMPa,
                0.84, steadyFactor, mobility, nucleationRate, 4.0);
    }

    private static Parameters main9(double temperatureK, double strainRate) {
        // Main (9).java:76-117. No hidden doubling of the supplied strain rate.
        double logZ = Math.log(strainRate) + 493_039.0 / (GAS_CONSTANT * temperatureK);
        double k2 = Math.exp(8.9 - 0.15 * logZ);
        double peakStressMPa = asinhExp((logZ - 40.26) / 3.86) / 0.005439;
        double criticalFactor = 0.0026 * logZ + 0.81;
        double steadyFactor = Math.min(1.0, 0.0096 * logZ + 0.526);

        // Main (9).java:125-137. Q_f and Q_n are kJ/mol. Combining the
        // exponentials is algebraically equivalent and avoids overflow of either.
        double qfKJPerMol = 130.0 / (1.0 + Math.pow(temperatureK / 1333.0, 37.0)) + 620.0;
        double logDb = Math.log(1.7e-22)
                - qfKJPerMol * 1000.0 / (GAS_CONSTANT * temperatureK) + qfKJPerMol * 0.091;
        double mobility = Math.exp(logDb + Math.log(BURGERS_VECTOR_M)
                - Math.log(BOLTZMANN_CONSTANT) - Math.log(temperatureK));
        double np = (0.61 / (1.0 + Math.pow(temperatureK / 1338.0, 59.0)) + 0.39)
                * Math.exp(0.05 * (Math.log(strainRate) - Math.log(0.001)));
        double nucleationRate = Math.exp(Math.log(3.83e15)
                - 120_000.0 / (GAS_CONSTANT * temperatureK)
                + np * (Math.log(strainRate) - Math.log(0.2)));
        // Sp/10 is declared in the original. Its density is a diagnostic only;
        // unlike CAC2/VARIANT, MAIN9 does not impose a yield-density floor.
        return parameters(temperatureK, strainRate, temperatureShearModulus(temperatureK),
                k2, peakStressMPa, criticalFactor, steadyFactor, mobility, nucleationRate, 10.0);
    }

    private static Parameters finalCa(double temperatureK, double strainRate) {
        // files654/final-CA-15-4/untitled/src/Main.java:76-136. Preserve the
        // active source fit, independently of MAIN9 and the stale saved class.
        double logZ = Math.log(strainRate) + 493_039.0 / (GAS_CONSTANT * temperatureK);
        double k2 = Math.exp(8.9 - 0.15 * logZ);
        // The source uses 3.86; the report's 3.91342 remains unresolved.
        double peakStressMPa = asinhExp((logZ - 40.26) / 3.86) / 0.005439;
        double steadyFactor = Math.min(1.0, 0.0096 * logZ + 0.526);
        double qfKJPerMol = 130.0 / (1.0 + Math.pow(temperatureK / 1333.0, 37.0)) + 620.0;
        double logDb = Math.log(1.7e-22)
                - qfKJPerMol * 1000.0 / (GAS_CONSTANT * temperatureK) + qfKJPerMol * 0.091;
        double mobility = Math.exp(logDb + Math.log(BURGERS_VECTOR_M)
                - Math.log(BOLTZMANN_CONSTANT) - Math.log(temperatureK));
        double np = 0.61 / (1.0 + Math.pow(temperatureK / 1338.0, 59.0)) + 0.39;
        double nucleationRate = Math.exp(Math.log(3.83e15)
                - 120_000.0 / (GAS_CONSTANT * temperatureK)
                + np * (Math.log(strainRate) - Math.log(0.2)));
        return parameters(temperatureK, strainRate, temperatureShearModulus(temperatureK),
                k2, peakStressMPa, 0.95, steadyFactor, mobility, nucleationRate, 10.0);
    }

    private static Parameters variant(double temperatureK, double strainRate) {
        // Main_variant.java:131-151. The fits use Celsius. Correct the source's
        // rounded offset (273) to 273.15 to match the CLI's actual Kelvin input.
        double temperatureC = temperatureK - 273.15;
        double k2 = 0.21929 * temperatureC - 182.22;
        double peakStressMPa = 1758.96 - 1.41364 * temperatureC;
        positive("VARIANT fitted k2 (outside its positive-coefficient temperature interval)", k2);
        positive("VARIANT fitted peakStressMPa (outside its positive-stress temperature interval)",
                peakStressMPa);
        // Source says exp(-450/(R*T)); retain 450 J/mol, not an invented
        // 450 kJ/mol. Dimensional provenance of the 5e-9 prefactor is unclear.
        double mobility = 5e-9 * Math.exp(-450.0 / (GAS_CONSTANT * temperatureK));
        return parameters(temperatureK, strainRate, temperatureShearModulus(temperatureK),
                k2, peakStressMPa, 0.84, 1.0, mobility, 8.76e10, 4.0);
    }

    private static double temperatureShearModulus(double temperatureK) {
        // Main (9).java:80-81 and Main_variant.java:75-76. 1453 C melting
        // temperature converted to Kelvin with 273.15 (source used rounded 273);
        // 300 is the source's reference Kelvin temperature.
        double modulusPa = 78.9e9 * (1.0 - 0.64 * (temperatureK - 300.0) / (1453.0 + 273.15));
        positive("temperature-dependent shearModulusPa", modulusPa);
        return modulusPa;
    }

    private static Parameters parameters(double temperatureK, double strainRate,
            double shearModulusPa, double k2, double peakStressMPa,
            double criticalFactor, double steadyFactor, double mobility,
            double nucleationRate, double yieldStressDivisor) {
        positive("k2", k2);
        positive("peakStressMPa", peakStressMPa);
        positive("criticalFactor", criticalFactor);
        nonnegative("steadyFactor", steadyFactor);
        // Sources derive k1 from peak stress and use k1/k2 for density thresholds.
        double saturationRoot = peakStressMPa * 1e6 / (ALPHA * shearModulusPa * BURGERS_VECTOR_M);
        double k1 = k2 * saturationRoot;
        double yieldRoot = saturationRoot / yieldStressDivisor;
        return new Parameters(temperatureK, strainRate, shearModulusPa, BURGERS_VECTOR_M,
                ALPHA, k1, k2, square(criticalFactor * saturationRoot), square(saturationRoot),
                steadyFactor, mobility, nucleationRate, square(yieldRoot), peakStressMPa,
                BOUNDARY_ENERGY_J_PER_M2);
    }

    /** Stable asinh(exp(logX)) for the sources' inverse hyperbolic sine laws. */
    private static double asinhExp(double logX) {
        if (logX >= 0.0) {
            return logX + Math.log1p(Math.sqrt(1.0 + Math.exp(-2.0 * logX)));
        }
        double x = Math.exp(logX);
        return Math.log1p(x + x * x / (1.0 + Math.sqrt(1.0 + x * x)));
    }

    private static double square(double value) {
        return value * value;
    }

    private static void positive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive; got " + value);
        }
    }

    private static void nonnegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and nonnegative; got " + value);
        }
    }
}
