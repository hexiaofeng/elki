package de.lmu.ifi.dbs.elki.algorithm.statistics;

import java.util.Arrays;
import java.util.Random;

import de.lmu.ifi.dbs.elki.algorithm.AbstractPrimitiveDistanceBasedAlgorithm;
import de.lmu.ifi.dbs.elki.data.NumberVector;
import de.lmu.ifi.dbs.elki.data.type.TypeInformation;
import de.lmu.ifi.dbs.elki.data.type.TypeUtil;
import de.lmu.ifi.dbs.elki.database.Database;
import de.lmu.ifi.dbs.elki.database.ids.DBIDIter;
import de.lmu.ifi.dbs.elki.database.ids.DBIDUtil;
import de.lmu.ifi.dbs.elki.database.ids.ModifiableDBIDs;
import de.lmu.ifi.dbs.elki.database.query.distance.DistanceQuery;
import de.lmu.ifi.dbs.elki.database.query.knn.KNNQuery;
import de.lmu.ifi.dbs.elki.database.relation.Relation;
import de.lmu.ifi.dbs.elki.database.relation.RelationUtil;
import de.lmu.ifi.dbs.elki.distance.distancefunction.NumberVectorDistanceFunction;
import de.lmu.ifi.dbs.elki.distance.distancefunction.PrimitiveDistanceFunction;
import de.lmu.ifi.dbs.elki.distance.distancefunction.minkowski.EuclideanDistanceFunction;
import de.lmu.ifi.dbs.elki.logging.Logging;
import de.lmu.ifi.dbs.elki.logging.Logging.Level;
import de.lmu.ifi.dbs.elki.logging.statistics.DoubleStatistic;
import de.lmu.ifi.dbs.elki.logging.statistics.LongStatistic;
import de.lmu.ifi.dbs.elki.math.MathUtil;
import de.lmu.ifi.dbs.elki.math.MeanVariance;
import de.lmu.ifi.dbs.elki.math.linearalgebra.Vector;
import de.lmu.ifi.dbs.elki.math.random.RandomFactory;
import de.lmu.ifi.dbs.elki.math.statistics.distribution.BetaDistribution;
import de.lmu.ifi.dbs.elki.result.Result;
import de.lmu.ifi.dbs.elki.utilities.datastructures.arraylike.ArrayLikeUtil;
import de.lmu.ifi.dbs.elki.utilities.documentation.Reference;
import de.lmu.ifi.dbs.elki.utilities.exceptions.AbortException;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.OptionID;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.constraints.AllOrNoneMustBeSetGlobalConstraint;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.constraints.CommonConstraints;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.constraints.EqualSizeGlobalConstraint;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameterization.Parameterization;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.DoubleListParameter;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.IntParameter;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.ObjectParameter;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.RandomParameter;
import de.lmu.ifi.dbs.elki.utilities.pairs.Pair;

/**
 * The Hopkins Statistic of Clustering Tendency measures the probability that a
 * data set is generated by a uniform data distribution.
 * 
 * The statistic compares the ratio of the 1NN distance for objects from the
 * data set compared to the 1NN distances of uniform distributed objects.
 * 
 * Reference:
 * <p>
 * B. Hopkins and J. G. Skellam<br />
 * A new method for determining the type of distribution of plant individuals<br />
 * Annals of Botany, 18(2), 213-227.
 * </p>
 * 
 * @author Lisa Reichert
 * @author Erich Schubert
 */
// TODO: allow using more than one k
@Reference(authors = "B. Hopkins and J. G. Skellam", //
title = "A new method for determining the type of distribution of plant individuals", //
booktitle = "Annals of Botany, 18(2), 213-227", //
url = "http://aob.oxfordjournals.org/content/18/2/213.short")
public class HopkinsStatisticClusteringTendency extends AbstractPrimitiveDistanceBasedAlgorithm<NumberVector, Result> {
  /**
   * The logger for this class.
   */
  private static final Logging LOG = Logging.getLogger(HopkinsStatisticClusteringTendency.class);

