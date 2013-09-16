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
import ij.process.ImageProcessor;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Filter;
import ini.trakem2.utils.Utils;

import java.awt.Rectangle;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;

/**
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
final public class AlignmentUtils
{
	final static protected class ParamPointMatch implements Serializable
	{
		private static final long serialVersionUID = 7526084042028501775L;

		final public FloatArray2DSIFT.Param sift = new FloatArray2DSIFT.Param();
		
		/**
		 * Closest/next closest neighbor distance ratio
		 */
		public float rod = 0.92f;
		
		@Override
		public boolean equals( final Object o )
		{
			if ( getClass().isInstance( o ) )
			{
				final ParamPointMatch oppm = ( ParamPointMatch )o;
				return 
					oppm.sift.equals( sift ) &
					oppm.rod == rod;
			}
			else
				return false;
		}
		
		public boolean clearCache = true;
		
		public int maxNumThreadsSift = Runtime.getRuntime().availableProcessors();
	}
	
	private AlignmentUtils() {}

	final static public String layerName( final Layer layer )
	{
		return new StringBuffer( "layer z=" )
			.append( String.format( "%.3f", layer.getZ() ) )
			.append( " `" )
			.append( layer.getTitle() )
			.append( "'" )
			.toString();
		
	}
	
	final static public List< Patch > filterPatches( final Layer layer, final Filter< Patch > filter )
	{
		final List< Patch > patches = layer.getAll( Patch.class );
		if ( filter != null )
		{
			for ( final Iterator< Patch > it = patches.iterator(); it.hasNext(); )
			{
				if ( !filter.accept( it.next() ) )
					it.remove();
			}
		}
		return patches;
	}
	
	/**
	 * Extract SIFT features and save them into the project folder.
	 * 
	 * @param layerSet the layerSet that contains all layers
	 * @param layerRange the list of layers to be aligned
	 * @param box a rectangular region of interest that will be used for alignment
	 * @param scale scale factor <= 1.0
	 * @param filter a name based filter for Patches (can be null)
	 * @param p SIFT extraction parameters
	 * @throws Exception
	 */
	final static protected void extractAndSaveLayerFeatures(
			final List< Layer > layerRange,
			final Rectangle box,
			final double scale,
			final Filter< Patch > filter,
			final FloatArray2DSIFT.Param siftParam,
			final boolean clearCache,
			final int numThreads ) throws ExecutionException, InterruptedException
	{
		final ExecutorService exec = Executors.newFixedThreadPool( numThreads );
		
		/* extract features for all slices and store them to disk */
		final AtomicInteger counter = new AtomicInteger( 0 );
		final ArrayList< Future< ArrayList< Feature > > > siftTasks = new ArrayList< Future< ArrayList< Feature > > >();
		
		for ( int i = 0; i < layerRange.size(); ++i )
		{
			final int layerIndex = i;
			final Rectangle finalBox = box;
			siftTasks.add(
					exec.submit( new Callable< ArrayList< Feature > >()
					{
						@Override
						public ArrayList< Feature > call()
						{
							final Layer layer = layerRange.get( layerIndex );
							
							final String layerName = layerName( layer );
							
							IJ.showProgress( counter.getAndIncrement(), layerRange.size() - 1 );
							
							final List< Patch > patches = filterPatches( layer, filter );
							
							ArrayList< Feature > fs = null;
							if ( !clearCache )
								fs = mpicbg.trakem2.align.Util.deserializeFeatures( layer.getProject(), siftParam, "layer", layer.getId() );
							
							if ( null == fs )
							{
								/* free memory */
								layer.getProject().getLoader().releaseAll();
								
								final FloatArray2DSIFT sift = new FloatArray2DSIFT( siftParam );
								final SIFT ijSIFT = new SIFT( sift );
								fs = new ArrayList< Feature >();
								final ImageProcessor ip = layer.getProject().getLoader().getFlatImage( layer, finalBox, scale, 0xffffffff, ImagePlus.GRAY8, Patch.class, patches, true ).getProcessor();
								ijSIFT.extractFeatures( ip, fs );
								Utils.log( fs.size() + " features extracted for " + layerName );
								
								if ( !mpicbg.trakem2.align.Util.serializeFeatures( layer.getProject(), siftParam, "layer", layer.getId(), fs ) )
									Utils.log( "FAILED to store serialized features for " + layerName );
							}
							else
								Utils.log( fs.size() + " features loaded for " + layerName );
							
							return fs;
						}
					} ) );
		}
		
		/* join */
		try
		{
			for ( final Future< ArrayList< Feature > > fu : siftTasks )
				fu.get();
		}
		catch ( final InterruptedException e )
		{
			Utils.log( "Feature extraction interrupted." );
			siftTasks.clear();
			exec.shutdownNow();
			throw e;
		}
		catch ( final ExecutionException e )
		{
			Utils.log( "Execution exception during feature extraction." );
			siftTasks.clear();
			exec.shutdownNow();
			throw e;
		}
		
		siftTasks.clear();
		exec.shutdown();
	}
}
