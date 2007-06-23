package mpi.fruitfly.registration;

/**
 * <p>Title: </p>
 *
 * <p>Description: </p>
 *
 * <p>Copyright: Copyright (c) 2007</p>
 *
 * <p>Company: </p>
 *
 * <p>License: GPL
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
 * @author Stephan Preibisch
 * @version 1.0
 */

import static mpi.fruitfly.registration.ImageFilter.*;
import static mpi.fruitfly.math.General.*;
import static mpi.fruitfly.general.ImageArrayConverter.*;

import mpi.fruitfly.math.Quicksortable;

import java.util.Iterator;
import java.util.Vector;

import mpi.fruitfly.math.datastructures.*;
import mpi.fruitfly.registration.SIFTProcessor;
import mpi.fruitfly.registration.ImageFilter;

import ij.process.ImageProcessor;
import ij.process.FloatProcessor;
import ij.io.Opener;
import ij.ImagePlus;
import ij.IJ;
import Jama.Matrix;
import Jama.SingularValueDecomposition;
import Jama.EigenvalueDecomposition;

public class SIFTProcessor {
	// @todo make those constants parameters in the user interface
	public static final double MIN_DOG_CONTRAST = 0.03;
	public static final double MAX_CURVATURE_RATIO = 10;
	public static final int FEATURE_DESCRIPTOR_SIZE = 8;

	static final int FEATURE_DESCRIPTOR_ORIENTATION_BINS = 8;

	static final float FEATURE_DESCRIPTOR_ORIENTATION_BIN_SIZE = 2.0f
			* (float) Math.PI / (float) FEATURE_DESCRIPTOR_ORIENTATION_BINS;

	static float[][] descriptorMask = new float[FEATURE_DESCRIPTOR_SIZE * 4][FEATURE_DESCRIPTOR_SIZE * 4];
	static {
		float two_sq_sigma = FEATURE_DESCRIPTOR_SIZE * FEATURE_DESCRIPTOR_SIZE
				* 8;
		for (int y = FEATURE_DESCRIPTOR_SIZE * 2 - 1; y >= 0; --y) {
			float fy = (float) y + 0.5f;
			for (int x = FEATURE_DESCRIPTOR_SIZE * 2 - 1; x >= 0; --x) {
				float fx = (float) x + 0.5f;
				float val = (float) Math.exp(-(fy * fy + fx * fx)
						/ two_sq_sigma);
				descriptorMask[2 * FEATURE_DESCRIPTOR_SIZE - 1 - y][2
						* FEATURE_DESCRIPTOR_SIZE - 1 - x] = val;
				descriptorMask[2 * FEATURE_DESCRIPTOR_SIZE + y][2
						* FEATURE_DESCRIPTOR_SIZE - 1 - x] = val;
				descriptorMask[2 * FEATURE_DESCRIPTOR_SIZE - 1 - y][2
						* FEATURE_DESCRIPTOR_SIZE + x] = val;
				descriptorMask[2 * FEATURE_DESCRIPTOR_SIZE + y][2
						* FEATURE_DESCRIPTOR_SIZE + x] = val;

			}
		}
	}

	private FloatArray2D image;

	private ScaleSpaceData ssd;

	private Vector<Feature> features;
	
	public Vector<Feature> getFeatures()
	{
		return features;
	}

	public class OctaveData {
		FloatArray2D[] gauss;

		FloatArray2D[] laPlace; // !< means difference of Gaussian (DoG)

		FloatArray2D[][] gradient; // !< gradients of the gaussian image
	}

	public class ScaleSpaceData {
		int width;

		int height;

		FloatArray2D backup;

		int OCT_STEPS;

		int minImageSize;

		float[] sigma, sigmaDiff;

		float k, K_MIN1_INV;

		int availableScales = -1;

		Vector<OctaveData> scaleSpace = new Vector<OctaveData>();

		public float get_sigma(int scale, float offset) {
			int o = scale / OCT_STEPS;
			int s = scale % OCT_STEPS;
			return sigma[0]
					* (float) Math.pow(2.0, (float) o + ((float) s + offset)
							/ (float) OCT_STEPS);
		}

		public int getNumOctaves() {
			return scaleSpace.size();
		}

		public OctaveData getLastOctave() {
			return scaleSpace.lastElement();
		}

		public double getLocation(double pos, int scale) {
			int octave = getOctave(scale);
			// pos = Math.pow(2, octave - 1) * (1 + pos);

			if (octave > 1)
				pos = (double) pow(2, octave - 1) * pos
						+ (double) pow(2, octave - 1) - 1.0;
			else if (octave < 1)
				pos = (pos - 1.0) / 2.0;

			return pos;
		}

		public int getOctave(int scale) {
			return scale / OCT_STEPS;
		}

		public FloatArray getLaPlace(int scale) {
			int octave = getOctave(scale);
			int entry = scale % OCT_STEPS;

			return scaleSpace.get(octave).laPlace[entry];
		}

