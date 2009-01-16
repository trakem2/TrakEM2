/**
 * 
 */
package mpicbg.trakem2.align;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import mpicbg.imagefeatures.FloatArray2DSIFT.Param;
import mpicbg.models.Tile;

import ij.gui.GenericDialog;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Selection;

/**
 * Methods collection to be called from the GUI for alignment tasks.
 *
 */
final public class AlignTask
{
	final static public void alignSelection( final Selection selection )
	{
		List< Patch > patches = new ArrayList< Patch >();
		for ( Displayable d : Display.getFront().getSelection().getSelected() )
			if ( d instanceof Patch ) patches.add( ( Patch )d );
		
		final Align.ParamOptimize p = new Align.ParamOptimize();
		final GenericDialog gd = new GenericDialog( "Align selected tiles" );
		p.addFields( gd );
		
		gd.addMessage( "Fancy Usability Field Section 1:" );
		gd.addCheckbox( "Tiles are rougly in place.", false );
		gd.addCheckbox( "Consider largest graph only.", false );
		gd.addCheckbox( "Hide tiles from non-largest graph.", false );
		gd.addCheckbox( "Delete tiles from non-largest graph", false );
		
		gd.showDialog();
		if ( gd.wasCanceled() ) return;
		
		p.readFields( gd );
		final boolean tilesAreInPlace = gd.getNextBoolean();
		final boolean largestGraphOnly = gd.getNextBoolean();
		final boolean hideDisconnectedTiles = gd.getNextBoolean();
		final boolean deleteDisconnectedTiles = gd.getNextBoolean();
			
		List< AbstractAffineTile2D< ? > > tiles = new ArrayList< AbstractAffineTile2D< ? > >();
		List< AbstractAffineTile2D< ? > > fixedTiles = new ArrayList< AbstractAffineTile2D< ? > > ();
		List< Patch > fixedPatches = new ArrayList< Patch > ();
		
		Align.tilesFromPatches( p, patches, fixedPatches, tiles, fixedTiles );
		final List< AbstractAffineTile2D< ? >[] > tilePairs = new ArrayList< AbstractAffineTile2D< ? >[] >();
		if ( tilesAreInPlace )
			AbstractAffineTile2D.pairOverlappingTiles( tiles, tilePairs );
		else
			AbstractAffineTile2D.pairTiles( tiles, tilePairs );
		
		Align.connectTilePairs( p, tiles, tilePairs, Runtime.getRuntime().availableProcessors() );
		
		List< Set< Tile< ? > > > graphs = AbstractAffineTile2D.identifyConnectedGraphs( tiles );
		
		final List< AbstractAffineTile2D< ? > > interestingTiles;
		if ( largestGraphOnly )
		{
			/** Find largest graph. */
			Set< Tile< ? > > largestGraph = null;
			for ( Set< Tile< ? > > graph : graphs )
				if ( largestGraph == null || largestGraph.size() < graph.size() )
					largestGraph = graph;
			
			interestingTiles = new ArrayList< AbstractAffineTile2D< ? > >();
			for ( Tile< ? > t : largestGraph )
				interestingTiles.add( ( AbstractAffineTile2D< ? > )t );
			
			//if ( hideDisconnectedTiles )
				
		}
		else
			interestingTiles = tiles;
			
		// align the largest graph only
		Align.optimizeTileConfiguration( p, interestingTiles, fixedTiles );
	}
}
