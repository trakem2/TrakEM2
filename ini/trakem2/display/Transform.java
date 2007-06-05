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

public class Transform {

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

	public Transform(double x, double y, double width, double height, double rot, float alpha, boolean visible, boolean locked, Color color, Layer layer) {
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
	}

	// for cloning purposes
	private Transform() {}

	/** Factory method, provides a Transform instance initialized to default values. */
	static public Transform createEmptyTransform() {
		return new Transform(0, 0, 0, 0, 0, 0, true, false, Color.yellow, null);
	}

	/** Stores the bounding box in the given rectangle and returns it. The bounding box is enlarged to include the rotation.*/
	public Rectangle getBoundingBox(Rectangle r) {
		if (null == r) r = new Rectangle();
		if (Math.abs(rot) < 0.001) {
			r.x = (int)x;
			r.y = (int)y;
			r.width = (int)Math.ceil(width);
			r.height = (int)Math.ceil(height);
		} else {
			// optimized version:
			double corner_angle2 = Utils.getAngle(width, height);
			double corner_angle1 = Math.PI - corner_angle2; //symmetric
			double hypot = Math.sqrt(width * width + height * height);//  / 2;
			double rot = Math.toRadians(this.rot);
			double cos1 = Math.abs(Math.cos(corner_angle1 - rot) * hypot);
			double cos2 = Math.abs(Math.cos(corner_angle2 - rot) * hypot);
			double sin1 = Math.abs(Math.sin(corner_angle1 - rot) * hypot);
			double sin2 = Math.abs(Math.sin(corner_angle2 - rot) * hypot);
			// choose largest
			double w = Math.ceil(( cos1 > cos2 ? cos1 : cos2 )); // * 2);
			double h = Math.ceil((sin1 > sin2 ? sin1 : sin2 ));// * 2);
			r.x = (int)(x + (width - w) / 2);
			r.y = (int)(y + (height - h) / 2);
			r.width = (int)w;
			r.height = (int)h;
		}
		return r;
	}

	public Polygon getPerimeter() {
		if (Math.abs(this.rot) < 0.001) {
			return new Polygon(new int[]{0, (int)Math.ceil(width), (int)Math.ceil(width), 0}, new int[]{0, 0, (int)Math.ceil(height), (int)Math.ceil(height)}, 4);
		} else {
			// rotate perimeter relative to the center.
			double rot = Math.toRadians(this.rot);
			double[][] p = Displayable.rotatePoints(new double[][]{{0, width, width, 0}, {0, 0, height, height}}, rot, width/2, height/2);
			Polygon pol = new Polygon();
			for (int i=0; i<p[0].length; i++) {
				pol.addPoint((int)p[0][i], (int)p[1][i]);
			}
			return pol;
		}
	}

	/** Will displace as well. The boxes are those of the general transformation.*/
	public void scale(double sx, double sy, Rectangle box, Rectangle box_old) {
		// if rotation exists:
		double w = this.width * sx;
		double h = this.height * sy;
		if (0 != rot) {
			Rectangle r = getBoundingBox(new Rectangle());
			this.x = box.x + (this.x + this.width/2  - box_old.x) * sx - w/2;
			this.y = box.y + (this.y + this.height/2 - box_old.y) * sy - h/2;
		} else {
			this.x = box.x + (this.x - box_old.x) * sx;
			this.y = box.y + (this.y - box_old.y) * sy;
		}
		this.width = w;
		this.height = h;
	}

	/*
	public void drag(int dx, int dy) {
		this.x += dx;
		this.y += dy;
	}
	*/

	public void translate(double dx, double dy) {
		this.x += dx;
		this.y += dy;
	}

	public void paintBox(Graphics g, Rectangle srcRect, double magnification) {
		Graphics2D g2d = (Graphics2D)g;
		g2d.translate((x - srcRect.x + width/2) * magnification, (y - srcRect.y + height/2) * magnification);
		AffineTransform original = g2d.getTransform(); // left in purpose after the translate command, because the transform does not store the translation in any case.
		g2d.rotate(rot * 2 * Math.PI / 360); //Math.toRadians(rot));

		// paint box
		g.drawRect(-(int)(width/2*magnification), -(int)(height/2*magnification), (int)Math.ceil(width * magnification), (int)Math.ceil(height * magnification));

		// reset
		g2d.setTransform(original);
		g2d.translate(- (x - srcRect.x + width/2)* magnification, - (y - srcRect.y + height/2)* magnification);
	}

	public Object clone() {
		Transform t = new Transform();
		t.x = x;
		t.y = y;
		t.width = width;
		t.height = height;
		t.rot = rot;
		t.alpha = alpha;
		t.visible = visible;
		t.locked = locked;
		t.color = color;
		t.layer = layer;
		return t;
	}

	/** Rotate by the given angle (in degrees) relative to the given center of coordinates. Will set the local angle and local x, y position accordingly. */
	public void rotate(double delta_angle, double xo, double yo) {
		if (0 == delta_angle) return;
		// Answer:
		// 1 - what is the delta local angle to add to the existing local angle?
		// 2 - what is the displacement of the x,y?
		//
		// center:
		double cx = x + width/2;
		double cy = y + height/2;

		// angle of object with origin of rotation
		double b1 = Utils.getAngle(xo - cx, yo - cy);
		//Utils.log2("\nb1: " + Math.toDegrees(b1));
		// new angle of object relative to origin of rotation
		double b2 = b1 + Math.toRadians(delta_angle);
		//Utils.log2("b2: " + Math.toDegrees(b2));
		//Utils.log2("delta_angle: " + delta_angle);
		// displacement
		double hypot = Math.sqrt((xo - cx)*(xo - cx) + (yo - cy)*(yo - cy));
		double dx = (Math.cos(b1) - Math.cos(b2)) * hypot;
		double dy = (Math.sin(b1) - Math.sin(b2)) * hypot;

		// set:
		this.x += dx;
		this.y += dy;
		this.rot += delta_angle;
		if (this.rot > 360) this.rot -= 360;
		if (this.rot < 0) this.rot += 360;
	}
}
