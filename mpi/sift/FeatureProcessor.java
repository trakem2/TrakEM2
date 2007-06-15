package mpi.sift;

import ij.ImagePlus;
import java.util.Vector;

public class FeatureProcessor {

	public ImagePlus image;
	ScaleSpaceData scaleSpace;
	Vector features;
	int mode;
	static final float MAX_CURVATURE = 10;
	static final float MAX_CURVATURE_RATIO = (MAX_CURVATURE+1)*(MAX_CURVATURE+1)/MAX_CURVATURE;

	FeatureProcessor() {}

	public int run(ImagePlus image) {
		this.image = image;
		// build scale space
		final FloatProcessor fp = image.getProcessor();				
		this.scaleSpace = ScaleSpace2D.buildScaleSpace(ImageToFloatArray2D(fp),
				1F,  /* Image sigma */
				1.6F, /* Initial sigma */
				3, /* laPlace steps per octave*/
				16); /* */
		// fill in the features Vector and get how many it found
		int n = findFeatures();
		// process candidates
		int processed_candiates = processCandidates(n);
	}

    /**
     * 
     * @param o scale space octave
     * @param s scale in octave
     * @param y y-coordinate
     * @param x x-coordinate
     * @param env 3d-environment of the location ( scale, y, x )
     * 
     * @returns true if the value is an extremum
     */
	public boolean isExt(int o, int s, int y, int x, float[][][] env) {
		// naive implementation - improve it later...
		boolean is_min, is_max;
		env = getEnv(o, s, y, x);
		if (null != env) {
			is_min = is_max = true;
			for ( unsigned si = 0; ( is_min || is_max ) && si < 3; ++si ) {
				bool si1 = si == 1;
				for ( unsigned yi = 0; ( is_min || is_max ) && yi < 3; ++yi ) {
					bool yi1 = yi == 1;
					for ( unsigned xi = 0; ( is_min || is_max ) && xi < 3; ++xi ) {
						if ( si1 && yi1 && xi == 1 ) continue;
						is_min &= env[si][yi][xi] > env[1][1][1];
						is_max &= env[si][yi][xi] < env[1][1][1];
					}
				}
			}
			if ( is_min || is_max ) return true;
			else return false;
		} else return false;
	}

