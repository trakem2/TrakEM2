package ini.trakem2.imaging;

import ij.ImagePlus;
import mpicbg.imglib.algorithm.fft.PhaseCorrelation;
import mpicbg.imglib.algorithm.fft.PhaseCorrelationPeak;
import mpicbg.imglib.image.ImagePlusAdapter;
import mpicbg.imglib.type.NumericType;

public class PhaseCorrelationCalculator
{
	final PhaseCorrelationPeak peak;
	
	public <T extends NumericType<T>, S extends NumericType<S>> PhaseCorrelationCalculator( ImagePlus imp1, ImagePlus imp2 )
	{
		mpicbg.imglib.image.Image<T> img1 = ImagePlusAdapter.wrap( imp1 );
		mpicbg.imglib.image.Image<S> img2 = ImagePlusAdapter.wrap( imp2 );
		
		PhaseCorrelation<T, S> phase = new PhaseCorrelation<T, S>( img1, img2 );
		
		if ( !phase.checkInput() || !phase.process() )
		{
			System.out.println( phase.getErrorMessage() );
		}
		
		peak = phase.getShift();
	}
	

	public PhaseCorrelationPeak getPeak()
	{
		return peak;
	}
		
}