		public FloatArray3D getEnvironment(int x, int y, int scale) {
			FloatArray3D env = new FloatArray3D(3, 3, 3);

			FloatArray2D[] laPlace = new FloatArray2D[3];
			laPlace[0] = (FloatArray2D) getLaPlace(scale - 1);
			laPlace[1] = (FloatArray2D) getLaPlace(scale);
			laPlace[2] = (FloatArray2D) getLaPlace(scale + 1);

			boolean scaleLower = false;
			boolean scaleHigher = false;
			float value;

			boolean debug = false;

			if (x < 0) {
				debug = true;
				x = -x;
				y = -y;

				System.out.println("x: " + x + " y: " + y + " scale: " + scale);
			}

			// check if laPlace step with lower sigma is bigger (other octave)
			if (laPlace[0].width > laPlace[1].width)
				scaleLower = true;

			// check if laPlace step with higher sigma is smaller (other octave)
			if (laPlace[2].width < laPlace[1].width)
				scaleHigher = true;

			if (debug)
				System.out.println("ScaleLower: " + scaleLower
						+ " ScaleHigher: " + scaleHigher);

			for (int xs = x - 1; xs <= x + 1; xs++)
				for (int ys = y - 1; ys <= y + 1; ys++) {
					int xf = 2 - (x - xs + 1);
					int yf = 2 - (y - ys + 1);

					env.set(laPlace[1].getMirror(xs, ys), xf, yf, 1);

					if (scaleLower) {
						value = laPlace[0].getMirror(xs * 2 + 1, ys * 2 + 1);
						env.set(value, xf, yf, 0);
					} else
						env.set(laPlace[0].getMirror(xs, ys), xf, yf, 0);

					if (!scaleHigher) {
						value = laPlace[2].getMirror(xs, ys);
						env.set(value, xf, yf, 2);
					}
				}

			if (scaleHigher) {
				float[][] env2D = new float[3][3];
				float temp;

				if (y % 2 == 1) {
					if (x % 2 == 1) {
						env2D[0][0] = laPlace[2].getMirror(x / 2, y / 2);
						env2D[2][0] = laPlace[2].getMirror(x / 2 + 1, y / 2);
						env2D[0][2] = laPlace[2].getMirror(x / 2, y / 2 + 1);
						env2D[2][2] = laPlace[2]
								.getMirror(x / 2 + 1, y / 2 + 1);
						env2D[1][0] = (env2D[0][0] + env2D[2][0]) / 2;
						env2D[1][2] = (env2D[0][2] + env2D[2][2]) / 2;
					} else {
						env2D[1][0] = laPlace[2].getMirror(x / 2, y / 2);
						env2D[1][2] = laPlace[2].getMirror(x / 2, y / 2 + 1);
						env2D[0][0] = (env2D[1][0] + laPlace[2].getMirror(
								x / 2, y / 2 - 1)) / 2;
						env2D[2][0] = (env2D[1][0] + laPlace[2].getMirror(
								x / 2, y / 2 + 1)) / 2;
						env2D[0][2] = (env2D[1][2] + laPlace[2].getMirror(
								x / 2 + 1, y / 2 - 1)) / 2;
						env2D[2][2] = (env2D[1][2] + laPlace[2].getMirror(
								x / 2 + 1, y / 2 + 1)) / 2;
					}
					env2D[0][1] = (env2D[0][0] + env2D[0][2]) / 2;
					env2D[2][1] = (env2D[2][0] + env2D[2][2]) / 2;
					env2D[1][1] = (env2D[0][1] + env2D[2][1]) / 2;
				} else {
					if (x % 2 == 1) {
						env2D[0][1] = laPlace[2].getMirror(x / 2, y / 2);
						env2D[2][1] = laPlace[2].getMirror(x / 2, y / 2 + 1);
						env2D[0][0] = (env2D[0][1] + laPlace[2].getMirror(
								x / 2 - 1, y / 2 - 1)) / 2;
						env2D[2][0] = (env2D[2][1] + laPlace[2].getMirror(
								x / 2 - 1, y / 2)) / 2;
						env2D[0][2] = (env2D[0][1] + laPlace[2].getMirror(
								x / 2 + 1, y / 2 - 1)) / 2;
						env2D[2][2] = (env2D[2][1] + laPlace[2].getMirror(
								x / 2 + 1, y / 2)) / 2;
						env2D[1][0] = (env2D[0][0] + env2D[2][0]) / 2;
						env2D[1][2] = (env2D[0][2] + env2D[2][2]) / 2;
						env2D[1][1] = (env2D[0][1] + env2D[2][1]) / 2;
					} else {
						env2D[1][1] = laPlace[2].getMirror(x / 2, y / 2);
						temp = laPlace[2].getMirror(x / 2 - 1, y / 2);
						env2D[1][0] = (env2D[1][1] + temp) / 2;
						temp = laPlace[2].getMirror(x / 2, y / 2 - 1);
						env2D[0][1] = (env2D[1][1] + temp) / 2;
						env2D[0][0] = ((temp + laPlace[2].getMirror(x / 2 - 1,
								y / 2 - 1)) / 2 + env2D[1][0]) / 2;
						temp = laPlace[2].getMirror(x / 2 + 1, y / 2);
						env2D[1][2] = (env2D[1][1] + temp) / 2;
						env2D[0][2] = ((temp + laPlace[2].getMirror(x / 2 + 1,
								y / 2 - 1)) / 2 + env2D[0][1]) / 2;
						temp = laPlace[2].getMirror(x / 2, y / 2 + 1);
						env2D[2][1] = (env2D[1][1] + temp) / 2;
						env2D[2][2] = ((temp + laPlace[2].getMirror(x / 2 + 1,
								y / 2 + 1)) / 2 + env2D[1][2]) / 2;
						env2D[2][0] = ((temp + laPlace[2].getMirror(x / 2 - 1,
								y / 2 + 1)) / 2 + env2D[1][0]) / 2;
					}
				}

				for (int xs = 0; xs < 3; xs++)
					for (int ys = 0; ys < 3; ys++)
						env.set(env2D[xs][ys], xs, ys, 2);

			}

			return env;
		}
		
		/**
		 * get the gaussian of scale index "scale"
		 * 
		 * @returns a reference to the image
		 */
		private FloatArray2D getGauss(int scale) {
			int o = scale / OCT_STEPS;
			int s = scale % OCT_STEPS;
			OctaveData octave = scaleSpace.get(o);
			return octave.gauss[ s ];
		}

		/**
		 * get the gradients of gaussian of scale index "scale", generates it on
		 * demand, if not yet available scale will not be checked for efficiency
		 * reasons, so take care that it is in a valid range
		 * 
		 * @returns a reference to the gradients
		 */
		private FloatArray2D[] getGaussGradients(int scale) {
			int o = scale / OCT_STEPS;
			int s = scale % OCT_STEPS;
			OctaveData octave = scaleSpace.get(o);
			if (octave.gradient == null)
			{
				octave.gradient = new FloatArray2D[OCT_STEPS][];
			} 
			
			if (octave.gradient[s] == null ) {
				octave.gradient[s] = ImageFilter.createGradients(octave.gauss[s]);
				//FloatArrayToImagePlus(octave.gradient[s][0], "o: " + o + " s: " + s + "-0", 0, 0).show();
				//FloatArrayToImagePlus(octave.gradient[s][1],  "o: " + o + " s: " + s + "-1", 0, 0).show();
			}
			return octave.gradient[s];
		}
	}

	public class Feature implements Quicksortable {
		public Feature(int x, int y, int scale, int iteration) {
			this.x = x;
			this.y = y;
			this.scale = scale;
			this.iteration = iteration;
		}
		
		public double getQuicksortValue()
		{
			return location[2];
		}

		public boolean hasSameLocation(Feature voxel) {
			if (this.x == voxel.x && this.y == voxel.y
					&& this.scale == voxel.scale)
				return true;
			else
				return false;
		}

		public float[][][] desc;

		public float[] location;

		public int x, y, scale;

		public int iteration;

		public double[][] hessianMatrix3;

		public double[][] hessianMatrix2;

		public double[] derivativeVector;

		public double[] eigenValues;

		public Matrix A = null, B = null, X = null;

		public double xD, yD, scaleD;

		public double EVratio;

		public double laPlaceValue, quadrFuncValue, sumValue, sigma;

		// public int o;
		public float orientation; // !< orientation of the feature [-PI...PI]

		public FloatArray3D env;
		public FloatArray2D pixel_pattern; //!< @todo this is for visualisation purposse onllly, remove it in the running version

		public int getOctave() {
			return this.scale / ssd.OCT_STEPS;
		}

		public Feature clone() {
			Feature clone = new Feature(x, y, scale, iteration);
			clone.location = this.location.clone();
			//clone.A = this.A.clone();
			//clone.B = this.B.clone();
			//clone.X = this.X.clone();
			clone.derivativeVector = this.derivativeVector.clone();
			clone.desc = this.desc.clone();
			//clone.eigenValues = eigenValues.clone();
			clone.env = env.clone();
			clone.EVratio = EVratio;
			clone.scaleD = scaleD;
			clone.xD = xD;
			clone.yD = yD;
			//clone.hessianMatrix2 = hessianMatrix2.clone();
			clone.hessianMatrix3 = hessianMatrix3.clone();
			clone.laPlaceValue = laPlaceValue;
			clone.orientation =orientation;
			clone.quadrFuncValue = quadrFuncValue;
			clone.sumValue = sumValue;
			clone.scale = scale;
			clone.sigma = sigma;
			
			return clone;
		}
	}

	public class FeatureMatch {
		public Feature f1, f2;

		public float sq_distance;
	}

	static int flip_range(int a, int mod) {
		int p = 2 * mod;
		if (a < 0)
			a = p + a % p;
		else if (a >= p)
			a = a % p;
		if (a >= mod)
			a = mod - a % mod - 1;
		return a;
	}

