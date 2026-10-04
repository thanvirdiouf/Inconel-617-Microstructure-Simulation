package inconel617;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Dependency-free regression tests. Run with assertions enabled or disabled. */
public final class SimulationTest {
    private static final Path OUTPUT = temporaryDirectory();
    private static int passed;

    private SimulationTest() { }

    public static void main(String[] args) {
        run("invalid inputs", SimulationTest::invalidInputs);
        run("CLI units and validation", SimulationTest::cliInputs);
        run("CLI final-report preset and explicit overrides", SimulationTest::cliPresets);
        run("model coefficients and domain", SimulationTest::modelCoefficients);
        run("FINAL_CA independent source anchors", SimulationTest::finalCaAnchors);
        run("FINAL_CA rate scaling and MAIN9 compatibility", SimulationTest::finalCaDifferences);
        run("initial state and conservation", SimulationTest::initialState);
        run("boundary coverage and clearing", SimulationTest::boundaryCoverage);
        run("physical nucleation area under grid refinement", SimulationTest::nucleationArea);
        run("seeded reproducibility", SimulationTest::seededReproducibility);
        run("requested strain and physical time", SimulationTest::requestedStrain);
        run("defensive state copies", SimulationTest::defensiveCopies);
        run("travel bound and evolved state", SimulationTest::travelBound);
        run("new nucleus density", SimulationTest::nucleusDensity);
        run("constant-grain step refinement", SimulationTest::stepRefinement);
        run("constant-grain spatial units", SimulationTest::spatialUnits);
        System.out.println("Passed " + passed + " tests.");
    }

    private static void invalidInputs() {
        expectInvalid(() -> configuration(24, 24, 1e-6, 8e-6, 1373.15, 0,
                0.05, 42, MaterialModels.Kind.CAC2, 0.00138));
        expectInvalid(() -> configuration(24, 24, 0, 8e-6, 1373.15, 0.1,
                0.05, 42, MaterialModels.Kind.CAC2, 0.00138));
        expectInvalid(() -> configuration(24, 24, 1e-6, 8e-6, Double.NaN, 0.1,
                0.05, 42, MaterialModels.Kind.CAC2, 0.00138));
        expectInvalid(() -> configuration(2, 24, 1e-6, 8e-6, 1373.15, 0.1,
                0.05, 42, MaterialModels.Kind.CAC2, 0.00138));
        expectInvalid(() -> configuration(24, 24, 1e-6, 8e-6, 1373.15, 0.1,
                -0.01, 42, MaterialModels.Kind.CAC2, 0.00138));
        expectInvalid(() -> configuration(24, 24, 1e-6, 8e-6, 1373.15, 0.1,
                0.05, 42, MaterialModels.Kind.CAC2, 0));
        expectInvalid(() -> new Config(24, 24, 1e-6, 8e-6, 1e-6, 2e-6, 1373.15, 0.1,
                0.05, 42, MaterialModels.Kind.CAC2, 0.00138, 0.6,
                0.01, false, OUTPUT));
        expectInvalid(() -> new Config(3, 3, 1e200, 3e200, 1e200, 2e-6, 1373.15, 0.1,
                0.05, 42, MaterialModels.Kind.CAC2, 0.00138, 0.25,
                0.01, false, OUTPUT));
        expectInvalid(() -> new Config(3, 3, 1e-200, 3e-200, 1e-200, 2e-6, 1373.15, 0.1,
                0.05, 42, MaterialModels.Kind.CAC2, 0.00138, 0.25,
                0.01, false, OUTPUT));
        expectInvalid(() -> new Config(24, 24, 1e-6, 8e-6, 1e-6, -1e-6, 1373.15, 0.1,
                0.05, 42, MaterialModels.Kind.CAC2, 0.00138, 0.25,
                0.01, false, OUTPUT));
        expectInvalid(() -> new Config(24, 24, 1e-6, 8e-6, 1e-6, 25e-6, 1373.15, 0.1,
                0.05, 42, MaterialModels.Kind.CAC2, 0.00138, 0.25,
                0.01, false, OUTPUT));
    }

