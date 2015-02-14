/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package mpicbg.trakem2.align;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.process.ImageProcessor;
import ini.trakem2.display.Display;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Filter;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.Choice;
import java.awt.Rectangle;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import mpicbg.ij.FeatureTransform;
import mpicbg.ij.SIFT;
import mpicbg.ij.util.Util;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.Transforms;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;
import mpicbg.trakem2.transform.RigidModel2D;
import mpicbg.trakem2.transform.TranslationModel2D;
import bunwarpj.Transformation;
import bunwarpj.bUnwarpJ_;
import bunwarpj.trakem2.transform.CubicBSplineTransform;

/**
 * Register a range of layers using linear or non-linear transformations.
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de> and Ignacio Arganda <ignacio.arganda@gmail.com>
 * @version 0.1a
 */
final public class AlignLayersTask
{
	static protected int LINEAR = 0, BUNWARPJ = 1, ELASTIC = 2;
	static protected int mode = LINEAR;
	final static String[] modeStrings = new String[]{
		"least squares (linear feature correspondences)",
		"bUnwarpJ (non-linear cubic B-Splines)",
		"elastic (non-linear block correspondences)" };

	static protected boolean propagateTransformBefore = false;
	static protected boolean propagateTransformAfter = false;
	static protected bunwarpj.Param elasticParam = new bunwarpj.Param();

	private AlignLayersTask(){}
	final static public Bureaucrat alignLayersTask ( final Layer l )
	{
		return alignLayersTask( l, null );
	}

	final static public Bureaucrat alignLayersTask ( final Layer l, final Rectangle fov )
	{
		final Worker worker = new Worker("Aligning layers", false, true) {
			@Override
			public void run() {
				startedWorking();
				try {
					alignLayers(l, fov);
				} catch (final Throwable e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
		};
		return Bureaucrat.createAndStart(worker, l.getProject());
	}

	final static public void alignLayers( final Layer l ) throws Exception
	{
		alignLayers(l, null);
	}

	final static public void alignLayers( final Layer l, final Rectangle fov ) throws Exception
	{
		final List< Layer > layers = l.getParent().getLayers();
		final String[] layerTitles = new String[ layers.size() ];
		final String[] noneAndLayerTitles = new String[ layers.size() + 1 ];
		noneAndLayerTitles[ 0 ] = "*None*";
		for ( int i = 0; i < layers.size(); ++i )
		{
			layerTitles[ i ] = l.getProject().findLayerThing(layers.get( i )).toString();
			noneAndLayerTitles[ i + 1 ] = layerTitles[ i ];
		}

		//Param p = Align.param;
		//Align.param.sift.maxOctaveSize = 1024;

		final GenericDialog gd = new GenericDialog( "Align Layers" );

		gd.addChoice( "mode :", modeStrings, modeStrings[ LINEAR ] );

		gd.addMessage( "Layer Range:" );
		final int sel = l.getParent().indexOf(l);
		gd.addChoice( "first :", layerTitles, layerTitles[ sel ] );
		gd.addChoice( "reference :", noneAndLayerTitles, layerTitles[ sel ] );
		gd.addChoice( "last :", layerTitles, layerTitles[ sel ] );
		gd.addStringField("Use only images whose title matches:", "", 30);
		gd.addCheckbox("Use visible images only", true);
		gd.addCheckbox( "propagate transform to first layer", propagateTransformBefore );
		gd.addCheckbox( "propagate transform to last layer", propagateTransformAfter );
		final Vector<?> v = gd.getChoices();
		final Choice cstart = (Choice) v.get(v.size() -3);
		final Choice cref = (Choice) v.get(v.size() -2);
		final Choice cend = (Choice) v.get(v.size() -1);
		final ItemListener startEndListener = new ItemListener() {
			@Override
			public void itemStateChanged(final ItemEvent ie) {
				final int startIndex = cstart.getSelectedIndex();
				final int endIndex = cend.getSelectedIndex();
				final int refIndex = cref.getSelectedIndex();

				final int min = Math.min(startIndex, endIndex);
				final int max = Math.max(startIndex, endIndex);

				if (cref.getSelectedIndex() > 0) {
					cref.select( Math.min( max + 1, Math.max( min + 1, refIndex ) ) );
				}
			}
		};
		cstart.addItemListener(startEndListener);
		cend.addItemListener(startEndListener);
		cref.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(final ItemEvent ie) {
				final int startIndex = cstart.getSelectedIndex();
				final int endIndex = cend.getSelectedIndex();
				final int refIndex = cref.getSelectedIndex() - 1;

				final int min = Math.min(startIndex, endIndex);
				final int max = Math.max(startIndex, endIndex);

				if (refIndex >= 0) {
					if (refIndex < min) {
						if ( startIndex <= endIndex )
							cstart.select(refIndex);
						else
							cend.select(refIndex);
					}
					else if (refIndex > max) {
						if ( startIndex <= endIndex )
							cend.select(refIndex);
						else
							cstart.select(refIndex);
					}
				}
			}
		});

		gd.showDialog();
		if ( gd.wasCanceled() ) return;

		mode = gd.getNextChoiceIndex();

		final int first = gd.getNextChoiceIndex();
		final int ref = gd.getNextChoiceIndex() - 1;
		final int last = gd.getNextChoiceIndex();

		final String toMatch1 = gd.getNextString().trim();
		final String toMatch2 = 0 == toMatch1.length() ? null : ".*" + toMatch1 + ".*";
		final boolean visibleOnly = gd.getNextBoolean();

		propagateTransformBefore = gd.getNextBoolean();
		propagateTransformAfter = gd.getNextBoolean();

		final Filter<Patch> filter = new Filter<Patch>() {
			@Override
			public final boolean accept(final Patch patch) {
				if (visibleOnly && !patch.isVisible()) return false;
				if (null != toMatch2 && !patch.getTitle().matches(toMatch2)) return false;
				return true;
			}
		};

		if ( mode == ELASTIC )
			new ElasticLayerAlignment().exec( l.getParent(), first, last, ref, propagateTransformBefore, propagateTransformAfter, fov, filter );
		else if ( mode == LINEAR )
			new RegularizedAffineLayerAlignment().exec( l.getParent(), first, last, ref, propagateTransformBefore, propagateTransformAfter, fov, filter );
		else
		{
			final GenericDialog gd2 = new GenericDialog( "Align Layers" );

			Align.param.addFields( gd2 );

			gd2.showDialog();
			if ( gd2.wasCanceled() ) return;

			Align.param.readFields( gd2 );

			if ( mode == BUNWARPJ && !elasticParam.showDialog() ) return;

			// From ref to first:
			if (ref - first > 0)
			{
				if ( mode == BUNWARPJ )
					alignLayersNonLinearlyJob(l.getParent(), ref, first, propagateTransformBefore, fov, filter);
				else
					alignLayersLinearlyJob(l.getParent(), ref, first, propagateTransformBefore, fov, filter);
			}
			// From ref to last:
			if (last - ref > 0)
			{
				if ( mode == BUNWARPJ )
					alignLayersNonLinearlyJob(l.getParent(), ref, last, propagateTransformAfter, fov, filter);
				else
					alignLayersLinearlyJob(l.getParent(), ref, last, propagateTransformAfter, fov, filter);
			}
		}
	}


