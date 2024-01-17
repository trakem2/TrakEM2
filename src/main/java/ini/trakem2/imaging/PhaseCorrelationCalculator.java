/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2024 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
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