	/**
	 * get the size x size environment of L(y, x) in the specified gradient of
	 * gaussian smoothed image used for a features orientation assignment
	 * 
	 * @todo flip gradient orientations for coordinates outside the range (
	 *       currently, values out of range are filled with the closest values
	 *       inside range )
	 */
	private FloatArray2D[] createGradientROI(int scale, int y, int x, int size) {
		FloatArray2D[] src = ssd.getGaussGradients(scale);
		FloatArray2D[] dst = new FloatArray2D[2];
		dst[0] = new FloatArray2D(size, size);
		dst[1] = new FloatArray2D(size, size);
		int half_size = size / 2;
		int p = size * size - 1;
		for (int yi = size - 1; yi >= 0; --yi) {
			int ra_y = src[0].width
					* Math.max(0, Math.min(src[0].height - 1, y + yi - half_size));
			int ra_x = ra_y + min(x, src[0].width - 1);

			for (int xi = size - 1; xi >= 0; --xi) {
				int pt = Math.max(ra_y, Math.min(ra_y + src[0].width - 2, ra_x + xi - half_size));
				dst[0].data[p] = src[0].data[pt];
				dst[1].data[p] = src[1].data[pt];
				--p;
			}
		}
		return dst;
	}
	
	/**
	 * sample the scaled and rotated gradients in a region around a features
	 * location, the regions size is defined priorly through the constant values
	 * sq( FEATURE_DESCRIPTOR_SIZE * 4 ) ( 4x4 subregions )
	 */
	void sampleFeatureROI( Feature feature, FloatArray2D region, FloatArray2D image ) {
		float cos_o = ( float )Math.cos( feature.orientation);
		float sin_o = ( float )Math.sin( feature.orientation);
		float octave_sigma = get_octave_sigma(
				feature.location[ 2 ], //!< Sigma of the feature
				feature.getOctave()  //!< Octave index
		);

		// ! sample the region arround the keypoint location
		for (int y = 4 * FEATURE_DESCRIPTOR_SIZE - 1; y >= 0; --y) {
			float ys = ((float) y - 2.0f * (float) FEATURE_DESCRIPTOR_SIZE + 0.5f)
					* octave_sigma; // !< scale y around 0,0
			for (int x = 4 * FEATURE_DESCRIPTOR_SIZE - 1; x >= 0; --x) {
				float xs = ((float) x - 2.0f * (float) FEATURE_DESCRIPTOR_SIZE + 0.5f)
						* octave_sigma; // !< scale x around 0,0
				float yr = cos_o * ys + sin_o * xs; // !< rotate y around 0,0
				float xr = cos_o * xs - sin_o * ys; // !< rotate x around 0,0

				int yg = flip_range((int) (Math.round(yr + (float) feature.y
						+ feature.yD)), image.height - 1); //!< translate ys to  sample y position in the gradient image
				int xg = flip_range((int) (Math.round(xr + (float) feature.x
						+ feature.xD)), image.width - 1); //!< translate xs to sample x position in the gradient image

				// ! get the samples
				int region_p = 4 * FEATURE_DESCRIPTOR_SIZE * y + x;
				int image_p = image.width * yg + xg;

				region.data[region_p] = image.data[image_p]; // !< weight the gradients
			}
		}
	}

	/**
	 * sample the scaled and rotated gradients in a region around a features
	 * location, the regions size is defined priorly through the constant values
	 * sq( FEATURE_DESCRIPTOR_SIZE * 4 ) ( 4x4 subregions )
	 */
	void sampleFeatureGradientROI(Feature feature, FloatArray2D[] region, FloatArray2D[] gradients) {
		float cos_o = (float) Math.cos(feature.orientation);
		float sin_o = (float) Math.sin(feature.orientation);
		float octave_sigma = get_octave_sigma(feature.location[2], //!< Sigma of the feature
				feature.getOctave() // !< Octave index
		);

		// ! sample the region arround the keypoint location
		for (int y = 4 * FEATURE_DESCRIPTOR_SIZE - 1; y >= 0; --y) {
			float ys = ((float) y - 2.0f * (float) FEATURE_DESCRIPTOR_SIZE + 0.5f)
					* octave_sigma; // !< scale y around 0,0
			for (int x = 4 * FEATURE_DESCRIPTOR_SIZE - 1; x >= 0; --x) {
				float xs = ((float) x - 2.0f * (float) FEATURE_DESCRIPTOR_SIZE + 0.5f)
						* octave_sigma; // !< scale x around 0,0
				float yr = cos_o * ys + sin_o * xs; // !< rotate y around 0,0
				float xr = cos_o * xs - sin_o * ys; // !< rotate x around 0,0

				/*
				 * // repeat border values int yg = range( ( int )( round( yr + (
				 * float )feature->octaveLocation[0] + feature->octaveOffset[1] ) ),
				 * 0, gradients[0]->height - 1 ); //!< translate ys to sample y
				 * position in the gradient image int xg = range( ( int )(
				 * round( xr + ( float )feature->octaveLocation[1] +
				 * feature->octaveOffset[2] ) ), 0, gradients[0]->width - 1 );
				 * //!< translate xs to sample x position in the gradient image
				 */

				// flip_range at borders
				// @todo for now the gradients orientations do not flip outside
				// the image even though they should do it. But would this
				// improve the result?
				int yg = flip_range((int) (Math.round(yr + (float) feature.y
						+ feature.yD)), gradients[0].height - 1); // !<
																	// translate
																	// ys to
																	// sample y
																	// position
																	// in the
																	// gradient
																	// image
				int xg = flip_range((int) (Math.round(xr + (float) feature.x
						+ feature.xD)), gradients[0].width - 1); // !<
																	// translate
																	// xs to
																	// sample x
																	// position
																	// in the
																	// gradient
																	// image

				// ! get the samples
				int region_p = 4 * FEATURE_DESCRIPTOR_SIZE * y + x;
				int gradient_p = gradients[0].width * yg + xg;

				region[0].data[region_p] = gradients[0].data[gradient_p]
						* descriptorMask[y][x]; // !< weight the gradients
				region[1].data[region_p] = gradients[1].data[gradient_p]
						- feature.orientation;
				// !< rotate the gradients orientation it with respect to the
				// features orientation
			}
		}
	}

