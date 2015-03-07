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
package mpicbg.trakem2.transform;

import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

/**
 *
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class PolynomialTransform2DTest
{
	static private double[] coefficients = new double[] { 67572.7357, 0.97263708, -0.0266434795, -3.08962708e-06, 3.52672467e-06, 1.36924462e-07, 5446.8534, 0.022404762, 0.96120261, -3.3675352e-07, -8.9721973e-07, -5.4985399e-06 };
	static private Random rnd = new Random(0);

	@Test
	public void test()
	{
		final PolynomialTransform2D t1 = new PolynomialTransform2D();
		t1.set( coefficients );

		final PolynomialTransform2D t2 = t1.copy();
		final PolynomialTransform2D t3 = new PolynomialTransform2D();
		t3.init( t1.toDataString() );

		for ( int i = 0; i < 1000; ++i )
		{
			final double[] a1 = new double[]{
					rnd.nextDouble() - 0.5 * Double.MAX_VALUE,
					rnd.nextDouble() - 0.5 * Double.MAX_VALUE };
			final double[] a2 = a1.clone();
			final double[] a3 = a1.clone();

			t1.applyInPlace( a1 );
			t2.applyInPlace( a2 );
			t3.applyInPlace( a3 );

			Assert.assertEquals( a1[ 0 ], a2[ 0 ], 0 );
			Assert.assertEquals( a1[ 1 ], a2[ 1 ], 0 );

			Assert.assertEquals( a1[ 0 ], a3[ 0 ], 0 );
			Assert.assertEquals( a1[ 1 ], a3[ 1 ], 0 );
		}
	}
}
