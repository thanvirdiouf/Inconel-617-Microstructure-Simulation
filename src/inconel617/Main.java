package inconel617;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;

/** Command-line entry point; input lengths are micrometres and temperature is Celsius. */
public final class Main {
    private static final Set<String> OPTIONS = Set.of(
            "temperature", "strain-rate", "strain", "width", "height", "cell-size",
            "grain-size", "nucleus-size", "nucleation-band", "seed", "model", "max-strain-step", "max-cell-travel",
            "output-strain-step", "output", "preset");
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private Main() {}

    public static void main(String[] args) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.print(usage());
            return;
        }
        try {
            Config config = parse(args);
            run(config);
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid configuration: " + exception.getMessage());
            System.err.println("Use --help for options and units.");
            System.exit(2);
        } catch (IOException | IllegalStateException exception) {
            System.err.println("Run failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    static Config parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        boolean images = true;
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (argument.equals("--no-images")) {
                if (!images) {
                    throw new IllegalArgumentException("duplicate option --no-images");
                }
                images = false;
                continue;
            }
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("expected an option, received " + argument);
            }
            int equals = argument.indexOf('=');
            String name = argument.substring(2, equals < 0 ? argument.length() : equals);
            if (!OPTIONS.contains(name)) {
                throw new IllegalArgumentException("unknown option --" + name);
            }
            String value;
            if (equals >= 0) {
                value = argument.substring(equals + 1);
            } else {
                if (++i >= args.length || args[i].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for --" + name);
                }
                value = args[i];
            }
            if (value.isBlank()) {
                throw new IllegalArgumentException("missing value for --" + name);
            }
            if (values.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("duplicate option --" + name);
            }
        }
        String preset = values.getOrDefault("preset", "legacy");
        if (!preset.equals("legacy") && !preset.equals("final-report")) {
            throw new IllegalArgumentException("preset must be legacy or final-report");
        }
        boolean finalReport = preset.equals("final-report");
        double temperatureC = number(values, "temperature", 1100.0);
        double strainRate = number(values, "strain-rate", 0.001);
        if (!Double.isFinite(temperatureC) || temperatureC < 900.0 || temperatureC > 1200.0) {
            throw new IllegalArgumentException("temperature must be between 900 and 1200 degrees C");
        }
        if (!Double.isFinite(strainRate) || strainRate < 0.001 || strainRate > 0.1) {
            throw new IllegalArgumentException("strain rate must be between 0.001 and 0.1 per second");
        }
        MaterialModels.Kind model;
        try {
            model = MaterialModels.Kind.valueOf(values.getOrDefault("model", finalReport ? "final_ca" : "cac2")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("model must be cac2, main9, final_ca, or variant");
        }
        Path output = values.containsKey("output") ? Path.of(values.get("output"))
                : Path.of(System.getProperty("inconel.output.root", "runs"),
                        RUN_TIME.format(Instant.now()) + "-" + model.name().toLowerCase(Locale.ROOT)
                                + "-" + UUID.randomUUID().toString().substring(0, 8));
        return new Config(integer(values, "width", finalReport ? 400 : 100),
                integer(values, "height", finalReport ? 400 : 100),
                number(values, "cell-size", 1.0) * 1e-6,
                number(values, "grain-size", finalReport ? 58.0 : 13.0) * 1e-6,
                number(values, "nucleus-size", 1.0) * 1e-6,
                number(values, "nucleation-band", 2.0) * 1e-6,
                temperatureC + 273.15, strainRate, number(values, "strain", 1.38),
                longInteger(values, "seed", 42), model,
                number(values, "max-strain-step", 0.00138),
                number(values, "max-cell-travel", 0.25),
                number(values, "output-strain-step", 0.05), images, output);
    }

    private static double number(Map<String, String> values, String name, double fallback) {
        try {
            return values.containsKey(name) ? Double.parseDouble(values.get(name)) : fallback;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + name + " requires a number");
        }
    }

    private static int integer(Map<String, String> values, String name, int fallback) {
        try {
            return values.containsKey(name) ? Integer.parseInt(values.get(name)) : fallback;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + name + " requires a whole number");
        }
    }

    private static long longInteger(Map<String, String> values, String name, long fallback) {
        try {
            return values.containsKey(name) ? Long.parseLong(values.get(name)) : fallback;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + name + " requires a 64-bit whole number");
        }
    }

    private static void run(Config config) throws IOException {
        prepareDirectory(config.outputDirectory());
        writeMetadata(config);
        System.out.printf(Locale.ROOT,
                "Running %s at %.2f C, %.6g s^-1, strain %.6g, seed %d%nOutput: %s%n",
                config.model().name().toLowerCase(Locale.ROOT), config.temperatureK() - 273.15,
                config.strainRate(), config.targetStrain(), config.seed(), config.outputDirectory());
        DrxSimulation simulation = new DrxSimulation(config);
        DrxSimulation.Snapshot snapshot = simulation.initialize();
        Path csv = config.outputDirectory().resolve("results.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writer.write("strain,time_s,flow_stress_MPa,mean_grain_diameter_um,"
                    + "mean_drx_grain_diameter_um,drx_fraction,grain_count,drx_grain_count,"
                    + "nuclei_count,step_count,max_cell_travel\n");
            writeRow(writer, snapshot);
            if (config.images()) {
                saveImage(simulation.render(), config.outputDirectory(), 0, snapshot.strain());
            }
            long index = 1;
            int reported = 0;
            while (snapshot.strain() < config.targetStrain()) {
                double target = Math.min(config.targetStrain(), index * config.outputStrainStep());
                if (!Double.isFinite(target) || target <= snapshot.strain()) {
                    throw new IllegalStateException("output strain step cannot advance at this precision");
                }
                snapshot = simulation.advanceTo(target);
                simulation.assertInvariants();
                writeRow(writer, snapshot);
                writer.flush();
                if (config.images()) {
                    saveImage(simulation.render(), config.outputDirectory(), index, snapshot.strain());
                }
                int percent = (int) Math.floor(snapshot.strain() / config.targetStrain() * 100.0);
                if (percent >= reported + 20 && percent < 100) {
                    reported = percent / 20 * 20;
                    System.out.printf(Locale.ROOT, "%d%% complete; strain %.5f%n",
                            reported, snapshot.strain());
                }
                index++;
            }
        }
        Files.writeString(config.outputDirectory().resolve("summary.txt"),
                String.format(Locale.ROOT,
                        "status=complete%nfinal_strain=%.12g%nfinal_time_s=%.12g%n"
                                + "final_flow_stress_MPa=%.12g%nfinal_drx_fraction=%.12g%n"
                                + "final_mean_grain_diameter_um=%.12g%n"
                                + "final_mean_drx_grain_diameter_um=%.12g%n"
                                + "steps=%d%nmaximum_observed_cell_travel=%.12g%n",
                        snapshot.strain(), snapshot.timeSeconds(), snapshot.flowStressMPa(),
                        snapshot.drxFraction(), snapshot.meanGrainDiameterM() * 1e6,
                        snapshot.meanDrxGrainDiameterM() * 1e6, simulation.stepCount(),
                        simulation.maximumObservedCellTravel()),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.printf(Locale.ROOT,
                "Complete: %d steps; stress %.3f MPa; DRX fraction %.4f%nResults: %s%n",
                simulation.stepCount(), snapshot.flowStressMPa(), snapshot.drxFraction(), csv);
    }

    private static void prepareDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            if (!Files.isDirectory(directory)) {
                throw new IOException("output path is not a directory: " + directory);
            }
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException("output directory must be empty: " + directory);
                }
            }
        } else {
            Files.createDirectories(directory);
        }
    }

    private static void writeRow(BufferedWriter writer, DrxSimulation.Snapshot snapshot)
            throws IOException {
        writer.write(String.format(Locale.ROOT,
                "%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%d,%d,%d,%d,%.12g%n",
                snapshot.strain(), snapshot.timeSeconds(), snapshot.flowStressMPa(),
                snapshot.meanGrainDiameterM() * 1e6, snapshot.meanDrxGrainDiameterM() * 1e6,
                snapshot.drxFraction(), snapshot.grainCount(), snapshot.drxGrainCount(),
                snapshot.nucleiCount(), snapshot.stepCount(), snapshot.maxCellTravel()));
    }

    private static void saveImage(BufferedImage image, Path directory, long index, double strain)
            throws IOException {
        Path file = directory.resolve(String.format(Locale.ROOT,
                "microstructure_%04d_strain_%.6f.png", index, strain));
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("a PNG encoder is unavailable");
        }
    }

    private static void writeMetadata(Config config) throws IOException {
        MaterialModels.Parameters parameters = MaterialModels.evaluate(config.model(),
                config.temperatureK(), config.strainRate());
        String text = String.format(Locale.ROOT,
                "created_utc=%s%nengine_version=2026-10-04-final-ca%n"
                        + "engine_bytecode_sha256=%s%nmodel=%s%nmodel_provenance=%s%n"
                        + "model_status=legacy empirical equations; calibration unverified%n"
                        + "temperature_C=%.12g%ntemperature_K=%.12g%nstrain_rate_s^-1=%.12g%n"
                        + "target_strain=%.12g%nwidth=%d%nheight=%d%ncell_size_m=%.12g%n"
                        + "initial_grain_diameter_m=%.12g%nnucleus_diameter_m=%.12g%n"
                        + "nucleation_band_width_m=%.12g%nseed=%d%n"
                        + "max_strain_step=%.12g%nmax_cell_travel=%.12g%n"
                        + "output_strain_step=%.12g%nimages=%s%njava_version=%s%n"
                        + "output_directory=%s%n",
                Instant.now(), engineBytecodeFingerprint(), config.model().name().toLowerCase(Locale.ROOT),
                MaterialModels.provenance(config.model()),
                config.temperatureK() - 273.15, config.temperatureK(), config.strainRate(),
                config.targetStrain(), config.width(), config.height(), config.cellSizeM(),
                config.initialGrainDiameterM(), config.nucleusDiameterM(),
                config.nucleationBandWidthM(), config.seed(), config.maxStrainStep(),
                config.maxCellTravel(), config.outputStrainStep(), config.images(),
                System.getProperty("java.version"), config.outputDirectory());
        text += String.format(Locale.ROOT,
                "shear_modulus_Pa=%.12g%nburgers_vector_m=%.12g%nalpha=%.12g%n"
                        + "k1=%.12g%nk2=%.12g%ncritical_density_m^-2=%.12g%n"
                        + "saturation_density_m^-2=%.12g%nsteady_factor=%.12g%n"
                        + "mobility=%.12g%nnucleation_rate_m^-2_s^-1=%.12g%n"
                        + "yield_density_m^-2=%.12g%npeak_stress_MPa=%.12g%n"
                        + "boundary_energy_J_m^-2=%.12g%n"
                        + "mobility_units=m_Pa^-1_s^-1 (effective; inherited prefactor needs verification)%n"
                        + "strain_rate_multiplier=1%ninitial_density_m^-2=1e6..1e7%n"
                        + "nucleus_density_m^-2=1%norientation_model=scalar_2D_modulo_pi%n"
                        + "domain_boundary=closed_no_flux%ngrowth_neighborhood=four_face_neighbors%n"
                        + "initialization=seeded_separated_Voronoi%n"
                        + "stress_average=Taylor_stress_from_cell_weighted_mean_density%n"
                        + "grain_diameter=number_mean_of_live_area_equivalent_diameters%n"
                        + "drx_fraction_definition=current_cell_area_with_recrystallized_grain_owner%n",
                parameters.shearModulusPa(), parameters.burgersVectorM(), parameters.alpha(),
                parameters.k1(), parameters.k2(), parameters.criticalDensityM2(),
                parameters.saturationDensityM2(), parameters.steadyFactor(), parameters.mobility(),
                parameters.nucleationRatePerM2S(), parameters.yieldDensityM2(),
                parameters.peakStressMPa(), parameters.boundaryEnergyJPerM2());
        Files.writeString(config.outputDirectory().resolve("run.properties"), text,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** Fingerprint the actual executable classes, independently of saved source files. */
    private static String engineBytecodeFingerprint() throws IOException {
        try {
            var location = Main.class.getProtectionDomain().getCodeSource().getLocation();
            Path classes = Path.of(location.toURI()).resolve("inconel617");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var paths = Files.walk(classes)) {
                for (Path file : paths.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".class"))
                        .filter(p -> !p.getFileName().toString().startsWith("SimulationTest"))
                        .sorted().toList()) {
                    digest.update(classes.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Files.readAllBytes(file));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.net.URISyntaxException | NoSuchAlgorithmException exception) {
            throw new IOException("unable to fingerprint executable classes", exception);
        }
    }

    private static String usage() {
        return """
                Inconel 617 dynamic recrystallization cellular automaton (Java 17+)
                Usage: ./run.sh [options]

                  --preset NAME            legacy (default) or final-report
                                           final-report selects final_ca, 400x400 cells, 58 um grains
                                           explicit options override these preset values
                  --temperature N          Celsius, 900..1200 (default 1100)
                  --strain-rate N          Per second, 0.001..0.1 (default 0.001)
                  --strain N               Applied true strain (default 1.38)
                  --width N                Grid columns (default 100)
                  --height N               Grid rows (default 100)
                  --cell-size N            Micrometres per cell (default 1)
                  --grain-size N           Initial mean diameter in micrometres (default 13)
                  --nucleus-size N         DRX nucleus diameter in micrometres (default 1)
                  --nucleation-band N      Physical reactive band width in micrometres (default 2)
                  --seed N                 Signed 64-bit random seed (default 42)
                  --model NAME             cac2, main9, final_ca, or variant (default cac2)
                  --max-strain-step N      Integration strain cap (default 0.00138)
                  --max-cell-travel N      Incoming migration hazard cap, 0 < N <= 0.5 (default 0.25)
                  --output-strain-step N   Sample/image spacing in strain (default 0.05)
                  --output PATH            New or empty directory (default a unique runs/ directory)
                  --no-images              Save tables and metadata only
                  --help                   Show these options

                Inputs may use scientific notation. Options also accept --name=value.
                Results use CSV with explicit units. Legacy material calibration is unverified.
                """;
    }
}