    private static void modelCoefficients() {
        for (MaterialModels.Kind kind : MaterialModels.Kind.values()) {
            for (double kelvin : new double[] {1173.15, 1373.15, 1473.15}) {
                var p = MaterialModels.evaluate(kind, kelvin, 0.01);
                finitePositive(p.k1(), "k1 " + kind);
                finitePositive(p.k2(), "k2 " + kind);
                finitePositive(p.shearModulusPa(), "shear modulus " + kind);
                finitePositive(p.criticalDensityM2(), "critical density " + kind);
                finitePositive(p.peakStressMPa(), "peak stress " + kind);
                finiteNonnegative(p.mobility(), "mobility " + kind);
                finiteNonnegative(p.nucleationRatePerM2S(), "nucleation " + kind);
            }
        }
        var slow = MaterialModels.evaluate(MaterialModels.Kind.MAIN9, 1373.15, 0.001);
        var fast = MaterialModels.evaluate(MaterialModels.Kind.MAIN9, 1373.15, 0.1);
        check(fast.peakStressMPa() > slow.peakStressMPa(),
                "MAIN9 peak stress must respond to increasing strain rate");
        var cooler = MaterialModels.evaluate(MaterialModels.Kind.MAIN9, 1173.15, 0.01);
        var hotter = MaterialModels.evaluate(MaterialModels.Kind.MAIN9, 1473.15, 0.01);
        check(cooler.peakStressMPa() > hotter.peakStressMPa(),
                "MAIN9 peak stress must respond to increasing temperature");
        check(hotter.mobility() > cooler.mobility(),
                "MAIN9 boundary mobility must increase over the supported temperature range");
        expectInvalid(() -> MaterialModels.evaluate(MaterialModels.Kind.VARIANT, 1523.15, 0.01));
        expectInvalid(() -> MaterialModels.evaluate(MaterialModels.Kind.VARIANT, 1073.15, 0.01));
    }

    private static void finalCaAnchors() {
        // Independent numerical reference: direct legacy expressions evaluated
        // outside this program, with the supplied rate and Celsius + 273.15.
        // Columns: T_K, rate, k2, peak_MPa, G_Pa, mobility, nDot,
        // critical_density, saturation_density, k1, steady_factor.
        double[][] anchors = {
            {1323.15, 0.001, 24.861318795723371, 95.673775829089919,
                48_969_239_405.613647, 2.4874684807053123e-12, 1_055_479_037.0385213,
                220_478_525_330_007.75, 244_297_534_991_698.34,
                388_582_917.79061717, 0.88994795945098804},
            {1473.15, 0.1, 24.706734448069071, 96.624413460794884,
                44_581_208_237.986275, 7.0971528000555738e-10, 162_211_840_778.60419,
                271_329_651_348_081.59, 300_642_272_961_863.25,
                428_391_031.81455135, 0.89034714520376657}
        };
        for (double[] reference : anchors) {
            var p = MaterialModels.evaluate(MaterialModels.Kind.FINAL_CA, reference[0], reference[1]);
            equal(reference[0], p.temperatureK(), 0, "FINAL_CA preserves supplied Kelvin temperature");
            equal(reference[1], p.strainRate(), 0, "FINAL_CA preserves supplied strain rate");
            relativeEqual(reference[2], p.k2(), "FINAL_CA k2 source anchor");
            relativeEqual(reference[3], p.peakStressMPa(), "FINAL_CA peak stress source anchor");
            relativeEqual(reference[4], p.shearModulusPa(), "FINAL_CA modulus source anchor");
            relativeEqual(reference[5], p.mobility(), "FINAL_CA mobility source anchor");
            relativeEqual(reference[6], p.nucleationRatePerM2S(), "FINAL_CA nucleation source anchor");
            relativeEqual(reference[7], p.criticalDensityM2(), "FINAL_CA critical density source anchor");
            relativeEqual(reference[8], p.saturationDensityM2(), "FINAL_CA saturation density source anchor");
            relativeEqual(reference[9], p.k1(), "FINAL_CA k1 source anchor");
            relativeEqual(reference[10], p.steadyFactor(), "FINAL_CA steady factor source anchor");
            equal(0.9025, p.criticalDensityM2() / p.saturationDensityM2(), 2e-15,
                    "FINAL_CA critical density is 0.95 squared times saturation density");
        }
    }

