package inconel617;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * Two-dimensional, isothermal DRX cellular automaton.
 *
 * <p>A step first predicts grain densities, then bounds all incoming migration
 * hazards before committing any state or drawing random events. Migration uses
 * a frozen lattice and competing Poisson hazards. Nuclei are inserted last and
 * start hardening/growing on the following step. No displacement array is shared
 * between cells. All physical quantities inside this class use SI units.
 */
public final class DrxSimulation {
    private static final double NUCLEUS_DENSITY_M2 = 1.0; // Legacy assumption, requires calibration.
    private static final double MAX_RECOVERY_STRAIN = 0.1;
    private static final long MAX_STEPS = 2_000_000;
    private static final int[] DX = {-1, 1, 0, 0};
    private static final int[] DY = {0, 0, -1, 1};

    private final Config config;
    private final MaterialModels.Parameters material;
    private final Random random;
    private final int size;
    private final double cellArea;
    private final ArrayList<Grain> grains = new ArrayList<>();
    private final double[] curvature;
    private final double[] linkRates;
    private final double[] incomingRates;
    private double[] reactiveAreas;
    private int[] ids;
    private boolean[] boundary;
    private int[] boundaryCells = new int[0];
    private boolean initialized;
    private double strain;
    private double time;
    private long steps;
    private long nuclei;
    private double maximumTravel;
    private double[] latestNucleusDensities = new double[0];

    public record Snapshot(double strain, double timeSeconds, double flowStressMPa,
            double meanGrainDiameterM, double meanDrxGrainDiameterM, double drxFraction,
            int grainCount, int drxGrainCount, long nucleiCount, long stepCount,
            double maxCellTravel) {}

    private static final class Grain {
        final double orientation;
        final boolean recrystallized;
        double density;
        int cells;

        Grain(double orientation, boolean recrystallized, double density) {
            this.orientation = orientation;
            this.recrystallized = recrystallized;
            this.density = density;
        }
    }

    public DrxSimulation(Config config) {
        this.config = config;
        material = MaterialModels.evaluate(config.model(), config.temperatureK(), config.strainRate());
        random = new Random(config.seed());
        size = Math.multiplyExact(config.width(), config.height());
        cellArea = config.cellSizeM() * config.cellSizeM();
        ids = new int[size];
        curvature = new double[size];
        linkRates = new double[Math.multiplyExact(size, 4)];
        incomingRates = new double[size];
    }