	final static public void alignLayersLinearlyJob( final LayerSet layerSet, final int first, final int last,
			final boolean propagateTransform, final Rectangle fov, final Filter<Patch> filter )
	{
		final List< Layer > layerRange = layerSet.getLayers(first, last); // will reverse order if necessary

		final Align.Param p = Align.param.clone();
		// find the first non-empty layer, and remove all empty layers
		Rectangle box = fov;
		for (final Iterator<Layer> it = layerRange.iterator(); it.hasNext(); ) {
			final Layer la = it.next();
			if (!la.contains(Patch.class, true)) {
				it.remove();
				continue;
			}
			if (null == box) {
				// The first layer:
				box = la.getMinimalBoundingBox(Patch.class, true); // Only for visible patches
			}
		}
		if (0 == layerRange.size()) {
			Utils.log("All layers in range are empty!");
			return;
		}

		/* do not work if there is only one layer selected */
		if ( layerRange.size() < 2 ) return;

		final double scale = Math.min(  1.0, Math.min( ( double )p.sift.maxOctaveSize / ( double )box.width, ( double )p.sift.maxOctaveSize / ( double )box.height ) );
		p.maxEpsilon *= scale;
		p.identityTolerance *= scale;

		//Utils.log2("scale: " + scale + "  maxOctaveSize: " + p.sift.maxOctaveSize + "  box: " + box.width + "," + box.height);

		final FloatArray2DSIFT sift = new FloatArray2DSIFT( p.sift );
		final SIFT ijSIFT = new SIFT( sift );

		Rectangle box1 = fov;
		Rectangle box2 = fov;
		final Collection< Feature > features1 = new ArrayList< Feature >();
		final Collection< Feature > features2 = new ArrayList< Feature >();
		final List< PointMatch > candidates = new ArrayList< PointMatch >();
		final List< PointMatch > inliers = new ArrayList< PointMatch >();

		final AffineTransform a = new AffineTransform();

		int s = 0;
		for ( final Layer layer : layerRange )
		{
			if ( Thread.currentThread().isInterrupted() ) return;

 			final long t0 = System.currentTimeMillis();

			features1.clear();
			features1.addAll( features2 );
			features2.clear();

			final Rectangle box3 = layer.getMinimalBoundingBox( Patch.class, true );

			if ( box3 == null || ( box3.width == 0 && box3.height == 0 ) ) continue; // skipping empty layer

			box1 = null == fov ? box2 : fov;
			box2 = null == fov ? box3 : fov;

			final List<Patch> patches = layer.getAll(Patch.class);
			if (null != filter) {
				for (final Iterator<Patch> it = patches.iterator(); it.hasNext(); ) {
					if (!filter.accept(it.next())) it.remove();
				}
			}

			final ImageProcessor flatImage = layer.getProject().getLoader().getFlatImage( layer, box2, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, patches, true ).getProcessor();

			ijSIFT.extractFeatures(
					flatImage,
					features2 );
			IJ.log( features2.size() + " features extracted in layer \"" + layer.getTitle() + "\" (took " + ( System.currentTimeMillis() - t0 ) + " ms)." );

			if ( features1.size() > 0 )
			{
				final long t1 = System.currentTimeMillis();

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

				final AbstractAffineModel2D< ? > desiredModel;
				switch ( p.desiredModelIndex )
				{
				case 0:
					desiredModel = new TranslationModel2D();
					break;
				case 1:
					desiredModel = new RigidModel2D();
					break;
				case 2:
					desiredModel = new SimilarityModel2D();
					break;
				case 3:
					desiredModel = new AffineModel2D();
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
								IJ.log( "Identity transform for " + inliers.size() + " matches rejected." );
								candidates.removeAll( inliers );
								inliers.clear();
								again = true;
							}
						}
					}
					while ( again );

					if ( modelFound )
						desiredModel.fit( inliers );
				}
				catch ( final NotEnoughDataPointsException e )
				{
					modelFound = false;
				}
				catch ( final IllDefinedDataPointsException e )
				{
					modelFound = false;
				}

