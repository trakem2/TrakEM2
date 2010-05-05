package ini.trakem2.utils;

import java.awt.geom.Area;
import java.awt.geom.GeneralPath;

/** Reconstruct an Area from a list of XML &lt;t2_path d="M ... z"/&gt; entries. */
public final class ReconstructArea {

	private final GeneralPath gp = new GeneralPath(GeneralPath.WIND_EVEN_ODD);

	/** To reconstruct from XML, takes a "M .... z" SVG path. */
	public final void add(String svg_path) {
		// Ensure format
		svg_path = svg_path.trim();
		while (-1 != svg_path.indexOf("  ")) {
			svg_path = svg_path.replaceAll("  "," "); // make all spaces be single
		}
		// parse according to assumptions
		final char[] data = new char[svg_path.length()];
		svg_path.getChars(0, data.length, data, 0);
		parse(gp, data);
	}

	public final Area getArea() {
		return new Area(gp);
	}

	public final GeneralPath getGeneralPath() {
		return gp;
	}

	/** Assumes first char is 'M' and last char is a 'z'*/
	static private final void parse(final GeneralPath gp, final char[] data) {
		if ('z' != data[data.length-1]) {
			Utils.log("AreaList: no closing z, ignoring sub path");
			return;
		}
		data[data.length-1] = 'L'; // replacing the closing z for an L, since we read backwards
		final float[] xy = new float[2];
		int i_L = -1;
		// find first L
		for (int i=0; i<data.length; i++) {
			if ('L' == data[i]) {
				i_L = i;
				break;
			}
		}
		readXY(data, i_L, xy);
		//final float x0 = xy[0];
		//final float y0 = xy[1];
		//gp.moveTo(x0, y0);
		gp.moveTo(xy[0], xy[1]);
		int first = i_L+1;
		while (-1 != (first = readXY(data, first, xy))) {
			gp.lineTo(xy[0], xy[1]);
		}

		// close loop
		//gp.lineTo(x0, y0); //TODO unnecessary?
		gp.closePath();
	}

	/** Assumes all read chars will be digits except for the separator (single white space char), and won't fail (but generate ugly results) when any char is not a digit. */
	static public final int readXY(final char[] data, int first, final float[] xy) { // final method: inline
		if (first >= data.length) return -1;
		int last = first;
		char c = data[first];
		while ('L' != c) {
			last++;
			if (data.length == last) return -1;
			c = data[last];
		}
		first = last +2; // the first digit position after the found L (the found L will be the next first)

		// skip the L and the white space separating <y> and L
		last -= 2;
		if (last < 0) return -1;
		c = data[last];

		// the 'y'
		xy[1] = 0;
		int pos = 1;
		while (' ' != c) {
			last--;
			if ('-' == c) {
				xy[1] *= -1;
				break;
			} else if ('.' == c) {
				// divide by position to make all numbers be after the comma
				xy[1] /= pos;
				pos = 1;
			} else {
				xy[1] += (((int)c) -48) * pos; // digit zero is char with int value 48
				pos *= 10;
			}
			c = data[last];
		}

		// skip separating space
		last--;

		// the 'x'
		c = data[last];
		pos = 1;
		xy[0] = 0;
		while (' ' != c) {
			last--;
			if ('-' == c) {
				xy[0] *= -1;
				break;
			} else if ('.' == c) {
				// divide by position to make all numbers be after the comma
				xy[0] /= pos;
				pos = 1;
			} else {
				xy[0] += (((int)c) -48) * pos;
				pos *= 10;
			}
			c = data[last];
		}
		return first;
	}
}
