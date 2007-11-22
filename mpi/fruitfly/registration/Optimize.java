package mpi.fruitfly.registration;

import java.util.List;
import ini.trakem2.display.Patch;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Display;
import mpi.fruitfly.analysis.FitLine;

public class Optimize {

	/**
	 * minimize the overall displacement of a set of tiles, propagate the
	 * estimated models to a corresponding set of patches and redraw
	 * 
	 * @param tiles
	 * @param patches
	 * @param fixed_tiles do not touch these tiles
	 * @param update_this
	 * 
	 * TODO revise convergence check
	 *   particularly for unguided minimization, it is hard to identify
	 *   convergence due to presence of local minima
	 *   
	 *   Johannes Schindelin suggested to start from a good guess, which is
	 *   e.g. the propagated unoptimized pose of a tile relative to its
	 *   connected tile that was already identified during RANSAC
	 *   correpondence check.  Thank you, Johannes, great hint!
	 */
	static public void minimizeAll(
			List< Tile > tiles,
			List< Patch > patches,
			List< Tile > fixed_tiles,
			float max_error )
	{
		int num_patches = patches.size();

		double od = Double.MAX_VALUE;
		double dd = Double.MAX_VALUE;
		double min_d = Double.MAX_VALUE;
		double max_d = Double.MIN_VALUE;
		int iteration = 1;
		int cc = 0;
		double[] dall = new double[100];
		int next = 0;
		//while ( cc < 10 )
		
		final Observer observer = new Observer();

		final Layer layer = patches.get(0).getLayer(); 
		
		while ( next < 100000 )  // safety check
//		while ( next < 10000 )  // safety check
		{
			for ( int i = 0; i < num_patches; ++i )
			{
				Tile tile = tiles.get( i );
				if ( fixed_tiles.contains( tile ) ) continue;
				tile.update();
				tile.minimizeModel();
				tile.update();
				patches.get( i ).getAffineTransform().setTransform( tile.getModel().getAffine() );
				//IJ.showStatus( "displacement: overall => " + od + ", current => " + tile.getDistance() );
			}
			double cd = 0.0;
			min_d = Double.MAX_VALUE;
			max_d = Double.MIN_VALUE;
			for ( Tile t : tiles )
			{
				t.update();
				double d = t.getDistance();
				if ( d < min_d ) min_d = d;
				if ( d > max_d ) max_d = d;
				cd += d;
			}
			cd /= tiles.size();
			dd = Math.abs( od - cd );
			od = cd;
			//IJ.showStatus( "displacement: " +  od + " [" + min_d + "; " + max_d + "] after " + iteration + " iterations");
			
			observer.add( od );			
			
			//cc = d < 0.00025 ? cc + 1 : 0;
			cc = dd < 0.001 ? cc + 1 : 0;

			if (dall.length  == next) {
				double[] dall2 = new double[dall.length + 100];
				System.arraycopy(dall, 0, dall2, 0, dall.length);
				dall = dall2;
			}
			dall[next++] = dd;
			
			// cut the last 'n'
			if (next > 100) { // wait until completing at least 'n' iterations
				double[] dn = new double[100];
				System.arraycopy(dall, dall.length - 100, dn, 0, 100);
				// fit curve
				double[] ft = FitLine.fitLine(dn);
				// ft[1] StdDev
				// ft[2] m (slope)
				//if ( Math.abs( ft[ 1 ] ) < 0.001 )

				// TODO revise convergence check or start from better guesses
				if ( od < max_error && ft[ 2 ] >= 0.0 )
				{
					System.out.println( "Exiting at iteration " + next + " with slope " + ft[ 2 ] );
					break;
				}

			}

			++iteration;

			if (0 == iteration / 1000) Display.repaint(layer, null, 0, false); // do the entire canvas, and do not update the navigator
		}
//		f.close();
		
		System.out.println( "Successfully optimized configuration of " + tiles.size() + " tiles:" );
		System.out.println( "  average displacement: " + od + "px" );
		System.out.println( "  minimal displacement: " + min_d + "px" );
		System.out.println( "  maximal displacement: " + max_d + "px" );
	}

	static private class Observer
	{
		public int i;			// iteration
		public double v;		// value
		public double d;		// first derivative
		public double m;		// mean
		public double var;		// variance
		public double std;		// standard-deviation
		
		public void add( double new_value )
		{
			if ( i == 0 )
			{
				i = 1;
				v = new_value;
				d = 0.0;
				m = v;
				var = 0.0;
				std = 0.0;
			}
			else
			{
				d = new_value - v;
				v = new_value;
				m = ( v + m * ( double )i++ ) / ( double )i;
				double tmp = v - m;
				var += tmp * tmp / ( double )i;
				std = Math.sqrt( var );
			}
		}
	}
}
