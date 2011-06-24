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


import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Filter;

import java.io.Serializable;
import java.util.Iterator;
import java.util.List;

import mpicbg.imagefeatures.FloatArray2DSIFT;

/**
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.1a
 */
public abstract class AbstractElasticAlignment
{
	final static protected class ParamPointMatch implements Serializable
	{
		final public FloatArray2DSIFT.Param sift = new FloatArray2DSIFT.Param();
		
		/**
		 * Closest/next closest neighbor distance ratio
		 */
		public float rod = 0.92f;
		
		@Override
		public boolean equals( Object o )
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
		
		public boolean clearCache = false;
	}
	
	final static protected class Pair< A, B >
	{
		final public A a;
		final public B b;
		
		Pair( final A a, final B b )
		{
			this.a = a;
			this.b = b;
		}
	}
	
	final static protected class Triple< A, B, C >
	{
		final public A a;
		final public B b;
		final public C c;
		
		Triple( final A a, final B b, final C c )
		{
			this.a = a;
			this.b = b;
			this.c = c;
		}
	}
	
	final static protected List< Patch > filterPatches( final Layer layer, final Filter< Patch > filter )
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
}