    /** Generate bounded random seeds and a completely filled Voronoi lattice. */
    public Snapshot initialize() {
        if (initialized) {
            throw new IllegalStateException("simulation has already been initialized");
        }
        double ratio = config.cellSizeM() / config.initialGrainDiameterM();
        int count = (int) Math.round(size * ratio * ratio * 4.0 / Math.PI);
        int[] seedX = new int[count];
        int[] seedY = new int[count];
        double minimumSpacingCells = 0.5 * config.initialGrainDiameterM() / config.cellSizeM();
        double minimumSpacingSquared = minimumSpacingCells * minimumSpacingCells;
        for (int g = 0; g < count; g++) {
            boolean found = false;
            // A bounded rejection loop provides a useful failure for impossible
            // seed layouts instead of silently hanging.
            for (int attempt = 0; attempt < 10_000; attempt++) {
                int x = random.nextInt(config.width());
                int y = random.nextInt(config.height());
                boolean separated = true;
                for (int other = 0; other < g; other++) {
                    double dx = x - seedX[other];
                    double dy = y - seedY[other];
                    if (dx * dx + dy * dy < minimumSpacingSquared) {
                        separated = false;
                        break;
                    }
                }
                if (separated) {
                    seedX[g] = x;
                    seedY[g] = y;
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("could not place separated grain seeds; "
                        + "increase the domain or change the grain size/seed");
            }
            grains.add(new Grain(random.nextDouble() * Math.PI, false,
                    1e6 * (1 + random.nextInt(10))));
        }
        for (int y = 0; y < config.height(); y++) {
            for (int x = 0; x < config.width(); x++) {
                int owner = 0;
                double distance = Double.POSITIVE_INFINITY;
                for (int g = 0; g < count; g++) {
                    double dx = x - seedX[g];
                    double dy = y - seedY[g];
                    double candidate = dx * dx + dy * dy;
                    if (candidate < distance) {
                        distance = candidate;
                        owner = g;
                    }
                }
                ids[y * config.width() + x] = owner;
            }
        }
        initialized = true;
        recountAndRebuild();
        assertInvariants();
        return snapshot();
    }

    /** Advance exactly to a sampling strain, never beyond the configured end. */
    public Snapshot advanceTo(double targetStrain) {
        requireInitialized();
        if (!Double.isFinite(targetStrain) || targetStrain < strain
                || targetStrain > config.targetStrain()) {
            throw new IllegalArgumentException("requested strain must be between the current "
                    + "and configured final strain");
        }
        while (strain < targetStrain) {
            if (steps >= MAX_STEPS) {
                throw new IllegalStateException("two million steps reached; review mobility, "
                        + "grid size, and time-step controls before continuing");
            }
            double remaining = targetStrain - strain;
            double delta = Math.min(remaining, Math.min(config.maxStrainStep(),
                    MAX_RECOVERY_STRAIN / material.k2()));
            if (strain + delta == strain) {
                // Only round a remainder below machine precision, never a
                // physically meaningful unintegrated interval.
                throw new IllegalStateException("strain step is too small to advance at this precision");
            }
            takeStep(delta);
            if (Math.abs(targetStrain - strain) <= 4.0 * Math.ulp(targetStrain)) {
                strain = targetStrain;
                time = strain / config.strainRate();
            }
        }
        return snapshot();
    }

    private void takeStep(double proposedStrain) {
        double delta = proposedStrain;
        double dt;
        double maxRate;
        double[] predicted = new double[grains.size()];
        // Trial steps neither mutate state nor consume random numbers. Shrink
        // and recompute densities AND velocities until the complete step fits.
        int trial = 0;
        while (true) {
            dt = delta / config.strainRate();
            predictDensities(delta, predicted);
            maxRate = migrationRates(predicted);
            double travel = maxRate * dt;
            if (Double.isFinite(travel) && travel <= config.maxCellTravel() * (1.0 + 1e-12)) {
                break;
            }
            if (++trial > 80 || !Double.isFinite(maxRate)) {
                throw new IllegalStateException("unable to resolve a finite migration time step");
            }
            double scale = Math.min(0.5, 0.9 * config.maxCellTravel() / travel);
            delta *= scale;
            if (!(delta > 0.0) || strain + delta == strain) {
                throw new IllegalStateException("migration requires a step below numerical precision; "
                        + "review the material mobility and length units");
            }
        }

        int[] next = ids.clone();
        // Independent random events, read from the same old lattice. At most
        // one capture per target cell; competing grains are selected by their
        // relative incoming rates rather than grain-list iteration order.
        for (int cell : boundaryCells) {
            double rate = incomingRates[cell];
            if (rate == 0.0 || random.nextDouble() >= -Math.expm1(-rate * dt)) {
                continue;
            }
            double choice = random.nextDouble() * rate;
            int selected = -1;
            for (int direction = 0; direction < 4; direction++) {
                double contribution = linkRates[cell * 4 + direction];
                if (contribution > 0.0) {
                    selected = neighbor(cell, direction);
                    choice -= contribution;
                    if (choice <= 0.0) {
                        break;
                    }
                }
            }
            if (selected < 0) {
                throw new IllegalStateException("positive incoming rate without a source cell");
            }
            next[cell] = ids[selected];
        }

        double maxDensity = 0.0;
        for (int g = 0; g < predicted.length; g++) {
            if (grains.get(g).cells > 0) {
                maxDensity = Math.max(maxDensity, predicted[g]);
                grains.get(g).density = predicted[g];
            }
        }
        boolean[] nucleusCells = new boolean[size];
        ArrayList<Double> insertedDensities = new ArrayList<>();
        // Randomized visits avoid favoring the top-left cell when finite nuclei
        // overlap. Candidate lists are bounded and contain each cell once.
        int[] candidates = boundaryCells.clone();
        for (int i = candidates.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int value = candidates[i];
            candidates[i] = candidates[j];
            candidates[j] = value;
        }
        for (int cell : candidates) {
            double rho = predicted[ids[cell]];
            if (nucleusCells[cell] || rho <= material.criticalDensityM2()) {
                continue;
            }
            double weight = (rho - material.criticalDensityM2())
                    / (maxDensity - material.criticalDensityM2());
            double hazard = material.nucleationRatePerM2S() * reactiveAreas[cell] * dt * weight;
            if (random.nextDouble() < -Math.expm1(-hazard)) {
                Grain grain = new Grain(random.nextDouble() * Math.PI, true, NUCLEUS_DENSITY_M2);
                int id = grains.size();
                grains.add(grain);
                insertNucleus(cell, id, next, nucleusCells);
                insertedDensities.add(grain.density);
                nuclei++;
            }
        }
        if (!insertedDensities.isEmpty()) {
            latestNucleusDensities = insertedDensities.stream().mapToDouble(Double::doubleValue).toArray();
        }
        ids = next;
        maximumTravel = Math.max(maximumTravel, maxRate * dt);
        strain += delta;
        time = strain / config.strainRate();
        steps++;
        recountAndRebuild();
    }

    private void insertNucleus(int center, int id, int[] next, boolean[] occupied) {
        int x = center % config.width();
        int y = center / config.width();
        double radiusCells = config.nucleusDiameterM() / (2.0 * config.cellSizeM());
        int reach = (int) Math.ceil(radiusCells);
        double radiusSquared = radiusCells * radiusCells;
        int parent = ids[center];
        // The physical patch is clipped to its parent grain and domain. It
        // overrides capture proposals, but never an earlier accepted nucleus.
        for (int yy = Math.max(0, y - reach); yy <= Math.min(config.height() - 1, y + reach); yy++) {
            for (int xx = Math.max(0, x - reach); xx <= Math.min(config.width() - 1, x + reach); xx++) {
                int cell = yy * config.width() + xx;
                double dx = xx - x;
                double dy = yy - y;
                if (dx * dx + dy * dy <= radiusSquared && ids[cell] == parent && !occupied[cell]) {
                    next[cell] = id;
                    occupied[cell] = true;
                }
            }
        }
    }

    /** Positive Strang splitting: exact recovery and second-order hardening. */
    private void predictDensities(double deltaStrain, double[] predicted) {
        double recovery = Math.exp(-0.5 * material.k2() * deltaStrain);
        for (int g = 0; g < grains.size(); g++) {
            Grain grain = grains.get(g);
            if (grain.cells == 0) {
                predicted[g] = grain.density;
                continue;
            }
            double diameterM = diameter(grain.cells);
            double sizeSource = 1.0 / (diameterM * material.burgersVectorM());
            double k1 = material.k1() * (grain.recrystallized
                    ? Math.min(1.0, material.steadyFactor()) : 1.0);
            double first = grain.density * recovery;
            double derivative = k1 * Math.sqrt(first) + sizeSource;
            double predictor = first + deltaStrain * derivative;
            double hardening = first + 0.5 * deltaStrain
                    * (derivative + k1 * Math.sqrt(predictor) + sizeSource);
            predicted[g] = hardening * recovery;
            if (!Double.isFinite(predicted[g]) || predicted[g] < 0.0) {
                throw new IllegalStateException("nonfinite/negative dislocation density in grain " + g);
            }
        }
    }

    private double migrationRates(double[] predicted) {
        double energy = material.alpha() * material.shearModulusPa()
                * material.burgersVectorM() * material.burgersVectorM();
        double maximum = 0.0;
        for (int cell : boundaryCells) {
            double sum = 0.0;
            int target = ids[cell];
            for (int direction = 0; direction < 4; direction++) {
                int sourceCell = neighbor(cell, direction);
                double rate = 0.0;
                if (sourceCell >= 0 && ids[sourceCell] != target) {
                    int source = ids[sourceCell];
                    double pressure = energy * (predicted[target] - predicted[source])
                            - boundaryEnergy(grains.get(source).orientation,
                                    grains.get(target).orientation) * curvature[sourceCell];
                    rate = Math.max(0.0, material.mobility() * pressure / config.cellSizeM());
                }
                linkRates[cell * 4 + direction] = rate;
                sum += rate;
            }
            incomingRates[cell] = sum;
            maximum = Math.max(maximum, sum);
        }
        return maximum;
    }

    private double boundaryEnergy(double first, double second) {
        double angle = Math.abs(first - second);
        angle = Math.min(angle, Math.PI - angle); // Scalar 2D orientation modulo pi.
        double critical = Math.toRadians(15.0);
        if (angle == 0.0) {
            return 0.0;
        }
        if (angle >= critical) {
            return material.boundaryEnergyJPerM2();
        }
        double ratio = angle / critical;
        return material.boundaryEnergyJPerM2() * ratio * (1.0 - Math.log(ratio));
    }

    private int neighbor(int cell, int direction) {
        int x = cell % config.width() + DX[direction];
        int y = cell / config.width() + DY[direction];
        if (x < 0 || x >= config.width() || y < 0 || y >= config.height()) {
            return -1; // Closed/no-flux domain; no off-grid capture.
        }
        return y * config.width() + x;
    }

    private void recountAndRebuild() {
        for (Grain grain : grains) {
            grain.cells = 0;
        }
        for (int id : ids) {
            grains.get(id).cells++;
        }
        boundary = detectBoundaries(config.width(), config.height(), ids);
        reactiveAreas = nucleationAreas(config.width(), config.height(), ids,
                config.cellSizeM(), config.nucleationBandWidthM());
        int count = 0;
        for (boolean value : boundary) {
            if (value) {
                count++;
            }
        }
        boundaryCells = new int[count];
        int index = 0;
        Arrays.fill(curvature, 0.0);
        for (int cell = 0; cell < size; cell++) {
            if (!boundary[cell]) {
                continue;
            }
            boundaryCells[index++] = cell;
            int x = cell % config.width();
            int y = cell / config.width();
            int same = 0;
            // Kink-method curvature, now scaled by the actual cell spacing.
            // Clamped exterior samples impose a no-flux edge rather than a
            // fictitious unlike grain outside the specimen.
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int xx = Math.max(0, Math.min(config.width() - 1, x + dx));
                    int yy = Math.max(0, Math.min(config.height() - 1, y + dy));
                    if (ids[yy * config.width() + xx] == ids[cell]) {
                        same++;
                    }
                }
            }
            curvature[cell] = 1.25 * (15.0 - same) / (25.0 * config.cellSizeM());
        }
    }

    /** Package-visible pure boundary operator, also exercised on rectangular grids. */
    static boolean[] detectBoundaries(int width, int height, int[] lattice) {
        if (width < 1 || height < 1 || lattice.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("invalid lattice dimensions");
        }
        boolean[] result = new boolean[lattice.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int cell = y * width + x;
                if (x + 1 < width && lattice[cell] != lattice[cell + 1]) {
                    result[cell] = result[cell + 1] = true;
                }
                if (y + 1 < height && lattice[cell] != lattice[cell + width]) {
                    result[cell] = result[cell + width] = true;
                }
            }
        }
        return result;
    }

    /**
     * Physical nucleation-band area allocated to each side of an interface.
     * A face of length h contributes half its configured band width to each
     * neighboring grain. This avoids the legacy intensity proportional to h
     * caused by multiplying one-cell-thick boundary counts by h squared.
     * The interface length still has the usual square-lattice discretization.
     */
    static double[] nucleationAreas(int width, int height, int[] lattice,
            double cellSizeM, double bandWidthM) {
        if (width < 1 || height < 1 || lattice.length != Math.multiplyExact(width, height)
                || !Double.isFinite(cellSizeM) || cellSizeM <= 0.0
                || !Double.isFinite(bandWidthM) || bandWidthM <= 0.0) {
            throw new IllegalArgumentException("invalid nucleation-band geometry");
        }
        double[] result = new double[lattice.length];
        double faceArea = 0.5 * cellSizeM * bandWidthM;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int cell = y * width + x;
                if (x + 1 < width && lattice[cell] != lattice[cell + 1]) {
                    result[cell] += faceArea;
                    result[cell + 1] += faceArea;
                }
                if (y + 1 < height && lattice[cell] != lattice[cell + width]) {
                    result[cell] += faceArea;
                    result[cell + width] += faceArea;
                }
            }
        }
        return result;
    }

    private double diameter(int cells) {
        return Math.sqrt(4.0 * cells * cellArea / Math.PI);
    }

    public Snapshot snapshot() {
        requireInitialized();
        double densitySum = 0.0;
        double diameterSum = 0.0;
        double drxDiameterSum = 0.0;
        int live = 0;
        int drxLive = 0;
        long drxCells = 0;
        for (Grain grain : grains) {
            if (grain.cells == 0) {
                continue;
            }
            live++;
            densitySum += grain.density * grain.cells;
            diameterSum += diameter(grain.cells);
            if (grain.recrystallized) {
                drxLive++;
                drxCells += grain.cells;
                drxDiameterSum += diameter(grain.cells);
            }
        }
        double stress = material.alpha() * material.shearModulusPa() * material.burgersVectorM()
                * Math.sqrt(densitySum / size) / 1e6;
        return new Snapshot(strain, time, stress, diameterSum / live,
                drxLive == 0 ? 0.0 : drxDiameterSum / drxLive, (double) drxCells / size,
                live, drxLive, nuclei, steps, maximumTravel);
    }

    public BufferedImage render() {
        requireInitialized();
        BufferedImage image = new BufferedImage(config.width(), config.height(), BufferedImage.TYPE_INT_RGB);
        for (int cell = 0; cell < size; cell++) {
            int color;
            if (boundary[cell]) {
                color = Color.BLACK.getRGB();
            } else if (!grains.get(ids[cell]).recrystallized) {
                color = Color.WHITE.getRGB();
            } else {
                // The palette consumes no random numbers, so enabling pictures
                // cannot alter the simulation trajectory.
                float hue = (float) ((ids[cell] * 0.6180339887498949) % 1.0);
                color = Color.HSBtoRGB(hue, 0.65f, 0.9f);
            }
            image.setRGB(cell % config.width(), cell / config.width(), color);
        }
        return image;
    }

    public int[] grainIds() {
        requireInitialized();
        return ids.clone();
    }

    public double[] grainDensities() {
        requireInitialized();
        return grains.stream().mapToDouble(g -> g.density).toArray();
    }

    public int[] grainCellCounts() {
        requireInitialized();
        return grains.stream().mapToInt(g -> g.cells).toArray();
    }

    public boolean[] boundaryMask() {
        requireInitialized();
        return boundary.clone();
    }

    // Densities observed at the most recent insertion event, retained across
    // subsequent steps so sampling can verify creation without missing events.
    double[] lastNucleusDensities() {
        return latestNucleusDensities.clone();
    }

    public long stepCount() {
        return steps;
    }

    public double maximumObservedCellTravel() {
        return maximumTravel;
    }

    /** State conservation and finite physical quantities; useful for every output row. */
    public void assertInvariants() {
        requireInitialized();
        int[] actual = new int[grains.size()];
        for (int id : ids) {
            if (id < 0 || id >= actual.length) {
                throw new IllegalStateException("invalid grain owner " + id);
            }
            actual[id]++;
        }
        long total = 0;
        for (int g = 0; g < grains.size(); g++) {
            Grain grain = grains.get(g);
            if (actual[g] != grain.cells || !Double.isFinite(grain.density) || grain.density < 0.0) {
                throw new IllegalStateException("grain conservation/density invariant failed at " + g);
            }
            total += grain.cells;
        }
        if (total != size || !Arrays.equals(boundary, detectBoundaries(config.width(), config.height(), ids))) {
            throw new IllegalStateException("lattice coverage or boundary invariant failed");
        }
        Snapshot current = snapshot();
        if (!Double.isFinite(current.flowStressMPa()) || current.flowStressMPa() < 0.0
                || current.drxFraction() < 0.0 || current.drxFraction() > 1.0
                || maximumTravel > config.maxCellTravel() * (1.0 + 1e-12)
                || Math.abs(time - strain / config.strainRate()) > 8.0 * Math.ulp(time)) {
            throw new IllegalStateException("observable/time-step invariant failed");
        }
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("initialize the simulation first");
        }
    }
}
