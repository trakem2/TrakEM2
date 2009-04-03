/**
 * 
 */
package mpicbg.trakem2.align;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import mpicbg.ij.FeatureTransform;
import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.Tile;
import mpicbg.trakem2.transform.RigidModel2D;
import mpicbg.trakem2.transform.TranslationModel2D;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Selection;
import ini.trakem2.utils.Worker;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

/**
 * Methods collection to be called from the GUI for alignment tasks.
 *
 */
final public class AlignTask
{
	static protected boolean tilesAreInPlace = false;
	static protected boolean largestGraphOnly = false;
	static protected boolean hideDisconnectedTiles = false;
	static protected boolean deleteDisconnectedTiles = false;
	
	final static public Bureaucrat alignSelectionTask ( final Selection selection )
	{
		Worker worker = new Worker("Aligning selected images", false, true) {
			public void run() {
				startedWorking();
				try {
					alignSelection( selection );
					Display.repaint(selection.getLayer());
				} catch (Throwable e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
			public void cleanup() {
				if (!selection.isEmpty())
					selection.getLayer().getParent().undoOneStep();
			}
		};
		return Bureaucrat.createAndStart( worker, selection.getProject() );
	}
	
		
	final static public void alignSelection( final Selection selection )
	{
		List< Patch > patches = new ArrayList< Patch >();
		for ( Displayable d : Display.getFront().getSelection().getSelected() )
			if ( d instanceof Patch ) patches.add( ( Patch )d );

		if ( patches.size() < 2 )
		{
			Utils.log("No images to align in the selection.");
			return;
		}
		
		//final Align.ParamOptimize p = Align.paramOptimize;
		final GenericDialog gd = new GenericDialog( "Align Selected Tiles" );
		Align.paramOptimize.addFields( gd );
		
		gd.addMessage( "Miscellaneous:" );
		gd.addCheckbox( "tiles are rougly in place", false );
		gd.addCheckbox( "consider largest graph only", false );
		gd.addCheckbox( "hide tiles from non-largest graph", false );
		gd.addCheckbox( "delete tiles from non-largest graph", false );
		
		gd.showDialog();
		if ( gd.wasCanceled() ) return;
		
		Align.paramOptimize.readFields( gd );
		tilesAreInPlace = gd.getNextBoolean();
		largestGraphOnly = gd.getNextBoolean();
		hideDisconnectedTiles = gd.getNextBoolean();
		deleteDisconnectedTiles = gd.getNextBoolean();
		
		final Align.ParamOptimize p = Align.paramOptimize.clone();
		List< Patch > fixedPatches = new ArrayList< Patch >();
		final Displayable active = selection.getActive();
		if ( active != null && active instanceof Patch )
			fixedPatches.add( ( Patch )active );

		List< AbstractAffineTile2D< ? > > tiles = new ArrayList< AbstractAffineTile2D< ? > >();
		List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
		Align.tilesFromPatches( p, patches, fixedPatches, tiles, fixedTiles );
		
		alignTiles( p, tiles, fixedTiles );
	}
	
	
	final static public void alignTiles(
			final Align.ParamOptimize p,
			final List< AbstractAffineTile2D< ? > > tiles,
			final List< AbstractAffineTile2D< ? > > fixedTiles )
	{
		final List< AbstractAffineTile2D< ? >[] > tilePairs = new ArrayList< AbstractAffineTile2D< ? >[] >();
		if ( tilesAreInPlace )
			AbstractAffineTile2D.pairOverlappingTiles( tiles, tilePairs );
		else
			AbstractAffineTile2D.pairTiles( tiles, tilePairs );
		
		Align.connectTilePairs( p, tiles, tilePairs, Runtime.getRuntime().availableProcessors() );
		
		if ( Thread.currentThread().isInterrupted() ) return;
		
		List< Set< Tile< ? > > > graphs = AbstractAffineTile2D.identifyConnectedGraphs( tiles );
		
		final List< AbstractAffineTile2D< ? > > interestingTiles;
		if ( largestGraphOnly )
		{
			/* find largest graph. */
			
			Set< Tile< ? > > largestGraph = null;
			for ( Set< Tile< ? > > graph : graphs )
				if ( largestGraph == null || largestGraph.size() < graph.size() )
					largestGraph = graph;
			
			interestingTiles = new ArrayList< AbstractAffineTile2D< ? > >();
			for ( Tile< ? > t : largestGraph )
				interestingTiles.add( ( AbstractAffineTile2D< ? > )t );
			
			if ( hideDisconnectedTiles )
				for ( AbstractAffineTile2D< ? > t : tiles )
					if ( !interestingTiles.contains( t ) )
						t.getPatch().setVisible( false );
			if ( deleteDisconnectedTiles )
				for ( AbstractAffineTile2D< ? > t : tiles )
					if ( !interestingTiles.contains( t ) )
						t.getPatch().remove( false );
		}
		else
		{
			interestingTiles = tiles;
			
			/**
			 * virtually interconnect disconnected intersecting graphs
			 * 
			 * TODO Not yet tested---Do we need these virtual connections?
			 */
			
//			if ( graphs.size() > 1 && tilesAreInPlace )
//			{
//				for ( AbstractAffineTile2D< ? >[] tilePair : tilePairs )
//					for ( Set< Tile< ? > > graph : graphs )
//						if ( graph.contains( tilePair[ 0 ] ) && !graph.contains( tilePair[ 1 ] ) )
//							tilePair[ 0 ].makeVirtualConnection( tilePair[ 1 ] );
//			}
		}
			
		if ( Thread.currentThread().isInterrupted() ) return;
		
		Align.optimizeTileConfiguration( p, interestingTiles, fixedTiles );
		
		for ( AbstractAffineTile2D< ? > t : tiles )
			t.getPatch().setAffineTransform( t.getModel().createAffine() );
	}
	

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
			layerTitles[ i ] = layers.get( i ).getTitle();
		
		//Param p = Align.param;
		Align.param.sift.maxOctaveSize = 1600;
		
		final GenericDialog gd = new GenericDialog( "Align Layers Linearly" );
		
		gd.addMessage( "Layer Range:" );
		gd.addChoice( "first :", layerTitles, l.getTitle() );
		gd.addChoice( "last :", layerTitles, l.getTitle() );
		Align.param.addFields( gd );
		
		gd.addMessage( "Miscellaneous:" );
		gd.addCheckbox( "propagate after last transform", false );
		
		gd.showDialog();
		if ( gd.wasCanceled() ) return;
		
		final int first = gd.getNextChoiceIndex();
		final int last = gd.getNextChoiceIndex();
		final int d = first < last ? 1 : -1;
		
		Align.param.readFields( gd );
		final boolean propagateTransform = gd.getNextBoolean();
		
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
	
	
	/**
	 * Align a multi-layer mosaic.
	 * 
	 * @param l the current layer
	 */
	final public static void alignMultiLayerMosaic( final Layer l )
	{
		/* layer range and misc */
		
		final List< Layer > layers = l.getParent().getLayers();
		final String[] layerTitles = new String[ layers.size() ];
		for ( int i = 0; i < layers.size(); ++i )
			layerTitles[ i ] = layers.get( i ).getTitle();
		
		final GenericDialog gd1 = new GenericDialog( "Align Multi-Layer Mosaic : Layer Range" );
		
		gd1.addMessage( "Layer Range:" );
		gd1.addChoice( "first :", layerTitles, l.getTitle() );
		gd1.addChoice( "last :", layerTitles, l.getTitle() );
		
		gd1.addMessage( "Miscellaneous:" );
		gd1.addCheckbox( "tiles are rougly in place", false );
		gd1.addCheckbox( "consider largest graph only", false );
		gd1.addCheckbox( "hide tiles from non-largest graph", false );
		gd1.addCheckbox( "delete tiles from non-largest graph", false );
		
		gd1.showDialog();
		if ( gd1.wasCanceled() ) return;
		
		final int first = gd1.getNextChoiceIndex();
		final int last = gd1.getNextChoiceIndex();
		final int d = first < last ? 1 : -1;
		
		tilesAreInPlace = gd1.getNextBoolean();
		largestGraphOnly = gd1.getNextBoolean();
		hideDisconnectedTiles = gd1.getNextBoolean();
		deleteDisconnectedTiles = gd1.getNextBoolean();
		
		
		/* intra-layer parameters */
		
		final GenericDialog gd2 = new GenericDialog( "Align Multi-Layer Mosaic : Intra-Layer" );

		Align.paramOptimize.addFields( gd2 );
		
		gd2.showDialog();
		if ( gd2.wasCanceled() ) return;
		
		Align.paramOptimize.readFields( gd2 );
		
		
		/* cross-layer parameters */
		
		final GenericDialog gd3 = new GenericDialog( "Align Multi-Layer Mosaic : Cross-Layer" );

		Align.param.addFields( gd3 );
		
		gd3.showDialog();
		if ( gd3.wasCanceled() ) return;
		
		Align.param.readFields( gd3 );
		
		Align.ParamOptimize p = Align.paramOptimize.clone();
		Align.Param cp = Align.param.clone();
		Align.ParamOptimize pcp = p.clone();
		pcp.desiredModelIndex = cp.desiredModelIndex;
		
		
		/* register */
		
		final List< Layer > layerRange = new ArrayList< Layer >();
		for ( int i = first; i != last + d; i += d )
			layerRange.add( layers.get( i ) );
		
		final List< AbstractAffineTile2D< ? > > allTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final List< AbstractAffineTile2D< ? > > allFixedTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final List< AbstractAffineTile2D< ? > > previousLayerTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		
		List< Patch > fixedPatches = new ArrayList< Patch >();
		final Displayable active = Display.getFront().getActive();
		if ( active != null && active instanceof Patch )
			fixedPatches.add( ( Patch )active );
		
		for ( final Layer layer : layerRange )
		{
			/* align all tiles in the layer */
			
			List< Patch > patches = new ArrayList< Patch >();
			for ( Displayable a : layer.getDisplayables( Patch.class ) )
				if ( a instanceof Patch ) patches.add( ( Patch )a );
			List< AbstractAffineTile2D< ? > > currentLayerTiles = new ArrayList< AbstractAffineTile2D< ? > >();
			List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
			Align.tilesFromPatches( p, patches, fixedPatches, currentLayerTiles, fixedTiles );
			
			/* add a fixed tile only if there was a Patch selected */
			allFixedTiles.addAll( fixedTiles );
			
			alignTiles( p, currentLayerTiles, fixedTiles );
			
			/* connect to the previous layer */
			
			final List< AbstractAffineTile2D< ? >[] > crossLayerTilePairs = new ArrayList< AbstractAffineTile2D< ? >[] >();
			AbstractAffineTile2D.pairTiles( previousLayerTiles, currentLayerTiles, crossLayerTilePairs );
			
			Align.connectTilePairs( cp, currentLayerTiles, crossLayerTilePairs, Runtime.getRuntime().availableProcessors() );
			
			/* prepare the next loop */
			
			allTiles.addAll( currentLayerTiles );
			previousLayerTiles.clear();
			previousLayerTiles.addAll( currentLayerTiles );
			currentLayerTiles.clear();
			
			/* optimize */
			Align.optimizeTileConfiguration( pcp, allTiles, allFixedTiles );
			
			for ( AbstractAffineTile2D< ? > t : allTiles )
				t.getPatch().setAffineTransform( t.getModel().createAffine() );
		}
		
		l.getParent().setMinimumDimensions();
		
		
		return;
		
	}
	
	
}