	/**
	 * calculate and assign SIFT descriptor to the given feature
	 */
	void assignFeatureDescriptor( Feature feature ) {
		
		FloatArray2D[] region = new FloatArray2D[2];
		region[0] = new FloatArray2D(4 * FEATURE_DESCRIPTOR_SIZE,
				4 * FEATURE_DESCRIPTOR_SIZE);
		region[1] = new FloatArray2D(4 * FEATURE_DESCRIPTOR_SIZE,
				4 * FEATURE_DESCRIPTOR_SIZE);
		FloatArray2D[] gradients = ssd.getGaussGradients( feature.scale ); //!< keep in mind that this "scale" means a scaleIndex in scalespace
		
		sampleFeatureGradientROI( feature, region, gradients );
		//FloatArrayToImagePlus( region[0], "Region Gradients", 0, 0 ).show();
		
		/*
		 * @todo this is for visualisation only, remove it in a productive version
		 * ------------------------------------------------------------------------
		 */
		feature.pixel_pattern = new FloatArray2D( 4 * FEATURE_DESCRIPTOR_SIZE, 4 * FEATURE_DESCRIPTOR_SIZE );
		//sampleFeatureROI(feature, feature.pixel_pattern, ssd.getGauss( feature.scale ) );
		//sampleFeatureROI(feature, feature.pixel_pattern, gradients[ 0 ] );
		
		/*
		 * ------------------------------------------------------------------------
		 */
		
		feature.desc = new float[FEATURE_DESCRIPTOR_SIZE][FEATURE_DESCRIPTOR_SIZE][FEATURE_DESCRIPTOR_ORIENTATION_BINS];

		// build the orientation histograms of 4x4 subregions
		for (int y = FEATURE_DESCRIPTOR_SIZE - 1; y >= 0; --y) {
			int yp = FEATURE_DESCRIPTOR_SIZE * 16 * y;
			for (int x = FEATURE_DESCRIPTOR_SIZE - 1; x >= 0; --x) {
				int xp = 4 * x;
				for (int ysr = 3; ysr >= 0; --ysr) {
					int ysrp = 4 * FEATURE_DESCRIPTOR_SIZE * ysr;
					for (int xsr = 3; xsr >= 0; --xsr) {
						float bin_location = (region[1].data[yp + xp + ysrp
								+ xsr] + (float) Math.PI)
								/ (float) FEATURE_DESCRIPTOR_ORIENTATION_BIN_SIZE;
						
						int bin_b = ((int)Math.floor(bin_location - 0.5) + 2 * FEATURE_DESCRIPTOR_ORIENTATION_BINS ) % FEATURE_DESCRIPTOR_ORIENTATION_BINS;
						int bin_t = ((int)Math.floor(bin_location + 0.5) + 2 * FEATURE_DESCRIPTOR_ORIENTATION_BINS ) % FEATURE_DESCRIPTOR_ORIENTATION_BINS;

						float d_b = bin_location - (float) bin_b - 0.5f;
						float d_t = (float) bin_t - bin_location + 0.5f;

						//float temp = region[0].data[yp + xp + ysrp + xsr] * (1 - d_b);
						
						//System.out.println( "bin_b = " + bin_b + ", bin_t = " + bin_t );
						
						feature.desc[y][x][bin_b % FEATURE_DESCRIPTOR_ORIENTATION_BINS] += region[0].data[yp + xp + ysrp + xsr] * (1 - d_b);
					}
				}
			}
		}
		// normalize, cut above 0.2 and renormalize
		float max_bin_val = 0;
		for (int y = FEATURE_DESCRIPTOR_SIZE - 1; y >= 0; --y) {
			for (int x = FEATURE_DESCRIPTOR_SIZE - 1; x >= 0; --x) {
				for (int b = FEATURE_DESCRIPTOR_ORIENTATION_BINS - 1; b >= 0; --b) {
					if (feature.desc[y][x][b] > max_bin_val)
						max_bin_val = feature.desc[y][x][b];
				}
			}
		}
		max_bin_val /= 0.2;
		for (int y = FEATURE_DESCRIPTOR_SIZE - 1; y >= 0; --y) {
			for (int x = FEATURE_DESCRIPTOR_SIZE - 1; x >= 0; --x) {
				for (int b = FEATURE_DESCRIPTOR_ORIENTATION_BINS - 1; b >= 0; --b) {
					feature.desc[y][x][b] = (float) Math.min(1.0,
							feature.desc[y][x][b] / max_bin_val);
				}
			}
		}

		// for visualization we preserve the gradient regions in the feature
		// please do not forget to release the regions here after removing this
		/*
		 * feature.gradientROI = region;
		 */
		return;
	}

	/**
	 * process all found feature candidates, find orientation, duplicate
	 * candidates with more than one orientation, ...
	 */
	int processCandidates() {
		int num_candidates = this.features.size();
		//System.out.println( "num_candidates: " + num_candidates + " " );
		for ( int i = 0; i < num_candidates; ++i ) {
			//System.out.println( "albert! -  processing candidate " + i + " " );
			processSingleCandidate(this.features.get(i));
		}
		//quicksort( features, 0, features.size()-1 );
		return features.size();
	}

	/**
	 * assign orientation to the given feature, if more than one orientations
	 * found, duplicate the feature for each orientation
	 */
	void processSingleCandidate(Feature feature) {
		final int ORIENTATION_BINS = 36;
		final float ORIENTATION_BIN_SIZE = 2.0f * (float) Math.PI
				/ (float) ORIENTATION_BINS;
		float[] histogram_bins = new float[ORIENTATION_BINS];

		float octave_sigma = get_octave_sigma(feature.location[2], //!< Sigma of the feature
				feature.getOctave() // !< Octave index
		);

		// ! create a circular gaussian window with sigma 1.5 times that of the
		// feature
		FloatArray2D gaussianMask = ImageFilter
				.create_gaussian_kernel_2D_offset(octave_sigma * 1.5f, //!< octave sigma
						(float) feature.xD, (float) feature.yD, false);
		//FloatArrayToImagePlus( gaussianMask, "gaussianMask", 0, 0 ).show();

		// ! get the gradients in a region arround the keypoints location
		FloatArray2D[] gradientROI = createGradientROI(feature.scale,
				feature.x, feature.y, gaussianMask.width);
		//FloatArrayToImagePlus( gradientROI[0], "gradientROI-0", 0, 0 ).show();
		//FloatArrayToImagePlus( gradientROI[1], "gradientROI-1", 0, 0 ).show();

		// ! and mask this region with the precalculated gaussion window
		for (int i = 0; i < gradientROI[0].data.length; ++i) {
			gradientROI[0].data[i] *= gaussianMask.data[i];
		}
		//FloatArrayToImagePlus( gradientROI[0], "gaussianMaskedGradientROI", 0, 0 ).show();


		// ! build an orientation histogram of the region
		for (int p = 0; p < gradientROI[0].data.length; ++p) {
			int bin = (int) Math.floor((gradientROI[1].data[p] + Math.PI)
					/ ORIENTATION_BIN_SIZE);
			histogram_bins[bin] += gradientROI[0].data[p];
		}

		// find the dominant orientation and interpolate it with respect to its
		// two neighbours
		int max_i = 0;
		for (int i = 0; i < ORIENTATION_BINS; ++i) {
			if (histogram_bins[i] > histogram_bins[max_i])
				max_i = i;
		}
		float[] env = new float[3];

		// interpolate oriantation
		// estimate the offset from center of the parabolic extremum of the
		// taylor
		// series through env[1], derivatives via central difference and laplace
		env[0] = histogram_bins[(max_i + ORIENTATION_BINS - 1)
				% ORIENTATION_BINS];
		env[1] = histogram_bins[max_i];
		env[2] = histogram_bins[(max_i + 1) % ORIENTATION_BINS];
		float offset = (env[0] - env[2]) / 2 / (env[0] - 2 * env[1] + env[2]);
		feature.orientation = ((float) max_i + 0.5f + offset)
				* ORIENTATION_BIN_SIZE - (float) Math.PI;
		
		
		// assign descriptor
		assignFeatureDescriptor( feature );
		
		//System.out.println( "descriptor assigned" );

		for (int i = 0; i < ORIENTATION_BINS; ++i) {
			if (i != max_i && (max_i + 1) % ORIENTATION_BINS != i
					&& (max_i - 1 + ORIENTATION_BINS) % ORIENTATION_BINS != i
					&& histogram_bins[i] > 0.8 * histogram_bins[max_i]) {
				
				// interpolate oriantation estimate the offset from center of the parabolic extremum of
				// the taylor series through env[1], derivatives via central difference and laplace
				env[0] = histogram_bins[(i + ORIENTATION_BINS - 1)
						% ORIENTATION_BINS];
				env[1] = histogram_bins[i];
				env[2] = histogram_bins[(i + 1) % ORIENTATION_BINS];
				
				if ( env[ 0 ] < env[ 1 ] && env[ 2 ] < env[ 1 ])
				{
					Feature f = feature.clone();
				
					float offset_i = (env[0] - env[2]) / 2 / (env[0] - 2 * env[1] + env[2]);
					f.orientation = ((float) i + 0.5f + offset_i) * ORIENTATION_BIN_SIZE - (float) Math.PI;

					// assign descriptor
					assignFeatureDescriptor(f);

					features.addElement(f);
				}
			}
		}
		return;
	}

