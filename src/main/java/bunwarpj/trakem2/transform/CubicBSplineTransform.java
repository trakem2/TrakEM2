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
package bunwarpj.trakem2.transform;

import java.util.Collection;
import java.util.Stack;

import mpicbg.models.AbstractModel;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.trakem2.transform.CoordinateTransform;
import bunwarpj.BSplineModel;
import bunwarpj.Param;
import bunwarpj.Transformation;
import bunwarpj.bUnwarpJ_;

/**
 * Class to implement elastic transforms based on cubic B-splines.
 *
 * @author Ignacio Arganda-Carreras (ignacio.arganda@gmail.com)
 */
public class CubicBSplineTransform extends AbstractModel< CubicBSplineTransform > implements CoordinateTransform
{
	/** grid of B-spline coefficients for x- transformation */
	private BSplineModel swx = null;
	/** grid of B-spline coefficients for y- transformation */
	private BSplineModel swy = null;
	/** number of intervals between B-spline coefficients */
	private int intervals = 0;
	/** width of the image to be transformed */
	private int width = 0;
	/** height of the image to be transformed */
	private int height = 0;
	/** width of the source image (necessary for the fit method) */
	private int sourceWidth = 0;
	/** height of the source image (necessary for the fit method) */
	private int sourceHeight = 0;
	/** bUnwarpJ parameters (necessary for the fit method) */
	private Param parameter = new Param(2, 0, 0, 2, 0.1, 0.1, 1.0, 0.0, 0.0, 0.01);

	// -------------------------------------------------------------------
	/**
	 * Empty constructor
	 */
	public CubicBSplineTransform(){}

	// -------------------------------------------------------------------
	/**
	 * Cubic B-spline transform constructor
	 *
	 * @param intervals intervals between B-spline coefficients
	 * @param cx B-spline coefficients for transformation in the x axis
	 * @param cy B-spline coefficients for transformation in the y axis
	 * @param width width of the target image
	 * @param height height of the target image
	 */
	public CubicBSplineTransform(final int intervals,
								  final double[][]cx,
								  final double[][]cy,
								  final int width,
								  final int height)
	{
		this.intervals = intervals;
		this.swx = new BSplineModel(cx);
		this.swy = new BSplineModel(cy);
		this.width = width;
		this.height = height;
	}

	// -------------------------------------------------------------------
	/**
	 * Cubic B-spline transform constructor
	 *
	 * @param intervals intervals between B-spline coefficients
	 * @param swx B-spline model for transformation in the x axis
	 * @param swy B-spline model for transformation in the y axis
	 * @param width width of the target image
	 * @param height height of the target image
	 */
	public CubicBSplineTransform(final int intervals,
								  final BSplineModel swx,
								  final BSplineModel swy,
								  final int width,
								  final int height)
	{
		this.intervals = intervals;
		this.swx = swx;
		this.swy = swy;
		this.width = width;
		this.height = height;
	}

	// -------------------------------------------------------------------
	/**
	 * Set cubic B-spline transform values
	 *
	 * @param intervals intervals between B-spline coefficients
	 * @param swx B-spline model for transformation in the x axis
	 * @param swy B-spline model for transformation in the y axis
	 * @param width width of the target image
	 * @param height height of the target image
	 */
	public void set(final int intervals,
					final BSplineModel swx,
					final BSplineModel swy,
					final int width,
					final int height)
	{
		this.intervals = intervals;
		this.swx = swx;
		this.swy = swy;
		this.width = width;
		this.height = height;
	}

	// -------------------------------------------------------------------
	/**
	 * Set cubic B-spline transform constructor
	 *
	 * @param intervals intervals between B-spline coefficients
	 * @param cx B-spline coefficients for transformation in the x axis
	 * @param cy B-spline coefficients for transformation in the y axis
	 * @param width width of the target image
	 * @param height height of the target image
	 */
	public void set(final int intervals,
					final double[][]cx,
					final double[][]cy,
					final int width,
					final int height)
	{
		this.intervals = intervals;
		this.swx = new BSplineModel(cx);
		this.swy = new BSplineModel(cy);
		this.width = width;
		this.height = height;
	}

	// -------------------------------------------------------------------
	/* (non-Javadoc)
	 * @see mpicbg.models.CoordinateTransform#apply(double[])
	 */
	@Override
    public double[] apply(final double[] l)
	{
		final double[] w = l.clone();
		applyInPlace(w);
		return w;
	}

	// -------------------------------------------------------------------
	/* (non-Javadoc)
	 * @see mpicbg.models.CoordinateTransform#applyInPlace(double[])
	 */
	@Override
    public void applyInPlace(final double[] l)
	{
		// Compute the transformation mapping
		final double tv = (double)(l[1] * intervals) / (double)(this.height - 1) + 1.0F;
		final double tu = (double)(l[0] * intervals) / (double)(this.width - 1) + 1.0F;

		l[0] = swx.prepareForInterpolationAndInterpolateI(tu, tv, false, false);
		l[1] = swy.prepareForInterpolationAndInterpolateI(tu, tv, false, false);
	}


