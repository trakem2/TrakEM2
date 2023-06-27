/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2022 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
/**
 *
 */
package mpicbg.trakem2.align;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Selection;
import ini.trakem2.persistence.FSLoader;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.Filter;
import ini.trakem2.utils.Utils;
import mpicbg.ij.FeatureTransform;
import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AbstractModel;
import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.Tile;
import mpicbg.models.Transforms;
import mpicbg.trakem2.transform.HomographyModel2D;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform2;
import mpicbg.trakem2.transform.RigidModel2D;
import mpicbg.trakem2.transform.TranslationModel2D;

/**
 * A collection of methods regarding SIFT-based alignment
 *
 * TODO Bring the methods and tasks into a class for each method and clean up this mess.
 *
 * @author Stephan Saalfeld saalfeld@mpi-cbg.de
 */
public class Align
{
	/**
	 * Class for getting information out of TrakEM2 and into the align.py script
	 */
	static public class Access 
	{
		public static List<TilePairClass> tile_pairs = new ArrayList<TilePairClass>();
	}
	/**
	 * Class where information is stored about the Fields.
	 */
	static public class TilePairClass
	{
		public final Patch tile1_name;
		public final Patch tile2_name;
		public final double r_error;
		public final List<InliersClass> inliers_list;

		/**
		 * Function to initialize class with information about a Field Pair
		 */
		public TilePairClass(Patch tile1_name, Patch tile2_name, double r_error, List<InliersClass> inliers_list)
			{
				this.tile1_name = tile1_name;
				this.tile2_name = tile2_name;
				this.r_error = r_error;
				this.inliers_list = inliers_list;
			}
	}
	/**
	 * Class for inliers information in Field Pairs
	 */
	static public class InliersClass
	{
		public final double p1_x;
		public final double p1_y;
		public final double p2_x;
		public final double p2_y;
		public final double distance;

		public InliersClass(double p1_x, double p1_y, double p2_x, double p2_y, double distance)
			{
				this.p1_x = p1_x;
				this.p1_y = p1_y;
				this.p2_x = p2_x;
				this.p2_y = p2_y;
				this.distance = distance;
			}
	}

	static public class Param implements Serializable
	{
        private static final long serialVersionUID = -6469820142091971052L;

        final public FloatArray2DSIFT.Param sift = new FloatArray2DSIFT.Param();

		/**
		 * Closest/next closest neighbour distance ratio
		 */
		public float rod = 0.92f;

		/**
		 * Maximal allowed alignment error in px
		 */
		public float maxEpsilon = 100.0f;

		/**
		 * Inlier/candidates ratio
		 */
		public float minInlierRatio = 0.2f;

		/**
		 * Minimal absolute number of inliers
		 */
		public int minNumInliers = 7;

		/**
		 * Implemeted transformation models for choice
		 */
//		final static public String[] modelStrings = new String[]{ "Translation", "Rigid", "Similarity", "Affine", "Homography" };
		final static public String[] modelStrings = new String[]{ "Translation", "Rigid", "Similarity", "Affine" };
		public int expectedModelIndex = 1;
		public int desiredModelIndex = 1;
		public int regularizerModelIndex = 1;

		/**
		 * Use a regularized model instead of a pure one.
		 */
		public boolean regularize = false;

		/**
		 * Regularization weight.
		 */
		public double lambda = 0.1;

		public float correspondenceWeight = 1;

		/**
		 * Ignore identity transform up to a given tolerance
		 */
		public boolean rejectIdentity = false;
		public float identityTolerance = 0.5f;

		public Param()
		{
			sift.maxOctaveSize = 600;
			sift.fdSize = 8;
		}

		public void addSIFTFields( final GenericDialog gd )
		{
			SIFT.addFields( gd, sift );
			gd.addNumericField( "closest/next_closest_ratio :", rod, 2 );
		}

		public void addGeometricConsensusFilterFields( final GenericDialog gd )
		{
			gd.addNumericField( "maximal_alignment_error :", maxEpsilon, 2, 6, "px" );
			gd.addNumericField( "minimal_inlier_ratio :", minInlierRatio, 2 );
			gd.addNumericField( "minimal_number_of_inliers :", minNumInliers, 0 );
			gd.addChoice( "expected_transformation :", modelStrings, modelStrings[ expectedModelIndex ] );
			gd.addCheckbox( "ignore constant background", rejectIdentity );
			gd.addNumericField( "tolerance :", identityTolerance, 2, 6, "px" );
		}

		public void addAlignmentFields( final GenericDialog gd )
		{
			gd.addChoice( "desired_transformation :", modelStrings, modelStrings[ desiredModelIndex ] );
			gd.addNumericField( "correspondence weight :", correspondenceWeight, 2 );
			gd.addCheckbox( "regularize", regularize );
		}

		public void addRegularizationFields( final GenericDialog gd )
		{
			gd.addChoice( "regularizer :", modelStrings, modelStrings[ regularizerModelIndex ] );
			gd.addNumericField( "lambda :", lambda, 2 );
		}


		public void addFields( final GenericDialog gd )
		{
			addSIFTFields( gd );

			gd.addMessage( "Geometric Consensus Filter:" );

			addGeometricConsensusFilterFields( gd );

			gd.addMessage( "Alignment:" );

			addAlignmentFields( gd );
			addRegularizationFields( gd );
		}

		public boolean readSIFTFields( final GenericDialog gd )
		{
			SIFT.readFields( gd, sift );
			rod = ( float )gd.getNextNumber();

			return !gd.invalidNumber();
		}

		public boolean readGeometricConsensusFilterFields( final GenericDialog gd )
		{
			maxEpsilon = ( float )gd.getNextNumber();
			minInlierRatio = ( float )gd.getNextNumber();
			minNumInliers = ( int )gd.getNextNumber();
			expectedModelIndex = gd.getNextChoiceIndex();

			rejectIdentity = gd.getNextBoolean();
			identityTolerance = ( float )gd.getNextNumber();

			return !gd.invalidNumber();
		}

		public boolean readAlignmentFields( final GenericDialog gd )
		{
			desiredModelIndex = gd.getNextChoiceIndex();
			correspondenceWeight = ( float )gd.getNextNumber();
			regularize = gd.getNextBoolean();

			return !gd.invalidNumber();
		}

		public boolean readRegularizationFields( final GenericDialog gd )
		{
			regularizerModelIndex = gd.getNextChoiceIndex();
			lambda = gd.getNextNumber();

			return !gd.invalidNumber();
		}

		public boolean readFields( final GenericDialog gd )
		{
			boolean b = readSIFTFields( gd );
			b &= readGeometricConsensusFilterFields( gd );
			b &= readAlignmentFields( gd );
			b &= readRegularizationFields( gd );
			return b;
		}

