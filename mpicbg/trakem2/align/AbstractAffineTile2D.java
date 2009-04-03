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
 * 
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 *
 */
package mpicbg.trakem2.align;

import ij.process.ByteProcessor;
import ini.trakem2.display.Patch;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.TileConfiguration;

/**
 * @version 0.1b
 */
abstract public class AbstractAffineTile2D< A extends AbstractAffineModel2D< A > > extends mpicbg.models.Tile< A >
{
	final protected Patch patch;
	final public Patch getPatch(){ return patch; }
	final public double getWidth(){ return patch.getWidth(); }
	final public double getHeight(){ return patch.getHeight(); }
	
	/**
	 * A set of virtual point correspondences that are used to connect a tile
	 * to the rest of the {@link TileConfiguration} assuming that the initial
	 * layout was correct.
	 * 
	 * Virtual point correspondences are also stored in matches.  This is just
	 * to keep track about them.
	 * 
	 * Virtual point correspondences have to be removed
	 * for real connections.
	 * 
	 * TODO Not yet tested---Do we need these virtual connections?
	 * 
	 */
	final protected Set< PointMatch > virtualMatches = new HashSet< PointMatch >();
	final public Set< PointMatch > getVirtualMatches(){ return virtualMatches; }
	
	final public boolean addVirtualMatch( final PointMatch match )
	{
		if ( virtualMatches.add( match ) )
			return matches.add( match );
		return false;
	}
	
	final public boolean removeVirtualMatch( final PointMatch match )
	{
		if ( virtualMatches.remove( match ) )
			return matches.remove( match );
		return false;
	}
	
	/**
	 * Remove all virtual {@link PointMatch matches}.
	 * 
	 * TODO Not yet tested---Do we need these virtual connections?
	 */
	final public void clearVirtualMatches()
	{
		for ( PointMatch m : virtualMatches )
			matches.remove( m );
		virtualMatches.clear();
	}
	
	/**
	 * Try to find the tile which is connected by a particular
	 * {@link PointMatch}.
	 * 
	 * Note that this method searches only the known connected tiles to limit
	 * the cost of that anyway expensive search.
	 * 
	 * @param match
	 * 
	 * @return connectedTile or null
	 */
	final public AbstractAffineTile2D< ? > findConnectedTile( PointMatch match )
	{
		final Point p = match.getP2();
		for ( final mpicbg.models.Tile< ? > t : connectedTiles )
		{
			for ( final PointMatch m : t.getMatches() )
			{
				if ( p == m.getP1() ) return ( AbstractAffineTile2D< ? > )t;
			}
		}
		return null;
	}
	
	abstract protected void initModel();
	
	public AbstractAffineTile2D( final A model, final Patch patch )
	{
		super( model );
		this.patch = patch;
		initModel();
	}
	
	final public AffineTransform createAffine()
	{
		return model.createAffine();
	}
	
	final public void updatePatch()
	{
		patch.setAffineTransform( createAffine() );
		patch.updateMipmaps();
	}
	
	final public ByteProcessor createMaskedByteImage()
	{
		final ByteProcessor mask;
		final Patch.PatchImage pai = patch.createTransformedImage();
		if ( pai.mask == null )
			mask = pai.outside;
		else
			mask = pai.mask;
		
		final ByteProcessor target = ( ByteProcessor )pai.target.convertToByte( true );
		
		if ( mask != null )
		{
			final byte[] targetBytes = ( byte[] )target.getPixels();
			final byte[] maskBytes = (byte[])mask.getPixels();
			
			if ( pai.outside != null )
			{
				final byte[] outsideBytes = (byte[])pai.outside.getPixels();
				for ( int i = 0; i < outsideBytes.length; ++i )
				{
					if ( ( outsideBytes[ i ]&0xff ) != 255 ) maskBytes[ i ] = 0;
					final float a = ( float )( maskBytes[ i ] & 0xff ) / 255f;
					final int t = ( targetBytes[ i ] & 0xff );
					targetBytes[ i ] = ( byte )( t * a + 127 * ( 1 - a ) );
				}
			}
			else
			{
				for ( int i = 0; i < targetBytes.length; ++i )
				{
					final float a = ( float )( maskBytes[ i ] & 0xff ) / 255f;
					final int t = ( targetBytes[ i ] & 0xff );
					targetBytes[ i ] = ( byte )( t * a + 127 * ( 1 - a ) );
				}
			}
		}
		
		return target;
	}
	