  /**
   * The parameter sampleSizes
   */
  private int sampleSize;

  /**
   * Number of repetitions
   */
  private int rep;

  /**
   * Nearest neighbor to use.
   */
  private int k;

  /**
   * Random generator seeding.
   */
  private RandomFactory random;

  /**
   * Stores the maximum in each dimension.
   */
  private double[] maxima = new double[0];

  /**
   * Stores the minimum in each dimension.
   */
  private double[] minima = new double[0];

  /**
   * Constructor.
   * 
   * @param distanceFunction Distance function
   * @param samplesize Sample size
   * @param random Random generator
   * @param rep Number of repetitions
   * @param k Nearest neighbor to use
   * @param minima Data space minima, may be {@code null} (get from data).
   * @param maxima Data space minima, may be {@code null} (get from data).
   */
  public HopkinsStatisticClusteringTendency(NumberVectorDistanceFunction<? super NumberVector> distanceFunction, int samplesize, RandomFactory random, int rep, int k, double[] minima, double[] maxima) {
    super(distanceFunction);
    this.sampleSize = samplesize;
    this.random = random;
    this.rep = rep;
    this.k = k;
    this.minima = minima;
    this.maxima = maxima;
  }

  /**
   * Runs the algorithm in the timed evaluation part.
   * 
   * @param database Database context
   * @param relation Relation to analyze
   */
  public Result run(Database database, Relation<NumberVector> relation) {
    final int dim = RelationUtil.dimensionality(relation);
    final DistanceQuery<NumberVector> distanceQuery = database.getDistanceQuery(relation, getDistanceFunction());
    final KNNQuery<NumberVector> knnQuery = database.getKNNQuery(distanceQuery, k + 1);

    final double[] min = new double[dim], extend = new double[dim];
    initializeDataExtends(relation, dim, min, extend);

    if(!LOG.isStatistics()) {
      LOG.warning("This algorithm must be used with at least logging level " + Level.STATISTICS);
    }

    MeanVariance hmean = new MeanVariance(), umean = new MeanVariance(), wmean = new MeanVariance();
    // compute the hopkins value several times and use the average value for a
    // more stable result
    for(int j = 0; j < this.rep; j++) {
      // Compute NN distances for random objects from within the database
      double w = computeNNForRealData(knnQuery, relation, dim);
      // Compute NN distances for randomly created new uniform objects
      double u = computeNNForUniformData(knnQuery, min, extend);
      // compute hopkins statistik
      double h = u / (u + w); // = a / (1+a)
      hmean.put(h);
      umean.put(u);
      wmean.put(w);
    }
    final String prefix = this.getClass().getName();
    LOG.statistics(new LongStatistic(prefix + ".samplesize", sampleSize));
    LOG.statistics(new LongStatistic(prefix + ".dim", dim));
    LOG.statistics(new LongStatistic(prefix + ".hopkins.nearest-neighbor", k));
    LOG.statistics(new DoubleStatistic(prefix + ".hopkins.h.mean", hmean.getMean()));
    LOG.statistics(new DoubleStatistic(prefix + ".hopkins.u.mean", umean.getMean()));
    LOG.statistics(new DoubleStatistic(prefix + ".hopkins.w.mean", wmean.getMean()));
    if(rep > 1) {
      LOG.statistics(new DoubleStatistic(prefix + ".hopkins.h.std", hmean.getSampleStddev()));
      LOG.statistics(new DoubleStatistic(prefix + ".hopkins.u.std", umean.getSampleStddev()));
      LOG.statistics(new DoubleStatistic(prefix + ".hopkins.w.std", wmean.getSampleStddev()));
    }
    // Evaluate:
    double x = hmean.getMean();
    // See Hopkins for a proof that x is supposedly Beta distributed.
    double ix = BetaDistribution.regularizedIncBeta(x, sampleSize, sampleSize);
    double p = (x > .5) ? (1. - ix) : ix;
    LOG.statistics(new DoubleStatistic(prefix + ".hopkins.p", p));
    return null;
  }

