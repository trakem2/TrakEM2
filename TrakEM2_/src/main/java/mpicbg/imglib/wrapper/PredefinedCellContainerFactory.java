/*
 * #%L
 * ImgLib: a general-purpose, multidimensional image processing library.
 * %%
 * Copyright (C) 2009 - 2013 Stephan Preibisch, Tobias Pietzsch, Barry DeZonia,
 * Stephan Saalfeld, Albert Cardona, Curtis Rueden, Christian Dietz, Jean-Yves
 * Tinevez, Johannes Schindelin, Lee Kamentsky, Larry Lindsey, Grant Harris,
 * Mark Hiner, Aivar Grislis, Martin Horn, Nick Perry, Michael Zinsmaier,
 * Steffen Jaensch, Jan Funke, Mark Longair, and Dimiter Prodanov.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are
 * those of the authors and should not be interpreted as representing official
 * policies, either expressed or implied, of any organization.
 * #L%
 */

package mpicbg.imglib.wrapper;

import mpicbg.imglib.container.DirectAccessContainer;
import mpicbg.imglib.container.basictypecontainer.array.ArrayDataAccess;
import mpicbg.imglib.container.basictypecontainer.array.BitArray;
import mpicbg.imglib.container.basictypecontainer.array.ByteArray;
import mpicbg.imglib.container.basictypecontainer.array.CharArray;
import mpicbg.imglib.container.basictypecontainer.array.DoubleArray;
import mpicbg.imglib.container.basictypecontainer.array.FloatArray;
import mpicbg.imglib.container.basictypecontainer.array.IntArray;
import mpicbg.imglib.container.basictypecontainer.array.LongArray;
import mpicbg.imglib.container.basictypecontainer.array.ShortArray;
import mpicbg.imglib.container.cell.CellContainer;
import mpicbg.imglib.container.cell.CellContainerFactory;
import mpicbg.imglib.type.Type;

/**
 * TODO
 *
 * @author Stephan Preibisch
 * @author Stephan Saalfeld
 */
public class PredefinedCellContainerFactory extends CellContainerFactory
{
	ArrayDataAccess< ? > existingArrays;
	
	public PredefinedCellContainerFactory( final int cellSize, final ArrayDataAccess< ? > existingArrays )
	{
		super( cellSize );
		this.existingArrays = existingArrays;
	}
	
	public PredefinedCellContainerFactory( final int[] cellSize, final ArrayDataAccess< ? > existingArrays )
	{
		super( cellSize );
		this.existingArrays = existingArrays;
	}
	
	public void disableWrapping() { this.existingArrays = null; }
	
	@Override
	public <T extends Type<T>> DirectAccessContainer<T, BitArray> createBitInstance( int[] dimensions, int entitiesPerPixel )
	{
		if ( existingArrays == null )
			return super.createBitInstance( dimensions, entitiesPerPixel );
		else
			throw new RuntimeException( "Not supported for BitArrays" );
	}
	
	@Override
	public <T extends Type<T>> DirectAccessContainer<T, ByteArray> createByteInstance( int[] dimensions, int entitiesPerPixel )
	{
		if ( existingArrays == null )
		{
			return super.createByteInstance( dimensions, entitiesPerPixel );
		}
		else
		{
			dimensions = checkDimensions( dimensions );
			int[] cellSize = checkCellSize( this.cellSize, dimensions );
			
			return new CellContainer<T, ByteArray>( new CellContainerFactory( cellSize ), (ExistingByteArrays)existingArrays, dimensions, cellSize, entitiesPerPixel );
		}
	}

	@Override
	public <T extends Type<T>> DirectAccessContainer<T, CharArray> createCharInstance(int[] dimensions, int entitiesPerPixel)
	{
		if ( existingArrays == null )
		{
			return super.createCharInstance( dimensions, entitiesPerPixel );
		}
		else
		{
			dimensions = checkDimensions( dimensions );
			int[] cellSize = checkCellSize( this.cellSize, dimensions );
			
			return new CellContainer<T, CharArray>( new CellContainerFactory( cellSize ), (ExistingCharArrays)existingArrays, dimensions, cellSize, entitiesPerPixel );
		}
	}

	@Override
	public <T extends Type<T>> DirectAccessContainer<T, DoubleArray> createDoubleInstance(int[] dimensions, int entitiesPerPixel)
	{
		if ( existingArrays == null )
		{
			return super.createDoubleInstance( dimensions, entitiesPerPixel );
		}
		else
		{
			dimensions = checkDimensions( dimensions );
			int[] cellSize = checkCellSize( this.cellSize, dimensions );
			
			return new CellContainer<T, DoubleArray>( new CellContainerFactory( cellSize ), (ExistingDoubleArrays)existingArrays, dimensions, cellSize, entitiesPerPixel );
		}
	}

	@Override
	public <T extends Type<T>> DirectAccessContainer<T, FloatArray> createFloatInstance(int[] dimensions, int entitiesPerPixel)
	{
		if ( existingArrays == null )
		{
			return super.createFloatInstance( dimensions, entitiesPerPixel );
		}
		else
		{
			dimensions = checkDimensions( dimensions );
			int[] cellSize = checkCellSize( this.cellSize, dimensions );
			
			return new CellContainer<T, FloatArray>( new CellContainerFactory( cellSize ), (ExistingFloatArrays)existingArrays, dimensions, cellSize, entitiesPerPixel );
		}
	}

	@Override
	public <T extends Type<T>> DirectAccessContainer<T, IntArray> createIntInstance(int[] dimensions, int entitiesPerPixel)
	{
		if ( existingArrays == null )
		{
			return super.createIntInstance( dimensions, entitiesPerPixel );
		}
		else
		{
			dimensions = checkDimensions( dimensions );
			int[] cellSize = checkCellSize( this.cellSize, dimensions );
			
			return new CellContainer<T, IntArray>( new CellContainerFactory( cellSize ), (ExistingIntArrays)existingArrays, dimensions, cellSize, entitiesPerPixel );
		}
	}

	@Override
	public <T extends Type<T>> DirectAccessContainer<T, LongArray> createLongInstance(int[] dimensions, int entitiesPerPixel)
	{
		if ( existingArrays == null )
		{
			return super.createLongInstance( dimensions, entitiesPerPixel );
		}
		else
		{
			dimensions = checkDimensions( dimensions );
			int[] cellSize = checkCellSize( this.cellSize, dimensions );
			
			return new CellContainer<T, LongArray>( new CellContainerFactory( cellSize ), (ExistingLongArrays)existingArrays, dimensions, cellSize, entitiesPerPixel );
		}
	}

	@Override
	public <T extends Type<T>> DirectAccessContainer<T, ShortArray> createShortInstance(int[] dimensions, int entitiesPerPixel)
	{
		if ( existingArrays == null )
		{
			return super.createShortInstance( dimensions, entitiesPerPixel );
		}
		else
		{
			dimensions = checkDimensions( dimensions );
			int[] cellSize = checkCellSize( this.cellSize, dimensions );
			
			return new CellContainer<T, ShortArray>( new CellContainerFactory( cellSize ), (ExistingShortArrays)existingArrays, dimensions, cellSize, entitiesPerPixel );
		}
	}
}