    private static void finalCaDifferences() {
        var slow = MaterialModels.evaluate(MaterialModels.Kind.FINAL_CA, 1373.15, 0.001);
        var fast = MaterialModels.evaluate(MaterialModels.Kind.FINAL_CA, 1373.15, 0.1);
        double inferredExponent = Math.log(fast.nucleationRatePerM2S() / slow.nucleationRatePerM2S())
                / Math.log(100.0);
        equal(0.49858033183066586, inferredExponent, 2e-13,
                "FINAL_CA rate scaling uses temperature-only np, without a hidden rate multiplier");
        relativeEqual(7_429_924_211.3540573, slow.nucleationRatePerM2S(),
                "FINAL_CA unmodified slow-rate nucleation anchor");
        relativeEqual(73_815_071_955.052689, fast.nucleationRatePerM2S(),
                "FINAL_CA unmodified fast-rate nucleation anchor");

        var oldSlow = MaterialModels.evaluate(MaterialModels.Kind.MAIN9, 1373.15, 0.001);
        var oldFast = MaterialModels.evaluate(MaterialModels.Kind.MAIN9, 1373.15, 0.1);
        relativeEqual(67_496_856_127.733086, oldFast.nucleationRatePerM2S(),
                "MAIN9 keeps its original rate-dependent np");
        relativeEqual(805_247_427_511_834.38, oldFast.criticalDensityM2(),
                "MAIN9 keeps its original lnZ-dependent critical density");
        equal(oldSlow.nucleationRatePerM2S(), slow.nucleationRatePerM2S(), 0,
                "MAIN9 and FINAL_CA share nucleation at the 0.001 reference rate");
        check(fast.nucleationRatePerM2S() > oldFast.nucleationRatePerM2S(),
                "FINAL_CA differs from MAIN9 away from the reference rate");
        check(fast.criticalDensityM2() > oldFast.criticalDensityM2(),
                "FINAL_CA retains a separate critical-density calibration");
        for (double rate : new double[] {0.001, 0.1}) {
            var current = MaterialModels.evaluate(MaterialModels.Kind.FINAL_CA, 1373.15, rate);
            var original = MaterialModels.evaluate(MaterialModels.Kind.MAIN9, 1373.15, rate);
            equal(original.k1(), current.k1(), 0, "common MAIN9/FINAL_CA k1");
            equal(original.k2(), current.k2(), 0, "common MAIN9/FINAL_CA k2");
            equal(original.peakStressMPa(), current.peakStressMPa(), 0,
                    "common MAIN9/FINAL_CA source peak law");
            equal(original.mobility(), current.mobility(), 0, "common MAIN9/FINAL_CA mobility");
            equal(original.shearModulusPa(), current.shearModulusPa(), 0,
                    "common MAIN9/FINAL_CA modulus");
        }
    }

    private static void cliInputs() {
        Config cfg = Main.parse(new String[] {
                "--temperature", "1100", "--strain-rate", ".001",
                "--cell-size", ".5", "--grain-size", "13", "--nucleus-size", "2",
                "--nucleation-band", "3",
                "--no-images", "--output", OUTPUT.toString()
        });
        equal(1373.15, cfg.temperatureK(), 1e-12, "CLI Celsius converts to kelvin");
        equal(0.001, cfg.strainRate(), 0, "CLI preserves supplied strain rate");
        equal(0.5e-6, cfg.cellSizeM(), 1e-18, "CLI cell size converts micrometres to metres");
        equal(13e-6, cfg.initialGrainDiameterM(), 1e-18, "CLI grain size converts to metres");
        equal(2e-6, cfg.nucleusDiameterM(), 1e-18, "CLI nucleus size converts to metres");
        equal(3e-6, cfg.nucleationBandWidthM(), 1e-18, "CLI nucleation band converts to metres");
        check(!cfg.images(), "CLI disables images when requested");
        Config finalCa = Main.parse(new String[] {"--model", "final_ca", "--temperature", "1050",
                "--strain-rate", "0.1", "--no-images", "--output", OUTPUT.toString()});
        check(finalCa.model() == MaterialModels.Kind.FINAL_CA, "CLI selects the distinct FINAL_CA model");
        equal(1323.15, finalCa.temperatureK(), 1e-12, "FINAL_CA CLI uses Celsius + 273.15");
        equal(0.1, finalCa.strainRate(), 0, "FINAL_CA CLI does not double the supplied rate");
        expectInvalid(() -> Main.parse(new String[] {"--temperature", "NaN"}));
        expectInvalid(() -> Main.parse(new String[] {"--temperature", "1250"}));
        expectInvalid(() -> Main.parse(new String[] {"--strain-rate", "1"}));
        expectInvalid(() -> Main.parse(new String[] {"--seed", "42", "--seed", "43"}));
        expectInvalid(() -> Main.parse(new String[] {"--unknown", "value"}));
        expectInvalid(() -> Main.parse(new String[] {"--temperature"}));
    }