		final public boolean setup( final String title )
		{
			/* SIFT */
			final GenericDialog gdSIFT = new GenericDialog( title + ": SIFT parameters" );
			addSIFTFields( gdSIFT );
			do
			{
				gdSIFT.showDialog();
				if ( gdSIFT.wasCanceled() ) return false;
			}
			while ( !readSIFTFields( gdSIFT ) );

			/* Geometric consensus */
			final GenericDialog gdGeometricConsensusFilter = new GenericDialog( title + ": Geometric Consensus Filter" );
			addGeometricConsensusFilterFields( gdGeometricConsensusFilter );
			do
			{
				gdGeometricConsensusFilter.showDialog();
				if ( gdGeometricConsensusFilter.wasCanceled() ) return false;
			}
			while ( !readGeometricConsensusFilterFields( gdGeometricConsensusFilter ) );

			/* Alignment */
			final GenericDialog gdAlignment = new GenericDialog( title + ": Alignment parameters" );
			addAlignmentFields( gdAlignment );
			do
			{
				gdAlignment.showDialog();
				if ( gdAlignment.wasCanceled() ) return false;
			}
			while ( !readAlignmentFields( gdAlignment ) );

			/* Regularization */
			if ( regularize )
			{
				final GenericDialog gdRegularization = new GenericDialog( title + ": Regularization parameters" );
				addRegularizationFields( gdRegularization );
				do
				{
					gdRegularization.showDialog();
					if ( gdRegularization.wasCanceled() ) return false;
				}
				while ( !readRegularizationFields( gdRegularization ) );
			}

			return true;
		}

		@Override
		public Param clone()
		{
			final Param p = new Param();

			p.sift.initialSigma = this.sift.initialSigma;
			p.sift.steps = this.sift.steps;
			p.sift.minOctaveSize = this.sift.minOctaveSize;
			p.sift.maxOctaveSize = this.sift.maxOctaveSize;
			p.sift.fdSize = this.sift.fdSize;
			p.sift.fdBins = this.sift.fdBins;

			p.rod = rod;
			p.maxEpsilon = maxEpsilon;
			p.minInlierRatio = minInlierRatio;
			p.minNumInliers = minNumInliers;
			p.expectedModelIndex = expectedModelIndex;
			p.rejectIdentity = rejectIdentity;
			p.identityTolerance = identityTolerance;

			p.desiredModelIndex = desiredModelIndex;
			p.correspondenceWeight = correspondenceWeight;
			p.regularize = regularize;
			p.regularizerModelIndex = regularizerModelIndex;
			p.lambda = lambda;

			return p;
		}

		/**
		 * Check if two parameter sets are equal.  So far, this method ignores
		 * the parameter {@link #desiredModelIndex} which defines the
		 * transformation class to be used for {@link Tile} alignment.  This
		 * makes sense for the current use in {@link PointMatch} serialization
		 * but might be misleading for other applications.
		 *
		 * TODO Think about this.
		 *
		 * @param p
		 * @return
		 */
		public boolean equals( final Param p )
		{
			return
				sift.equals( p.sift ) &&
				( rod == p.rod ) &&
				( maxEpsilon == p.maxEpsilon ) &&
				( minInlierRatio == p.minInlierRatio ) &&
				( minNumInliers == p.minNumInliers ) &&
				( expectedModelIndex == p.expectedModelIndex ) &&
				( rejectIdentity == p.rejectIdentity ) &&
				( identityTolerance == p.identityTolerance );
//			&& ( desiredModelIndex == p.desiredModelIndex );
		}
	}

	final static public Param param = new Param();

	static public class ParamOptimize extends Param
	{
		private static final long serialVersionUID = 2173278806083343006L;

		/**
		 * Maximal number of iteration allowed for the optimizer.
		 */
		public int maxIterations = 2000;

		/**
		 * Maximal number of iterations allowed to not change the parameter to
		 * be optimized.
		 */
		public int maxPlateauwidth = 200;

		/**
		 * Filter outliers
		 */
		public boolean filterOutliers = false;
		public float meanFactor = 3.0f;

		@Override
		public void addAlignmentFields( final GenericDialog gd )
		{
			super.addAlignmentFields( gd );

			gd.addMessage( "Optimization:" );

			gd.addNumericField( "maximal_iterations :", maxIterations, 0 );
			gd.addNumericField( "maximal_plateauwidth :", maxPlateauwidth, 0 );
			gd.addCheckbox( "filter outliers", filterOutliers );
			gd.addNumericField( "mean_factor :", meanFactor, 2 );
		}

		@Override
		public boolean readAlignmentFields( final GenericDialog gd )
		{
			super.readAlignmentFields( gd );

			maxIterations = ( int )gd.getNextNumber();
			maxPlateauwidth = ( int )gd.getNextNumber();
			filterOutliers = gd.getNextBoolean();
			meanFactor = ( float )gd.getNextNumber();

			return !gd.invalidNumber();
		}

		@Override
		public void addFields( final GenericDialog gd )
		{
			super.addFields( gd );

			gd.addNumericField( "maximal_iterations :", maxIterations, 0 );
			gd.addNumericField( "maximal_plateauwidth :", maxPlateauwidth, 0 );
			gd.addCheckbox( "filter outliers", filterOutliers );
			gd.addNumericField( "mean_factor :", meanFactor, 2 );
		}

		@Override
		public boolean readFields( final GenericDialog gd )
		{
			super.readFields( gd );

			maxIterations = ( int )gd.getNextNumber();
			maxPlateauwidth = ( int )gd.getNextNumber();
			filterOutliers = gd.getNextBoolean();
			meanFactor = ( float )gd.getNextNumber();

			return !gd.invalidNumber();
		}



		@Override
		final public ParamOptimize clone()
		{
			final ParamOptimize p = new ParamOptimize();

			p.sift.initialSigma = this.sift.initialSigma;
			p.sift.steps = this.sift.steps;
			p.sift.minOctaveSize = this.sift.minOctaveSize;
			p.sift.maxOctaveSize = this.sift.maxOctaveSize;
			p.sift.fdSize = this.sift.fdSize;
			p.sift.fdBins = this.sift.fdBins;

			p.rod = rod;
			p.maxEpsilon = maxEpsilon;
			p.minInlierRatio = minInlierRatio;
			p.minNumInliers = minNumInliers;
			p.expectedModelIndex = expectedModelIndex;
			p.rejectIdentity = rejectIdentity;
			p.identityTolerance = identityTolerance;

			p.desiredModelIndex = desiredModelIndex;
			p.regularize = regularize;
			p.regularizerModelIndex = regularizerModelIndex;
			p.lambda = lambda;
			p.maxIterations = maxIterations;
			p.maxPlateauwidth = maxPlateauwidth;
			p.filterOutliers = filterOutliers;
			p.meanFactor = meanFactor;

			return p;
		}

		public boolean equals( final ParamOptimize p )
		{
			return
				super.equals( p ) &&
				( maxIterations == p.maxIterations ) &&
				( maxPlateauwidth == p.maxPlateauwidth ) &&
				( filterOutliers == p.filterOutliers ) &&
				( meanFactor == p.meanFactor );
		}
	}

	final static public ParamOptimize paramOptimize = new ParamOptimize();

	final static private class Features implements Serializable
	{
		private static final long serialVersionUID = 2689219384710526198L;

		FloatArray2DSIFT.Param p;
		ArrayList< Feature > features;
		Features( final FloatArray2DSIFT.Param p, final ArrayList< Feature > features )
		{
			this.p = p;
			this.features = features;
		}
	}

	final static private class PointMatches implements Serializable
	{
		private static final long serialVersionUID = -2564147268101223484L;

