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
package mpicbg.trakem2.transform;

import java.awt.geom.AffineTransform;

public class AffineModel2D extends mpicbg.models.AffineModel2D implements InvertibleCoordinateTransform
{

	//@Override
	final public void init( final String data )
	{
		final String[] fields = data.split( "\\s+" );
		if ( fields.length == 6 )
		{
			final float m00 = Float.parseFloat( fields[ 0 ] );
			final float m10 = Float.parseFloat( fields[ 1 ] );
			final float m01 = Float.parseFloat( fields[ 2 ] );
			final float m11 = Float.parseFloat( fields[ 3 ] );
			final float m02 = Float.parseFloat( fields[ 4 ] );
			final float m12 = Float.parseFloat( fields[ 5 ] );
			set( m00, m10, m01, m11, m02, m12 );
		}
		else throw new NumberFormatException( "Inappropriate parameters for " + this.getClass().getCanonicalName() );
	}

	//@Override
	final public String toXML( final String indent )
	{
		return indent + "<iict_transform class=\"" + this.getClass().getCanonicalName() + "\" data=\"" + toDataString() + "\" />";
	}
	
	//@Override
	final public String toDataString()
	{
		return m00 + " " + m10 + " " + m01 + " " + m11 + " " + m02 + " " + m12;
	}
	
	@Override
	public AffineModel2D clone()
	{
		final AffineModel2D m = new AffineModel2D();
		m.m00 = m00;
		m.m01 = m01;
		m.m10 = m10;
		m.m11 = m11;

		m.m02 = m02;
		m.m12 = m12;
		
		m.cost = cost;
		
		m.invert();

		return m;
	}
	
	/**
	 * TODO Not yet tested
	 */
	//@Override
	public AffineModel2D createInverse()
	{
		final AffineModel2D ict = new AffineModel2D();
		
		ict.m00 = i00;
		ict.m10 = i10;
		ict.m01 = i01;
		ict.m11 = i11;
		ict.m02 = i02;
		ict.m12 = i12;
		
		ict.i00 = m00;
		ict.i10 = m10;
		ict.i01 = m01;
		ict.i11 = m11;
		ict.i02 = m02;
		ict.i12 = m12;
		
		ict.cost = cost;
		
		return ict;
	}
	
	/**
	 * Makes an affine transformation matrix from the given scale, shear,
	 * rotation and translation values
     * if you want a uniquely retrievable matrix, give sheary=0
     * 
	 * @param scalex scaling in x
	 * @param scaley scaling in y
	 * @param shearx shearing in x
	 * @param sheary shearing in y
	 * @param rotang angle of rotation (in radians)
	 * @param transx translation in x
	 * @param transy translation in y
	 * @return affine transformation matrix
	 */
	private static AffineTransform makeAffineMatrix(
			final double scalex, 
			final double scaley, 
			final double shearx, 
			final double sheary, 
			final double rotang, 
			final double transx, 
			final double transy)
	{
		/*
		%makes an affine transformation matrix from the given scale, shear,
		%rotation and translation values
		%if you want a uniquely retrievable matrix, give sheary=0
		%by Daniel Berger for MIT-BCS Seung, April 19 2009

		A=[[scalex shearx transx];[sheary scaley transy];[0 0 1]];
		A=[[cos(rotang) -sin(rotang) 0];[sin(rotang) cos(rotang) 0];[0 0 1]] * A;
		*/
		
		final double m00 = Math.cos(rotang) * scalex - Math.sin(rotang) * sheary;
		final double m01 = Math.cos(rotang) * shearx - Math.sin(rotang) * scaley;
		final double m02 = Math.cos(rotang) * transx - Math.sin(rotang) * transy;
		
		final double m10 = Math.sin(rotang) * scalex + Math.cos(rotang) * sheary;
		final double m11 = Math.sin(rotang) * shearx + Math.cos(rotang) * scaley;
		final double m12 = Math.sin(rotang) * transx + Math.cos(rotang) * transy;
		
		return new AffineTransform( m00,  m10,  m01,  m11,  m02,  m12);		
	}
	
	
	public void rigidize( final float cx, final float cy, final float tweakScale, final float tweakIso, final float tweakShear )
	{
		final AffineTransform a = createAffine();
		
		// Move to the center of the image
		a.translate(cx, cy);
		
		/*
		IJ.log(" A: " + a.getScaleX() + " " + a.getShearY() + " " + a.getShearX()
				+ " " + a.getScaleY() + " " + a.getTranslateX() + " " + 
				+ a.getTranslateY() );
				*/
		
		// retrieves scaling, shearing, rotation and translation from an affine
		// transformation matrix A (which has translation values in the right column)
		// by Daniel Berger for MIT-BCS Seung, April 19 2009

		// We assume that sheary=0
		// scalex=sqrt(A(1,1)*A(1,1)+A(2,1)*A(2,1));
		final double a11 = a.getScaleX();
		final double a21 = a.getShearY();
		final double scaleX = Math.sqrt( a11 * a11 + a21 * a21 );
		// rotang=atan2(A(2,1)/scalex,A(1,1)/scalex);
		final double rotang = Math.atan2( a21/scaleX, a11/scaleX);

		// R=[[cos(-rotang) -sin(-rotang)];[sin(-rotang) cos(-rotang)]];
		
		// rotate back shearx and scaley
		//v=R*[A(1,2) A(2,2)]';
		final double a12 = a.getShearX();
		final double a22 = a.getScaleY();
		final double shearX = Math.cos(-rotang) * a12 - Math.sin(-rotang) * a22;
		final double scaleY = Math.sin(-rotang) * a12 + Math.cos(-rotang) * a22;

		// rotate back translation
		// v=R*[A(1,3) A(2,3)]';
		final double transX = Math.cos(-rotang) * a.getTranslateX() - Math.sin(-rotang) * a.getTranslateY();
		final double transY = Math.sin(-rotang) * a.getTranslateX() + Math.cos(-rotang) * a.getTranslateY();
		
		// TWEAK		
		
		final double new_shearX = shearX * (1.0 - tweakShear); 
		//final double new_shearY = 0; // shearY * (1.0 - tweakShear);
		
		final double avgScale = (scaleX + scaleY)/2;
	    final double aspectRatio = scaleX / scaleY;
	    final double regAvgScale = avgScale * (1.0 - tweakScale) + 1.0  * tweakScale;
	    final double regAspectRatio = aspectRatio * (1.0 - tweakIso) + 1.0 * tweakIso;
	    
	    //IJ.log("avgScale = " + avgScale + " aspectRatio = " + aspectRatio + " regAvgScale = " + regAvgScale + " regAspectRatio = " + regAspectRatio);
	    
	    final double new_scaleY = (2.0 * regAvgScale) / (regAspectRatio + 1.0);
	    final double new_scaleX = regAspectRatio * new_scaleY;
		
		final AffineTransform b = makeAffineMatrix(new_scaleX, new_scaleY, new_shearX, 0, rotang, transX, transY);
								
	    //IJ.log("new_scaleX = " + new_scaleX + " new_scaleY = " + new_scaleY + " new_shearX = " + new_shearX + " new_shearY = " + new_shearY);		    		    		    
		
		// Move back the center
		b.translate(-cx, -cy);
		
		set( b );
	}
}