    /**
     * 
     * @param o scale space octave
     * @param s scale in octave
     * @param y y-coordinate
     * @param x x-coordinate
     *
     * @returns bool when it wa spossible to fill env 3d-environment of the location ( scale, y, x )
     */
	public boolean getEnv( int o, int s, int y, int x, float[][][] ) {
		// check preconditions
		if ((0 == o && 0 == s) || o >= scaleSpace.num_octaves || (o == scaleSpace.num_octaves -1 && s == ScaleOctave.OCT_STEPS -1)) return null;
		//
		float temp;
		FloatProcessor fp_mat = scaleSpace.getDiffImage(o, s);
		float[] mat = (float[])fp_mat.getPixels();
		if (y < 1 || x < 1 || y > fp_mat.getHeight()-2 || x > fp_mat.getWidth()) return false;
		// TODO: above, check why there's a -2 for the height
		int width = fp_mat.getWidth();
		--x;
		--y;
		//final float[][][] env = new float[3][3][3];
		for (int yi=0; yi < 3; ++yi) {
			for (int xi=0; xi<3; ++xi) {
				env[1][yi][xi] = mat[(y+yi)*width + x+xi];
			}
		}
		if (0 == s) {
			fp_mat = scaleSpace.getDiffImage(o-1, ScaleOctave.OCT_STEPS-1);
			int width = fp_mat.getWidth();
			mat = (float[])fp_mat.getPixels();
			for (int yi = 0; yi < 3; ++yi)
			{
			    for (int xi = 0; xi < 3; ++xi)
			    {
				// may be - we have to smooth it?
				env[0][yi][xi] = mat[(2*(y+yi)+1)*width + 2*(x+xi)+1];
				/*
				env[0][yi][xi] = (get_val_32f(mat, 2*(y+yi)+1, 2*(x+xi)+1)+
						 get_val_32f(mat, 2*(y+yi)+1, 2*(x+xi))+
						 get_val_32f(mat, 2*(y+yi), 2*(x+xi)+1)+
						 get_val_32f(mat, 2*(y+yi), 2*(x+xi)))/4;
				*/
			    }
			}
		} else {
			fp_mat = scaleSpace.getDiffImage(o, s-1);
			width = fp_mat.getWidth();
			mat = (float[])fp_mat.getPixels();
			for (int yi = 0; yi < 3; ++yi)
			{
			    for (int xi = 0; xi < 3; ++xi)
			    {
				env[0][yi][xi] = mat[(y+yi)*width + x+xi];
			    }
			}	
		}
		if (s == OCT_STEPS-1) {
			fp_mat = scaleSpace.getDiffImage(o+1, 0);
			mat = (float[])fp_mat.getPixels();
			width = fp_mat.getWidth();
			int height = fp_mat.getHeight();
			int w2 = width/2,
			    h2 = height/2;
			// remember that we decreased x and y !!
			if (y%2 == 1) {
				if (x%2 == 1) {
					env[2][0][0] = get_val_32f_checkrange(mat, y/2, x/2, w2, h2);
					env[2][0][2] = get_val_32f_checkrange(mat, y/2, x/2+1, w2, h2);
					env[2][2][0] = get_val_32f_checkrange(mat, y/2+1, x/2, w2, h2);
					env[2][2][2] = get_val_32f_checkrange(mat, y/2+1, x/2+1, w2, h2);
					env[2][0][1] = (env[2][0][0]+env[2][0][2])/2;
					env[2][2][1] = (env[2][2][0]+env[2][2][2])/2;
				} else {
					env[2][0][1] = get_val_32f_checkrange(mat, y/2, x/2, w2, h2);
					env[2][2][1] = get_val_32f_checkrange(mat, y/2+1, x/2, w2, h2);
					env[2][0][0] = (env[2][0][1]+get_val_32f_checkrange(mat, y/2, x/2-1, w2, h2))/2;
					env[2][0][2] = (env[2][0][1]+get_val_32f_checkrange(mat, y/2, x/2+1, w2, h2))/2;
					env[2][2][0] = (env[2][2][1]+get_val_32f_checkrange(mat, y/2+1, x/2-1, w2, h2))/2;
					env[2][2][2] = (env[2][2][1]+get_val_32f_checkrange(mat, y/2+1, x/2+1, w2, h2))/2;
				}
				env[2][1][0] = (env[2][0][0]+env[2][2][0])/2;
				env[2][1][2] = (env[2][0][2]+env[2][2][2])/2;
				env[2][1][1] = (env[2][1][0]+env[2][1][2])/2;
			} else {
				if (x%2 == 1) {
					env[2][1][0] = get_val_32f_checkrange(mat, y/2, x/2, w2, h2);
					env[2][1][2] = get_val_32f_checkrange(mat, y/2, x/2+1, w2, h2);
					env[2][0][0] = (env[2][1][0]+get_val_32f_checkrange(mat, y/2-1, x/2-1, w2, h2))/2;
					env[2][0][2] = (env[2][1][2]+get_val_32f_checkrange(mat, y/2-1, x/2, w2, h2))/2;
					env[2][2][0] = (env[2][1][0]+get_val_32f_checkrange(mat, y/2+1, x/2-1, w2, h2))/2;
					env[2][2][2] = (env[2][1][2]+get_val_32f_checkrange(mat, y/2+1, x/2, w2, h2))/2;
					env[2][0][1] = (env[2][0][0]+env[2][0][2])/2;
					env[2][2][1] = (env[2][2][0]+env[2][2][2])/2;
					env[2][1][1] = (env[2][1][0]+env[2][1][2])/2;
				 } else {
					env[2][1][1] = get_val_32f_checkrange(mat, y/2, x/2, w2, h2);
					temp = get_val_32f_checkrange(mat, y/2-1, x/2, w2, h2);
					env[2][0][1] = (env[1][1][1]+temp)/2;
					temp = get_val_32f_checkrange(mat, y/2, x/2-1, w2, h2);
					env[2][1][0] = (env[1][1][1]+temp)/2;
					env[2][0][0] = ((temp+get_val_32f_checkrange(mat, y/2-1, x/2-1, w2, h2))/2+env[2][0][1])/2;
					temp = get_val_32f_checkrange(mat, y/2+1, x/2, w2, h2);
					env[2][2][1] = (env[1][1][1]+temp)/2;
					env[2][2][0] = ((temp+get_val_32f_checkrange(mat, y/2+1, x/2-1, w2, h2))/2+env[2][1][0])/2;
					temp = get_val_32f_checkrange(mat, y/2, x/2+1, w2, h2);
					env[2][1][2] = (env[1][1][1]+temp)/2;
					env[2][2][2] = ((temp+get_val_32f_checkrange(mat, y/2+1, x/2+1, w2, h2))/2+env[2][2][1])/2;
					env[2][0][2] = ((temp+get_val_32f_checkrange(mat, y/2-1, x/2+1, w2, h2))/2+env[2][0][1])/2;
				}
			}
		} else {
			fp_mat = scaleSpace.getDiffImage(o, s+1);
			width = fp_mat.getWidth();
			mat = (float[])fp_mat.getPixels();
			for (int yi = 0; yi < 3; ++yi) {
				for (int xi = 0; xi < 3; ++xi) {
				    env[2][yi][xi] = mat[(y+yi)*width + x+xi];
				}
			}
		}

		return env;
	}