	// -------------------------------------------------------------------
	/**
	 * Initialize cubic B-spline transform from the parameters of a string
	 *
	 * @param dataString basic cubic B-spline transform parameters
	 */
	@Override
    public void init(final String dataString) throws NumberFormatException
	{
		// Read parameter between spaces
		final String[] fields = dataString.split( "\\s+" );

		int j = 0;

		this.width = Integer.parseInt(fields[j++]);
		this.height = Integer.parseInt(fields[j++]);
		this.intervals = Integer.parseInt(fields[j++]);

		final int size = (this.intervals + 3);
		final int size2 = size * size;

		//IJ.log("width = " + this.width + " height = " + this.height + " intervals = " + this.intervals);


		if (fields.length < (2*size2 + 3))
			throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );

		else
		{
			final double[] cx = new double[size2];
			for(int i = 0; i < size2; i++)
				cx[i] = Double.parseDouble(fields[j++]);

			final double[] cy = new double[size2];
			for(int i = 0; i < size2; i++)
				cy[i] = Double.parseDouble(fields[j++]);

			this.swx = new BSplineModel(cx, size, size, 0);
			this.swy = new BSplineModel(cy, size, size, 0);
		}

	}


	// -------------------------------------------------------------------
	/**
	 * Save cubic B-spline transform information into String
	 */
	@Override
    public String toDataString()
	{
		final StringBuffer text = new StringBuffer(this.width + " " + this.height +  " " + intervals);

		final int size = (intervals + 3) * (intervals + 3);

		final double[] cx = this.swx.getCoefficients();

		for(int i = 0; i < size; i ++)
			text.append( " " + cx[i] );

		final double[] cy = this.swy.getCoefficients();

		for(int i = 0; i < size; i ++)
			text.append( " " + cy[i] );

		return text.toString();
	}


	// -------------------------------------------------------------------
	//@Override
	@Override
    final public String toXML( final String indent )
	{
		return indent + "<ict_transform class=\"" + this.getClass().getCanonicalName() + "\" data=\"" + toDataString() + "\"/>";
	}

	// -------------------------------------------------------------------
	/**
	 * Clone method
	 */
	@Override
    final public CubicBSplineTransform copy()
	{
		final CubicBSplineTransform transf = new CubicBSplineTransform();
		transf.init( toDataString() );
		return transf;
	}

	//@Override
	@Override
    public < P extends PointMatch >void fit(final Collection< P > matches)
			throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{

		final Stack< java.awt.Point > sourcePoints = new Stack<java.awt.Point>();
		final Stack< java.awt.Point > targetPoints = new Stack<java.awt.Point>();

		for ( final P pm : matches )
		{
			final double[] p1 = pm.getP1().getL();
			final double[] p2 = pm.getP2().getL();

			targetPoints.add( new java.awt.Point( ( int )Math.round( p1[ 0 ] ), ( int )Math.round( p1[ 1 ] ) ) );
			sourcePoints.add( new java.awt.Point( ( int )Math.round( p2[ 0 ] ), ( int )Math.round( p2[ 1 ] ) ) );
		}

		final Transformation transf = bUnwarpJ_.computeTransformationBatch(sourceWidth,
				sourceHeight, width, height, sourcePoints, targetPoints, parameter);
		this.set(transf.getIntervals(), transf.getDirectDeformationCoefficientsX(),
				transf.getDirectDeformationCoefficientsY(), width, height);
	}


	public void scale(final double xScale, final double yScale)
	{
		// Adapt transformation to scale

    	final double[] cx = swx.getCoefficients();
    	final double[] cy = swy.getCoefficients();

    	final double xScaleFactor = 1.0/xScale;
    	final double yScaleFactor = 1.0/yScale;

    	for(int i = 0; i < cx.length; i++)
    	{
    		cx[i] *= xScaleFactor;
    		cy[i] *= yScaleFactor;
    	}

	}

	public void set(final Param p, final int sourceWidth, final int sourceHeight, final int targetWidth, final int targetHeight)
	{
		this.parameter = p;
		this.sourceHeight = sourceHeight;
		this.sourceWidth = sourceWidth;
		this.width = targetWidth;
		this.height = targetHeight;
	}

	//@Override
	@Override
    public int getMinNumMatches() {
		// TODO Auto-generated method stub
		return 0;
	}

	//@Override
	@Override
    public void set(final CubicBSplineTransform m) {
		init( m.toDataString() );
	}



//	@Override
//	public void shake(float amount) {
//		// TODO If you really need, implement it ...
//
//	}


	//@Override
	@Override
    public String toString() {
		return toDataString();
	}


}// end class CubicBSplineTransform
