/**
 * 
 */
package mpicbg.trakem2.align;

import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.trakem2.transform.AffineModel2D;
import ij.IJ;

public class TileConfiguration extends mpicbg.models.TileConfiguration
{
	@Override
	protected void println( String s ){ IJ.log( s ); }
	
	/**
	 * Minimize the displacement of all {@link PointMatch Correspondence pairs}
	 * of all {@link Tile Tiles}
	 * 
	 * @param maxAllowedError do not accept convergence if error is > max_error
	 * @param maxIterations stop after that many iterations even if there was
	 *   no minimum found
	 * @param maxPlateauwidth convergence is reached if the average absolute
	 *   slope in an interval of this size and half this size is smaller than
	 *   0.0001 (in double accuracy).  This is assumed to prevent the algorithm
	 *   from stopping at plateaus smaller than this value.
	 */
	public void optimizeRigidized(
			final float maxAllowedError,
			final int maxIterations,
			final int maxPlateauwidth,
			final float tweakScale,
			final float tweakIso,
			final float tweakShear ) throws NotEnoughDataPointsException, IllDefinedDataPointsException 
	{
		final ErrorStatistic observer = new ErrorStatistic( maxPlateauwidth + 1 );
		
		int i = 0;
		
		boolean proceed = i < maxIterations;
		
		while ( proceed )
		{
			for ( final Tile< ? > tile : tiles )
			{
				if ( fixedTiles.contains( tile ) ) continue;
				tile.update();
				tile.fitModel();
				if ( AffineModel2D.class.isInstance( tile.getModel() ) )
				{
					IJ.log( "rigidizing" );
					
					double cx = 0.0;
					double cy = 0.0;
					double s = 0.0;
					/* Calculating the center of gravity */
					for ( final PointMatch pm : tile.getMatches() )
					{
						final float[] l = pm.getP1().getL();
						cx += l[ 0 ];
						cy += l[ 1 ];
						s += pm.getWeight();
					}
					
					if ( s != 0 )
					{
						cx /= s;
						cy /= s;
					}
					
					( ( AffineModel2D )tile.getModel() ).rigidize( ( float )cx, ( float )cy, tweakScale, tweakIso, tweakShear );
				}
				tile.update();
			}
			update();
			observer.add( error );
			
			if ( i > maxPlateauwidth )
			{
				proceed = error > maxAllowedError;
				
				int d = maxPlateauwidth;
				while ( !proceed && d >= 1 )
				{
					try
					{
						proceed |= Math.abs( observer.getWideSlope( d ) ) > 0.0001;
					}
					catch ( Exception e ) { e.printStackTrace(); }
					d /= 2;
				}
			}
			
			proceed &= ++i < maxIterations;
		}
		
		println( "Successfully optimized configuration of " + tiles.size() + " tiles after " + i + " iterations:" );
		println( "  average displacement: " + decimalFormat.format( error ) + "px" );
		println( "  minimal displacement: " + decimalFormat.format( minError ) + "px" );
		println( "  maximal displacement: " + decimalFormat.format( maxError ) + "px" );
	}

}
