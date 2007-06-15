
package mpi.sift;

public class Feature {

	static final int FEATURE_DESCRIPTOR_SIZE = 8;
	static final int FEATURE_DESCRIPTOR_ORIENTATION_BINS = 8;
	static final float FEATURE_DESCRIPTOR_ORIENTATION_BIN_SIZE = (float)((2.0 * Math.PI) / FEATURE_DESCRIPTOR_ORIENTATION_BINS);


	private float[][][] desc = new float[FEATURE_DESCRIPTOR_SIZE][FEATURE_DESCRIPTOR_SIZE][FEATURE_DESCRIPTOR_ORIENTATION_BINS];

	private final float[] location = new float[3];
	private final float[] octaveLocation = new float[2];
	private final float[] octaveOffset = new float[3];

	int c, o, s;
	float orientation;

	static float[][] descriptorMask = new float[FEATURE_DESCRIPTOR_SIZE * 4][FEATURE_DESCRIPTOR_SIZE * 4];

	static {
		float two_sq_sigma = FEATURE_DESCRIPTOR_SIZE * FEATURE_DESCRIPTOR_SIZE * 8; 
		for ( int y = FEATURE_DESCRIPTOR_SIZE * 2 - 1; y >= 0; --y )
		{
			float fy = ( float )y + 0.5f;
			for ( int x = FEATURE_DESCRIPTOR_SIZE * 2 - 1; x >= 0; --x )
			{
				float fx = ( float )x + 0.5f;
				float val = (float)Math.exp( -( fy * fy + fx * fx ) / two_sq_sigma );
				descriptorMask[2 * FEATURE_DESCRIPTOR_SIZE - 1 - y][2 * FEATURE_DESCRIPTOR_SIZE - 1 - x] = val;
				descriptorMask[2 * FEATURE_DESCRIPTOR_SIZE + y][2 * FEATURE_DESCRIPTOR_SIZE - 1 - x] = val;
				descriptorMask[2 * FEATURE_DESCRIPTOR_SIZE - 1 - y][2 * FEATURE_DESCRIPTOR_SIZE + x] = val;
				descriptorMask[2 * FEATURE_DESCRIPTOR_SIZE + y][2 * FEATURE_DESCRIPTOR_SIZE + x] = val;

			}
		}
	}

	public Feature() {}

	static public float compare(final Feature f1, final Feature f2) {
		float sq_d = 0;
		for ( int y = 0; y < FEATURE_DESCRIPTOR_SIZE; ++y )
		{
			for ( int x = 0; x < FEATURE_DESCRIPTOR_SIZE; ++x )
			{
				for ( int b = 0; b < FEATURE_DESCRIPTOR_ORIENTATION_BINS; ++b )
				{
					float a = ( f1.desc[y][x][b] - f2.desc[y][x][b] );
					sq_d += a * a;
				}
			}
		}
		return sq_d;
	}

	static public float compareThreshold(final Feature f1, final Feature f2, final float threshold) {

		float sq_d = 0;
		outside: for ( int y = 0; y < FEATURE_DESCRIPTOR_SIZE; ++y )
		{
			for ( int x = 0; x < FEATURE_DESCRIPTOR_SIZE; ++x )
			{
				for ( int b = 0; b < FEATURE_DESCRIPTOR_ORIENTATION_BINS; ++b )
				{
					float a = ( f1.desc[y][x][b] - f2.desc[y][x][b] );
					sq_d += a * a;
					if ( sq_d > threshold )
					{
						sq_d = -1;
						break outside;
					}
				}
			}
		}
		return sq_d;
	}

	public String descriptorToString() {
		StringBuffer sb = new StringBuffer();
		sb.append('(');
		for ( int y = 0; y < FEATURE_DESCRIPTOR_SIZE; ++y )
		{
			for ( int x = 0; x < FEATURE_DESCRIPTOR_SIZE; ++x )
			{
				for ( int b = 0; b < FEATURE_DESCRIPTOR_ORIENTATION_BINS; ++b )
				{
					sb.append(desc[y][x][b]);
					if ( !( y == FEATURE_DESCRIPTOR_SIZE - 1 &&
							x == FEATURE_DESCRIPTOR_SIZE - 1 &&
							b == FEATURE_DESCRIPTOR_ORIENTATION_BINS - 1 ) )
						sb.append(", ");
				}
			}
		}
		return sb.append(")").toString(); 
	}

	public String toString() {
		StringBuffer sb = new StringBuffer();
		//! location as vector <x, y, sigma>
		sb.append("location: <").append(location[2]).append(", ").append(location[1]).append(", ").append(location[0]).append(">\n");

		//! orientation in radians [-PI, PI]
		sb.append("orientation: ").append(orientation).append('\n');
		
		//! for each channel one SIFT descriptor
		sb.append("\ndescriptor: ").append(desc[c]);

		return sb.toString();
	}
}