		Param p;
		ArrayList< PointMatch > pointMatches;
		PointMatches( final Param p, final ArrayList< PointMatch > pointMatches )
		{
			this.p = p;
			this.pointMatches = pointMatches;
		}
	}

	/**
	 * Extracts {@link Feature SIFT-features} from a {@link List} of
	 * {@link AbstractAffineTile2D Tiles} and saves them to disk.
	 */
	final static protected class ExtractFeaturesThread extends Thread
	{
		final protected Param p;
		final protected List< AbstractAffineTile2D< ? > > tiles;
//		final protected HashMap< AbstractAffineTile2D< ? >, Collection< Feature > > tileFeatures;
		final protected AtomicInteger ai;
		final protected AtomicInteger ap;
		final protected int steps;

		public ExtractFeaturesThread(
				final Param p,
				final List< AbstractAffineTile2D< ? > > tiles,
//				final HashMap< AbstractAffineTile2D< ? >, Collection< Feature > > tileFeatures,
				final AtomicInteger ai,
				final AtomicInteger ap,
				final int steps )
		{
			this.p = p;
			this.tiles = tiles;
			this.ai = ai;
			this.ap = ap;
			this.steps = steps;
		}

		@Override
		final public void run()
		{
			final FloatArray2DSIFT sift = new FloatArray2DSIFT( p.sift );
			final SIFT ijSIFT = new SIFT( sift );

			for ( int i = ai.getAndIncrement(); i < tiles.size() && !isInterrupted(); i = ai.getAndIncrement() )
			{
				if (isInterrupted()) return;
				final AbstractAffineTile2D< ? > tile = tiles.get( i );
				Collection< Feature > features = deserializeFeatures( p, tile );
				if ( features == null )
				{
					/* extract features and, in case there is not enough memory available, try to free it and do again */
					boolean memoryFlushed;
					do
					{
						try
						{
							features = new ArrayList< Feature >();
							final long s = System.currentTimeMillis();
							ijSIFT.extractFeatures( tile.createMaskedByteImage(), features );
							Utils.log( features.size() + " features extracted in tile " + i + " \"" + tile.getPatch().getTitle() + "\" (took " + ( System.currentTimeMillis() - s ) + " ms)." );
							if ( !serializeFeatures( p, tile, features ) )
								Utils.log( "Saving features failed for tile \"" + tile.getPatch() + "\"" );
							memoryFlushed = false;
						}
						catch ( final OutOfMemoryError e )
						{
							Utils.log2( "Flushing memory for feature extraction" );
							Loader.releaseAllCaches();
							memoryFlushed = true;
						}
					}
					while ( memoryFlushed );
				}
				else
				{
					Utils.log( features.size() + " features loaded for tile " + i + " \"" + tile.getPatch().getTitle() + "\"." );
				}
				IJ.showProgress( ap.getAndIncrement(), steps );
			}
		}
	}


	final static protected class MatchFeaturesAndFindModelThread extends Thread
	{
		final protected Param p;
		final protected List< AbstractAffineTile2D< ? > > tiles;
		//final protected HashMap< AbstractAffineTile2D< ? >, Collection< Feature > > tileFeatures;
		final protected List< AbstractAffineTile2D< ? >[] > tilePairs;
		final protected AtomicInteger ai;
		final protected AtomicInteger ap;
		final protected int steps;
		final protected boolean multipleHypotheses;

		public MatchFeaturesAndFindModelThread(
				final Param p,
				final List< AbstractAffineTile2D< ? > > tiles,
				final List< AbstractAffineTile2D< ? >[] > tilePairs,
				final AtomicInteger ai,
				final AtomicInteger ap,
				final int steps,
				final boolean multipleHypotheses )
		{
			this.p = p;
			this.tiles = tiles;
			this.tilePairs = tilePairs;
			this.ai = ai;
			this.ap = ap;
			this.steps = steps;
			this.multipleHypotheses = multipleHypotheses;
		}

		public MatchFeaturesAndFindModelThread(
				final Param p,
				final List< AbstractAffineTile2D< ? > > tiles,
				final List< AbstractAffineTile2D< ? >[] > tilePairs,
				final AtomicInteger ai,
				final AtomicInteger ap,
				final int steps )
		{
			this( p, tiles, tilePairs, ai, ap, steps, false );
		}

		@Override
		final public void run()
		{
			final List< PointMatch > candidates = new ArrayList< PointMatch >();
			//Keep track of where to store the current Field information in the access class
			int listindex = 0;
			for ( int i = ai.getAndIncrement(); i < tilePairs.size() && !isInterrupted(); i = ai.getAndIncrement() )
			{
				if (isInterrupted()) return;
				candidates.clear();
				final AbstractAffineTile2D< ? >[] tilePair = tilePairs.get( i );

				Collection< PointMatch > inliers = deserializePointMatches( p, tilePair[ 0 ], tilePair[ 1 ] );

				if ( inliers == null )
				{
					inliers = new ArrayList< PointMatch >();

					final long s = System.currentTimeMillis();

					FeatureTransform.matchFeatures(
						fetchFeatures( p, tilePair[ 0 ] ),
						fetchFeatures( p, tilePair[ 1 ] ),
						candidates,
						p.rod );

					/* find the model */
					final AbstractModel< ? > model;
					switch ( p.expectedModelIndex )
					{
					case 0:
						model = new TranslationModel2D();
						break;
					case 1:
						model = new RigidModel2D();
						break;
					case 2:
						model = new SimilarityModel2D();
						break;
					case 3:
						model = new AffineModel2D();
						break;
					case 4:
						model = new HomographyModel2D();
						break;
					default:
						return;
					}

					final boolean modelFound = findModel(
							model,
							candidates,
							inliers,
							p.maxEpsilon,
							p.minInlierRatio,
							p.minNumInliers,
							p.rejectIdentity,
							p.identityTolerance,
							multipleHypotheses );

					if ( modelFound )
					{
						//Create temporary Array list of Inliers class to store information about inliers for current Field Pair
						List<InliersClass> inliers_list_temp = new ArrayList<InliersClass>();
						//index  to keep track where to put the inliers class into the array list
						int m = 0;
						//loop for all inliers in the Field Pair
						for(PointMatch pointmatch: inliers)
						{	
							//Get information from pointmatch into a Point object
							Point point1 = pointmatch.getP1();
							Point point2 = pointmatch.getP2();
							
							//Use local coordinate function to get an actual coordiante
							double[] world1 = point1.getL();
							double[] world2 = point2.getL();					
							
							//Add information to inliers class
							InliersClass inliers_var = new InliersClass(world1[0], world1[1], world2[0], world2[1], pointmatch.getDistance());
							
							//add inliers class to inliers class list
							inliers_list_temp.add(m, inliers_var);
							m++;
						}
						//Create TilePair class object and fill it with the Field Pair information, including the inliers list
						TilePairClass Pair_var = new TilePairClass(tilePair[ 0 ].getPatch(), tilePair[ 1 ].getPatch(), model.getCost(), inliers_list_temp);
						//Add TilePair class object to the Access Array
						Access.tile_pairs.add(listindex, Pair_var); 
						//Keep track of where to add the TilePair class opbject
						listindex++;			
						Utils.log( "Model found for tiles \"" + tilePair[ 0 ].getPatch() + "\" and \"" + tilePair[ 1 ].getPatch() + "\":\n  correspondences  " + inliers.size() + " of " + candidates.size() + "\n  average residual error  " + model.getCost() + " px\n  took " + ( System.currentTimeMillis() - s ) + " ms" );
					}
					else
						Utils.log( "No model found for tiles \"" + tilePair[ 0 ].getPatch() + "\" and \"" + tilePair[ 1 ].getPatch() + "\":\n  correspondence candidates  " + candidates.size() + "\n  took " + ( System.currentTimeMillis() - s ) + " ms" );

					if ( !serializePointMatches( p, tilePair[ 0 ], tilePair[ 1 ], inliers ) )
						Utils.log( "Saving point matches failed for tiles \"" + tilePair[ 0 ].getPatch() + "\" and \"" + tilePair[ 1 ].getPatch() + "\"" );

				}
				else
					Utils.log( "Point matches for tiles \"" + tilePair[ 0 ].getPatch().getTitle() + "\" and \"" + tilePair[ 1 ].getPatch().getTitle() + "\" fetched from disk cache" );

				if ( inliers != null && inliers.size() > 0 )
				{
					/* weight the inliers */
					for ( final PointMatch pm : inliers )
						pm.setWeights( new double[]{ p.correspondenceWeight } );

					synchronized ( tilePair[ 0 ] )
					{
						synchronized ( tilePair[ 1 ] ) { tilePair[ 0 ].connect( tilePair[ 1 ], inliers ); }
						tilePair[ 0 ].clearVirtualMatches();
					}
					synchronized ( tilePair[ 1 ] ) { tilePair[ 1 ].clearVirtualMatches(); }
				}

				IJ.showProgress( ap.getAndIncrement(), steps );
			}
		}
	}