    private static void cliPresets() {
        Config preset = Main.parse(new String[] {"--preset", "final-report",
                "--output", OUTPUT.toString()});
        check(preset.model() == MaterialModels.Kind.FINAL_CA, "final-report preset chooses FINAL_CA");
        check(preset.width() == 400 && preset.height() == 400,
                "final-report preset uses the nested source's 400 by 400 grid");
        equal(58e-6, preset.initialGrainDiameterM(), 1e-18,
                "final-report preset uses the source's 58 micrometre initial diameter");
        equal(1e-6, preset.cellSizeM(), 1e-18, "final-report preset uses 1 micrometre cells");

        Config before = Main.parse(new String[] {"--preset", "final-report", "--model", "main9",
                "--width", "60", "--height", "80", "--grain-size", "13", "--cell-size", "0.5",
                "--temperature", "1050", "--strain-rate", "0.1", "--strain", "0.2", "--seed", "17",
                "--no-images", "--output", OUTPUT.toString()});
        Config after = Main.parse(new String[] {"--model", "main9", "--width", "60", "--height", "80",
                "--grain-size", "13", "--cell-size", "0.5", "--temperature", "1050",
                "--strain-rate", "0.1", "--strain", "0.2", "--seed", "17", "--no-images",
                "--output", OUTPUT.toString(), "--preset", "final-report"});
        check(before.equals(after), "explicit overrides do not depend on preset argument order");
        check(before.model() == MaterialModels.Kind.MAIN9 && before.width() == 60 && before.height() == 80,
                "explicit model and grid override the final-report preset");
        equal(13e-6, before.initialGrainDiameterM(), 1e-18, "explicit grain size overrides preset");
        equal(0.5e-6, before.cellSizeM(), 1e-18, "explicit cell size overrides preset");
        equal(1323.15, before.temperatureK(), 1e-12, "preset honors explicit temperature");
        equal(0.1, before.strainRate(), 0, "preset honors the unmodified explicit rate");
        equal(0.2, before.targetStrain(), 0, "preset honors explicit strain");
        check(before.seed() == 17 && !before.images(), "preset honors seed and image options");

        Config implicitLegacy = Main.parse(new String[] {"--output", OUTPUT.toString()});
        Config explicitLegacy = Main.parse(new String[] {"--preset", "legacy", "--output", OUTPUT.toString()});
        check(implicitLegacy.equals(explicitLegacy), "omitting a preset preserves legacy defaults");
        check(implicitLegacy.model() == MaterialModels.Kind.CAC2 && implicitLegacy.width() == 100
                        && implicitLegacy.height() == 100, "legacy model and grid remain unchanged");
        equal(13e-6, implicitLegacy.initialGrainDiameterM(), 1e-18, "legacy grain size remains unchanged");
        expectInvalid(() -> Main.parse(new String[] {"--preset", "unknown"}));
    }

    private static void initialState() {
        DrxSimulation simulation = new DrxSimulation(small(42, 0.1, 0.06));
        var s = simulation.initialize();
        equal(0, s.strain(), 0, "initial strain");
        equal(0, s.timeSeconds(), 0, "initial time");
        equal(0, s.drxFraction(), 0, "initial DRX fraction");
        check(s.nucleiCount() == 0, "initial grains are not recrystallization events");
        check(s.drxGrainCount() == 0, "no initial DRX grains");
        check(s.stepCount() == 0, "initialization does not advance deformation");
        assertState(simulation, 24, 24);
        check(simulation.render().getWidth() == 24 && simulation.render().getHeight() == 24,
                "render matches the grid");
    }

    private static void boundaryCoverage() {
        int width = 4, height = 3;
        int[] rightSplit = {
                0, 0, 0, 1,
                0, 0, 0, 1,
                0, 0, 0, 1
        };
        boolean[] expectedRight = {
                false, false, true, true,
                false, false, true, true,
                false, false, true, true
        };
        check(Arrays.equals(expectedRight,
                DrxSimulation.detectBoundaries(width, height, rightSplit)),
                "a split on the last column must include both grain sides and the last row");
        int[] bottomSplit = {
                0, 0, 0, 0,
                0, 0, 0, 0,
                1, 1, 1, 1
        };
        boolean[] expectedBottom = {
                false, false, false, false,
                true, true, true, true,
                true, true, true, true
        };
        check(Arrays.equals(expectedBottom,
                DrxSimulation.detectBoundaries(width, height, bottomSplit)),
                "a split on the last row must include both grain sides and the last column");
        boolean[] uniform = DrxSimulation.detectBoundaries(width, height, new int[width * height]);
        for (boolean boundary : uniform) {
            check(!boundary, "uniform grain has no internal or stale boundaries");
        }
    }

