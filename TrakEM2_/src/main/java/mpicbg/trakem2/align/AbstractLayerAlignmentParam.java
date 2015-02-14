package mpicbg.trakem2.align;

import ij.gui.GenericDialog;

import java.io.Serializable;

import mpicbg.ij.SIFT;
import mpicbg.trakem2.align.AlignmentUtils.ParamPointMatch;

public class AbstractLayerAlignmentParam implements Serializable
{
    private static final long serialVersionUID = 4186011617208423197L;

    final public ParamPointMatch ppm = new ParamPointMatch();
	{
		ppm.sift.fdSize = 8;
	}

	/**
	 * Maximal accepted alignment error in px
	 */
	public float maxEpsilon = 200.0f;

	/**
	 * Inlier/candidates ratio
	 */
	public float minInlierRatio = 0.0f;

	/**
	 * Minimal absolute number of inliers
	 */
	public int minNumInliers = 12;

	/**
	 * Transformation models for choice
	 */
	final static public String[] modelStrings = new String[]{ "Translation", "Rigid", "Similarity", "Affine" };
	public int expectedModelIndex = 3;
	public boolean multipleHypotheses = false;

	/**
	 * Ignore identity transform up to a given tolerance
	 */
	public boolean rejectIdentity = false;
	public float identityTolerance = 5.0f;

	/**
	 * Maximal number of consecutive sections to be tested for an alignment model
	 */
	public int maxNumNeighbors = 1;

	/**
	 * Maximal number of consecutive slices for which no model could be found
	 */
	public int maxNumFailures = 3;

	public int desiredModelIndex = 1;
	public int maxIterationsOptimize = 1000;
	public int maxPlateauwidthOptimize = 200;

	public boolean visualize = false;

	public int maxNumThreads = Runtime.getRuntime().availableProcessors();


	public boolean setupSIFT( final String title )
	{
		/* SIFT */
		final GenericDialog gdSIFT = new GenericDialog( title + "SIFT parameters" );

		SIFT.addFields( gdSIFT, ppm.sift );

		gdSIFT.addMessage( "Local Descriptor Matching:" );
		gdSIFT.addNumericField( "closest/next_closest_ratio :", ppm.rod, 2 );

		gdSIFT.addMessage( "Miscellaneous:" );
		gdSIFT.addCheckbox( "clear_cache", ppm.clearCache );
		gdSIFT.addNumericField( "feature_extraction_threads :", ppm.maxNumThreadsSift, 0 );

		gdSIFT.showDialog();

		if ( gdSIFT.wasCanceled() )
			return false;

		SIFT.readFields( gdSIFT, ppm.sift );

		ppm.rod = ( float )gdSIFT.getNextNumber();
		ppm.clearCache = gdSIFT.getNextBoolean();
		ppm.maxNumThreadsSift = ( int )gdSIFT.getNextNumber();

		return true;
	}

	public AbstractLayerAlignmentParam() {}

	public AbstractLayerAlignmentParam(
			final int SIFTfdBins,
			final int SIFTfdSize,
			final float SIFTinitialSigma,
			final int SIFTmaxOctaveSize,
			final int SIFTminOctaveSize,
			final int SIFTsteps,

			final boolean clearCache,
			final int maxNumThreadsSift,
			final float rod,

			final int desiredModelIndex,
			final int expectedModelIndex,
			final float identityTolerance,
			final float maxEpsilon,
			final int maxIterationsOptimize,
			final int maxNumFailures,
			final int maxNumNeighbors,
			final int maxNumThreads,
			final int maxPlateauwidthOptimize,
			final float minInlierRatio,
			final int minNumInliers,
			final boolean multipleHypotheses,
			final boolean rejectIdentity,
			final boolean visualize )
	{
		ppm.sift.fdBins = SIFTfdBins;
		ppm.sift.fdSize = SIFTfdSize;
		ppm.sift.initialSigma = SIFTinitialSigma;
		ppm.sift.maxOctaveSize = SIFTmaxOctaveSize;
		ppm.sift.minOctaveSize = SIFTminOctaveSize;
		ppm.sift.steps = SIFTsteps;

		ppm.clearCache = clearCache;
		ppm.maxNumThreadsSift = maxNumThreadsSift;
		ppm.rod = rod;

		this.desiredModelIndex = desiredModelIndex;
		this.expectedModelIndex = expectedModelIndex;
		this.identityTolerance = identityTolerance;
		this.maxEpsilon = maxEpsilon;
		this.maxIterationsOptimize = maxIterationsOptimize;
		this.maxNumFailures = maxNumFailures;
		this.maxNumNeighbors = maxNumNeighbors;
		this.maxNumThreads = maxNumThreads;
		this.maxPlateauwidthOptimize = maxPlateauwidthOptimize;
		this.minInlierRatio = minInlierRatio;
		this.minNumInliers = minNumInliers;
		this.multipleHypotheses = multipleHypotheses;
		this.rejectIdentity = rejectIdentity;
		this.visualize = visualize;
	}

	@Override
	public AbstractLayerAlignmentParam clone()
	{
		return new AbstractLayerAlignmentParam(
				ppm.sift.fdBins,
				ppm.sift.fdSize,
				ppm.sift.initialSigma,
				ppm.sift.maxOctaveSize,
				ppm.sift.minOctaveSize,
				ppm.sift.steps,

				ppm.clearCache,
				ppm.maxNumThreadsSift,
				ppm.rod,

				desiredModelIndex,
				expectedModelIndex,
				identityTolerance,
				maxEpsilon,
				maxIterationsOptimize,
				maxNumFailures,
				maxNumNeighbors,
				maxNumThreads,
				maxPlateauwidthOptimize,
				minInlierRatio,
				minNumInliers,
				multipleHypotheses,
				rejectIdentity,
				visualize );
	}
}