	static float compare(Feature f1, Feature f2) {
		float sq_d = 0;
		for (int y = 0; y < FEATURE_DESCRIPTOR_SIZE; ++y) {
			for (int x = 0; x < FEATURE_DESCRIPTOR_SIZE; ++x) {
				for (int b = 0; b < FEATURE_DESCRIPTOR_ORIENTATION_BINS; ++b) {
					sq_d += Math.pow(f1.desc[y][x][b] - f2.desc[y][x][b], 2);
				}
			}
		}
		return sq_d;
	}

	/**
	 * 
	 * get the square distance of two features descriptors, stop and return -1
	 * if the square distance > threshold
	 */
	static float compareThreshold(Feature f1, Feature f2,
			float threshold) {
		float sq_d = 0;
		breakCT: for (int y = 0; y < FEATURE_DESCRIPTOR_SIZE; ++y) {
			for (int x = 0; x < FEATURE_DESCRIPTOR_SIZE; ++x) {
				for (int b = 0; b < FEATURE_DESCRIPTOR_ORIENTATION_BINS; ++b) {
					sq_d += Math.pow(f1.desc[y][x][b] - f2.desc[y][x][b], 2);
					if (sq_d > threshold) {
						sq_d = -1;
						break breakCT;
					}
				}
			}
		}
		return sq_d;
	}

	/**
	 * 
	 * find similar features to f having descriptors with square distance lower
	 * than min_sq_d
	 * 
	 * Setting similarScale restricts the possible candidates to features with
	 * akin size. This is useful for cases wher you were looking for features of
	 * variuos sizes but no scale was introduced during transformation.
	 */
	int getSimilarFeatures(Feature f, float min_sq_d,	//!< similarity criterion, square of the minimum distance of two descriptors
			Vector<Feature> siblings,					//!< all features matching the similarity criterion
			boolean similarScale							//!< true means, that
	) {
		// DEFAULT is similarScale = false;
		siblings.clear();
		int num_features = features.size();
		if (num_features < 1)
			return 0; // no features there

		Iterator<Feature> it = features.iterator();
		while (it.hasNext()) {
			Feature fi = it.next();
			if (fi != f) {
				float sq_d = compare(fi, f);
				if (sq_d != -1 && sq_d < min_sq_d) {
					siblings.addElement(fi);
				}
			}
		}

		return siblings.size();
	}

	/**
	 * 
	 * find the <num> most similar features to f
	 * 
	 * Setting similarScale restricts the possible candidates to features with
	 * akin size. This is useful for cases you were looking for features of
	 * various sizes but no scale was introduced during transformation.
	 */
	int getSimilarFeatures(
			Feature f,							//!< the tested candidate 
			int num,							//!< the maximal number of correspondences
			Vector<FeatureMatch> siblings,		//!< found correspondences
			boolean similarScale )
	{
		final float SIMILAR_SCALE_TOLERANCE = 0.33f;

		int num_features = features.size();
		if ( num_features < 1 )
			return 0; // no features there

		float max_sq_d = Float.POSITIVE_INFINITY;

		for ( Feature fi : features )
		{
			if ( similarScale && Math.abs( f.location[2] - fi.location[ 2 ] ) > f.location[2] * SIMILAR_SCALE_TOLERANCE )
				continue;
			if (fi != f)
			{
				if (similarScale
						&& Math.abs(fi.location[2] - f.location[2]) > f.location[2]
								* SIMILAR_SCALE_TOLERANCE)
					continue;
				else {
					float sq_d = compareThreshold(fi, f, max_sq_d);
					if (sq_d != -1 && sq_d < max_sq_d) {
						boolean inserted = false;
						FeatureMatch fs = new FeatureMatch();
						fs.f1 = f;
						fs.f2 = fi;
						fs.sq_distance = sq_d;
						Iterator<FeatureMatch> jt = siblings.iterator();
						int j = 0;
						while (jt.hasNext()) {
							FeatureMatch fj = jt.next();
							if (fj.sq_distance > sq_d) {
								siblings.insertElementAt(fs, j);
								inserted = true;
								break;
							}
							++j;
						}
						if (!inserted)
							siblings.addElement(fs);
						if (siblings.size() > num) {
							siblings.removeElement(siblings.lastElement());
							max_sq_d = siblings.lastElement().sq_distance;
						}
					}
				}
			}
		}

		return siblings.size();
	}

	/**
	 * find matching features of two feature processors
	 */
	public static int matchFeatures( SIFTProcessor fp1, SIFTProcessor fp2,
			Vector< FeatureMatch > matches, //!< contains FeatureMatch instances
			boolean similarScale )
	{
		for ( Feature fi : fp1.features )
		{
			Vector< FeatureMatch > tempMatches = new Vector< FeatureMatch >();
			fp2.getSimilarFeatures( fi, 2, tempMatches, similarScale );
			if ( tempMatches.size() == 2
					&& tempMatches.firstElement().sq_distance
							/ tempMatches.lastElement().sq_distance < 0.8 ) {
				matches.addElement( tempMatches.firstElement() );
				System.out.println( "matches " + matches.size() );
			}
			tempMatches.clear();
		}

		// ! remove ambiguous matchings
		for ( int i = 0; i < matches.size(); )
		{
			boolean amb = false;
			for ( int j = i + 1; j < matches.size(); )
			{
				FeatureMatch fm = matches.get( i );
				if ( fm.f2.equals( matches.get( j ).f2 ) )
				{
					amb = true;
					matches.removeElementAt( j );
				}
				else ++j;
			}
			if ( amb )
			{
				System.out.println( "removing ambiguous match at " + i );
				matches.removeElementAt( i );
			}
			else ++i;
		}

		return matches.size();

	}

	public SIFTProcessor(ImageProcessor ip) {
		image = ImageToFloatArray2D(ip.convertToFloat());
	}

	/**
	 * start feature processing for an image
	 */
	public int run() {
		ssd = buildScaleSpace(image, 1F, // !< Image sigma
				1.6F, // !< Initial sigma
				3, // !< laPlace steps per octave
				16); // !< Feature descriptor size
		// fill in the features Vector and get how many it found

		//
		// extract points
		//
		this.features = extractPoints(ssd);

		// process candidates
		int processed_candidates = processCandidates();
		return processed_candidates;
	}

	public SIFTProcessor(String image) {
		ImagePlus imp = new Opener().openImage(image);
		imp.show();

		FloatProcessor fp = (FloatProcessor) imp.getProcessor();

		/*
		 * fp.setInterpolate(true); FloatProcessor fpBig =
		 * (FloatProcessor)fp.resize(fp.getWidth() * 2, fp.getHeight() * 2);
		 */

		//
		// build the scale space
		//
		ssd = buildScaleSpace(ImageToFloatArray2D(fp), 1F, 1.6F, 3, 16);

		if (ssd == null)
			return;

		//
		// extract points
		//
		Vector features = extractPoints(ssd);
	}