	final static public boolean findModel(
			final Model< ? > model,
			final List< PointMatch > candidates,
			final Collection< PointMatch > inliers,
			final float maxEpsilon,
			final float minInlierRatio,
			final int minNumInliers,
			final boolean rejectIdentity,
			final float identityTolerance,
			final boolean multipleHypotheses )
	{
		boolean again = false;
		int nHypotheses = 0;
		final ArrayList< PointMatch > hypothesisCandidates = new ArrayList< PointMatch >( candidates );
		try
		{
			do
			{
				again = false;
				final ArrayList< PointMatch > inliers2 = new ArrayList< PointMatch >();
				final boolean modelFound = model.filterRansac( hypothesisCandidates, inliers2, 1000, maxEpsilon, minInlierRatio, minNumInliers, 3 );
				if ( modelFound )
				{
					hypothesisCandidates.removeAll( inliers2 );

					if ( rejectIdentity )
					{
						final ArrayList< Point > points = new ArrayList< Point >();
						PointMatch.sourcePoints( inliers2, points );
						if ( Transforms.isIdentity( model, points, param.identityTolerance ) )
						{
							Utils.log( "Identity transform for " + inliers2.size() + " matches rejected." );
							again = true;
						}
						else
						{
							++nHypotheses;
							inliers.addAll( inliers2 );
							again = multipleHypotheses;
						}
					}
					else
					{
						++nHypotheses;
						inliers.addAll( inliers2 );
						again = multipleHypotheses;
					}
				}
			}
			while ( again );
		}
		catch ( final NotEnoughDataPointsException e ) {}

		if ( nHypotheses > 0 && multipleHypotheses )
		{
			try
			{
				model.fit( inliers );
				PointMatch.apply( inliers, model );
				model.setCost( PointMatch.meanDistance( inliers ) );
				Utils.log( nHypotheses + " hypotheses" );
			}
			catch ( final NotEnoughDataPointsException e ) {}
			catch ( final IllDefinedDataPointsException e )
			{
				nHypotheses = 0;
			}
		}

		return nHypotheses > 0;
	}

	final static public boolean findModel(
			final Model< ? > model,
			final List< PointMatch > candidates,
			final Collection< PointMatch > inliers,
			final float maxEpsilon,
			final float minInlierRatio,
			final int minNumInliers,
			final boolean rejectIdentity,
			final float identityTolerance )
	{
		return findModel(
				model,
				candidates,
				inliers,
				maxEpsilon,
				minInlierRatio,
				minNumInliers,
				rejectIdentity,
				identityTolerance,
				false );
	}


	final static protected boolean serializeFeatures( final Param p, final AbstractAffineTile2D< ? > t, final Collection< Feature > f )
	{
		final ArrayList< Feature > list = new ArrayList< Feature >();
		list.addAll( f );
		final Patch patch = t.getPatch();
		final Loader loader = patch.getProject().getLoader();
		final Features fe = new Features( p.sift, list );
		return loader.serialize( fe, new StringBuilder( loader.getUNUIdFolder() ).append( "features.ser/" )
			.append( FSLoader.createIdPath( Long.toString( patch.getId() ), "features", ".ser" ) ).toString() );
	}

	/**
	 * Retrieve the features only if saved with the exact same relevant SIFT parameters.
	 */
	final static protected Collection< Feature > deserializeFeatures( final Param p, final AbstractAffineTile2D< ? > t )
	{
		final Patch patch = t.getPatch();
		final Loader loader = patch.getProject().getLoader();

		final Object ob = loader.deserialize( new StringBuilder( loader.getUNUIdFolder() ).append( "features.ser/" )
			.append( FSLoader.createIdPath( Long.toString( patch.getId() ), "features", ".ser" ) ).toString() );
		if ( null != ob )
		{
			try
			{
				final Features fe = ( Features )ob;
				if ( p.sift.equals( fe.p ) && null != fe.p )
				{
					return fe.features;
				}
			}
			catch ( final Exception e )
			{
				e.printStackTrace();
			}
		}
		return null;
	}


	final static protected Collection< Feature > fetchFeatures(
			final Param p,
			final AbstractAffineTile2D< ? > t )
	{
		Collection< Feature > features = deserializeFeatures( p, t );
		if ( features == null )
		{
			final FloatArray2DSIFT sift = new FloatArray2DSIFT( p.sift );
			final SIFT ijSIFT = new SIFT( sift );
			features = new ArrayList< Feature >();
			final long s = System.currentTimeMillis();
			ijSIFT.extractFeatures( t.createMaskedByteImage(), features );
			Utils.log( features.size() + " features extracted in tile \"" + t.getPatch().getTitle() + "\" (took " + ( System.currentTimeMillis() - s ) + " ms)." );
			if ( !serializeFeatures( p, t, features ) )
				Utils.log( "Saving features failed for tile: " + t.getPatch() );
		}
		return features;
	}


