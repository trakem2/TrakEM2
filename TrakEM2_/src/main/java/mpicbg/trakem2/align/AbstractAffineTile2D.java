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
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;

abstract public class AbstractAffineTile2D< A extends Model< A > & Affine2D< A > > extends mpicbg.models.Tile< A >
{
    private static final long serialVersionUID = -2229469138975635535L;

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
		for ( final PointMatch m : virtualMatches )
			matches.remove( m );
		virtualMatches.clear();
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
		patch.updateMipMaps();
	}

	final public ByteProcessor createMaskedByteImage()
	{
		final ByteProcessor mask;
		final Patch.PatchImage pai = patch.createTransformedImage();
		if ( pai.mask == null )
			mask = pai.outside;
		else
			mask = pai.mask;

		pai.target.setMinAndMax( patch.getMin(), patch.getMax() );

		final ByteProcessor target = ( ByteProcessor )pai.target.convertToByte( true );

		/* Other than any other ImageProcessor, ByteProcessors ignore scaling, so ... */
		if ( ByteProcessor.class.isInstance( pai.target ) )
		{
			final float s = 255.0f / ( float )( patch.getMax() - patch.getMin() );
			final int m = ( int )patch.getMin();
			final byte[] targetBytes = ( byte[] )target.getPixels();
			for ( int i = 0; i < targetBytes.length; ++i )
			{
				targetBytes[ i ] = ( byte )( Math.max( 0, Math.min( 255, ( ( targetBytes[ i ] & 0xff ) - m ) * s ) ) );
			}
			target.setMinAndMax( 0, 255 );
		}


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

		final double[] fa = new double[ 2 ];
		int i = 0;

		final double[] coords = new double[ 6 ];

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

			final double[] fb = fa. clone();
			try
			{
				model.applyInverseInPlace( fa );
				t.model.applyInverseInPlace( fb );
			}
			catch ( final NoninvertibleModelException e ) { return; }

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
	final static public <AAT extends AbstractAffineTile2D< ? >> void pairOverlappingTiles(
			final List< AAT > tiles,
			final List< AbstractAffineTile2D< ? >[] > tilePairs )
	{
		final HashSet< Patch > visited = new HashSet< Patch >();

		final ArrayList< AbstractAffineTile2D< ? >[] > tilePairCandidates = new ArrayList< AbstractAffineTile2D< ? >[] >();

		/* LUT for tiles */
		final Hashtable< Patch, AAT > lut = new Hashtable< Patch, AAT >();
		for ( final AAT tile : tiles )
			lut.put( tile.patch, tile );

		for ( int a = 0; a < tiles.size(); ++a )
		{
			final AbstractAffineTile2D< ? > ta = tiles.get( a );
			final Patch pa = ta.patch;
			visited.add( pa );
			final Layer la = pa.getLayer();
			for ( final Displayable d : la.getDisplayables( Patch.class, pa.getBoundingBox() ) )
			{
				final Patch pb = ( Patch )d;
				if ( visited.contains( pb ) )
					continue;

				final AAT tb = lut.get( pb );
				if ( tb == null )
					continue;

				tilePairCandidates.add( new AbstractAffineTile2D< ? >[]{ ta, tb } );
			}
		}

		// TODO Fix this and use what the user wants to provide
		final ExecutorService exec = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
		final ArrayList< Future< ? > > futures = new ArrayList< Future< ? > >();

		for ( final AbstractAffineTile2D< ? >[] tatb : tilePairCandidates )
		{
			futures.add( exec.submit(
					new Runnable()
					{
						@Override
						public void run()
						{
							if ( tatb[ 0 ].intersects( tatb[ 1 ] ) )
								synchronized ( tilePairs )
								{
									tilePairs.add( tatb );
								}
						}
					} ) );
		}

		try
		{
			for ( final Future< ? > f : futures )
				f.get();
		}
		catch ( final InterruptedException e )
		{
			e.printStackTrace();
		}
		catch ( final ExecutionException e )
		{
			e.printStackTrace();
		}
		finally
		{
			exec.shutdown();
		}


//		// 1. Precompute the Area of each tile's Patch
//		final HashMap< Patch, Pair< AAT, Area > > m = new HashMap< Patch, Pair< AAT, Area > >();
//		// A lazy collection of pairs, computed in parallel ahead of consumption
//		final Iterable< Pair< AAT, Area > > ts =
//				new ParallelMapping< AAT, Pair< AAT, Area > >(
//						tiles,
//						new TaskFactory< AAT, Pair< AAT, Area > >() {
//							@Override
//							public Pair< AAT, Area > process( final AAT tile ) {
//								return new Pair< AAT, Area >( tile, tile.patch.getArea() );
//							}
//						}
//				);
//		for ( final Pair< AAT, Area > t : ts) {
//			m.put( t.a.patch, t );
//		}
//
//		// 2. Obtain the list of tile pairs, at one list per tile
//		final Iterable< List<AbstractAffineTile2D< ? >[]> > pairsList =
//				new ParallelMapping< AAT, List<AbstractAffineTile2D< ? >[]> >(
//						tiles,
//						new TaskFactory< AAT, List<AbstractAffineTile2D< ? >[]> >() {
//							@Override
//							public List<AbstractAffineTile2D< ? >[]> process( final AAT ta ) {
//								final Area a;
//								synchronized (m) {
//									a = m.get( ta.patch ).b;
//								}
//								final ArrayList<AbstractAffineTile2D< ? >[]> seq = new ArrayList<AbstractAffineTile2D< ? >[]>();
//								for ( final Patch p : ta.patch.getLayer().getIntersecting( ta.patch, Patch.class ) ) {
//									if ( p == ta.patch )
//										continue;
//									final Pair< AAT, Area > pair;
//									synchronized (m) {
//										pair =  m.get(p);
//									}
//									// Check that the Patch is among those to consider in the alignment
//									if ( null != pair ) {
//										// Check that the Patch visible pixels overlap -- may not if it has an alpha mask or coordinate transform
//										if ( M.intersects( a, pair.b ) ) {
//											seq.add( new AbstractAffineTile2D< ? >[]{ ta, pair.a });
//										}
//									}
//								}
//								return seq;
//							}
//						}
//				);
//
//		for (final List<AbstractAffineTile2D<?>[]> list: pairsList) {
//			tilePairs.addAll(list);
//		}
	}

	/**
	 * Pair all {@link AbstractAffineTile2D Tiles} from two {@link List Lists}.
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
	 * Search two {@link List Lists} of {@link AbstractAffineTile2D Tiles} for
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
			for ( int b = 0; b < tilesB.size(); ++b )
			{
				final AbstractAffineTile2D< ? > ta = tilesA.get( a );
				final AbstractAffineTile2D< ? > tb = tilesB.get( b );
				if ( ta.intersects( tb ) )
					tilePairs.add( new AbstractAffineTile2D< ? >[]{ ta, tb } );
			}
		}
	}

	/**
	 * Extract the common {@linkplain PointMatch PointMatches} of two tiles.
	 *
	 * @param other
	 * @param commonMatches
	 */
	final public void commonPointMatches( final Tile< ? > other, final Collection< PointMatch > commonMatches )
	{
		for ( final PointMatch pm : matches )
			for ( final PointMatch otherPm : other.getMatches() )
				if ( pm.getP1() == otherPm.getP2() )
				{
					commonMatches.add( pm );
					break;
				}
	}
}