	private FloatArray2D createUpScaledImage(FloatArray2D normal) {
		/*
		 * * linear interpolation for every 2*n-th pixel (not every 2*n+1-th
		 * pixel!) * dest's width will be 2*src->width (height similar) *
		 * height_mod must be dest->height%2 except 0 is predefined (width
		 * similar)
		 */

		FloatArray2D upscale = new FloatArray2D(normal.width * 2,
				normal.height * 2);

		// first four pixel in dest = first pixel in src
		float value = normal.get(0, 0);
		upscale.set(value, 0, 0);
		upscale.set(value, 1, 0);
		upscale.set(value, 0, 1);
		upscale.set(value, 1, 1);

		// first two rows in dest
		for (int x = 1; x < normal.width; ++x) {
			value = (normal.get(x, 0) + normal.get(x - 1, 0)) / 2;
			upscale.set(value, 2 * x, 0);
			upscale.set(value, 2 * x, 1);

			value = normal.get(x, 0);
			upscale.set(value, 2 * x + 1, 0);
			upscale.set(value, 2 * x + 1, 1);
		}

		// all the other rows
		for (int y = 1; y < normal.height; ++y) {
			// first four pixel in dest's row
			value = (normal.get(0, y) + normal.get(0, y - 1)) / 2;
			upscale.set(value, 0, 2 * y);
			upscale.set(value, 1, 2 * y);

			value = normal.get(0, y);
			upscale.set(value, 0, 2 * y + 1);
			upscale.set(value, 1, 2 * y + 1);

			for (int x = 1; x < normal.width; ++x) {
				value = normal.get(x, y);
				upscale.set(value, 2 * x + 1, 2 * y + 1);

				value = (normal.get(x, y) + normal.get(x - 1, y)) / 2;
				upscale.set(value, 2 * x, 2 * y + 1);

				value = (normal.get(x, y) + normal.get(x, y - 1)) / 2;
				upscale.set(value, 2 * x + 1, 2 * y);

				value = (upscale.get(2 * x - 1, 2 * y) + upscale.get(2 * x + 1,
						2 * y)) / 2;
				upscale.set(value, 2 * x, 2 * y);
			}
		}

		return upscale;
	}

	private Vector<Feature> extractPoints(ScaleSpaceData ssd) {
		// we can analyze all La Place layers exept the first and the last one
		// (-2)
		ssd.availableScales = ssd.scaleSpace.size() * ssd.OCT_STEPS;

		// FloatArray2D roi = new FloatArray2D(ssd.backup.width,
		// ssd.backup.height);

		Vector<Feature> features = new Vector();

		for (int scale = 1; scale < ssd.availableScales - 1; scale++) {
			//System.out.println(scale);

			FloatArray2D[] laPlace = new FloatArray2D[3];
			laPlace[0] = (FloatArray2D) ssd.getLaPlace(scale - 1);
			laPlace[1] = (FloatArray2D) ssd.getLaPlace(scale);
			laPlace[2] = (FloatArray2D) ssd.getLaPlace(scale + 1);

			for (int x = 1; x < laPlace[1].width - 1; x++)
				for (int y = 1; y < laPlace[1].height - 1; y++)
					if (isSpecialPoint(ssd.getEnvironment(x, y, scale))) {
						/*
						 * roi.set(128 - scale, (int)(ssd.getLocation(x,
						 * scale)+0.5), (int)(ssd.getLocation(y, scale)+0.5));
						 */

						// roi.set(110 - scale,x,y);
						/*
						 * if (x == 79 && y == 82) {
						 * printEnvironment(ssd.getEnvironment(-x, -y, scale));
						 * System.out.println(isSpecialPoint(ssd.getEnvironment(x,
						 * y, scale))); }
						 * 
						 * if (x == 79 && y == 83) {
						 * printEnvironment(ssd.getEnvironment(-x, -y, scale));
						 * System.out.println(isSpecialPoint(ssd.getEnvironment(x,
						 * y, scale))); try { do { Thread.sleep(1000);
						 * }while(true);
						 *  } catch (Exception e){}; }
						 */

						// System.out.println(x + " " + y);
						int xs = x;
						int ys = y;
						int scales = scale;

						boolean foundStableMaxima = false, pointsValid = true;
						int count = 0;

						Feature resultVoxel = null;

						do {
							Feature currentVoxel = new Feature(xs,
									ys, scales, count);
							count++;

							//
							// get the environment of the pixel
							//
							currentVoxel.env = ssd.getEnvironment(xs, ys,
									scales);

							// find a fitted extremum and if the extremum is
							// shifted more than 0.5 in one or more direction we
							// test
							// wheather it is better there
							// this did not work out to be very stable, that's
							// why we just take those positions which are
							// within a range of 0...1,5 of the found maxima in
							// the laplcae space

							//
							// fill hessian matrix with second derivatives
							//
							// hessianMatrix = computeHessianMatrix4(laPlace,
							// xs, ys, zs, is);
							currentVoxel.hessianMatrix3 = computeHessianMatrix(currentVoxel.env);

							//
							// Invert hessian Matrix
							//
							currentVoxel.A = computePseudoInverseMatrix(
									new Matrix(currentVoxel.hessianMatrix3),
									0.001);

							// cannot inverse matrix properly
							if (currentVoxel.A == null) {
								// System.out.println("Cannot compute inverse of
								// Hessian Matrix!");
								continue;
							}

							//
							// fill first derivate vector
							//

							currentVoxel.derivativeVector = computeDerivativeVector(currentVoxel.env);
							currentVoxel.B = new Matrix(
									currentVoxel.derivativeVector, 3);

							//
							// compute the extremum of the quadratic fit
							//

							currentVoxel.X = (currentVoxel.A.uminus())
									.times(currentVoxel.B);

							currentVoxel.xD = currentVoxel.X.get(0, 0);
							currentVoxel.yD = currentVoxel.X.get(1, 0);
							currentVoxel.scaleD = currentVoxel.X.get(2, 0);

							//
							// check all directions for changes
							//
							foundStableMaxima = true;

							if (Math.abs(currentVoxel.xD) > 0.5) {
								xs += sign(currentVoxel.xD);
								foundStableMaxima = false;
							}

							if (Math.abs(currentVoxel.yD) > 0.5) {
								ys += sign(currentVoxel.yD);
								foundStableMaxima = false;
							}

							if (Math.abs(currentVoxel.scaleD) > 0.5) {
								int oldOctave = ssd.getOctave(scales);

								scales += sign(currentVoxel.scaleD);
								foundStableMaxima = false;

								int newOctave = ssd.getOctave(scales);

								if (newOctave > oldOctave) {
									xs /= 2;
									ys /= 2;
								} else if (newOctave < oldOctave) {
									xs = xs * 2 + 1;
									ys = ys * 2 + 1;
								}
							}

							//
							// check validity of new point
							//
							pointsValid = true;

							if (!foundStableMaxima)
								if (xs == 0 || xs == laPlace[1].width - 1
										|| ys == 0
										|| ys == laPlace[1].height - 1
										|| scales == 0
										|| scales == ssd.availableScales - 1)
									pointsValid = false;

							resultVoxel = currentVoxel;

						} while (count <= 5 && !foundStableMaxima
								&& pointsValid);

						// could not invert hessian matrix properly
						if (resultVoxel == null || resultVoxel.A == null)
							continue;

						// did not found a stable maxima
						if (!foundStableMaxima)
							continue;

						resultVoxel.quadrFuncValue = 0;

						for (int j = 0; j < 3 /* 4 */; j++)
							resultVoxel.quadrFuncValue += resultVoxel.X.get(j,
									0)
									* resultVoxel.derivativeVector[j];
						resultVoxel.quadrFuncValue /= 2d;

						resultVoxel.laPlaceValue = resultVoxel.env.get(1, 1, 1);
						resultVoxel.sumValue = resultVoxel.quadrFuncValue
								+ resultVoxel.laPlaceValue;

						// reject pixels with low contrast and significant principal curvature ratio
						if (Math.abs(resultVoxel.sumValue) < MIN_DOG_CONTRAST || !this.curvatureFilter( resultVoxel.hessianMatrix3 ) ) {
							resultVoxel = null;
							continue;
						}
						
						// now reject where curvatures are not equal enough in
						// all directions
						/*resultVoxel.hessianMatrix2 = new double[2][2];
						resultVoxel.hessianMatrix2[0][0] = resultVoxel.hessianMatrix3[0][0];
						resultVoxel.hessianMatrix2[1][0] = resultVoxel.hessianMatrix3[1][0];
						resultVoxel.hessianMatrix2[0][1] = resultVoxel.hessianMatrix3[0][1];
						resultVoxel.hessianMatrix2[1][1] = resultVoxel.hessianMatrix3[1][1];
						resultVoxel.eigenValues = computeEigenValues(resultVoxel.hessianMatrix2);

						// there were imaginary numbers for the eigenvectors
						// -> bad!
						if (resultVoxel.eigenValues == null) {
							resultVoxel = null;
							continue;
						}

						// compute ratio of the eigenvalues
						if (resultVoxel.eigenValues[0] >= resultVoxel.eigenValues[1])
							resultVoxel.EVratio = resultVoxel.eigenValues[1]
									/ resultVoxel.eigenValues[0];
						else
							resultVoxel.EVratio = resultVoxel.eigenValues[0]
									/ resultVoxel.eigenValues[1];

						if (resultVoxel.EVratio < 0.5)
							continue;*/

						// update positions according to the scale

						/*
						 * roi.set((float)Math.abs(resultVoxel.sumValue),
						 * (int)(ssd.getLocation(xs+resultVoxel.xD, scales)),
						 * (int)(ssd.getLocation(ys+resultVoxel.yD, scales)));
						 * 
						 * if ( (float)Math.abs(resultVoxel.sumValue) > 10) {
						 * System.out.println("sumvalue: " +
						 * resultVoxel.sumValue);
						 * System.out.println("eigenvalues: " +
						 * resultVoxel.eigenValues[0] + " " +
						 * resultVoxel.eigenValues[1]);
						 * System.out.println("eigenvalue ratio: " +
						 * resultVoxel.EVratio); System.out.println("Final
						 * scaled: " + ssd.getLocation(xs + resultVoxel.xD,
						 * scales) + " " + ssd.getLocation(ys + resultVoxel.yD,
						 * scales)); System.out.println("Grid Pos: " + xs + " " +
						 * ys + " " + scales); System.out.println("Double
						 * offsets: " + resultVoxel.xD + " " + resultVoxel.yD + " " +
						 * resultVoxel.scaleD); System.out.println("Final
						 * unscaled: " + (xs + resultVoxel.xD) + " " + (ys +
						 * resultVoxel.yD) + " " + (scales +
						 * resultVoxel.scaleD)); System.out.println(); }
						 */
						// WRONG!!!!
						// resultVoxel.sigma = ssd.sigma[scale];
						resultVoxel.location = new float[] {
								(float) ssd.getLocation(xs + resultVoxel.xD,scale),
								(float) ssd.getLocation(ys + resultVoxel.yD,scale),
								ssd.get_sigma(scales,(float) resultVoxel.scaleD) };

						// resultVoxel.o = ssd.getOctave(scales); // get the
						// index of the octave for the given scale"s"

						features.addElement(resultVoxel);
					}
		}

		System.out.println("done");
		// FloatArrayToImagePlus(roi, "DGFSDG",0,0).show();

		return features;
	}