	/**
	 * Save a {@link Collection} of {@link PointMatch PointMatches} two-sided.
	 * Creates two serialization files which is desperately required to clean
	 * up properly invalid serializations on change of a {@link Patch}.
	 *
	 * @param p
	 * @param t1
	 * @param t2
	 * @param m
	 * @return
	 */
	final static protected boolean serializePointMatches(
			final Param p,
			final AbstractAffineTile2D< ? > t1,
			final AbstractAffineTile2D< ? > t2,
			final Collection< PointMatch > m )
	{
		final ArrayList< PointMatch > list = new ArrayList< PointMatch >();
		list.addAll( m );
		final ArrayList< PointMatch > tsil = new ArrayList< PointMatch >();
		PointMatch.flip( m, tsil );
		final Patch p1 = t1.getPatch();
		final Patch p2 = t2.getPatch();
		final Loader loader = p1.getProject().getLoader();
		return
			loader.serialize(
				new PointMatches( p, list ),
				new StringBuilder( loader.getUNUIdFolder() ).append( "pointmatches.ser/" ).append( FSLoader.createIdPath( Long.toString( p1.getId() ) + "_" + Long.toString( p2.getId() ), "pointmatches", ".ser" ) ).toString() ) &&
			loader.serialize(
				new PointMatches( p, tsil ),
				new StringBuilder( loader.getUNUIdFolder() ).append( "pointmatches.ser/" ).append( FSLoader.createIdPath( Long.toString( p2.getId() ) + "_" + Long.toString( p1.getId() ), "pointmatches", ".ser" ) ).toString() );
	}


	final static protected Collection< PointMatch > deserializePointMatches(
			final Param p,
			final AbstractAffineTile2D< ? > t1,
			final AbstractAffineTile2D< ? > t2 )
	{
		final Patch p1 = t1.getPatch();
		final Patch p2 = t2.getPatch();
		final Loader loader = p1.getProject().getLoader();

		final Object ob = loader.deserialize( new StringBuilder( loader.getUNUIdFolder() ).append( "pointmatches.ser/" )
				.append( FSLoader.createIdPath( Long.toString( p1.getId() ) + "_" + Long.toString( p2.getId() ), "pointmatches", ".ser" ) ).toString() );

		if ( null != ob )
		{
			try
			{
				final PointMatches pm = ( PointMatches )ob;
				if ( p.equals( pm.p ) && null != pm.p )
				{
					return pm.pointMatches;
				}
			}
			catch ( final Exception e )
			{
				e.printStackTrace();
			}
		}
		return null;
	}


	/**
	 * Fetch a {@link Collection} of corresponding
	 * {@link Feature SIFT-features}.  Both {@link Feature SIFT-features} and
	 * {@linkplain PointMatch corresponding points} are cached to disk.
	 *
	 * @param p
	 * @param t1
	 * @param t2
	 * @return
	 *   <dl>
	 *     <dt>null</dt><dd>if matching failed for some reasons</dd>
	 *     <dt>empty {@link Collection}</dt><dd>if there was no consistent set
	 *       of {@link PointMatch matches}</dd>
	 *     <dt>{@link Collection} of {@link PointMatch PointMatches}</dt>
	 *       <dd>if there was a consistent set of {@link PointMatch
	 *         PointMatches}</dd>
	 *   </dl>
	 */
	final static protected Collection< PointMatch > fetchPointMatches(
			final Param p,
			final AbstractAffineTile2D< ? > t1,
			final AbstractAffineTile2D< ? > t2 )
	{
		final Collection< PointMatch > pointMatches = deserializePointMatches( p, t1, t2 );
		if ( pointMatches == null )
		{
			final List< PointMatch > candidates = new ArrayList< PointMatch >();
			final List< PointMatch > inliers = new ArrayList< PointMatch >();

			final long s = System.currentTimeMillis();
			FeatureTransform.matchFeatures(
					fetchFeatures( p, t1 ),
					fetchFeatures( p, t2 ),
					candidates,
					p.rod );

			final AbstractAffineModel2D< ? > model;
			switch ( p.expectedModelIndex )
			{
			case 0:
				model = new TranslationModel2D();
				break;
			case 1:
				model = new RigidModel2D();
				break;
			case 2:
				model = new SimilarityModel2D();
				break;
			case 3:
				model = new AffineModel2D();
				break;
			default:
				return null;
			}

			final boolean modelFound = findModel(
					model,
					candidates,
					inliers,
					p.maxEpsilon,
					p.minInlierRatio,
					p.minNumInliers,
					p.rejectIdentity,
					p.identityTolerance );

			if ( modelFound )
				Utils.log( "Model found for tiles \"" + t1.getPatch() + "\" and \"" + t2.getPatch() + "\":\n  correspondences  " + inliers.size() + " of " + candidates.size() + "\n  average residual error  " + model.getCost() + " px\n  took " + ( System.currentTimeMillis() - s ) + " ms" );
			else
				Utils.log( "No model found for tiles \"" + t1.getPatch() + "\" and \"" + t2.getPatch() + "\":\n  correspondence candidates  " + candidates.size() + "\n  took " + ( System.currentTimeMillis() - s ) + " ms" );

			if ( !serializePointMatches( p, t1, t2, pointMatches ) )
				Utils.log( "Saving point matches failed for tile \"" + t1.getPatch() + "\" and tile \"" + t2.getPatch() + "\"" );
		}
		return pointMatches;
	}


	/**
	 * Align a set of {@link AbstractAffineTile2D tiles} using
	 * the following procedure:
	 *
	 * <ol>
	 * <li>Extract {@link Feature SIFT-features} from all
	 * {@link AbstractAffineTile2D tiles}.</li>
	 * <li>Establish {@link PointMatch point-correspondences} from
	 * consistent sets of {@link Feature feature} matches among tile pairs,
	 * optionally inspect only those that are already overlapping.</li>
	 * <li>Globally align the tile configuration.</li>
	 * </ol>
	 *
	 * Both
	 * {@link SIFT#extractFeatures(ij.process.ImageProcessor, Collection) feature extraction}
	 * and {@link FeatureTransform#matchFeatures(Collection, Collection, List, float) matching}
	 * are executed in multiple {@link Thread Threads}, with the number of
	 * {@link Thread Threads} being a parameter of the method.
	 *
	 * @param p
	 * @param tiles
	 * @param fixedTiles
	 * @param tilesAreInPlace
	 * @param numThreads
	 */
	final static public void alignTiles(
			final ParamOptimize p,
			final List< AbstractAffineTile2D< ? > > tiles,
			final List< AbstractAffineTile2D< ? > > fixedTiles,
			final boolean tilesAreInPlace,
			final int numThreads )
	{
		final ArrayList< AbstractAffineTile2D< ? >[] > tilePairs = new ArrayList< AbstractAffineTile2D< ? >[] >();
		if ( tilesAreInPlace )
			AbstractAffineTile2D.pairOverlappingTiles( tiles, tilePairs );
		else
			AbstractAffineTile2D.pairTiles( tiles, tilePairs );
		connectTilePairs( p, tiles, tilePairs, numThreads );
		optimizeTileConfiguration( p, tiles, fixedTiles );
	}


