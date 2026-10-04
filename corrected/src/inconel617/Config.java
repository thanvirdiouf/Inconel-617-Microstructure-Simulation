package inconel617;

import java.nio.file.Path;
import java.util.Objects;

/** All physical lengths and temperatures are stored in SI units. */
public record Config(
        int width,
        int height,
        double cellSizeM,
        double initialGrainDiameterM,
        double nucleusDiameterM,
        double nucleationBandWidthM,
        double temperatureK,
        double strainRate,
        double targetStrain,
        long seed,
        MaterialModels.Kind model,
        double maxStrainStep,
        double maxCellTravel,
        double outputStrainStep,
        boolean images,
        Path outputDirectory) {

    public Config {
        if (width < 3 || height < 3) {
            throw new IllegalArgumentException("width and height must each be at least 3 cells");
        }
        long cells = (long) width * height;
        if (cells > 2_000_000) {
            throw new IllegalArgumentException("the grid must contain at most 2,000,000 cells");
        }
        positive("cell size", cellSizeM);
        positive("initial grain diameter", initialGrainDiameterM);
        positive("nucleus diameter", nucleusDiameterM);
        positive("nucleation band width", nucleationBandWidthM);
        double cellAreaM2 = cellSizeM * cellSizeM;
        positive("derived cell area", cellAreaM2);
        positive("derived domain area", cells * cellAreaM2);
        positive("derived initial grain area", (Math.PI / 4.0)
                * initialGrainDiameterM * initialGrainDiameterM);
        positive("derived domain width", width * cellSizeM);
        positive("derived domain height", height * cellSizeM);
        positive("derived reactive boundary area", 0.5 * nucleationBandWidthM * cellSizeM);
        if (nucleationBandWidthM > Math.min(width, height) * cellSizeM) {
            throw new IllegalArgumentException("nucleation band width must fit inside the domain");
        }
        if (nucleusDiameterM < cellSizeM) {
            throw new IllegalArgumentException("nucleus diameter must be at least one cell size");
        }
        if (nucleusDiameterM > initialGrainDiameterM
                || nucleusDiameterM > Math.min(width, height) * cellSizeM) {
            throw new IllegalArgumentException("nucleus diameter must fit within the initial grain diameter and domain");
        }
        positive("temperature in kelvin", temperatureK);
        positive("strain rate", strainRate);
        positive("target strain", targetStrain);
        positive("maximum strain step", maxStrainStep);
        positive("maximum cell travel", maxCellTravel);
        positive("output strain step", outputStrainStep);
        if (maxCellTravel > 0.5) {
            throw new IllegalArgumentException("maximum cell travel must be at most 0.5 cells per step");
        }
        double ratio = cellSizeM / initialGrainDiameterM;
        double estimatedGrains = cells * ratio * ratio * 4.0 / Math.PI;
        long grainCount = Math.round(estimatedGrains);
        if (!Double.isFinite(estimatedGrains) || grainCount < 1 || grainCount > cells) {
            throw new IllegalArgumentException("grain size and grid area must imply between 1 and "
                    + cells + " initial grains");
        }
        model = Objects.requireNonNull(model, "model");
        outputDirectory = Objects.requireNonNull(outputDirectory, "output directory")
                .toAbsolutePath().normalize();
    }

    private static void positive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and greater than zero");
        }
    }
}
