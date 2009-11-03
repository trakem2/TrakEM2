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
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Worker;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import mpicbg.ij.FeatureTransform;
import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;
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
	
	private AlignLayersTask(){}
	
	final static public Bureaucrat alignLayersLinearlyTask ( final Layer l )
	{
		Worker worker = new Worker("Aligning layers", false, true) {
			public void run() {
				startedWorking();
				try {
					alignLayersLinearly(l);
				} catch (Throwable e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
		};
		return Bureaucrat.createAndStart(worker, l.getProject());
	}
	

	final static public void alignLayersLinearly( final Layer l )
	{
		final List< Layer > layers = l.getParent().getLayers();
		final String[] layerTitles = new String[ layers.size() ];
		for ( int i = 0; i < layers.size(); ++i )
			layerTitles[ i ] = l.getProject().findLayerThing(layers.get( i )).toString();
		
		//Param p = Align.param;
		//Align.param.sift.maxOctaveSize = 1024;
		
		final GenericDialog gd = new GenericDialog( "Align Layers Linearly" );
		
		gd.addMessage( "Layer Range:" );
		final int sel = layers.indexOf(l);
		gd.addChoice( "first :", layerTitles, layerTitles[ sel ] );
		gd.addChoice( "last :", layerTitles, layerTitles[ sel ] );
		Align.param.addFields( gd );
		
		gd.addMessage( "Miscellaneous:" );
		gd.addCheckbox( "propagate after last transform", propagateTransform );
		
		gd.showDialog();
		if ( gd.wasCanceled() ) return;
		
		final int first = gd.getNextChoiceIndex();
		final int last = gd.getNextChoiceIndex();
		
		Align.param.readFields( gd );
		propagateTransform = gd.getNextBoolean();
		
		alignLayersLinearlyJob( l, first, last, propagateTransform );		
	}
	
	
	final static public void alignLayersLinearlyJob( final Layer l, final int first, final int last, final boolean propagateTransform )
	{
		final List< Layer > layers = l.getParent().getLayers();
		final int d = first < last ? 1 : -1;
		
		final Align.Param p = Align.param.clone();
		final Rectangle box = layers.get( 0 ).getParent().getMinimalBoundingBox( Patch.class );
		final float scale = Math.min(  1.0f, Math.min( ( float )p.sift.maxOctaveSize / ( float )box.width, ( float )p.sift.maxOctaveSize / ( float )box.height ) );
		p.maxEpsilon *= scale;
		
		final List< Layer > layerRange = new ArrayList< Layer >();
		for ( int i = first; i != last + d; i += d )
			layerRange.add( layers.get( i ) );
		
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
			
 			long t = System.currentTimeMillis();
			
			features1.clear();
			features1.addAll( features2 );
			features2.clear();
			
			final Rectangle box3 = layer.getMinimalBoundingBox( Patch.class );
			
			if ( box3 == null || ( box.width == 0 && box.height == 0 ) ) continue;
			
			box1 = box2;
			box2 = box3;
			
			ijSIFT.extractFeatures(
					layer.getProject().getLoader().getFlatImage( layer, box2, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, true ).getProcessor(),
					features2 );
			IJ.log( features2.size() + " features extracted in layer \"" + layer.getTitle() + "\" (took " + ( System.currentTimeMillis() - t ) + " ms)." );
			
			if ( features1.size() > 0 )
			{
				t = System.currentTimeMillis();
				
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
					IJ.log( "Model found for layer \"" + layer.getTitle() + "\" and its predecessor:\n  correspondences  " + inliers.size() + " of " + candidates.size() + "\n  average residual error  " + ( model.getCost() / scale ) + " px\n  took " + ( System.currentTimeMillis() - t ) + " ms" );
					final AffineTransform b = new AffineTransform();
					b.translate( box1.x, box1.y );
					b.scale( 1.0f / scale, 1.0f / scale );
					b.concatenate( model.createAffine() );
					b.scale( scale, scale );
					b.translate( -box2.x, -box2.y);
					
					a.concatenate( b );
					layer.apply( Displayable.class, a );
					Display.repaint( layer );
				}
				else
					IJ.log( "No model found for layer \"" + layer.getTitle() + "\" and its predecessor." );
			}
			IJ.showProgress( ++s, layerRange.size() );	
		}
		if ( propagateTransform )
		{
			for ( int i = last + d; i >= 0 && i < layers.size(); i += d )
				layers.get( i ).apply( Displayable.class, a );
		}
	}
}