	final int flip_range(int a, int mod) {
		int p = 2 * mod;
		if ( a < 0 ) a = p + a % p;
		else if ( a >= p ) a = a % p;
		if ( a >= mod ) a = mod - a % mod - 1;
		return a;
	}

	final float get_val_32f_checkrange(float[] src, int row, int col, int width, int height) {
		return src[flip_range( row, height - 1 ) * width + flip_range( col, width - 1 ) ];
	}

	/**
     * 
     * create the hessian and derivation of the center of env
     * 
     * @param env 3d-environment of the location ( scale, y, x )
     * @param deriv derivative matrix
     * @param hessian hessian matrix
     *
     * This method fills deriv and hessian arrays with proper values.
     */
	void derive(float[][][] env, float[][] deriv, float[][] hessian) {
		// derive at (s, y, x) via center of difference
		deriv[0][0] = ( env[1][1][2] - env[1][1][0] ) / 2;
		deriv[0][1] = ( env[1][2][1] - env[1][0][1] ) / 2;
		deriv[0][2] = ( env[2][1][1] - env[0][1][1] ) / 2;

		// create hessian at (s[1],y[1],x[1]) via laplace
		//dxx
		hessian[0][0] = env[1][1][0] - 2 * env[1][1][1] + env[1][1][2];
		//dyy
		hessian[1][1] = env[1][0][1] - 2 * env[1][1][1] + env[1][2][1];
		//dss
		hessian[2][2] = env[0][1][1] - 2 * env[1][1][1] + env[2][1][1];
		
		//dxy
		hessian[0][1] =
		hessian[1][0] = ( env[1][2][2] - env[1][2][0] - env[1][0][2] + env[1][0][0] ) / 4;
		//dxs
		hessian[0][2] =
		hessian[2][0] = ( env[2][1][2] - env[2][1][0] - env[0][1][2] + env[0][1][0] ) / 4;
		//dys
		hessian[1][2] =
		hessian[2][1] = ( env[2][2][1] - env[2][0][1] - env[0][2][1] + env[0][0][1] ) / 4;
	}

/**
     * 
     * [re]localize feature accurately, update env and derivatives
     * 
     * @param o scale space octave
     * @param s scale in octave
     * @param y y-coordinate
     * @param x x-coordinate
     * @param env 3d-environment of the location ( scale, y, x )
     * @param offset
     * @param location
     * @param deriv derivative matrix
     * @param hessian hessian matrix
     *
     * Will write to
     * 
     * @returns true if [re]localization was successful
     */
    boolean localizeFeature(
		Int o,
		Int s,
		Int y,
		Int x,
		float env[][][],
		float offset[],
		float location[],
		float[][] deriv,
		float[][] hessian,
		Float val
	) {
		int t = 0;
		float d	= 1000,
			nd = 1000,
			p = Math.pow( 2, o ) / 2;
		bool	located		= false;
		float[][] invHessian	= new float[3][3]; //cvCreateMat( 3, 3, CV_32FC1 );
		float[] loc;
	
	do
	{
		located = true;
		derive( env, deriv, hessian );
		if ( cvInvert( hessian, invHessian, CV_LU ) == 0 ) return false;
		cvConvertScale( invHessian, invHessian, -1 );
		loc = create_mul_3x3f_1x3f( invHessian, deriv );

		if ( loc[0] > .5 )
		{
			if ( ++s.i > OCT_STEPS - 1 )
			{
				if ( ++o.i > ( int )scaleSpace.getNumOctaves() ) return false;
				s.i = 0;
				y.i /= 2;
				x.i /= 2;
				p = Math.pow( 2, o.i ) / 2;
			}
			located = false;
		}
		else if ( loc[1] < -.5 )
		{
			if ( --s.i < 0 )
			{
				if ( --o.i < 0 ) return false;
				s.i = OCT_STEPS - 1;
				y.i = y.i * 2 + 1;
				x.i = x.i * 2 + 1;
				p = Math.pow( 2, o.i ) / 2;
			}
			located = false;
		}
		int dir_y = direction( direction( loc[1] ) );	
		if ( dir_y != 0 )
		{
			y.i += dir_y;
			located = false;
		}
		int dir_x = direction( direction( loc[2] );
		if ( dir_x != 0 )
		{
			x.i += dir_x;
			located = false;
		}
		d = nd;
		nd = loc[0]*loc[0]+ loc[1]*loc[1]+ loc[2]*loc[2];
		++t;
		
//		cout << "\n  moving (" << *(loc->data.fl) << ", " << *(loc->data.fl+1) << ", " << *(loc->data.fl+2) << ")";
	}
	while ( !located && d > nd && t < 4 && getEnv( c, o.i, s.i, y, x, env ) );
/*
	// no localization
	while ( false );
	located = true;
	*( loc->data.fl ) = 0;
	*( loc->data.fl + 1 ) = 0;
	*( loc->data.fl + 2 ) = 0;
*/
	if ( located )
	{
		offset[0] = loc[0];
		offset[1] = loc[1];
		offset[2] = loc[2];
		location[0] = get_sigma( o.i, s.i, offset[0] );
		location[1] = p * ( 1.0 + ( float )y + offset[1] );
		location[2] = p * ( 1.0 + ( float )x + offset[2] );
		val = env[1][1][1] + .5 * ( deriv[0] * offset[0] +
			( deriv[1] ) * offset[1] +
			( deriv[2] ) * offset[2] );
/*
// print location changes
		
//		if (t > 1)
//		{
			cout << "\n    moved " << t - 1 << " times";
			cout << "\n        o: " << o << " -> " << no;
			cout << "\n        s: " << s << " -> " << ns;
			cout << "\n    sigma: " << get_sigma( o, s ) << " -> " << location[0];
			cout << "\n        y: " << p * y << " -> " << location[1];
			cout << "\n        x: " << p * x << " -> " << location[2];
//		}

		cout << "\n  accurate location ( " << location[0] << ", " << location[1] << ", " << location[2] << " ), val = " << val;
//		cin.get();
*/
		return true;
	}
	else 
	{
/*
		cout << "\n  localization failed";
		if ( t >= 4 ) cout << " because moved too often...";
		if ( nd >= d ) cout << " because offset rised after moving";
		cout << "\n";
*/
		return false;
	}



	}




	/**
	 * @param hessian hessian matrix
     * 
     * @returns true if the ratio of the principal curvatures is smaller than MAX_CURVATURE
     */
	public boolean curvatureFilter(float[][] hessian) {
		//det = dxx * dyy - ( dxy )^2
		float det = hessian[0] * hessian[3] - hessian[1] * hessian[1]; // WAS: 4
		
		//trace = dxx + dyy
		float trace = *( hessian->data.fl ) + *( hessian->data.fl + 3 ); // WAS: 4
		
	//	if ( verbose ) std::cout << "\nDet: " << det << "; Trace: " << trace;

	// 	if ( verbose ) std::cout << "\n" << *( hessian->data.fl ) + *( hessian->data.fl + 4 );

		return trace * trace / det < MAX_CURVATURE_RATIO;
	}
    
    /**
     * find and filter all feature candidates
     * 
     * @returns count of found featurs after filtering
     */
	int findFeatures() {
		this.features = ScaleSpace2D.extractPoints(ssd);
		return features.size();
	}

    /**
     * sample the scaled and rotated gradients in a region around a features
     * location, the regions size is defined prior through the constant values
     * sq( FEATURE_DESCRIPTOR_SIZE * 4 ) ( 4x4 subregions )
     */
    void sampleFeatureGradientROI(
    		Feature feature,
    		float[][] region,		//!< the sampled region
		float[][] gradients		//!< the gradients of the sampled region
	) {
}
	
    /**
     * calculate and assign SIFT descriptor to the given feature
     */
    void assignFeatureDescriptor(
		Feature feature
    ) {
	}
/**
     * assign orientation to the given feature, if more than one
     * orientations found, duplicate the feature for each orientation
     */
    void processSingleCandidate( analyzedVoxel feature) {
	   final int ORIENTATION_BINS = 36;
	float[] histogram_bins = new float[ORIENTATION_BINS];

	float octave_sigma = get_octave_sigma( /*Sigma of the feature*/ feature.location[2], /*Octave index*/ feature.o);

    //! create a circular gaussian window with sigma 1.5 times that of the feature    
//  CvMat* gaussianMask = create_gaussian_kernel_2D( octave_sigma * 1.5, false );
	FloatArray2D gaussianMask = ImageFilter.create_gaussian_kernel_2D_offset(
			octave_sigma * 1.5,
			feature.xD,
			feature.yD,
			false );

	//! get the gradients in a region arround the keypoints location
	FloatArray2D gradientROI = scaleSpace.createGradientROI(
			feature.s,
			feature.x,
			feature.y,
			gaussianMask.width );
	
	//! and mask this region with the precalculated gaussion window
	mul_first_elem_32fc2_32fc1( gradientROI, gaussianMask );
	
	//! build an orientation histogramm of the region
	for ( int p = gradientROI->width * gradientROI->width - 1; p >= 0; --p )
	{
		int bin = ( unsigned )floor( ( *( gradientROI->data.fl + 2 * p + 1 ) + M_PI ) / ORIENTATION_BIN_SIZE );
		histogram_bins[bin] += *( gradientROI->data.fl + 2 * p );
	
//		if ( verbose ) std::cout << "\nvalue = " << *(gradientROI->data.fl+2*p) << " ";
//		if ( verbose ) std::cout << "\nbin[" << bin << "] has " << histogram_bins[bin];
//		cin.get();
	}

/*
    // print histogram
    if ( verbose ) std::cout << "\nhistogram: ";
    for (unsigned i = 0; i < ORIENTATION_BINS; ++i)
    {
//	if ( verbose ) std::cout << "bin[" << i << "]: " << histogram_bins[i] << "  ";
	if ( verbose ) std::cout << histogram_bins[i] << "  ";
    }
    // ---------------
*/    

    // find the dominant orientation and interpolate it with respect to its two neighbours
    int max_i = 0;
    for (unsigned i = 0; i < ORIENTATION_BINS; ++i)
    {
	if (histogram_bins[i] > histogram_bins[max_i]) max_i = i;
    }
    float env[3];
    env[0] = histogram_bins[(max_i-1)%ORIENTATION_BINS];
    env[1] = histogram_bins[max_i];
    env[2] = histogram_bins[(max_i+1)%ORIENTATION_BINS];
    float offset = fit_parabola_1d(env); // interpolate orientation
    feature->orientation = ((float)max_i+0.5+offset)*ORIENTATION_BIN_SIZE-M_PI;

    // assign descriptor
    assignFeatureDescriptor( feature );
    
    for ( unsigned i = 0; i < ORIENTATION_BINS; ++i )
    {
		if ( i != ( unsigned )max_i &&
			( unsigned )( max_i + 1 ) % ORIENTATION_BINS != i &&
			( unsigned )( max_i - 1 ) % ORIENTATION_BINS != i &&
			histogram_bins[i] > 0.8 * histogram_bins[max_i] )
		{
			Feature* f = new Feature( scaleSpace->channels );
			f->location[0] = feature->location[0];
			f->location[1] = feature->location[1];
			f->location[2] = feature->location[2];
			f->octaveLocation[0] = feature->octaveLocation[0];
			f->octaveLocation[1] = feature->octaveLocation[1];
			f->octaveOffset[0] = feature->octaveOffset[0];
			f->octaveOffset[1] = feature->octaveOffset[1];
			f->octaveOffset[2] = feature->octaveOffset[2];
			f->c = feature->c;
			f->o = feature->o;
			f->s = feature->s;
			env[0] = histogram_bins[( i - 1 ) % ORIENTATION_BINS];
			env[1] = histogram_bins[i];
			env[2] = histogram_bins[( i + 1 ) % ORIENTATION_BINS];
			float offset = fit_parabola_1d( env );		//!< interpolate orientation
			f->orientation = ( ( float )i + 0.5 + offset ) * ORIENTATION_BIN_SIZE - M_PI;
			
			// assign descriptor
			assignFeatureDescriptor( f );
			
			features.push_back( f );
		}
	}

	cvReleaseMat( &gaussianMask );
	cvReleaseMat( &gradientROI );
	}
    
    /**
     *  process all found feature candidates, find orientation, duplicate candidates
     *  with more than one orientation, ...
     */
 	int processCandidates( int num_candidates) {
	    //
	    for (int i = 0; i < num_candidates; ++i) {
	    	processSingleCandidate((analyzedVoxel)features.get(i)); // analyzedVoxel is 'Feature' type in C++
	    }
	    return features.size();
	}
    
/**
     * 
     * start feature processing for an image
     */    
    int run( ImagePlus image) {
}
    
/**
     * 
     *  find similar features to f having descriptors with square distance lower than min_sq_d
     */
    int getSimilarFeatures(
    	Feature f,
    	float min_sq_d,					//!< similarity criterion, square of the minimum distance between descriptors
    	ArrayList siblings, // contains Feature instances   //!< all features matching the similarity criterion
    	bool similarScale
    ) {
	// DEFAULT is similarScale = false;
}
    
    /**
     * 
     *  find the <num> most similar features to f
     */
    int getSimilarFeatures(
    	Feature f,
    	unsigned num,
    	ArrayList siblings, // contains FeatureMatch instaces
    	bool similarScale
    ) {
	// DEFAULT similarScale = false
}
    
    /**
     * 
     *  find matching features of two feature processors
     */
    static int matchFeatures(
    	FeatureProcessor fp1,
    	FeatureProcessor fp2,
    	ArrayList matches, // contains FeatureMatch instances
    	bool similarScale = false
   );
    
    /**
     * estimate all possible models (translation and rotation)
     */
/*  
   static vector< Model > estimateModels(
     	FeatureProcessor fp1,
    	FeatureProcessor fp2,
    	vector< FeatureMatch* > &matches );
*/
}
