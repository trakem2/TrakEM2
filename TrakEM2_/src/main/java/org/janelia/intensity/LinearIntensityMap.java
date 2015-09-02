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
package org.janelia.intensity;

import ij.ImageJ;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.basictypeaccess.array.IntArray;
import net.imglib2.img.basictypeaccess.array.ShortArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale;
import net.imglib2.realtransform.Translation;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.imglib2.view.composite.CompositeIntervalView;
import net.imglib2.view.composite.RealComposite;

/**
 * Transfer intensities by a linear function <em>y</em>=<em>ax</em>+<em>b</em>
 * with the coefficients <em>a</em> and <em>b</em> being stored as a
 * {@link RealComposite RealComposite&lt;T&gt;} 2d-vector
 * (<em>a</em>, <em>b</em>) in a map.  The
 * map is a {@link RealRandomAccessible} and the defined interval as passed as
 * an independent parameter.  If the coefficients are passed as a raster, the
 * interval is that of a raster.  The map is scaled such that it applies to
 * the interval of the input image.
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class LinearIntensityMap< T extends RealType< T > >
{
	static public enum Interpolation{ NN, NL };

	final static private < T extends RealType< T > >InterpolatorFactory< RealComposite< T >, RandomAccessible< RealComposite< T > > > interpolatorFactory( final Interpolation interpolation )
	{
		switch ( interpolation )
		{
		case NN:
			return new NearestNeighborInterpolatorFactory< RealComposite< T > >();
		default:
			return new NLinearInterpolatorFactory< RealComposite< T > >();
		}
	}

	final protected Dimensions dimensions;
	final protected Translation translation;
	final protected RealRandomAccessible< RealComposite< T > > coefficients;

	final protected InterpolatorFactory< RealComposite< T >, RandomAccessible< RealComposite< T > > > interpolatorFactory;

	public LinearIntensityMap( final RandomAccessibleInterval< T > source, final InterpolatorFactory< RealComposite< T >, RandomAccessible< RealComposite< T > > > interpolatorFactory )
	{
		this.interpolatorFactory = interpolatorFactory;
		final CompositeIntervalView< T, RealComposite< T > > collapsedSource = Views.collapseReal( source );
		dimensions = new FinalInterval( collapsedSource );
		final double[] shift = new double[ dimensions.numDimensions() ];
		for ( int d = 0; d < shift.length; ++d )
			shift[ d ] = 0.5;
		translation = new Translation( shift );

		final RandomAccessible< RealComposite< T > > extendedCollapsedSource = Views.extendBorder( collapsedSource );
		coefficients = Views.interpolate( extendedCollapsedSource, interpolatorFactory );
	}

	public LinearIntensityMap( final RandomAccessibleInterval< T > source )
	{
		this( source, new NLinearInterpolatorFactory< RealComposite< T > >() );
	}

	public LinearIntensityMap( final RandomAccessibleInterval< T > source, final Interpolation interpolation )
	{
		this( source, LinearIntensityMap.< T >interpolatorFactory( interpolation ) );
	}

	@SuppressWarnings( { "rawtypes", "unchecked" } )
	public < S extends NumericType< S > > void run( final RandomAccessibleInterval< S > image )
	{
		assert image.numDimensions() == dimensions.numDimensions() : "Number of dimensions do not match.";

		final double[] s = new double[ dimensions.numDimensions() ];
		for ( int d = 0; d < s.length; ++d )
			s[ d ] = image.dimension( d ) / dimensions.dimension( d );
		final Scale scale = new Scale( s );

		System.out.println( "translation-n " + translation.numDimensions() );

		final RandomAccessibleInterval< RealComposite< T > > stretchedCoefficients =
				Views.offsetInterval(
						Views.raster(
								RealViews.transform(
										RealViews.transform(
												coefficients,
												translation ),
										scale ) ),
						image );

		/* decide on type which mapping to use */
		final S t = image.randomAccess().get();

		if ( ARGBType.class.isInstance( t ) )
			mapARGB( Views.flatIterable( ( RandomAccessibleInterval< ARGBType > )image ), Views.flatIterable( stretchedCoefficients ) );
		else if ( RealComposite.class.isInstance( t ) )
			mapComposite( Views.flatIterable( ( RandomAccessibleInterval )image ), Views.flatIterable( stretchedCoefficients ) );
		else if ( RealType.class.isInstance( t ) )
		{
			final RealType< ? > r = ( RealType )t;
			if ( r.getMinValue() > -Double.MAX_VALUE || r.getMaxValue() < Double.MAX_VALUE )
//			    TODO Bug in javac does not enable cast from RandomAccessibleInterval< S > to RandomAccessibleInterval< RealType >, remove when fixed
				mapCrop( Views.flatIterable( ( RandomAccessibleInterval< RealType > )( Object )image ), Views.flatIterable( stretchedCoefficients ) );
			else
//              TODO Bug in javac does not enable cast from RandomAccessibleInterval< S > to RandomAccessibleInterval< RealType >, remove when fixed
				map( Views.flatIterable( ( RandomAccessibleInterval< RealType > )( Object )image ), Views.flatIterable( stretchedCoefficients ) );
		}

	}

	final static protected < S extends RealType< S >, T extends RealType< T > > void map(
			final IterableInterval< S > image,
			final IterableInterval< RealComposite< T > > coefficients )
	{
		final Cursor< S > cs = image.cursor();
		final Cursor< RealComposite< T > > ct = coefficients.cursor();

		while ( cs.hasNext() )
		{
			final S s = cs.next();
			final RealComposite< T > t = ct.next();
			s.setReal( s.getRealDouble() * t.get( 0 ).getRealDouble() + t.get( 1 ).getRealDouble() );
		}
	}

	final static protected < S extends RealType< S >, T extends RealType< T > > void mapCrop(
			final IterableInterval< S > image,
			final IterableInterval< RealComposite< T > > coefficients )
	{
		final Cursor< S > cs = image.cursor();
		final Cursor< RealComposite< T > > ct = coefficients.cursor();
		final S firstValue = cs.next();
		final double minS = firstValue.getMinValue();
		final double maxS = firstValue.getMaxValue();

		while ( cs.hasNext() )
		{
			final S s = cs.next();
			final RealComposite< T > t = ct.next();

			s.setReal( Math.max( minS, Math.min( maxS, s.getRealDouble() * t.get( 0 ).getRealDouble() + t.get( 1 ).getRealDouble() ) ) );
		}
	}

	final static protected < S extends RealType< S >, T extends RealType< T > > void mapComposite(
			final IterableInterval< RealComposite< S > > image,
			final IterableInterval< RealComposite< T > > coefficients )
	{
		final Cursor< RealComposite< S > > cs = image.cursor();
		final Cursor< RealComposite< T > > ct = coefficients.cursor();

		while ( cs.hasNext() )
		{
			final RealComposite< S > c = cs.next();
			final RealComposite< T > t = ct.next();

			for ( final S s : c )
				s.setReal( s.getRealDouble() * t.get( 0 ).getRealDouble() + t.get( 1 ).getRealDouble() );
		}
	}

	final static protected < T extends RealType< T > > void mapARGB(
			final IterableInterval< ARGBType > image,
			final IterableInterval< RealComposite< T > > coefficients )
	{
		final Cursor< ARGBType > cs = image.cursor();
		final Cursor< RealComposite< T > > ct = coefficients.cursor();

		while ( cs.hasNext() )
		{
			final RealComposite< T > t = ct.next();
			final double alpha = t.get( 0 ).getRealDouble();
			final double beta = t.get( 1 ).getRealDouble();

			final ARGBType s = cs.next();
			final int argb = s.get();
			final int a = ( ( argb >> 24 ) & 0xff );
			final double r = ( ( argb >> 16 ) & 0xff ) * alpha + beta;
			final double g = ( ( argb >> 8 ) & 0xff ) * alpha + beta;
			final double b = ( argb & 0xff ) * alpha + beta;

			s.set(
					( a << 24 ) |
					( ( r < 0 ? 0 : r > 255 ? 255 : ( int )( r + 0.5 ) ) << 16 ) |
					( ( g < 0 ? 0 : g > 255 ? 255 : ( int )( g + 0.5 ) ) << 8 ) |
					( b < 0 ? 0 : b > 255 ? 255 : ( int )( b + 0.5 ) ) );
		}
	}

	public static void main( final String[] args )
	{
		new ImageJ();

		final double[] coefficients = new double[]{
				0, 2, 4, 8,
				1, 1, 1, 1,
				1, 10, 5, 1,
				1, 1, 1, 1,

				0, 10, 20, 30,
				40, 50, 60, 70,
				80, 90, 100, 110,
				120, 130, 140, 150
		};

		final LinearIntensityMap< DoubleType > transform = new LinearIntensityMap< DoubleType >( ArrayImgs.doubles( coefficients, 4, 4, 2 ) );

		//final ImagePlus imp = new ImagePlus( "http://upload.wikimedia.org/wikipedia/en/2/24/Lenna.png" );
		final ImagePlus imp1 = new ImagePlus( "http://fly.mpi-cbg.de/~saalfeld/Pictures/norway.jpg");

		final ArrayImg< FloatType, FloatArray > image1 = ArrayImgs.floats( ( float[] )imp1.getProcessor().convertToFloatProcessor().getPixels(), imp1.getWidth(), imp1.getHeight() );
		final ArrayImg< UnsignedByteType, ByteArray > image2 = ArrayImgs.unsignedBytes( ( byte[] )imp1.getProcessor().convertToByteProcessor().getPixels(), imp1.getWidth(), imp1.getHeight() );
		final ArrayImg< UnsignedShortType, ShortArray > image3 = ArrayImgs.unsignedShorts( ( short[] )imp1.getProcessor().convertToShortProcessor().getPixels(), imp1.getWidth(), imp1.getHeight() );
		final ArrayImg< ARGBType, IntArray > image4 = ArrayImgs.argbs( ( int[] )imp1.getProcessor().getPixels(), imp1.getWidth(), imp1.getHeight() );

		ImageJFunctions.show( ArrayImgs.doubles( coefficients, 4, 4, 2 ) );

		transform.run( image1 );
		transform.run( image2 );
		transform.run( image3 );
		transform.run( image4 );

		ImageJFunctions.show( image1 );
		ImageJFunctions.show( image2 );
		ImageJFunctions.show( image3 );
		ImageJFunctions.show( image4 );
	}
}