	boolean curvatureFilter( double[][] hessianMatrix )                                                                                      
	{                                                                                                                                             
	    //det = dxx * dyy - ( dxy )^2
	    double det = hessianMatrix[ 0 ][0] * hessianMatrix[1][1] - hessianMatrix[0][1] * hessianMatrix[0][1];

	    //trace = dxx + dyy
	    double trace = hessianMatrix[0][0] + hessianMatrix[1][1];

	    return trace * trace / det < MAX_CURVATURE_RATIO;                                                                                         
	}                                                                                                  

	private Matrix computePseudoInverseMatrix(Matrix M, double threshold) {
		SingularValueDecomposition svd = new SingularValueDecomposition(M);

		Matrix U = svd.getU(); // U Left Matrix
		Matrix S = svd.getS(); // W
		Matrix V = svd.getV(); // VT Right Matrix

		double temp;

		// invert S
		for (int j = 0; j < S.getRowDimension(); j++) {
			temp = S.get(j, j);
			if (temp < threshold)
				return null;
			else
				temp = 1 / temp;
			S.set(j, j, temp);
		}
		// done

		// transponse U
		U = U.transpose();

		//
		// compute result
		//
		return ((V.times(S)).times(U));
	}

	private double[] computeEigenValues(double[][] matrix) {
		Matrix M = new Matrix(matrix);
		EigenvalueDecomposition E = new EigenvalueDecomposition(M);

		double[] result = E.getImagEigenvalues();

		boolean found = false;

		for (double im : result)
			if (im > 0)
				found = true;

		if (found)
			return null;
		else
			return E.getRealEigenvalues();
	}

	private double[] computeDerivativeVector(FloatArray3D env) {
		double[] derivativeVector = new double[3];

		// x
		derivativeVector[0] = (env.get(2, 1, 1) - env.get(0, 1, 1)) / 2;

		// y
		derivativeVector[1] = (env.get(1, 2, 1) - env.get(1, 0, 1)) / 2;

		// z
		derivativeVector[2] = (env.get(1, 1, 2) - env.get(1, 1, 0)) / 2;

		return derivativeVector;

	}

	private double[][] computeHessianMatrix(FloatArray3D env) {
		double[][] hessianMatrix = new double[3][3]; // zeile, spalte

		double temp = 2 * env.get(1, 1, 1);

		// xx
		hessianMatrix[0][0] = env.get(2, 1, 1) - temp + env.get(0, 1, 1);

		// yy
		hessianMatrix[1][1] = env.get(1, 2, 1) - temp + env.get(1, 0, 1);

		// scale scale
		hessianMatrix[2][2] = env.get(1, 1, 2) - temp + env.get(1, 1, 0);

		// xy
		hessianMatrix[0][1] = hessianMatrix[1][0] = ((env.get(2, 2, 1) - env
				.get(0, 2, 1)) / 2 - (env.get(2, 0, 1) - env.get(0, 0, 1)) / 2) / 2;

		// x scale
		hessianMatrix[0][2] = hessianMatrix[2][0] = ((env.get(2, 1, 2) - env
				.get(0, 1, 2)) / 2 - (env.get(2, 1, 0) - env.get(0, 1, 0)) / 2) / 2;

		// y scale
		hessianMatrix[1][2] = hessianMatrix[2][1] = ((env.get(1, 2, 2) - env
				.get(1, 0, 2)) / 2 - (env.get(1, 2, 0) - env.get(1, 0, 0)) / 2) / 2;

		return hessianMatrix;
	}

