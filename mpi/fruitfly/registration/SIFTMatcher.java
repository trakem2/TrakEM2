package mpi.fruitfly.registration;

import mpi.fruitfly.registration.SIFTProcessor;
import mpi.fruitfly.registration.SIFTProcessor.FeatureMatch;
import ij.process.*;
import mpi.fruitfly.registration.TRModel;
import mpi.fruitfly.registration.Match;

import java.util.Iterator;
import java.util.Vector;


public class SIFTMatcher {
	
	static public boolean align( ImageProcessor ip1, ImageProcessor ip2, float min_epsilon, float inlier_ratio, float[] tr )
	{
		// instantiate a SIFProcessor for each image and extract Feature candidates
		// @todo make a pipeline, that would not 
		SIFTProcessor sp1 = new SIFTProcessor( ip1 );
		SIFTProcessor sp2 = new SIFTProcessor( ip2 );
		int num_candidates1 = sp1.run();
		int num_candidates2 = sp2.run();
		
		// find correspondencesVector< SIFTProcessor.FeatureMatch > matches = new Vector< SIFTProcessor.FeatureMatch >();
		// @todo optimize match search by hierarchical prealignment during search for a specific class of transformation model
		Vector< SIFTProcessor.FeatureMatch > matches = new Vector< SIFTProcessor.FeatureMatch >();
		int num_matches = SIFTProcessor.matchFeatures( sp1, sp2, matches, true );
		
		// vector of general point matches
		// @todo return those correspondence matches instead of FeatureMatches from SIFProcessor
		Vector< Match > correspondences = new Vector< Match >();
		for ( Iterator< FeatureMatch > it = matches.iterator(); it.hasNext(); )
		{
			correspondences.addElement( new Match( it.next() ) );
		}
		
		TRModel model = null;
        //float[] tr = new float[6];
        float epsilon = min_epsilon;
		do
		{
			System.out.println( "Estimating model for epsilon = " + epsilon );
			model = TRModel.estimateModel( correspondences, matches.size(), epsilon , 0.1f , tr);
			epsilon *= 2;
		}
		while ( model == null && epsilon < 10 * min_epsilon );
		
		return ( model != null );	
	}
}
