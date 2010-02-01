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
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Paintable;
import ini.trakem2.display.Patch;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.Choice;
import java.awt.event.ItemListener;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.Vector;

import bunwarpj.Transformation;
import bunwarpj.bUnwarpJ_;
import bunwarpj.trakem2.transform.CubicBSplineTransform;

import mpicbg.ij.FeatureTransform;
import mpicbg.ij.SIFT;
import mpicbg.ij.util.Util;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;
import mpicbg.trakem2.transform.RigidModel2D;
import mpicbg.trakem2.transform.TranslationModel2D;

/**
 * Register a range of layers using linear or non-linear transformations.
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de> and Ignacio Arganda <ignacio.arganda@gmail.com>
 * @version 0.1a
 */
final public class AlignLayersTask
{
	static protected boolean propagateTransform = false;
	static protected boolean useBUnwarpJ = false;
	static protected bunwarpj.Param elasticParam = new bunwarpj.Param();
	
	private AlignLayersTask(){}
	
	final static public Bureaucrat alignLayersTask ( final Layer l )
	{
		Worker worker = new Worker("Aligning layers", false, true) {
			public void run() {
				startedWorking();
				try {
					alignLayers(l);
				} catch (Throwable e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
		};
		return Bureaucrat.createAndStart(worker, l.getProject());
	}
	
	
	final static public void alignLayers( final Layer l )
	{
		final List< Layer > layers = l.getParent().getLayers();
		final String[] layerTitles = new String[ layers.size() ];
		for ( int i = 0; i < layers.size(); ++i )
			layerTitles[ i ] = l.getProject().findLayerThing(layers.get( i )).toString();
		
		//Param p = Align.param;
		//Align.param.sift.maxOctaveSize = 1024;
		
		final GenericDialog gd = new GenericDialog( "Align Layers" );
		
		gd.addMessage( "Layer Range:" );
		final int sel = layers.indexOf(l);
		gd.addChoice( "first :", layerTitles, layerTitles[ sel ] );
		gd.addChoice( "reference :", layerTitles, layerTitles[ sel ] );
		gd.addChoice( "last :", layerTitles, layerTitles[ sel ] );
		final Vector v = gd.getChoices();
		final Choice cstart = (Choice) v.get(v.size() -3);
		final Choice cref = (Choice) v.get(v.size() -2);
		final Choice cend = (Choice) v.get(v.size() -1);
		cstart.addItemListener(new ItemListener() {
			public void itemStateChanged(ItemEvent ie) {
				int index = cstart.getSelectedIndex();
				if (index > cref.getSelectedIndex()) cref.select(index);
				if (index > cend.getSelectedIndex()) cend.select(index);
			}
		});
		cref.addItemListener(new ItemListener() {
			public void itemStateChanged(ItemEvent ie) {
				int index = cref.getSelectedIndex();
				if (index < cstart.getSelectedIndex()) cstart.select(index);
				if (index > cend.getSelectedIndex()) cend.select(index);
			}
		});
		cend.addItemListener(new ItemListener() {
			public void itemStateChanged(ItemEvent ie) {
				int index = cend.getSelectedIndex();
				if (index < cstart.getSelectedIndex()) cstart.select(index);
				if (index < cref.getSelectedIndex()) cref.select(index);
			}
		});		

		Align.param.addFields( gd );
		gd.addCheckbox( "use bUnwarpJ (non-linear cubic B-Splines)", useBUnwarpJ );
		
		gd.addMessage( "Miscellaneous:" );
		gd.addCheckbox( "propagate after last transform", propagateTransform );
		
		gd.showDialog();
		if ( gd.wasCanceled() ) return;
		
		final int first = gd.getNextChoiceIndex();
		final int ref = gd.getNextChoiceIndex();
		final int last = gd.getNextChoiceIndex();
		
		Align.param.readFields( gd );
		
		useBUnwarpJ = gd.getNextBoolean();
		propagateTransform = gd.getNextBoolean();
		
		if (useBUnwarpJ && !elasticParam.showDialog()) return;

		// From ref to first:
		if (ref - first > 0) {
			if (useBUnwarpJ) alignLayersNonLinearlyJob(l, ref, first, propagateTransform);
			else alignLayersLinearlyJob(l, ref, first, propagateTransform);
		}
		// From ref to last:
		if (last - ref > 0) {
			if (useBUnwarpJ) alignLayersNonLinearlyJob(l, ref, last, propagateTransform);
			else alignLayersLinearlyJob(l, ref, last, propagateTransform);
		}
	}
	
	
	final static public void alignLayersLinearlyJob( final Layer l, final int first, final int last, final boolean propagateTransform )
	{
		final List< Layer > layerRange = l.getParent().getLayers(first, last); // will reverse order if necessary
		
		final Align.Param p = Align.param.clone();
		Rectangle box = null;
		// find the first non-empty layer, and remove all empty layers
		for (Iterator<Layer> it = layerRange.iterator(); it.hasNext(); ) {
			Layer la = it.next();
			if (!la.contains(Patch.class)) {
				it.remove();
				continue;
			}
			if (null == box) {
				// The first layer:
				box = la.getMinimalBoundingBox(Patch.class);
			}
		}
		if (0 == layerRange.size()) {
			Utils.log("All layers in range are empty!");
			return;
		}

		/* do not work if there is only one layer selected */
		if ( layerRange.size() < 2 ) return;

		final float scale = Math.min(  1.0f, Math.min( ( float )p.sift.maxOctaveSize / ( float )box.width, ( float )p.sift.maxOctaveSize / ( float )box.height ) );
		p.maxEpsilon *= scale;

		//Utils.log2("scale: " + scale + "  maxOctaveSize: " + p.sift.maxOctaveSize + "  box: " + box.width + "," + box.height);
		
		final FloatArray2DSIFT sift = new FloatArray2DSIFT( p.sift );
		final SIFT ijSIFT = new SIFT( sift );
		
		Rectangle box1 = null;
		Rectangle box2 = null;
		final Collection< Feature > features1 = new ArrayList< Feature >();
		final Collection< Feature > features2 = new ArrayList< Feature >();
		List< PointMatch > candidates = new ArrayList< PointMatch >();
		List< PointMatch > inliers = new ArrayList< PointMatch >();
		
		AffineTransform a = new AffineTransform();
		
		int s = 0;
		for ( final Layer layer : layerRange )
		{
			if ( Thread.currentThread().isInterrupted() ) break;
			
 			final long t0 = System.currentTimeMillis();
			
			features1.clear();
			features1.addAll( features2 );
			features2.clear();
			
			final Rectangle box3 = layer.getMinimalBoundingBox( Patch.class );
			
			if ( box3 == null || ( box3.width == 0 && box3.height == 0 ) ) continue; // skipping empty layer
			
			box1 = box2;
			box2 = box3;
			
			final ImageProcessor flatImage = layer.getProject().getLoader().getFlatImage( layer, box2, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, true ).getProcessor();

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
	
				boolean modelFound;
				try
				{
					modelFound = model.filterRansac(
							candidates,
							inliers,
							1000,
							p.maxEpsilon,
							p.minInlierRatio,
							3 * model.getMinNumMatches(),
							3 );
				}
				catch ( NotEnoughDataPointsException e )
				{
					modelFound = false;
				}
				
				if ( modelFound )
				{
					IJ.log( "Model found for layer \"" + layer.getTitle() + "\" and its predecessor:\n  correspondences  " + inliers.size() + " of " + candidates.size() + "\n  average residual error  " + ( model.getCost() / scale ) + " px\n  took " + ( System.currentTimeMillis() - t1 ) + " ms" );
					final AffineTransform b = new AffineTransform();
					b.translate( box1.x, box1.y );
					b.scale( 1.0f / scale, 1.0f / scale );
					b.concatenate( model.createAffine() );
					b.scale( scale, scale );
					b.translate( -box2.x, -box2.y);
					
					a.concatenate( b );
					AlignTask.transformPatchesAndVectorData(layer, a);
					Display.repaint( layer );
				}
				else
					IJ.log( "No model found for layer \"" + layer.getTitle() + "\" and its predecessor." );
			}
			IJ.showProgress( ++s, layerRange.size() );
		}
		if ( propagateTransform )
		{
			for (Layer la : l.getParent().getLayers(last > first ? last +1 : first -1, last > first ? l.getParent().size() -1 : 0)) {
				AlignTask.transformPatchesAndVectorData(la, a);
			}
		}
	}
	
	final static public void alignLayersNonLinearlyJob( final Layer l, final int first, final int last, final boolean propagateTransform )
	{
		final List< Layer > layerRange = l.getParent().getLayers(first, last); // will reverse order if necessary

		final Align.Param p = Align.param.clone();

		// find the first non-empty layer, and remove all empty layers
		for (Iterator<Layer> it = layerRange.iterator(); it.hasNext(); ) {
			if (!it.next().contains(Patch.class)) {
				it.remove();
			}
		}
		if (0 == layerRange.size()) {
			Utils.log("All layers in range are empty!");
			return;
		}
		
		/* do not work if there is only one layer selected */
		if ( layerRange.size() < 2 ) return;

		final List<Patch> all = new ArrayList<Patch>();
		for (final Layer la : layerRange) all.addAll((Collection<Patch>)(Collection)la.getDisplayables(Patch.class));

		AlignTask.transformPatchesAndVectorData(all, new Runnable() { public void run() {

			/////

		final Loader loader = l.getProject().getLoader();

		// Not concurrent safe! So two copies, one per layer and Thread:
		final SIFT ijSIFT1 = new SIFT( new FloatArray2DSIFT( p.sift ) );
		final SIFT ijSIFT2 = new SIFT( new FloatArray2DSIFT( p.sift ) ); 
		
		final Collection< Feature > features1 = new ArrayList< Feature >();
		final Collection< Feature > features2 = new ArrayList< Feature >();
		final List< PointMatch > candidates = new ArrayList< PointMatch >();
		final List< PointMatch > inliers = new ArrayList< PointMatch >();

		final int n_proc = Runtime.getRuntime().availableProcessors() > 1 ? 2 : 1;
		final ExecutorService exec = Utils.newFixedThreadPool(n_proc, "alignLayersNonLinearly");
		
		int s = 0;
		for ( int i = 1; i < layerRange.size(); ++i )
		{
			if ( Thread.currentThread().isInterrupted() ) break;
			
 			final Layer layer1 = layerRange.get( i - 1 );
			final Layer layer2 = layerRange.get( i );
			
			final long t0 = System.currentTimeMillis();
			
			features1.clear();
			features2.clear();
			
			final Rectangle box1 = layer1.getMinimalBoundingBox( Patch.class );
			final Rectangle box2 = layer2.getMinimalBoundingBox( Patch.class );
			
			/* calculate the common scale factor for both flat images */
			final float scale = Math.min(  1.0f, ( float )p.sift.maxOctaveSize / ( float )Math.max( box1.width, Math.max( box1.height, Math.max( box2.width, box2.height ) ) ) );
			
			
			final Future<ImageProcessor> fu1 = exec.submit(new Callable<ImageProcessor>() {
				public ImageProcessor call() {
					final ImageProcessor ip1 = loader.getFlatImage( layer1, box1, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, true ).getProcessor();
					ijSIFT1.extractFeatures(
							ip1,
							features1 );
					Utils.log( features1.size() + " features extracted in layer \"" + layer1.getTitle() + "\" (took " + ( System.currentTimeMillis() - t0 ) + " ms)." );
					return ip1;
				}});
			final Future<ImageProcessor> fu2 = exec.submit(new Callable<ImageProcessor>() {
				public ImageProcessor call() {
					final ImageProcessor ip2 = loader.getFlatImage( layer2, box2, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, true ).getProcessor();
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
			} catch (Exception e) {
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
				try
				{
					modelFound = model.filterRansac(
							candidates,
							inliers,
							1000,
							p.maxEpsilon,
							p.minInlierRatio,
							3 * model.getMinNumMatches(),
							3 );
				}
				catch ( NotEnoughDataPointsException e )
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
					
					Transformation warp = bUnwarpJ_.computeTransformationBatch(imp2, imp1, mask2, mask1, elasticParam);
					
					final CubicBSplineTransform transf = new CubicBSplineTransform();
					transf.set(warp.getIntervals(), warp.getDirectDeformationCoefficientsX(), warp.getDirectDeformationCoefficientsY(),
	                		imp2.getWidth(), imp2.getHeight());

					final ArrayList<Future> fus = new ArrayList<Future>();
					
					for ( final Displayable disp : layer2.getDisplayables( Patch.class ) )
					{
						try {								
							final Patch patch = ( Patch )disp;
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
						} catch ( Exception e ) {
							e.printStackTrace();
						}
					}

					// await regeneration of all mipmaps
					Utils.wait(fus);
					
					Display.repaint( layer2 );
				}
				else
					IJ.log( "No model found for layer \"" + layer2.getTitle() + "\" and its predecessor." );
			}
			IJ.showProgress( ++s, layerRange.size() );	
		}

		exec.shutdown();

		if (propagateTransform)	Utils.log("Propagation not implemented yet for non-linear layer alignment.");
		/* TODO do this
		if ( propagateTransform )
		{
			for (Layer la : l.getParent().getLayers(last > first ? last +1 : first -1, last > first ? l.getParent().size() -1 : 0)) {
				la.apply( Displayable.class, a );
			}
		}
		*/

		}}); // end of transformPatchesAndVectorData
	}
}