	private ScaleSpaceData buildScaleSpace(FloatArray2D img, float imageSigma,
			float initialSigma, int laPlaceStepsPerOctave, int descriptorSize) {
		normFloatArray(img);
		FloatArray2D scaledImg = createUpScaledImage(img);

		//FloatArrayToImagePlus(scaledImg, "scaled", 0, 0).show();

		ScaleSpaceData ssd = computeFirstOctave(scaledImg, imageSigma,
				initialSigma, laPlaceStepsPerOctave, descriptorSize);
		ssd.backup = img;

		while (min(ssd.getLastOctave().gauss[0].width / 2,
				ssd.getLastOctave().gauss[0].height / 2) >= ssd.minImageSize)
			computeNextOctave(ssd);

		return ssd;
	}

	private ScaleSpaceData computeFirstOctave(FloatArray2D img,
			float imageSigma, float initialSigma, int laPlaceStepsPerOctave,
			int descriptorSize) {
		//
		// Store everything in here
		//
		ScaleSpaceData ssd = new ScaleSpaceData();

		//
		// get image dimensions and save original image
		//
		ssd.width = img.width;
		ssd.height = img.height;

		//
		// scale space parameters and data structure
		//
		ssd.OCT_STEPS = laPlaceStepsPerOctave;
		ssd.minImageSize = descriptorSize;

		ssd.sigma = new float[ssd.OCT_STEPS + 1];
		ssd.sigmaDiff = new float[ssd.OCT_STEPS + 1];

		ssd.k = (float) Math.pow(2f, 1f / ssd.OCT_STEPS);
		ssd.K_MIN1_INV = 1.0f / (ssd.k - 1.0f);

		ssd.sigma[0] = initialSigma;
		ssd.sigmaDiff[0] = getDiffSigma(imageSigma, initialSigma);

		ssd.scaleSpace = new Vector<OctaveData>();

		//
		// Compute the Sigmas for the gaussian folding
		//

		for (int i = 1; i <= ssd.OCT_STEPS; i++) {
			ssd.sigma[i] = ssd.sigma[i - 1] * ssd.k;
			ssd.sigmaDiff[i] = getDiffSigma(ssd.sigmaDiff[0], ssd.sigma[i]);
		}

		//
		// Now initially fold with gaussian kernel to get to sigma = 1.6
		//

		OctaveData octData = new OctaveData();
		octData.gauss = new FloatArray2D[ssd.OCT_STEPS + 1];
		octData.laPlace = new FloatArray2D[ssd.OCT_STEPS];
		octData.gauss[0] = computeGaussianFastMirror(img, ssd.sigmaDiff[0]);

		// compute gaussian and La Place images for actual octave
		for (int i = 1; i <= ssd.OCT_STEPS; i++) {
			octData.gauss[i] = computeGaussianFastMirror(octData.gauss[0],
					ssd.sigmaDiff[i]);
			octData.laPlace[i - 1] = subtractArrays(octData.gauss[i],
					octData.gauss[i - 1], ssd.K_MIN1_INV);
			//FloatArrayToImagePlus(octData.laPlace[i - 1], "LaPlace " + i, 0, 0).show();
		}

		ssd.scaleSpace.add(octData);

		return ssd;
	}

	private void computeNextOctave(ScaleSpaceData ssd) {
		//
		// create new octave
		//

		OctaveData octData = new OctaveData();
		octData.gauss = new FloatArray2D[ssd.OCT_STEPS + 1];
		octData.laPlace = new FloatArray2D[ssd.OCT_STEPS];

		//
		// create first image by halfing the last gauss image from the previous
		// octave
		//

		octData.gauss[0] = createHalfImage(ssd.getLastOctave().gauss[ssd.OCT_STEPS]);

		//
		// compute gaussian and La Place images for first octave
		//
		for (int i = 1; i <= ssd.OCT_STEPS; i++) {
			octData.gauss[i] = computeGaussianFastMirror(octData.gauss[0],
					ssd.sigmaDiff[i]);
			octData.laPlace[i - 1] = subtractArrays(octData.gauss[i],
					octData.gauss[i - 1], ssd.K_MIN1_INV);
			//FloatArrayToImagePlus(octData.laPlace[i - 1], "LaPlace " + i, 0, 0).show();
		}

		ssd.scaleSpace.add(octData);
	}

	private static boolean isSpecialPoint(FloatArray3D env) {
		boolean isMin = true;
		boolean isMax = true;

		float value = env.get(1, 1, 1);
		float compare1, compare2, compare3;

		for (int xs = 0; xs <= 2 && (isMin || isMax); xs++)
			for (int ys = 0; ys <= 2 && (isMin || isMax); ys++) {
				compare1 = env.get(xs, ys, 1);
				compare2 = env.get(xs, ys, 0);
				compare3 = env.get(xs, ys, 2);

				if (isMin) {
					if (compare1 < value)
						isMin = false;

					if (compare2 < value)
						isMin = false;

					if (compare3 < value)
						isMin = false;
				}

				if (isMax) {
					if (compare1 > value)
						isMax = false;

					if (compare2 > value)
						isMax = false;

					if (compare3 > value)
						isMax = false;

				}

				if (xs != 1 && ys != 1)
					if (compare1 == value || compare2 == value
							|| compare3 == value)
						isMin = isMax = false;
					else if (compare2 == value || compare3 == value)
						isMin = isMax = false;
			}

		return (isMin || isMax);
	}

	private FloatArray2D createHalfImage(FloatArray2D image) {
		FloatArray2D halfImage = new FloatArray2D(image.width / 2,
				image.height / 2);

		for (int x = 0; x < image.width / 2; x++)
			for (int y = 0; y < image.height / 2; y++)
				halfImage.set(image.get(x * 2 + 1, y * 2 + 1), x, y);

		return halfImage;
	}

	private void printEnvironment(FloatArray3D env) {
		for (int scale = 0; scale <= 2; scale++) {
			if (scale == 0)
				System.out.println("\nScale-1:\n");
			else if (scale == 1)
				System.out.println("\nScale:\n");
			else
				System.out.println("\nScale + 1:\n");

			for (int y = 0; y <= 2; y++) {
				for (int x = 0; x <= 2; x++)
					System.out.print(env.get(x, y, scale) + "\t");

				System.out.println();
			}
		}
	}

	private FloatArray2D subtractArrays(FloatArray2D a, FloatArray2D b,
			float norm) {
		FloatArray2D result = new FloatArray2D(a.width, a.height);

		for (int i = 0; i < a.data.length; i++)
			result.data[i] = (a.data[i] - b.data[i]) * norm;

		return result;
	}

	private float getDiffSigma(float sigma_a, float sigma_b) {
		return (float) Math.sqrt(sigma_b * sigma_b - sigma_a * sigma_a);
	}

	/**
	 * get appropriate octave-sigma for sigma
	 */
	public float get_octave_sigma(float sigma, int octave) {
		return sigma / (float) Math.pow(2.0, octave);
	}
}