	final public boolean intersects( final AbstractAffineTile2D< ? > t )
	{
		return patch.intersects( t.patch );
	}
	
	
	/**
	 * Add a virtual {@linkplain PointMatch connection} between two
	 * {@linkplain AbstractAffineTile2D Tiles}.  The
	 * {@linkplain PointMatch connection} is placed in the center of the
	 * intersection area of both tiles.
	 * 
	 * TODO Not yet tested---Do we need these virtual connections?
	 * 
	 * @param t
	 */
	final public void makeVirtualConnection( final AbstractAffineTile2D< ? > t )
	{
		final Area a = new Area( patch.getPerimeter() );
		final Area b = new Area( t.patch.getPerimeter() );
		a.intersect( b );
		
		final float[] fa = new float[ 2 ];
		int i = 0;
		
		final float[] coords = new float[ 6 ];
		
		final PathIterator p = a.getPathIterator( null );
		while ( !p.isDone() )
		{
			p.currentSegment( coords );
			fa[ 0 ] += coords[ 0 ];
			fa[ 1 ] += coords[ 1 ];
			++i;
			p.next();
		}
		
		if ( i > 0 )
		{
			fa[ 0 ] /= i;
			fa[ 1 ] /= i;
			
			final float[] fb = fa. clone();
			try
			{
				model.applyInverseInPlace( fa );
				t.model.applyInverseInPlace( fb );
			}
			catch ( NoninvertibleModelException e ) { return; }
			
			final Point pa = new Point( fa );
			final Point pb = new Point( fb );
			final PointMatch ma = new PointMatch( pa, pb, 0.1f );
			final PointMatch mb = new PointMatch( pb, pa, 0.1f );
			
			addVirtualMatch( ma );
			addConnectedTile( t );
			t.addVirtualMatch( mb );
			t.addConnectedTile( this );
		}
	}
	
	
	/**
	 * Pair all {@link AbstractAffineTile2D Tiles} from a {@link List}.
	 * Adds the pairs into tilePairs.
	 * 
	 * @param tiles
	 * @param tilePairs
	 */
	final static public void pairTiles(
			final List< AbstractAffineTile2D< ? > > tiles,
			final List< AbstractAffineTile2D< ? >[] > tilePairs )
	{
		for ( int a = 0; a < tiles.size(); ++a )
		{
			for ( int b = a + 1; b < tiles.size(); ++b )
			{
				final AbstractAffineTile2D< ? > ta = tiles.get( a );
				final AbstractAffineTile2D< ? > tb = tiles.get( b );
				tilePairs.add( new AbstractAffineTile2D< ? >[]{ ta, tb } );
			}
		}		
	}
	
	
	/**
	 * Search a {@link List} of {@link AbstractAffineTile2D Tiles} for
	 * overlapping pairs.  Adds the pairs into tilePairs.
	 * 
	 * @param tiles
	 * @param tilePairs
	 */
	final static public void pairOverlappingTiles(
			final List< AbstractAffineTile2D< ? > > tiles,
			final List< AbstractAffineTile2D< ? >[] > tilePairs )
	{
		for ( int a = 0; a < tiles.size(); ++a )
		{
			for ( int b = a + 1; b < tiles.size(); ++b )
			{
				final AbstractAffineTile2D< ? > ta = tiles.get( a );
				final AbstractAffineTile2D< ? > tb = tiles.get( b );
				if ( ta.intersects( tb ) )
					tilePairs.add( new AbstractAffineTile2D< ? >[]{ ta, tb } );
			}
		}		
	}
	
	/**
	 * Pair all {@link AbstractAffineTile2D Tiles} from two {@link Lists}.
	 * Adds the pairs into tilePairs.
	 * 
	 * @param tilesA
	 * @param tilesB
	 * @param tilePairs
	 */
	final static public void pairTiles(
			final List< AbstractAffineTile2D< ? > > tilesA,
			final List< AbstractAffineTile2D< ? > > tilesB,
			final List< AbstractAffineTile2D< ? >[] > tilePairs )
	{
		for ( int a = 0; a < tilesA.size(); ++a )
		{
			for ( int b = 0; b < tilesB.size(); ++b )
			{
				final AbstractAffineTile2D< ? > ta = tilesA.get( a );
				final AbstractAffineTile2D< ? > tb = tilesB.get( b );
				tilePairs.add( new AbstractAffineTile2D< ? >[]{ ta, tb } );
			}
		}		
	}
	
	
	/**
	 * Search two {@link Lists} of {@link AbstractAffineTile2D Tiles} for
	 * overlapping pairs.  Adds the pairs into tilePairs.
	 * 
	 * @param tilesA
	 * @param tilesB
	 * @param tilePairs
	 */
	final static public void pairOverlappingTiles(
			final List< AbstractAffineTile2D< ? > > tilesA,
			final List< AbstractAffineTile2D< ? > > tilesB,
			final List< AbstractAffineTile2D< ? >[] > tilePairs )
	{
		for ( int a = 0; a < tilesA.size(); ++a )
		{
			for ( int b = a + 1; b < tilesB.size(); ++b )
			{
				final AbstractAffineTile2D< ? > ta = tilesA.get( a );
				final AbstractAffineTile2D< ? > tb = tilesB.get( b );
				if ( ta.intersects( tb ) )
					tilePairs.add( new AbstractAffineTile2D< ? >[]{ ta, tb } );
			}
		}		
	}
}
