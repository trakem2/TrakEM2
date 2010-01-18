/**
 * 
 */
package mpicbg.trakem2.align;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform;

import ij.IJ;
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
	static protected boolean deform = false;
	
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
		for ( Displayable d : selection.getSelected() )
			if ( d instanceof Patch ) patches.add( ( Patch )d );

		List< Patch > fixedPatches = new ArrayList< Patch >();

		// Add active Patch, if any, as the nail
		Displayable active = selection.getActive();
		if ( null != active && active instanceof Patch )
			fixedPatches.add( (Patch)active );

		// Add all locked Patch instances to fixedPatches
		for (final Patch patch : patches)
			if ( patch.isLocked() )
				fixedPatches.add( patch );

		alignPatches( patches, fixedPatches );
	}

	final static public Bureaucrat alignPatchesTask ( final List< Patch > patches , final List< Patch > fixedPatches )
	{
		if ( 0 == patches.size())
		{
			Utils.log("Can't align zero patches.");
			return null;
		}
		Worker worker = new Worker("Aligning images", false, true) {
			public void run() {
				startedWorking();
				try {
					alignPatches( patches, fixedPatches );
					Display.repaint();
				} catch (Throwable e) {
					IJError.print(e);
				} finally {
					finishedWorking();
				}
			}
			public void cleanup() {
				patches.get(0).getLayer().getParent().undoOneStep();
			}
		};
		return Bureaucrat.createAndStart( worker, patches.get(0).getProject() );
	}

	/**
	 * @param patches: the list of Patch instances to align, all belonging to the same Layer.
	 * @param fixed: the list of Patch instances to keep locked in place, if any.
	 */
	final static public void alignPatches( final List< Patch > patches , final List< Patch > fixedPatches )
	{
		if ( patches.size() < 2 )
		{
			Utils.log("No images to align.");
			return;
		}

		for ( final Patch patch : fixedPatches )
		{
			if ( !patches.contains( patch ) )
			{
				Utils.log("The list of fixed patches contains at least one Patch not included in the list of patches to align!");
				return;
			}
			if ( patch.isLinked() && !patch.isOnlyLinkedTo( Patch.class ) )
			{
				Utils.log("At least one Patch is linked to non-image data, can't align!");
				return;
			}
		}

		//final Align.ParamOptimize p = Align.paramOptimize;
		final GenericDialog gd = new GenericDialog( "Align Tiles" );
		Align.paramOptimize.addFields( gd );
		
		gd.addMessage( "Miscellaneous:" );
		gd.addCheckbox( "tiles are rougly in place", tilesAreInPlace );
		gd.addCheckbox( "consider largest graph only", largestGraphOnly );
		gd.addCheckbox( "hide tiles from non-largest graph", hideDisconnectedTiles );
		gd.addCheckbox( "delete tiles from non-largest graph", deleteDisconnectedTiles );
		
		gd.showDialog();
		if ( gd.wasCanceled() ) return;
		
		Align.paramOptimize.readFields( gd );
		tilesAreInPlace = gd.getNextBoolean();
		largestGraphOnly = gd.getNextBoolean();
		hideDisconnectedTiles = gd.getNextBoolean();
		deleteDisconnectedTiles = gd.getNextBoolean();
		
		final Align.ParamOptimize p = Align.paramOptimize.clone();

		alignPatches( p, patches, fixedPatches );
	}

	/** Montage each layer independently, with SIFT.
	 *  Does NOT register layers to each other.
	 *  Considers visible Patches only. */
	final static public Bureaucrat montageLayersTask(final List<Layer> layers) {
		if (null == layers || layers.isEmpty()) return null;
		return Bureaucrat.createAndStart(new Worker.Task("Montaging layers") {
			public void exec() {
				//final Align.ParamOptimize p = Align.paramOptimize;
				final GenericDialog gd = new GenericDialog( "Montage Layers" );
				Align.paramOptimize.addFields( gd );
				
				gd.addMessage( "Miscellaneous:" );
				gd.addCheckbox( "tiles are rougly in place", tilesAreInPlace );
				gd.addCheckbox( "consider largest graph only", largestGraphOnly );
				gd.addCheckbox( "hide tiles from non-largest graph", hideDisconnectedTiles );
				gd.addCheckbox( "delete tiles from non-largest graph", deleteDisconnectedTiles );
				
				gd.showDialog();
				if ( gd.wasCanceled() ) return;
				
				Align.paramOptimize.readFields( gd );
				tilesAreInPlace = gd.getNextBoolean();
				largestGraphOnly = gd.getNextBoolean();
				hideDisconnectedTiles = gd.getNextBoolean();
				deleteDisconnectedTiles = gd.getNextBoolean();
				
				final Align.ParamOptimize p = Align.paramOptimize.clone();
				montageLayers(p, layers);
			}
		}, layers.get(0).getProject());
	}

	final static public void montageLayers(final Align.ParamOptimize p, final List<Layer> layers) {
		int i = 0;
		for (final Layer layer : layers) {
			if (Thread.currentThread().isInterrupted()) return;
			Collection<Displayable> patches = layer.getDisplayables(Patch.class, true);
			if (patches.isEmpty()) continue;
			for (final Displayable patch : patches) {
				if (patch.isLinked() && !patch.isOnlyLinkedTo(Patch.class)) {
					Utils.log("Cannot montage layer " + layer + "\nReason: at least one Patch is linked to non-image data: " + patch);
					continue;
				}
			}
			Utils.log("====\nMontaging layer " + layer);
			Utils.showProgress(((double)i)/layers.size());
			i++;
			alignPatches(p, new ArrayList<Patch>((Collection<Patch>)(Collection)patches), new ArrayList<Patch>());
			Display.repaint(layer);
		}
	}

	final static public void alignPatches(
			final Align.ParamOptimize p,
			final List< Patch > patches,
			final List< Patch > fixedPatches )
	{
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
		
		for ( AbstractAffineTile2D< ? > t : interestingTiles )
			t.getPatch().setAffineTransform( t.getModel().createAffine() );
		
		Utils.log( "Montage done." );
	}
	
	
	final static public Bureaucrat alignMultiLayerMosaicTask( final Layer l )
	{
		Worker worker = new Worker( "Aligning multi-layer mosaic", false, true )
		{
			public void run()
			{
				startedWorking();
				try { alignMultiLayerMosaic( l ); }
				catch ( Throwable e ) { IJError.print( e ); }
				finally { finishedWorking(); }
			}
		};
		return Bureaucrat.createAndStart(worker, l.getProject());
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
			layerTitles[ i ] = l.getProject().findLayerThing(layers.get( i )).toString();
		
		final GenericDialog gd1 = new GenericDialog( "Align Multi-Layer Mosaic : Layer Range" );
		
		gd1.addMessage( "Layer Range:" );
		final int sel = layers.indexOf(l);
		gd1.addChoice( "first :", layerTitles, layerTitles[ sel ] );
		gd1.addChoice( "last :", layerTitles, layerTitles[ sel ] );
		
		gd1.addMessage( "Miscellaneous:" );
		gd1.addCheckbox( "tiles are rougly in place", tilesAreInPlace );
		gd1.addCheckbox( "consider largest graph only", largestGraphOnly );
		gd1.addCheckbox( "hide tiles from non-largest graph", hideDisconnectedTiles );
		gd1.addCheckbox( "delete tiles from non-largest graph", deleteDisconnectedTiles );
		gd1.addCheckbox( "deform layers", deform );
		
		gd1.showDialog();
		if ( gd1.wasCanceled() ) return;
		
		final int first = gd1.getNextChoiceIndex();
		final int last = gd1.getNextChoiceIndex();
		final int d = first < last ? 1 : -1;
		
		tilesAreInPlace = gd1.getNextBoolean();
		largestGraphOnly = gd1.getNextBoolean();
		hideDisconnectedTiles = gd1.getNextBoolean();
		deleteDisconnectedTiles = gd1.getNextBoolean();
		deform = gd1.getNextBoolean();
		
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

		final List< Layer > layerRange = new ArrayList< Layer >();
		for ( int i = first; i != last + d; i += d )
			layerRange.add( layers.get( i ) );

		alignMultiLayerMosaicTask( layerRange, cp, p, pcp, tilesAreInPlace, largestGraphOnly, hideDisconnectedTiles, deleteDisconnectedTiles, deform );
	}


	public static final void alignMultiLayerMosaicTask( final List< Layer > layerRange, final Align.Param cp, final Align.ParamOptimize p, final Align.ParamOptimize pcp, final boolean tilesAreInPlace, final boolean largestGraphOnly, final boolean hideDisconnectedTiles, final boolean deleteDisconnectedTiles, final boolean deform ) {

		/* register */
		
		final List< AbstractAffineTile2D< ? > > allTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final List< AbstractAffineTile2D< ? > > allFixedTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final List< AbstractAffineTile2D< ? > > previousLayerTiles = new ArrayList< AbstractAffineTile2D< ? > >();
		final HashMap< Patch, PointMatch > tileCenterPoints = new HashMap< Patch, PointMatch >();
		
		List< Patch > fixedPatches = new ArrayList< Patch >();
		final Displayable active = Display.getFront().getActive();
		if ( active != null && active instanceof Patch )
			fixedPatches.add( ( Patch )active );
		
		for ( final Layer layer : layerRange )
		{
			/* align all tiles in the layer */
			
			final List< Patch > patches = new ArrayList< Patch >();
			for ( Displayable a : layer.getDisplayables( Patch.class ) )
				if ( a instanceof Patch ) patches.add( ( Patch )a );
			final List< AbstractAffineTile2D< ? > > currentLayerTiles = new ArrayList< AbstractAffineTile2D< ? > >();
			final List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
			Align.tilesFromPatches( p, patches, fixedPatches, currentLayerTiles, fixedTiles );
			
			alignTiles( p, currentLayerTiles, fixedTiles );
			
			/* connect to the previous layer */
			
			
			/* generate tiles with the cross-section model from the current layer tiles */
			/* TODO step back and make tiles bare containers for a patch and a model such that by changing the model the tile can be reused */
			final HashMap< Patch, AbstractAffineTile2D< ? > > currentLayerPatchTiles = new HashMap< Patch, AbstractAffineTile2D<?> >();
			for ( final AbstractAffineTile2D< ? > t : currentLayerTiles )
				currentLayerPatchTiles.put( t.getPatch(), t );
			
			final List< AbstractAffineTile2D< ? > > csCurrentLayerTiles = new ArrayList< AbstractAffineTile2D< ? > >();
			final List< AbstractAffineTile2D< ? > > csFixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
			Align.tilesFromPatches( cp, patches, fixedPatches, csCurrentLayerTiles, csFixedTiles );
			
			final HashMap< Tile< ? >, AbstractAffineTile2D< ? > > tileTiles = new HashMap< Tile< ? >, AbstractAffineTile2D<?> >();
			for ( final AbstractAffineTile2D< ? > t : csCurrentLayerTiles )
				tileTiles.put( currentLayerPatchTiles.get( t.getPatch() ), t );
			
			for ( final AbstractAffineTile2D< ? > t : currentLayerTiles )
			{
				final AbstractAffineTile2D< ? > csLayerTile = tileTiles.get( t );
				csLayerTile.addMatches( t.getMatches() );
				for ( Tile< ? > ct : t.getConnectedTiles() )
					csLayerTile.addConnectedTile( tileTiles.get( ct ) );
			}
			
			/* add a fixed tile only if there was a Patch selected */
			allFixedTiles.addAll( csFixedTiles );
			
			/* first, align connected graphs againt each other */
			//List< Set< Tile< ? > > > graphs = AbstractAffineTile2D.identifyConnectedGraphs( currentLayerTiles );
			/* TODO Do it really... */
			
			
			final List< AbstractAffineTile2D< ? >[] > crossLayerTilePairs = new ArrayList< AbstractAffineTile2D< ? >[] >();
			AbstractAffineTile2D.pairTiles( previousLayerTiles, csCurrentLayerTiles, crossLayerTilePairs );
			
			Align.connectTilePairs( cp, csCurrentLayerTiles, crossLayerTilePairs, Runtime.getRuntime().availableProcessors() );
			
			/* prepare the next loop */
			
			allTiles.addAll( csCurrentLayerTiles );
			previousLayerTiles.clear();
			previousLayerTiles.addAll( csCurrentLayerTiles );
			
			/* optimize */
			Align.optimizeTileConfiguration( pcp, allTiles, allFixedTiles );
			
			for ( AbstractAffineTile2D< ? > t : allTiles )
				t.getPatch().setAffineTransform( t.getModel().createAffine() );
		}
		
		List< Set< Tile< ? > > > graphs = AbstractAffineTile2D.identifyConnectedGraphs( allTiles );
		
		final List< AbstractAffineTile2D< ? > > interestingTiles;
		if ( largestGraphOnly )
		{
			if ( Thread.currentThread().isInterrupted() ) return;
			
			/* find largest graph. */
			
			Set< Tile< ? > > largestGraph = null;
			for ( Set< Tile< ? > > graph : graphs )
				if ( largestGraph == null || largestGraph.size() < graph.size() )
					largestGraph = graph;
			
			interestingTiles = new ArrayList< AbstractAffineTile2D< ? > >();
			for ( Tile< ? > t : largestGraph )
				interestingTiles.add( ( AbstractAffineTile2D< ? > )t );
			
			if ( hideDisconnectedTiles )
				for ( AbstractAffineTile2D< ? > t : allTiles )
					if ( !interestingTiles.contains( t ) )
						t.getPatch().setVisible( false );
			if ( deleteDisconnectedTiles )
				for ( AbstractAffineTile2D< ? > t : allTiles )
					if ( !interestingTiles.contains( t ) )
						t.getPatch().remove( false );
			
			if ( deform )
			{
				/* ############################################ */
				/* experimental: use the center points of all tiles to define a MLS deformation from the pure intra-layer registration to the globally optimal */
				
				Utils.log( "deforming..." );
				
				/* store the center location of each single tile for later deformation */
				for ( final AbstractAffineTile2D< ? > t : interestingTiles )
				{
					final float[] c = new float[]{ ( float )t.getWidth() / 2.0f,( float )t.getHeight() / 2.0f };
					t.getModel().applyInPlace( c );
					final Point q = new Point( c );
					tileCenterPoints.put( t.getPatch(), new PointMatch( q.clone(), q ) );
				}
				
				for ( final Layer layer : layerRange )
				{
					Utils.log( "layer" + layer );
					
					if ( Thread.currentThread().isInterrupted() ) return;
	
					/* again, align all tiles in the layer */
					
					List< Patch > patches = new ArrayList< Patch >();
					for ( Displayable a : layer.getDisplayables( Patch.class ) )
						if ( a instanceof Patch ) patches.add( ( Patch )a );
					final List< AbstractAffineTile2D< ? > > currentLayerTiles = new ArrayList< AbstractAffineTile2D< ? > >();
					final List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
					Align.tilesFromPatches( p, patches, fixedPatches, currentLayerTiles, fixedTiles );
								
					/* add a fixed tile only if there was a Patch selected */
					allFixedTiles.addAll( fixedTiles );
					
					alignTiles( p, currentLayerTiles, fixedTiles );
					
					/* update the tile-center pointmatches */
					final Collection< PointMatch > matches = new ArrayList< PointMatch >();
					final Collection< AbstractAffineTile2D< ? > > toBeDeformedTiles = new ArrayList< AbstractAffineTile2D< ? > >();
					for ( final AbstractAffineTile2D< ? > t : currentLayerTiles )
					{
						final PointMatch pm = tileCenterPoints.get( t.getPatch() );
						if ( pm == null ) continue;
						
						final float[] pl = pm.getP1().getL();
						pl[ 0 ] = ( float )t.getWidth() / 2.0f;
						pl[ 1 ] = ( float )t.getHeight() / 2.0f;
						t.getModel().applyInPlace( pl );
						matches.add( pm );
						toBeDeformedTiles.add( t );
					}
					
					for ( final AbstractAffineTile2D< ? > t : toBeDeformedTiles )
					{
						if ( Thread.currentThread().isInterrupted() ) return;
						
						try
						{
							final Patch patch = t.getPatch();
							final Rectangle pbox = patch.getCoordinateTransformBoundingBox();
							final AffineTransform pat = new AffineTransform();
							pat.translate( -pbox.x, -pbox.y );
							pat.preConcatenate( patch.getAffineTransform() );
							
							final mpicbg.trakem2.transform.AffineModel2D toWorld = new mpicbg.trakem2.transform.AffineModel2D();
							toWorld.set( pat );
							
							final MovingLeastSquaresTransform mlst = Align.createMLST( matches, 1.0f );
							
							final CoordinateTransformList< CoordinateTransform > ctl = new CoordinateTransformList< CoordinateTransform >();
							ctl.add( toWorld );
							ctl.add( mlst );
							ctl.add( toWorld.createInverse() );
							
							patch.appendCoordinateTransform( ctl );
							
							patch.getProject().getLoader().regenerateMipMaps( patch );
						}
						catch ( Exception e )
						{
							e.printStackTrace();
						}
					}
				}
			}
		}
		
		layerRange.get(0).getParent().setMinimumDimensions();
		IJ.log( "Done: register multi-layer mosaic." );
		
		return;
	}


	/** The ParamOptimize object containg all feature extraction and registration model parameters for the "snap" function. */
	static public final Align.ParamOptimize p_snap = Align.paramOptimize.clone();

	/** Find the most overlapping image to @param patch in the same layer where @param patch sits, and snap @param patch and all its linked Displayable objects.
	 *  If a null @param p_snap is given, it will use the AlignTask.p_snap.
	 *  If @param setup is true, it will show a dialog to adjust parameters. */
	static public final Bureaucrat snap(final Patch patch, final Align.ParamOptimize p_snap, final boolean setup) {
		return Bureaucrat.createAndStart(new Worker.Task("Snapping") {
			public void exec() {

		final Align.ParamOptimize p = null == p_snap ? AlignTask.p_snap : p_snap;
		if (setup) p.setup("Snap");

		// Collect Patch linked to active
		final List<Displayable> linked_images = new ArrayList<Displayable>();
		for (final Displayable d : patch.getLinkedGroup(null)) {
			if (d.getClass() == Patch.class && d != patch) linked_images.add(d);
		}
		// Find overlapping images
		final List<Patch> overlapping = new ArrayList<Patch>( (Collection<Patch>) (Collection) patch.getLayer().getIntersecting(patch, Patch.class));
		overlapping.remove(patch);
		if (0 == overlapping.size()) return; // nothing overlaps

		// Discard from overlapping any linked images
		overlapping.removeAll(linked_images);

		if (0 == overlapping.size()) {
			Utils.log("Cannot snap: overlapping images are linked to the one to snap.");
			return;
		}

		// flush
		linked_images.clear();

		// Find the image that overlaps the most
		Rectangle box = patch.getBoundingBox(null);
		Patch most = null;
		Rectangle most_inter = null;
		for (final Patch other : overlapping) {
			if (null == most) {
				most = other;
				most_inter = other.getBoundingBox();
				continue;
			}
			Rectangle inter = other.getBoundingBox().intersection(box);
			if (inter.width * inter.height > most_inter.width * most_inter.height) {
				most = other;
				most_inter = inter;
			}
		}
		// flush
		overlapping.clear();

		// Define two lists:
		//  - a list with all involved tiles: the active and the most overlapping one
		final List<Patch> patches = new ArrayList<Patch>();
		patches.add(most);
		patches.add(patch);
		//  - a list with all tiles except the active, to be set as fixed, immobile
		final List<Patch> fixedPatches = new ArrayList<Patch>();
		fixedPatches.add(most);

		// Patch as Tile
		List< AbstractAffineTile2D< ? > > tiles = new ArrayList< AbstractAffineTile2D< ? > >();
		List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
		Align.tilesFromPatches( p, patches, fixedPatches, tiles, fixedTiles );

		// Pair and connect overlapping tiles
		final List< AbstractAffineTile2D< ? >[] > tilePairs = new ArrayList< AbstractAffineTile2D< ? >[] >();
		AbstractAffineTile2D.pairOverlappingTiles( tiles, tilePairs );
		Align.connectTilePairs( p, tiles, tilePairs, Runtime.getRuntime().availableProcessors() );

		if ( Thread.currentThread().isInterrupted() ) return;

		Align.optimizeTileConfiguration( p, tiles, fixedTiles );

		for ( AbstractAffineTile2D< ? > t : tiles ) {
			if (t.getPatch() == patch) {
				AffineTransform at = t.getModel().createAffine();
				try {
					at.concatenate(patch.getAffineTransform().createInverse());
					patch.transform(at);
				} catch (java.awt.geom.NoninvertibleTransformException nite) {
					IJError.print(nite);
				}
				break;
			}
		}

		Display.repaint();

		}}, patch.getProject());
	}

	static public final Bureaucrat registerStackSlices(final Patch slice) {
		return Bureaucrat.createAndStart(new Worker.Task("Registering slices") {
			public void exec() {

		// build the list
		ArrayList<Patch> slices = slice.getStackPatches();
		if (slices.size() < 2) {
			Utils.log2("Not a stack!");
			return;
		}

		// check that none are linked to anything other than images
		for (final Patch patch : slices) {
			if (!patch.isOnlyLinkedTo(Patch.class)) {
				Utils.log("Can't register: one or more slices are linked to objects other than images.");
				return;
			}
		}

		// ok proceed
		final Align.ParamOptimize p = Align.paramOptimize.clone();
		p.setup("Register stack slices");

		List<Patch> fixedSlices = new ArrayList<Patch>();
		fixedSlices.add(slice);

		alignPatches( p, slices, fixedSlices );

		Display.repaint();

		}}, slice.getProject());
	}
}