    private static void seededReproducibility() {
        DrxSimulation first = initialized(small(734, 0.1, 0.06));
        DrxSimulation second = initialized(small(734, 0.1, 0.06));
        int[] initialIds = first.grainIds();
        check(Arrays.equals(first.grainIds(), second.grainIds()), "seeded initial grain map");
        check(Arrays.equals(first.grainDensities(), second.grainDensities()), "seeded initial density");
        first.render();
        first.advanceTo(0.0237);
        second.advanceTo(0.0237);
        check(first.snapshot().equals(second.snapshot()), "seeded evolved observables");
        check(Arrays.equals(first.grainIds(), second.grainIds()), "seeded evolved grain map");
        check(Arrays.equals(first.grainDensities(), second.grainDensities()), "seeded evolved densities");
        check(Arrays.equals(first.grainCellCounts(), second.grainCellCounts()), "seeded evolved counts");
        DrxSimulation different = initialized(small(735, 0.1, 0.06));
        check(!Arrays.equals(initialIds, different.grainIds()), "different seed changes initial microstructure");
    }

    private static void nucleationArea() {
        double coarseArea = straightInterfaceArea(24, 1e-6, 2e-6);
        double fineArea = straightInterfaceArea(48, 0.5e-6, 2e-6);
        double expectedPhysicalArea = 24e-6 * 2e-6;
        equal(expectedPhysicalArea, coarseArea, 1e-23,
                "straight-interface reactive area is interface length times physical band width");
        equal(expectedPhysicalArea, fineArea, 1e-23,
                "grid refinement preserves total physical nucleation area");
        double[] uniform = DrxSimulation.nucleationAreas(24, 24, new int[24 * 24], 1e-6, 2e-6);
        for (double area : uniform) {
            equal(0, area, 0, "a uniform grain has no internal nucleation interface");
        }
    }

