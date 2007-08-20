/** Adapted by Albert Cardona from the ArcBall C code in the Graphics Gems IV, 1993. */
package ini.trakem2.utils;

public class Vector3D {

	public double x, y, z;

	public Vector3D(double x, double y, double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public Vector3D() { this(0,0,0); }

	public Vector3D(Vector3D v) { this(v.x, v.y, v.z); }

	public Object clone() { return new Vector3D(x, y, z); }

	public String toString() { return "Vector3D(" + x + ", " + y + ", " + z + ")"; }

	public double length() { return Math.sqrt(x*x + y*y + z*z); }

	/** Does nothing if the length is zero. */
	public void normalize() {
		double vlen = length();
		if (vlen != 0.0) {
			x /= vlen;
			y /= vlen;
			z /= vlen;
		}
	}

	public void scale(double s) {
		x *= s;
		y *= s;
		z *= s;
	}

	/** Substracts the given vector to this one. */
	public void substract(Vector3D v) {
		x -= v.x;
		y -= v.y;
		z -= v.z;
	}

	public void add(Vector3D v) {
		x += v.x;
		y += v.y;
		z += v.z;
	}

	public void negate() {
		x = -x;
		y = -y;
		z = -z;
	}

	public double dotProduct(Vector3D v) {
		return x*v.x + y*v.y + z*v.z;
	}

	public Vector3D crossProduct(Vector3D v) {
		return new Vector3D(y*v.z - z*v.y, z*v.x - x*v.z, x*v.y - y*v.x);
	}

	/** @return half arc between vector and v */
	public Vector3D bisect(Vector3D v) {
		Vector3D r = new Vector3D(x + v.x, y + v.y, z + v.z);
		double length = r.length();
		if (length < 1.0e-7) {
			r.set(0, 0, 1);
		} else {
			r.scale(1/length);
		}
		return r;
	}

	public void set(double x, double y, double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public Vector3D rotateAroundAxis(Vector3D axis, double sin, double cos) {
		// obtain a normalized axis first
		Vector3D r = new Vector3D(axis);
		r.normalize();
		return new Vector3D((cos + (1-cos) * r.x * r.x) * x + ((1-cos) * r.x * r.y - r.z * sin) * y + ((1-cos) * r.x * r.z + r.y * sin) * z,
		           ((1-cos) * r.x * r.y + r.z * sin) * x + (cos + (1-cos) * r.y * r.y) * y + ((1-cos) * r.y * r.z - r.x * sin) * z,
		           ((1-cos) * r.y * r.z - r.y * sin) * x + ((1-cos) * r.y * r.z + r.x * sin) * y + (cos + (1-cos) * r.z * r.z) * z);
	}

}