	/**
	 * Align a set of overlapping {@link AbstractAffineTile2D tiles} using
	 * the following procedure:
	 *
	 * <ol>
	 * <li>Extract {@link Feature SIFT-features} from all
	 * {@link AbstractAffineTile2D tiles}.</li>
	 * <li>Establish {@link PointMatch point-correspondences} from
	 * consistent sets of {@link Feature feature} matches among overlapping
	 * tiles.</li>
	 * <li>Globally align the tile configuration.</li>
	 * </ol>
	 *
	 * Both
	 * {@link SIFT#extractFeatures(ij.process.ImageProcessor, Collection) feature extraction}
	 * and {@link FeatureTransform#matchFeatures(Collection, Collection, List, float) matching}
	 * are executed in multiple {@link Thread Threads}, with the number of
	 * {@link Thread Threads} being a parameter of the method.
	 *
	 * @param p
	 * @param tiles
	 * @param numThreads
	 */
	final static public void alignTiles(
			final ParamOptimize p,
			final List< AbstractAffineTile2D< ? > > tiles,
			final List< AbstractAffineTile2D< ? > > fixedTiles,
			final int numThreads )
	{
		alignTiles( p, tiles, fixedTiles, true, numThreads );
	}



	/**
	 * Align a set of {@link AbstractAffineTile2D tiles} that are
	 * interconnected by {@link PointMatch point-correspondences}.
	 */
	final static public void optimizeTileConfiguration(
			final ParamOptimize p,
			final List< AbstractAffineTile2D< ? > > tiles,
			final List< AbstractAffineTile2D< ? > > fixedTiles )
	{
		final TileConfiguration tc = new TileConfiguration();
		for ( final AbstractAffineTile2D< ? > t : tiles )
			if ( t.getConnectedTiles().size() > 0 )
				tc.addTile( t );

//		ArrayList< Set< Tile< ? > > > graphs = Tile.identifyConnectedGraphs( tiles );
//		for ( Set< Tile< ? > > graph : graphs )
//		{
//			boolean pleaseFix = true;
//			if ( fixedTiles != null )
//				for ( final Tile< ? > t : fixedTiles )
//					if ( graph.contains( t ) )
//					{
//						pleaseFix = false;
//						break;
//					}
//			if ( pleaseFix )
//				tc.fixTile( graph.iterator().next() );
//		}
		for ( final Tile< ? > t : fixedTiles )
			tc.fixTile( t );

		try
		{
			if ( p.filterOutliers )
				tc.optimizeAndFilter( p.maxEpsilon, p.maxIterations, p.maxPlateauwidth, p.meanFactor );
			else
				tc.optimize( p.maxEpsilon, p.maxIterations, p.maxPlateauwidth );
		}
		catch ( final Exception e ) { IJ.error( e.getMessage() + " " + e.getStackTrace() ); }
	}


	final static protected void pairwiseAlign(
			final AbstractAffineTile2D< ? > tile,
			final Set< AbstractAffineTile2D< ? > > visited )
	{
		visited.add( tile );
		for ( final Tile< ? > t : tile.getConnectedTiles() )
		{
			if ( visited.contains( t ) ) continue;
			pairwiseAlign( ( AbstractAffineTile2D< ? > )t, visited );
			// TODO Actually do it ...
		}
	}


	final static public void pairwiseAlignTileConfiguration(
			final List< AbstractAffineTile2D< ? > > tiles )
	{
		// TODO Implement it
	}


	/**
	 * Connect a {@link List} of {@link AbstractAffineTile2D Tiles} by
	 * geometrically consistent {@link Feature SIFT-feature} correspondences.
	 *
	 * @param p
	 * @param tiles
	 * @param tilePairs
	 * @param numThreads
	 * @param multipleHypotheses
	 */
	final static public void connectTilePairs(
			final Param p,
			final List< AbstractAffineTile2D< ? > > tiles,
			final List< AbstractAffineTile2D< ? >[] > tilePairs,
			final int numThreads,
			final boolean multipleHypotheses )
	{
		final AtomicInteger ai = new AtomicInteger( 0 );
		final AtomicInteger ap = new AtomicInteger( 0 );
		final int steps = tiles.size() + tilePairs.size();
		final List< ExtractFeaturesThread > extractFeaturesThreads = new ArrayList< ExtractFeaturesThread >();
		final List< MatchFeaturesAndFindModelThread > matchFeaturesAndFindModelThreads = new ArrayList< MatchFeaturesAndFindModelThread >();

		/** Extract and save Features */
		for ( int i = 0; i < numThreads; ++i )
		{
			final ExtractFeaturesThread thread = new ExtractFeaturesThread( p.clone(), tiles, ai, ap, steps );
			extractFeaturesThreads.add( thread );
			thread.start();
		}

		try
		{
			for ( final ExtractFeaturesThread thread : extractFeaturesThreads )
				thread.join();
		}
		catch ( final InterruptedException e )
		{
			Utils.log( "Feature extraction interrupted." );
			for ( final Thread thread : extractFeaturesThreads )
				thread.interrupt();
			try
			{
				for ( final Thread thread : extractFeaturesThreads )
					thread.join();
			}
			catch ( final InterruptedException f ) {}
			Thread.currentThread().interrupt();
			IJ.showProgress( 1.0 );
			return;
		}

		/** Establish correspondences */
		ai.set( 0 );
		for ( int i = 0; i < numThreads; ++i )
		{
			final MatchFeaturesAndFindModelThread thread = new MatchFeaturesAndFindModelThread( p.clone(), tiles, tilePairs, ai, ap, steps, multipleHypotheses );
			matchFeaturesAndFindModelThreads.add( thread );
			thread.start();
		}
		try
		{
			for ( final MatchFeaturesAndFindModelThread thread : matchFeaturesAndFindModelThreads )
				thread.join();
		}
		catch ( final InterruptedException e )
		{
			Utils.log( "Establishing feature correspondences interrupted." );
			for ( final Thread thread : matchFeaturesAndFindModelThreads )
				thread.interrupt();
			try
			{
				for ( final Thread thread : matchFeaturesAndFindModelThreads )
					thread.join();
			}
			catch ( final InterruptedException f ) {}
			Thread.currentThread().interrupt();
			IJ.showProgress( 1.0 );
		}
	}


	/**
	 * Connect a {@link List} of {@link AbstractAffineTile2D Tiles} by
	 * geometrically consistent {@link Feature SIFT-feature} correspondences.
	 *
	 * @param p
	 * @param tiles
	 * @param tilePairs
	 * @param numThreads
	 */
	final static public void connectTilePairs(
			final Param p,
			final List< AbstractAffineTile2D< ? > > tiles,
			final List< AbstractAffineTile2D< ? >[] > tilePairs,
			final int numThreads )
	{
		connectTilePairs(p, tiles, tilePairs, numThreads, false);
	}


