package ini.trakem2.imaging;

import ij.ImagePlus;
import mpicbg.imglib.algorithm.fft.PhaseCorrelation;
import mpicbg.imglib.algorithm.fft.PhaseCorrelationPeak;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.image.ImagePlusAdapter;
import mpicbg.imglib.type.numeric.RealType;

public class PhaseCorrelationCalculator
{
	final PhaseCorrelationPeak peak;
	
	public <T extends RealType<T>, S extends RealType<S>> PhaseCorrelationCalculator( ImagePlus imp1, ImagePlus imp2 )
	{
		Image<T> img1 = ImagePlusAdapter.wrap( imp1 );
		Image<S> img2 = ImagePlusAdapter.wrap( imp2 );
		
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