  /**
   * Search nearest neighbors for <em>real</em> data members.
   * 
   * @param knnQuery KNN query
   * @param relation Data relation
   * @return Aggregated 1NN distances
   */
  protected double computeNNForRealData(final KNNQuery<NumberVector> knnQuery, Relation<NumberVector> relation, final int dim) {
    double w = 0.;
    ModifiableDBIDs dataSampleIds = DBIDUtil.randomSample(relation.getDBIDs(), sampleSize, random);
    for(DBIDIter iter = dataSampleIds.iter(); iter.valid(); iter.advance()) {
      final double kdist = knnQuery.getKNNForDBID(iter, k + 1).getKNNDistance();
      w += MathUtil.powi(kdist, dim);
    }
    return w;
  }

  /**
   * Search nearest neighbors for <em>artificial, uniform</em> data.
   * 
   * @param knnQuery KNN query
   * @param min Data minima
   * @param extend Data extend
   * @return Aggregated 1NN distances
   */
  protected double computeNNForUniformData(final KNNQuery<NumberVector> knnQuery, final double[] min, final double[] extend) {
    final Random rand = random.getSingleThreadedRandom();
    final int dim = min.length;

    Vector vec = new Vector(dim);
    double[] buf = vec.getArrayRef(); // Reference!

    double u = 0.;
    for(int i = 0; i < sampleSize; i++) {
      // New random vector
      for(int d = 0; d < buf.length; d++) {
        buf[d] = min[d] + (rand.nextDouble() * extend[d]);
      }
      final double kdist = knnQuery.getKNNForObject(vec, k).getKNNDistance();
      u += MathUtil.powi(kdist, dim);
    }
    return u;
  }

  /**
   * Initialize the uniform sampling area.
   * 
   * @param relation Data relation
   * @param dim Dimensionality
   * @param min Minima output array (preallocated!)
   * @param extend Data extend output array (preallocated!)
   */
  protected void initializeDataExtends(Relation<NumberVector> relation, int dim, double[] min, double[] extend) {
    assert (min.length == dim && extend.length == dim);
    // if no parameter for min max compute min max values for each dimension
    // from dataset
    if(minima == null || maxima == null || minima.length == 0 || maxima.length == 0) {
      Pair<Vector, Vector> minmax = RelationUtil.computeMinMax(relation);
      final double[] dmin = minmax.first.getArrayRef(), dmax = minmax.second.getArrayRef();
      for(int d = 0; d < dim; d++) {
        min[d] = dmin[d];
        extend[d] = dmax[d] - dmin[d];
      }
      return;
    }
    if(minima.length == dim) {
      System.arraycopy(minima, 0, min, 0, dim);
    }
    else if(minima.length == 1) {
      Arrays.fill(min, minima[0]);
    }
    else {
      throw new AbortException("Invalid minima specified: expected " + dim + " got minima dimensionality: " + minima.length);
    }
    if(maxima.length == dim) {
      for(int d = 0; d < dim; d++) {
        extend[d] = maxima[d] - min[d];
      }
      return;
    }
    else if(maxima.length == 1) {
      for(int d = 0; d < dim; d++) {
        extend[d] = maxima[0] - min[d];
      }
      return;
    }
    else {
      throw new AbortException("Invalid maxima specified: expected " + dim + " got maxima dimensionality: " + maxima.length);
    }
  }

  @Override
  protected Logging getLogger() {
    return LOG;
  }

  @Override
  public TypeInformation[] getInputTypeRestriction() {
    return TypeUtil.array(TypeUtil.NUMBER_VECTOR_FIELD);
  }