	/**
	 * If a Patch is locked or in fixedPatches, its corresponding Tile is added to fixedTiles.
	 *
	 * @param p
	 * @param patches
	 * @param fixedPatches
	 * @param tiles will contain the generated
	 *   {@link AbstractAffineTile2D Tiles}
	 * @param fixedTiles will contain the {@link AbstractAffineTile2D Tiles}
	 *   corresponding to the {@link Patch Patches} in fixedPatches
	 */
	@SuppressWarnings( { "unchecked", "rawtypes" } )
	final static public void tilesFromPatches(
			final Param p,
			final List< ? extends Patch > patches,
			final Collection< ? extends Patch > fixedPatches,
			final List< AbstractAffineTile2D< ? > > tiles,
			final Collection< AbstractAffineTile2D< ? > > fixedTiles )
	{
		for ( final Patch patch : patches )
		{
			final AbstractAffineTile2D< ? > t;
			if ( p.regularize )
			{
				/* can only be affine per convention */
				final AbstractAffineModel2D< ? > m = ( AbstractAffineModel2D< ? > )Util.createModel( p.desiredModelIndex );
				final AbstractAffineModel2D< ? > r = ( AbstractAffineModel2D< ? > )Util.createModel( p.regularizerModelIndex );

				/* for type safety one would test both models as for the simple
				 * case below but here I will go for the easy route and let
				 * Java cast it to what is required and ignore the warning.
				 */
				@SuppressWarnings( { } )
				final InterpolatedAffineModel2D< ?, ? > interpolatedModel = new InterpolatedAffineModel2D( m, r, p.lambda );

				t = new GenericAffineTile2D( interpolatedModel, patch );
			}
			else
			{
				switch ( p.desiredModelIndex )
				{
				case 0:
					t = new TranslationTile2D( patch );
					break;
				case 1:
					t = new RigidTile2D( patch );
					break;
				case 2:
					t = new SimilarityTile2D( patch );
					break;
				case 3:
					t = new AffineTile2D( patch );
					break;
				default:
					return;
				}
			}
			tiles.add( t );
			if ( ( fixedPatches != null && fixedPatches.contains( patch ) ) || patch.isLocked() )
				fixedTiles.add( t );
		}
	}


	/**
	 * Align a selection of {@link Patch patches} in a Layer.
	 *
	 * @param selection
	 * @param numThreads
	 */
	final static public void alignSelectedPatches( final Selection selection, final int numThreads )
	{
		final List< Patch > patches = new ArrayList< Patch >();
		for ( final Displayable d : selection.getSelected() )
			if ( d instanceof Patch ) patches.add(  ( Patch )d );

		if ( patches.size() < 2 ) return;

		if ( !paramOptimize.setup( "Align selected patches" ) ) return;

		final List< AbstractAffineTile2D< ? > > tiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final List< Patch > fixedPatches = new ArrayList< Patch >();
		final Displayable active = selection.getActive();
		if ( active != null && active instanceof Patch )
			fixedPatches.add( ( Patch )active );
		tilesFromPatches( paramOptimize, patches, fixedPatches, tiles, fixedTiles );

		alignTiles( paramOptimize, tiles, fixedTiles, numThreads );

		for ( final AbstractAffineTile2D< ? > t : tiles )
			t.getPatch().setAffineTransform( t.getModel().createAffine() );
	}


	/**
	 * Align all {@link Patch patches} in a Layer.
	 *
	 * @param layer
	 */
	final static public void alignLayer( final Layer layer, final int numThreads )
	{
		if ( !paramOptimize.setup( "Align patches in layer" ) ) return;

		final List< Displayable > displayables = layer.getDisplayables( Patch.class );
		final List< Patch > patches = new ArrayList< Patch >();
		for ( final Displayable d : displayables )
			patches.add( ( Patch )d );
		final List< AbstractAffineTile2D< ? > > tiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		tilesFromPatches( paramOptimize, patches, null, tiles, fixedTiles );

		alignTiles( paramOptimize, tiles, fixedTiles, numThreads );

		for ( final AbstractAffineTile2D< ? > t : tiles )
			t.getPatch().setAffineTransform( t.getModel().createAffine() );
	}

	/**
	 * Align a range of layers by accumulating pairwise alignments of contiguous layers.
	 *
	 * @param layers The range of layers to align pairwise.
	 * @param numThreads The number of threads to use.
	 */
	final static public void alignLayersLinearly( final List< Layer > layers, final int numThreads )
	{
		alignLayersLinearly(layers, numThreads, null);
	}

	/**
	 * Align a range of layers by accumulating pairwise alignments of contiguous layers.
	 *
	 * @param layers The range of layers to align pairwise.
	 * @param numThreads The number of threads to use.
	 * @param filter The {@link Filter} to decide which {@link Patch} instances to use in each {@link Layer}. Can be null.
	 */
	final static public void alignLayersLinearly( final List< Layer > layers, final int numThreads, final Filter<Patch> filter )
	{
		param.sift.maxOctaveSize = 1600;

		if ( !param.setup( "Align layers linearly" ) ) return;

		final Rectangle box = layers.get( 0 ).getParent().getMinimalBoundingBox( Patch.class );
		final double scale = Math.min(  1.0, Math.min( ( double )param.sift.maxOctaveSize / box.width, ( double )param.sift.maxOctaveSize / box.height ) );
		final Param p = param.clone();
		p.maxEpsilon *= scale;

		final FloatArray2DSIFT sift = new FloatArray2DSIFT( p.sift );
		final SIFT ijSIFT = new SIFT( sift );

		Rectangle box1 = null;
		Rectangle box2 = null;
		final Collection< Feature > features1 = new ArrayList< Feature >();
		final Collection< Feature > features2 = new ArrayList< Feature >();
		final List< PointMatch > candidates = new ArrayList< PointMatch >();
		final List< PointMatch > inliers = new ArrayList< PointMatch >();

		final AffineTransform a = new AffineTransform();

		int i = 0;
		for ( final Layer l : layers )
		{
			long s = System.currentTimeMillis();

			features1.clear();
			features1.addAll( features2 );
			features2.clear();

			final Rectangle box3 = l.getMinimalBoundingBox( Patch.class );

			if ( box3 == null ) continue;

			box1 = box2;
			box2 = box3;

			final List<Patch> patches = l.getAll(Patch.class);
			if (null != filter) {
				for (final Iterator<Patch> it = patches.iterator(); it.hasNext(); ) {
					if (!filter.accept(it.next())) it.remove();
				}
			}

			ijSIFT.extractFeatures(
					l.getProject().getLoader().getFlatImage( l, box2, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, patches, true ).getProcessor(),
					features2 );
			Utils.log( features2.size() + " features extracted in layer \"" + l.getTitle() + "\" (took " + ( System.currentTimeMillis() - s ) + " ms)." );

			if ( features1.size() > 0 )
			{
				s = System.currentTimeMillis();

				candidates.clear();

				FeatureTransform.matchFeatures(
					features2,
					features1,
					candidates,
					p.rod );

				final AbstractAffineModel2D< ? > model;
				switch ( p.expectedModelIndex )
				{
				case 0:
					model = new TranslationModel2D();
					break;
				case 1:
					model = new RigidModel2D();
					break;
				case 2:
					model = new SimilarityModel2D();
					break;
				case 3:
					model = new AffineModel2D();
					break;
				default:
					return;
				}

				boolean modelFound;
				boolean again = false;
				try
				{
					do
					{
						again = false;
						modelFound = model.filterRansac(
								candidates,
								inliers,
								1000,
								p.maxEpsilon,
								p.minInlierRatio,
								p.minNumInliers,
								3 );
						if ( modelFound && p.rejectIdentity )
						{
							final ArrayList< Point > points = new ArrayList< Point >();
							PointMatch.sourcePoints( inliers, points );
							if ( Transforms.isIdentity( model, points, p.identityTolerance ) )
							{
								Utils.log( "Identity transform for " + inliers.size() + " matches rejected." );
								candidates.removeAll( inliers );
								inliers.clear();
								again = true;
							}
						}
					}
					while ( again );
				}
				catch ( final NotEnoughDataPointsException e )
				{
					modelFound = false;
				}

				if ( modelFound )
				{
					Utils.log( "Model found for layer \"" + l.getTitle() + "\" and its predecessor:\n  correspondences  " + inliers.size() + " of " + candidates.size() + "\n  average residual error  " + ( model.getCost() / scale ) + " px\n  took " + ( System.currentTimeMillis() - s ) + " ms" );
					final AffineTransform b = new AffineTransform();
					b.translate( box1.x, box1.y );
					b.scale( 1.0f / scale, 1.0f / scale );
					b.concatenate( model.createAffine() );
					b.scale( scale, scale );
					b.translate( -box2.x, -box2.y);

					a.concatenate( b );
					l.apply( Displayable.class, a );
					Display.repaint( l );
				}
				else
				{
					Utils.log( "No model found for layer \"" + l.getTitle() + "\" and its predecessor:\n  correspondence candidates  " + candidates.size() + "\n  took " + ( System.currentTimeMillis() - s ) + " ms" );
					a.setToIdentity();
				}
			}
			IJ.showProgress( ++i, layers.size() );
		}
	}