    private static double straightInterfaceArea(int side, double cellSize, double bandWidth) {
        int[] ids = new int[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = side / 2; x < side; x++) {
                ids[y * side + x] = 1;
            }
        }
        double total = 0;
        for (double area : DrxSimulation.nucleationAreas(side, side, ids, cellSize, bandWidth)) {
            finiteNonnegative(area, "local nucleation area");
            total += area;
        }
        return total;
    }

    private static void requestedStrain() {
        for (MaterialModels.Kind kind : MaterialModels.Kind.values()) {
            Config cfg = configuration(24, 24, 1e-6, 30e-6, 1373.15,
                    0.1, 0.035, 42, kind, 0.003);
            DrxSimulation simulation = initialized(cfg);
            for (double strain : new double[] {0.0073, 0.0127, 0.035}) {
                var s = simulation.advanceTo(strain);
                equal(strain, s.strain(), 1e-12, "exact requested strain " + kind);
                equal(strain / cfg.strainRate(), s.timeSeconds(), 1e-10,
                        "time uses the requested rate without an implicit multiplier " + kind);
                assertState(simulation, 24, 24);
            }
        }
    }

    private static void defensiveCopies() {
        DrxSimulation simulation = initialized(small(99, 0.1, 0.06));
        int[] ids = simulation.grainIds();
        int[] counts = simulation.grainCellCounts();
        double[] density = simulation.grainDensities();
        boolean[] boundary = simulation.boundaryMask();
        int firstId = ids[0], firstCount = counts[0];
        double firstDensity = density[0];
        boolean firstBoundary = boundary[0];
        ids[0] = Integer.MAX_VALUE;
        counts[0] = -1;
        density[0] = Double.NaN;
        boundary[0] = !boundary[0];
        check(simulation.grainIds()[0] == firstId, "grain ids are read-only copies");
        check(simulation.grainCellCounts()[0] == firstCount, "grain counts are read-only copies");
        equal(firstDensity, simulation.grainDensities()[0], 0, "densities are read-only copies");
        check(simulation.boundaryMask()[0] == firstBoundary, "boundary mask is a read-only copy");
        simulation.assertInvariants();
    }

    private static void travelBound() {
        Config cfg = small(42, 0.001, 0.025);
        DrxSimulation simulation = initialized(cfg);
        simulation.advanceTo(cfg.targetStrain());
        check(simulation.stepCount() > 0, "deformation advances steps");
        check(simulation.maximumObservedCellTravel() > 0,
                "the travel-bound scenario must exercise boundary migration");
        check(simulation.maximumObservedCellTravel() <= cfg.maxCellTravel() + 1e-10,
                "every accepted step respects the configured cell-travel limit");
        check(simulation.snapshot().maxCellTravel() <= cfg.maxCellTravel() + 1e-10,
                "reported cell travel respects the configured limit");
        assertState(simulation, cfg.width(), cfg.height());
    }

    private static void nucleusDensity() {
        Config cfg = configuration(24, 24, 1e-6, 8e-6, 1173.15,
                0.001, 0.2, 123, MaterialModels.Kind.CAC2, 0.00138);
        DrxSimulation simulation = initialized(cfg);
        boolean observed = false;
        for (double strain = 0.00138; strain < cfg.targetStrain(); strain += 0.00138) {
            simulation.advanceTo(strain);
            double[] newborn = simulation.lastNucleusDensities();
            if (newborn.length != 0) {
                for (double rho : newborn) {
                    equal(1, rho, 0, "a newly inserted nucleus retains its specified density");
                }
                observed = true;
                break;
            }
        }
        check(observed, "the seeded nucleation scenario must exercise at least one insertion");
        assertState(simulation, cfg.width(), cfg.height());
    }

    private static void stepRefinement() {
        Config coarse = configuration(24, 24, 1e-6, 30e-6, 1373.15,
                0.01, 0.1, 901, MaterialModels.Kind.CAC2, 0.005);
        Config fine = configuration(24, 24, 1e-6, 30e-6, 1373.15,
                0.01, 0.1, 901, MaterialModels.Kind.CAC2, 0.0005);
        Config reference = configuration(24, 24, 1e-6, 30e-6, 1373.15,
                0.01, 0.1, 901, MaterialModels.Kind.CAC2, 0.00005);
        DrxSimulation a = initialized(coarse), b = initialized(fine), c = initialized(reference);
        double initialStress = a.snapshot().flowStressMPa();
        a.advanceTo(coarse.targetStrain());
        b.advanceTo(fine.targetStrain());
        c.advanceTo(reference.targetStrain());
        check(a.snapshot().grainCount() == 1 && b.snapshot().grainCount() == 1,
                "refinement scenario remains a single grain");
        check(a.snapshot().nucleiCount() == 0 && b.snapshot().nucleiCount() == 0,
                "refinement excludes stochastic recrystallization");
        check(a.snapshot().flowStressMPa() >= initialStress,
                "short boundary-free deformation hardens the initial grain");
        double relative = relativeDifference(a.snapshot().flowStressMPa(), b.snapshot().flowStressMPa());
        check(relative < 0.02, "constant-grain stress changes less than 2% under 10x step refinement: " + relative);
        double coarseError = relativeDifference(a.snapshot().flowStressMPa(), c.snapshot().flowStressMPa());
        double fineError = relativeDifference(b.snapshot().flowStressMPa(), c.snapshot().flowStressMPa());
        check(fineError < coarseError,
                "smaller strain steps improve agreement with the 100x nominal refinement reference");
        System.out.printf("  stress MPa (coarse/fine/reference): %.9g / %.9g / %.9g; "
                + "relative reference errors %.6g / %.6g%n", a.snapshot().flowStressMPa(),
                b.snapshot().flowStressMPa(), c.snapshot().flowStressMPa(), coarseError, fineError);
        assertState(a, 24, 24);
        assertState(b, 24, 24);
        assertState(c, 24, 24);
    }

    private static void spatialUnits() {
        Config coarse = configuration(24, 24, 1e-6, 30e-6, 1373.15,
                0.01, 0.1, 901, MaterialModels.Kind.CAC2, 0.001);
        Config fine = configuration(48, 48, 0.5e-6, 30e-6, 1373.15,
                0.01, 0.1, 901, MaterialModels.Kind.CAC2, 0.001);
        DrxSimulation a = initialized(coarse), b = initialized(fine);
        a.advanceTo(coarse.targetStrain());
        b.advanceTo(fine.targetStrain());
        check(a.snapshot().grainCount() == 1 && b.snapshot().grainCount() == 1,
                "spatial-unit scenario remains a single grain");
        equal(a.snapshot().meanGrainDiameterM(), b.snapshot().meanGrainDiameterM(), 1e-12,
                "equal physical domains retain equal grain diameters under grid refinement");
        equal(a.snapshot().flowStressMPa(), b.snapshot().flowStressMPa(),
                1e-9 * Math.max(1, a.snapshot().flowStressMPa()),
                "boundary-free material response uses physical units independently of pixel size");
        assertState(a, 24, 24);
        assertState(b, 48, 48);
    }

    private static void assertState(DrxSimulation simulation, int width, int height) {
        simulation.assertInvariants();
        int[] ids = simulation.grainIds(), counts = simulation.grainCellCounts();
        double[] density = simulation.grainDensities();
        boolean[] boundary = simulation.boundaryMask();
        check(ids.length == width * height && boundary.length == ids.length, "cell-array dimensions");
        check(counts.length == density.length, "grain-array dimensions");
        int[] actual = new int[counts.length];
        for (int id : ids) {
            check(id >= 0 && id < counts.length, "every cell belongs to an existing grain");
            actual[id]++;
        }
        check(Arrays.equals(actual, counts), "grain cell counts agree with the complete lattice");
        long sum = 0;
        for (int i = 0; i < counts.length; i++) {
            check(counts[i] >= 0, "no negative grain population");
            sum += counts[i];
            finiteNonnegative(density[i], "grain density");
        }
        check(sum == (long) width * height, "cell conservation");
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                boolean differs = x > 0 && ids[i - 1] != ids[i]
                        || x + 1 < width && ids[i + 1] != ids[i]
                        || y > 0 && ids[i - width] != ids[i]
                        || y + 1 < height && ids[i + width] != ids[i];
                check(boundary[i] == differs, "boundary mask matches current neighbours at " + x + "," + y);
            }
        }
        var s = simulation.snapshot();
        finiteNonnegative(s.strain(), "strain");
        finiteNonnegative(s.timeSeconds(), "time");
        finiteNonnegative(s.flowStressMPa(), "flow stress");
        finitePositive(s.meanGrainDiameterM(), "mean grain diameter");
        finiteNonnegative(s.meanDrxGrainDiameterM(), "mean DRX grain diameter");
        check(Double.isFinite(s.drxFraction()) && s.drxFraction() >= 0 && s.drxFraction() <= 1,
                "DRX fraction lies in [0,1]");
        int live = 0;
        for (int count : counts) {
            if (count > 0) live++;
        }
        check(s.grainCount() == live, "reported grain count includes only live grains");
    }

    private static Config small(long seed, double rate, double strain) {
        return configuration(24, 24, 1e-6, 8e-6, 1373.15, rate,
                strain, seed, MaterialModels.Kind.CAC2, 0.00138);
    }

    private static Config configuration(int width, int height, double cell, double diameter,
            double temperature, double rate, double strain, long seed,
            MaterialModels.Kind model, double strainStep) {
        return new Config(width, height, cell, diameter, 1e-6, 2e-6, temperature, rate, strain,
                seed, model, strainStep, 0.25, 0.01, false, OUTPUT);
    }

    private static DrxSimulation initialized(Config cfg) {
        DrxSimulation simulation = new DrxSimulation(cfg);
        simulation.initialize();
        return simulation;
    }

    private static double relativeDifference(double a, double b) {
        return Math.abs(a - b) / Math.max(1e-12, Math.abs(b));
    }

    private static void finitePositive(double value, String name) {
        check(Double.isFinite(value) && value > 0, name + " must be finite and positive: " + value);
    }

    private static void finiteNonnegative(double value, String name) {
        check(Double.isFinite(value) && value >= 0, name + " must be finite and nonnegative: " + value);
    }

    private static void equal(double expected, double actual, double tolerance, String message) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
                message + ": expected " + expected + ", got " + actual);
    }

    private static void relativeEqual(double expected, double actual, String message) {
        equal(expected, actual, Math.abs(expected) * 2e-12, message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectInvalid(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid input was accepted");
    }

    private static void run(String name, Runnable test) {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("inconel617-tests-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