  /**
   * Parameterization class.
   * 
   * @author Lisa Reichert
   * 
   * @apiviz.exclude
   */
  public static class Parameterizer extends AbstractPrimitiveDistanceBasedAlgorithm.Parameterizer<NumberVector> {
    /**
     * Sample size.
     */
    public static final OptionID SAMPLESIZE_ID = new OptionID("hopkins.samplesize", "Number of object / random samples to analyze.");

    /**
     * Parameter to specify the number of repetitions of computing the hopkins
     * value.
     */
    public static final OptionID REP_ID = new OptionID("hopkins.rep", "The number of times to repeat the experiment (default: 1)");

    /**
     * Parameter to specify the random generator seed.
     */
    public static final OptionID SEED_ID = new OptionID("hopkins.seed", "The random number generator.");

    /**
     * Parameter for minimum.
     */
    public static final OptionID MINIMA_ID = new OptionID("hopkins.min", "Minimum values in each dimension. If no value is specified, the minimum value in each dimension will be used. If only one value is specified, this value will be used for all dimensions.");

    /**
     * Parameter for maximum.
     */
    public static final OptionID MAXIMA_ID = new OptionID("hopkins.max", "Maximum values in each dimension. If no value is specified, the maximum value in each dimension will be used. If only one value is specified, this value will be used for all dimensions.");

    /**
     * Parameter for k.
     */
    public static final OptionID K_ID = new OptionID("hopkins.k", "Nearest neighbor to use for the statistic");

    /**
     * Sample size.
     */
    protected int sampleSize = 0;

    /**
     * Number of repetitions.
     */
    protected int rep = 1;

    /**
     * Nearest neighbor number.
     */
    protected int k = 1;

    /**
     * Random source.
     */
    protected RandomFactory random;

    /**
     * Stores the maximum in each dimension.
     */
    private double[] maxima = null;

    /**
     * Stores the minimum in each dimension.
     */
    private double[] minima = null;

    @Override
    protected void makeOptions(Parameterization config) {
      ObjectParameter<PrimitiveDistanceFunction<NumberVector>> distanceFunctionP = makeParameterDistanceFunction(EuclideanDistanceFunction.class, NumberVectorDistanceFunction.class);
      if(config.grab(distanceFunctionP)) {
        distanceFunction = distanceFunctionP.instantiateClass(config);
      }

      IntParameter sampleP = new IntParameter(SAMPLESIZE_ID);
      if(config.grab(sampleP)) {
        sampleSize = sampleP.getValue();
      }

      IntParameter repP = new IntParameter(REP_ID, 1) //
      .addConstraint(CommonConstraints.GREATER_EQUAL_ONE_INT);
      if(config.grab(repP)) {
        rep = repP.getValue();
      }

      IntParameter kP = new IntParameter(K_ID, 1) //
      .addConstraint(CommonConstraints.GREATER_EQUAL_ONE_INT);
      if(config.grab(kP)) {
        k = kP.getValue();
      }

      final RandomParameter randomP = new RandomParameter(SEED_ID);
      if(config.grab(randomP)) {
        random = randomP.getValue();
      }
      DoubleListParameter minimaP = new DoubleListParameter(MINIMA_ID)//
      .setOptional(true);
      if(config.grab(minimaP)) {
        minima = ArrayLikeUtil.toPrimitiveDoubleArray(minimaP.getValue());
      }
      DoubleListParameter maximaP = new DoubleListParameter(MAXIMA_ID)//
      .setOptional(true);
      if(config.grab(maximaP)) {
        maxima = ArrayLikeUtil.toPrimitiveDoubleArray(maximaP.getValue());
      }

      config.checkConstraint(new AllOrNoneMustBeSetGlobalConstraint(minimaP, maximaP));
      config.checkConstraint(new EqualSizeGlobalConstraint(minimaP, maximaP));
    }

    @Override
    protected HopkinsStatisticClusteringTendency makeInstance() {
      return new HopkinsStatisticClusteringTendency((NumberVectorDistanceFunction<? super NumberVector>) distanceFunction, sampleSize, random, rep, k, minima, maxima);
    }
  }
}