	/**
	 * Temporary helper method that creates
	 * @param matches
	 * @param alpha
	 * @return
	 * @throws Exception
	 */
	final static public MovingLeastSquaresTransform2 createMLST( final Collection< PointMatch > matches, final double alpha ) throws Exception
	{
		final MovingLeastSquaresTransform2 mlst = new MovingLeastSquaresTransform2();
		mlst.setAlpha( alpha );
		Class< ? extends AbstractAffineModel2D< ? > > c = AffineModel2D.class;
		switch ( matches.size() )
		{
			case 1:
				c = TranslationModel2D.class;
				break;
			case 2:
				c = SimilarityModel2D.class;
				break;
			default:
				break;
		}
		mlst.setModel( c );
		mlst.setMatches( matches );

		return mlst;
	}


	/**
	 * Align two collections of tiles
	 * @param p
	 * @param a
	 * @param b
	 */
	final static public void alignTileCollections( final Param p, final Collection< AbstractAffineTile2D< ? > > a, final Collection< AbstractAffineTile2D< ? > > b )
	{
		final ArrayList< Patch > pa = new ArrayList< Patch >();
		final ArrayList< Patch > pb = new ArrayList< Patch >();
		for ( final AbstractAffineTile2D< ? > t : a )
			pa.add( t.getPatch() );
		for ( final AbstractAffineTile2D< ? > t : b )
			pb.add( t.getPatch() );

		final Layer la = pa.iterator().next().getLayer();
		final Layer lb = pb.iterator().next().getLayer();

		final Rectangle boxA = Displayable.getBoundingBox( pa, null );
		final Rectangle boxB = Displayable.getBoundingBox( pb, null );

		final double scale = Math.min(
				1.0,
				Math.min(
					Math.min(
							( double )p.sift.maxOctaveSize / boxA.width,
							( double )p.sift.maxOctaveSize / boxA.height ),
					Math.min(
							( double )p.sift.maxOctaveSize / boxB.width,
							( double )p.sift.maxOctaveSize / boxB.height ) ) );

		final Param pp = p.clone();
		pp.maxEpsilon *= scale;

		final FloatArray2DSIFT sift = new FloatArray2DSIFT( pp.sift );
		final SIFT ijSIFT = new SIFT( sift );

		final Collection< Feature > featuresA = new ArrayList< Feature >();
		final Collection< Feature > featuresB = new ArrayList< Feature >();
		final List< PointMatch > candidates = new ArrayList< PointMatch >();
		final List< PointMatch > inliers = new ArrayList< PointMatch >();

		long s = System.currentTimeMillis();
		ijSIFT.extractFeatures(
				la.getProject().getLoader().getFlatImage( la, boxA, scale, 0xffffffff, ImagePlus.GRAY8, null, pa, true, Color.GRAY ).getProcessor(), featuresA );
		Utils.log( featuresA.size() + " features extracted in graph A in layer \"" + la.getTitle() + "\" (took " + ( System.currentTimeMillis() - s ) + " ms)." );

		s = System.currentTimeMillis();
		ijSIFT.extractFeatures(
				lb.getProject().getLoader().getFlatImage( lb, boxB, scale, 0xffffffff, ImagePlus.GRAY8, null, pb, true, Color.GRAY ).getProcessor(), featuresB );
		Utils.log( featuresB.size() + " features extracted in graph B in layer \"" + lb.getTitle() + "\" (took " + ( System.currentTimeMillis() - s ) + " ms)." );

		if ( featuresA.size() > 0 && featuresB.size() > 0 )
		{
			s = System.currentTimeMillis();
			FeatureTransform.matchFeatures(
					featuresA,
					featuresB,
					candidates,
					pp.rod );

			final AbstractAffineModel2D< ? > model;
			switch ( p.expectedModelIndex )
			{
			case 0:
				model = new TranslationModel2D();
				break;
			case 1:
				model = new RigidModel2D();
				break;
			case 2:
				model = new SimilarityModel2D();
				break;
			case 3:
				model = new AffineModel2D();
				break;
			default:
				return;
			}

			boolean modelFound;
			boolean again = false;
			try
			{
				do
				{
					again = false;
					modelFound = model.filterRansac(
							candidates,
							inliers,
							1000,
							p.maxEpsilon,
							p.minInlierRatio,
							p.minNumInliers,
							3 );
					if ( modelFound && p.rejectIdentity )
					{
						final ArrayList< Point > points = new ArrayList< Point >();
						PointMatch.sourcePoints( inliers, points );
						if ( Transforms.isIdentity( model, points, p.identityTolerance ) )
						{
							Utils.log( "Identity transform for " + inliers.size() + " matches rejected." );
							candidates.removeAll( inliers );
							inliers.clear();
							again = true;
						}
					}
				}
				while ( again );
			}
			catch ( final NotEnoughDataPointsException e )
			{
				modelFound = false;
			}

			if ( modelFound )
			{
				Utils.log( "Model found for graph A and B in layers \"" + la.getTitle() + "\" and \"" + lb.getTitle() + "\":\n  correspondences  " + inliers.size() + " of " + candidates.size() + "\n  average residual error  " + ( model.getCost() / scale ) + " px\n  took " + ( System.currentTimeMillis() - s ) + " ms" );
				final AffineTransform at = new AffineTransform();
				at.translate( boxA.x, boxA.y );
				at.scale( 1.0f / scale, 1.0f / scale );
				at.concatenate( model.createAffine() );
				at.scale( scale, scale );
				at.translate( -boxB.x, -boxB.y);

				for ( final Patch t : pa )
					t.preTransform( at, false );
				Display.repaint( la );
			}
			else
				Utils.log( "No model found for graph A and B in layers \"" + la.getTitle() + "\" and \"" + lb.getTitle() + "\":\n  correspondence candidates  " + candidates.size() + "\n  took " + ( System.currentTimeMillis() - s ) + " ms" );
		}
	}
}