				if ( Thread.currentThread().isInterrupted() ) return;

				if ( modelFound )
				{
					IJ.log( "Model found for layer \"" + layer.getTitle() + "\" and its predecessor:\n  correspondences  " + inliers.size() + " of " + candidates.size() + "\n  average residual error  " + ( model.getCost() / scale ) + " px\n  took " + ( System.currentTimeMillis() - t1 ) + " ms" );
					final AffineTransform b = new AffineTransform();
					b.translate( box1.x, box1.y );
					b.scale( 1.0f / scale, 1.0f / scale );
					b.concatenate( desiredModel.createAffine() );
					b.scale( scale, scale );
					b.translate( -box2.x, -box2.y);

					a.concatenate( b );
					AlignTask.transformPatchesAndVectorData(patches, a);
					Display.repaint( layer );
				}
				else
				{
					IJ.log( "No model found for layer \"" + layer.getTitle() + "\" and its predecessor:\n  correspondence candidates  " + candidates.size() + "\n  took " + ( System.currentTimeMillis() - s ) + " ms" );
					a.setToIdentity();
				}
			}
			IJ.showProgress( ++s, layerRange.size() );
		}

		if ( Thread.currentThread().isInterrupted() ) return;

		if ( propagateTransform )
		{
			if ( last > first && last < layerSet.size() - 2 )
				for ( final Layer la : layerSet.getLayers( last + 1, layerSet.size() - 1 ) ) {
					if (Thread.currentThread().isInterrupted()) return;
					AlignTask.transformPatchesAndVectorData( la, a );
				}
			else if ( first > last && last > 0 )
				for ( final Layer la : layerSet.getLayers( 0, last - 1 ) ) {
					if (Thread.currentThread().isInterrupted()) return;
					AlignTask.transformPatchesAndVectorData( la, a );
				}
		}
	}

	final static public void alignLayersNonLinearlyJob( final LayerSet layerSet, final int first, final int last,
			final boolean propagateTransform, final Rectangle fov, final Filter<Patch> filter )
	{
		final List< Layer > layerRange = layerSet.getLayers(first, last); // will reverse order if necessary

		final Align.Param p = Align.param.clone();

		// Remove all empty layers
		for (final Iterator<Layer> it = layerRange.iterator(); it.hasNext(); ) {
			if (!it.next().contains(Patch.class, true)) {
				it.remove();
			}
		}
		if (0 == layerRange.size()) {
			Utils.log("No layers in range show any images!");
			return;
		}

		/* do not work if there is only one layer selected */
		if ( layerRange.size() < 2 ) return;

		final List<Patch> all = new ArrayList<Patch>();
		for (final Layer la : layerRange) {
			for (final Patch patch : la.getAll(Patch.class)) {
				if (null != filter && !filter.accept(patch)) continue;
				all.add(patch);
			}
		}

		AlignTask.transformPatchesAndVectorData(all, new Runnable() { @Override
		public void run() {

			/////

		final Loader loader = layerSet.getProject().getLoader();

		// Not concurrent safe! So two copies, one per layer and Thread:
		final SIFT ijSIFT1 = new SIFT( new FloatArray2DSIFT( p.sift ) );
		final SIFT ijSIFT2 = new SIFT( new FloatArray2DSIFT( p.sift ) );

		final Collection< Feature > features1 = new ArrayList< Feature >();
		final Collection< Feature > features2 = new ArrayList< Feature >();
		final List< PointMatch > candidates = new ArrayList< PointMatch >();
		final List< PointMatch > inliers = new ArrayList< PointMatch >();

		final int n_proc = Runtime.getRuntime().availableProcessors() > 1 ? 2 : 1;
		final ExecutorService exec = Utils.newFixedThreadPool(n_proc, "alignLayersNonLinearly");

		List<Patch> previousPatches = null;

		int s = 0;
		for ( int i = 1; i < layerRange.size(); ++i )
		{
			if ( Thread.currentThread().isInterrupted() ) break;

 			final Layer layer1 = layerRange.get( i - 1 );
			final Layer layer2 = layerRange.get( i );

			final long t0 = System.currentTimeMillis();

			features1.clear();
			features2.clear();

			final Rectangle box1 = null == fov ? layer1.getMinimalBoundingBox( Patch.class, true ) : fov;
			final Rectangle box2 = null == fov ? layer2.getMinimalBoundingBox( Patch.class, true ) : fov;

			/* calculate the common scale factor for both flat images */
			final double scale = Math.min(  1.0f, ( double )p.sift.maxOctaveSize / ( double )Math.max( box1.width, Math.max( box1.height, Math.max( box2.width, box2.height ) ) ) );

			final List<Patch> patches1;
			if (null == previousPatches) {
				patches1 = layer1.getAll(Patch.class);
				if (null != filter) {
					for (final Iterator<Patch> it = patches1.iterator(); it.hasNext(); ) {
						if (!filter.accept(it.next())) it.remove();
					}
				}
			} else {
				patches1 = previousPatches;
			}

			final List<Patch> patches2 = layer2.getAll(Patch.class);
			if (null != filter) {
				for (final Iterator<Patch> it = patches2.iterator(); it.hasNext(); ) {
					if (!filter.accept(it.next())) it.remove();
				}
			}

			final Future<ImageProcessor> fu1 = exec.submit(new Callable<ImageProcessor>() {
				@Override
				public ImageProcessor call() {
					final ImageProcessor ip1 = loader.getFlatImage( layer1, box1, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, patches1, true ).getProcessor();
					ijSIFT1.extractFeatures(
							ip1,
							features1 );
					Utils.log( features1.size() + " features extracted in layer \"" + layer1.getTitle() + "\" (took " + ( System.currentTimeMillis() - t0 ) + " ms)." );
					return ip1;
				}});
			final Future<ImageProcessor> fu2 = exec.submit(new Callable<ImageProcessor>() {
				@Override
				public ImageProcessor call() {
					final ImageProcessor ip2 = loader.getFlatImage( layer2, box2, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, patches2, true ).getProcessor();
					ijSIFT2.extractFeatures(
							ip2,
							features2 );
					Utils.log( features2.size() + " features extracted in layer \"" + layer2.getTitle() + "\" (took " + ( System.currentTimeMillis() - t0 ) + " ms)." );
					return ip2;
				}});

			final ImageProcessor ip1, ip2;
			try {
				ip1 = fu1.get();
				ip2 = fu2.get();
			} catch (final Exception e) {
				IJError.print(e);
				return;
			}

			if ( features1.size() > 0 && features2.size() > 0 )
			{
				final long t1 = System.currentTimeMillis();

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
								IJ.log( "Identity transform for " + inliers.size() + " matches rejected." );
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
					IJ.log( "Model found for layer \"" + layer2.getTitle() + "\" and its predecessor:\n  correspondences  " + inliers.size() + " of " + candidates.size() + "\n  average residual error  " + ( model.getCost() / scale ) + " px\n  took " + ( System.currentTimeMillis() - t1 ) + " ms" );

					final ImagePlus imp1 = new ImagePlus("target", ip1);
					final ImagePlus imp2 = new ImagePlus("source", ip2);

					final List< Point > sourcePoints = new ArrayList<Point>();
					final List< Point > targetPoints = new ArrayList<Point>();

					PointMatch.sourcePoints( inliers, sourcePoints );
					PointMatch.targetPoints( inliers, targetPoints );

					imp2.setRoi( Util.pointsToPointRoi(sourcePoints) );
					imp1.setRoi( Util.pointsToPointRoi(targetPoints) );

					final ImageProcessor mask1 = ip1.duplicate();
					mask1.threshold(1);
					final ImageProcessor mask2 = ip2.duplicate();
					mask2.threshold(1);

					final Transformation warp = bUnwarpJ_.computeTransformationBatch(imp2, imp1, mask2, mask1, elasticParam);

					final CubicBSplineTransform transf = new CubicBSplineTransform();
					transf.set(warp.getIntervals(), warp.getDirectDeformationCoefficientsX(), warp.getDirectDeformationCoefficientsY(),
	                		imp2.getWidth(), imp2.getHeight());

					final ArrayList<Future<?>> fus = new ArrayList<Future<?>>();

					// Transform desired patches only
					for ( final Patch patch : patches2 )
					{
						try {
							final Rectangle pbox = patch.getCoordinateTransformBoundingBox();
							final AffineTransform at = patch.getAffineTransform();
							final AffineTransform pat = new AffineTransform();
							pat.scale( scale, scale );
							pat.translate( -box2.x, -box2.y );
							pat.concatenate( at );
							pat.translate( -pbox.x, -pbox.y );


							final mpicbg.trakem2.transform.AffineModel2D toWorld = new mpicbg.trakem2.transform.AffineModel2D();
							toWorld.set( pat );

							final CoordinateTransformList< CoordinateTransform > ctl = new CoordinateTransformList< CoordinateTransform >();
							// move the patch into the global space where bUnwarpJ calculated the transformation
							ctl.add( toWorld );
							// Apply non-linear transformation
							ctl.add( transf );
							// move it back
							ctl.add( toWorld.createInverse() );

							patch.appendCoordinateTransform( ctl );

							fus.add(patch.updateMipMaps());

							// Compensate for offset between boxes
							final AffineTransform offset = new AffineTransform();
							offset.translate( box1.x - box2.x , box1.y - box2.y );
							offset.concatenate( at );
							patch.setAffineTransform( offset );
						} catch ( final Exception e ) {
							e.printStackTrace();
						}
					}

					// await regeneration of all mipmaps
					Utils.wait(fus);

					Display.repaint( layer2 );
				}
				else
					IJ.log( "No model found for layer \"" + layer2.getTitle() + "\" and its predecessor:\n  correspondence candidates  " + candidates.size() + "\n  took " + ( System.currentTimeMillis() - s ) + " ms" );
			}
			IJ.showProgress( ++s, layerRange.size() );

			previousPatches = patches2; // for next iteration
		}

		exec.shutdown();

		if (propagateTransform)	Utils.log("Propagation not implemented yet for non-linear layer alignment.");

		/* // CANNOT be done (at least not trivially:
		 * //an appropriate "scale" cannot be computed, and the box2 is part of the spline computation.
		if ( propagateTransform && null != lastTransform )
		{
			for (final Layer la : l.getParent().getLayers(last > first ? last +1 : first -1, last > first ? l.getParent().size() -1 : 0)) {
				// Transform visible patches only
				final Rectangle box2 = la.getMinimalBoundingBox( Patch.class, true );
				for ( final Displayable disp : la.getDisplayables( Patch.class, true ) )
				{
					// ...
				}
			}
		}
		*/

		}}); // end of transformPatchesAndVectorData
	}
}
