/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/
package ini.trakem2.display;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.AffineTransform;
import ini.trakem2.display.Layer;
import ini.trakem2.utils.Utils;

public class DisplayableProperties {

	public double x;
	public double y;
	public double width;
	public double height;
	public double rot;
	public float alpha;
	public boolean visible;
	public boolean locked;
	public Color color;
	public Layer layer;
	public double sx=1, sy=1;
	/* 
	 * AffineTransform as below. Note that the dx,dy is kept local to the x,y which means they are zero in almost all occasions.
	 *
	 * 	{a11, a12, dx}
	 * 	{a21, a22, dy}
	 * 	{  0,   0, 1}
	 *
	 */
	public AffineTransform at;
	/**
	 * The AffineTransform is always local to the x,y of this object.
	 *
	 *
	 * */
	public DisplayableProperties(double x, double y, double width, double height, float alpha, boolean visible, boolean locked, Color color, Layer layer, float a11, float a21, float a12, float a22) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.rot = rot;
		this.alpha = alpha;
		this.visible = visible;
		this.locked = locked;
		this.color = color;
		this.layer = layer;
		this.sx = sx;
		this.sy = sy;
		this.at = new AffineTransform(new float[]{a11, a21, a12, a22});
		// the order is VERY important
		//at.scale(sx, sy);
		//at.rotate(Math.toRadians(rot));
		//at.translate(x, y);
	}

	// for cloning purposes
	private DisplayableProperties() {}

	/** Returns the rotation and shear matrix, a 2x2, not translated to x,y. */
	/*
	public final double[] getMatrix() {
		double[] m = new double[4];
		at.getMatrix(m);
		return m;
	}
	*/
	/** Returns the rotation and shear matrix, a 2x2, not translated to x,y. */
	public final double[] getMatrix() {
		double[] m = new double[4];
		at.getMatrix(m);
		return m;
	}
	/** Returns the rotation and shear matrix, a 2x2, not translated to x,y. */
	public final float[] getMatrixF() {
		double[] m = new double[4];
		at.getMatrix(m);
		return new float[]{(float)m[0],(float)m[1],(float)m[2],(float)m[3]};
	}

	/** Returns a new AffineTransform that fully represents this object, including displacement. */
	public AffineTransform getAffineTransform() {
		AffineTransform a = (AffineTransform)at.clone();
		a.translate(x,y);
		return a;
	}

	/** Factory method, provides a Transform instance initialized to default values. */
	static final public DisplayableProperties createEmptyProperties() {
		return new DisplayableProperties(0, 0, 0, 0, 0, true, false, Color.yellow, null, 1, 0, 0, 1);
	}

	/** Stores the bounding box in the given rectangle and returns it. The bounding box is enlarged to include the rotation. Does not check of the rRectangle is null. */
	public final Rectangle getBoundingBox(Rectangle r) {
		final float[] v1 = 	{(float)x, 		(float)y,
				 	 (float)(x+width), 	(float)y,
					 (float)(x+width),	(float)(y+height),
					 (float)x,		(float)(y+height)};
		final float[] v2 = new float[v1.length];
		at.transform(v1, 0, v2, 0, v1.length);

		float min_x=  Float.MAX_VALUE, min_y = min_x,
		      max_x= -Float.MAX_VALUE, max_y = max_x;

		for (int i=v2.length-1; i>-1; i-=2) {
			if (v2[i-1] < min_x) min_x = v2[i-1];
			if (v2[i] < min_y) min_y = v2[i];
			if (v2[i-1] > max_x) max_x = v2[i-1];
			if (v2[i] > max_y) max_y = v2[i];
		}
		r.setBounds((int)min_x, (int)min_y, (int)Math.ceil(max_x), (int)Math.ceil(max_y));
		return r;
	}

	public final Polygon getPerimeter() {
		final float[] v1 = 	{(float)x, 		(float)y,
				 	 (float)(x+width), 	(float)y,
					 (float)(x+width),	(float)(y+height),
					 (float)x,		(float)(y+height)};
		final float[] v2 = new float[v1.length];
		at.transform(v1, 0, v2, 0, v1.length);

		return new Polygon(new int[]{(int)v2[0], (int)Math.ceil(v2[2]), (int)Math.ceil(v2[4]), (int)Math.ceil(v2[6])},
				   new int[]{(int)v2[1], (int)v2[3], (int)Math.ceil(v2[5]), (int)Math.ceil(v2[7])},
				   4);
	}

	public final void scale(final double sx, final double sy) {
		at.scale(sx, sy);
	}

	public final void translate(final double dx, final double dy) {
		at.translate(dx, dy);
	}

	/** Rotate by the given angle (in degrees) relative to the given center of coordinates. Will set the local angle and local x, y position accordingly. */
	public final void rotate(final double delta_angle, final double xo, final double yo) {
		if (0 == delta_angle) return;
		at.rotate(Math.toRadians(delta_angle), xo -x, yo -y);
	}

	public Object clone() {
		final DisplayableProperties t = new DisplayableProperties();
		t.x = x;
		t.y = y;
		t.width = width;
		t.height = height;
		t.alpha = alpha;
		t.visible = visible;
		t.locked = locked;
		t.color = color; // TODO should make a proper copy
		t.layer = layer;
		t.at = (AffineTransform)at.clone();
		return t;
	}
}
