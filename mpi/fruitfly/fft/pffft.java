package mpi.fruitfly.fft;

/**
 * <p>Title: pffft</p>
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
import edu.mines.jtk.dsp.FftReal;
import edu.mines.jtk.dsp.FftComplex;
import mpi.fruitfly.math.datastructures.*;

import mpi.fruitfly.general.MultiThreading;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Date;

public class pffft
{
    public static boolean SCALE = true;
    public static boolean DONOTSCALE = false;
    public static boolean OVERWRITE = true;
    public static boolean DONOTOVERWRITE = false;

    public static float[] computePowerSpectrum(float[] complex)
    {
        int wComplex = complex.length / 2;

        float[] powerSpectrum = new float[wComplex];

        for (int pos = 0; pos < wComplex; pos++)
            powerSpectrum[pos] = (float)Math.sqrt(Math.pow(complex[pos*2],2) + Math.pow(complex[pos*2 + 1],2));

        return powerSpectrum;
    }

    public static float[] computeLogPowerSpectrum(float[] complex)
    {
        float[] logPowerSpectrum = computePowerSpectrum(complex);

        for (int i = 0; i < logPowerSpectrum.length; i++)
            logPowerSpectrum[i] = (float)Math.log10(1 + logPowerSpectrum[i]);

        return logPowerSpectrum;
    }

    /*only needed for phase correlation, real and img value have the power-value*/
    private static float[] computePowerSpectrumComplex(float[] complex)
    {
        int wComplex = complex.length / 2;

        float[] powerSpectrum = new float[wComplex*2];

        for (int pos = 0; pos < wComplex; pos++)
            powerSpectrum[2*pos+1] = powerSpectrum[2*pos] = (float) Math.sqrt(complex[pos * 2]*complex[pos * 2] + complex[pos * 2 + 1]*complex[pos * 2 + 1]);

        return powerSpectrum;
    }

    public static float[] computePhaseSpectrum(float[] complex)
    {
        int wComplex = complex.length / 2;

        float[] phaseSpectrum = new float[wComplex];
        float a,b;

        for (int pos = 0; pos < phaseSpectrum.length; pos++)
        {
            a = complex[pos * 2];
            b = complex[pos * 2 + 1];

            if (a != 0.0 || b != 0)
                phaseSpectrum[pos] = (float)Math.atan2(b,a);
            else
                phaseSpectrum[pos] = 0;
        }
        return phaseSpectrum;
    }

    public static float[] logNormalizeComplexVectors(float[] complex)
    {
        int wComplex = complex.length / 2;

        double length, logLength;
        double avgLogLength = 0;
        int count = 0;

        for (int pos = 0; pos < wComplex; pos++)
        {
            length = Math.sqrt(Math.pow(complex[pos*2],2) + Math.pow(complex[pos*2 + 1],2));
            logLength = Math.log(1d + length);

            avgLogLength += logLength;
            count++;
        }

        avgLogLength /= (double)count;

        for (int pos = 0; pos < wComplex; pos++)
        {
            complex[pos * 2 + 1] /= Math.exp(avgLogLength);
            complex[pos * 2] /= Math.exp(avgLogLength);
        }

        return complex;
    }

    public static void normalizeComplexVectorsToUnitVectors(float[] complex)
    {
        int wComplex = complex.length / 2;

        double length;

        for (int pos = 0; pos < wComplex; pos++)
        {
            length = Math.sqrt(Math.pow(complex[pos*2],2) + Math.pow(complex[pos*2 + 1],2));

            if (length > 1E-5)
            {
                complex[pos * 2 + 1] /= length;
                complex[pos * 2] /= length;
            }
            else
            {
                complex[pos * 2 + 1] = complex[pos * 2] = 0;
            }
        }

    }

    public static float[] computePhaseCorrelationMatrix(final float[] fft1, final float[] fft2, boolean inPlace)
    {
        /*matlab code

         threshold = 1E-5;

         FFT3_V1 = fftn(V1);
         FFT3_V2 = fftn(V2);

         FFT3_V1  = FFT3_V1 ./abs(FFT3_V1 );
         FFT3_V2  = FFT3_V2 ./abs(FFT3_V2 ).*(abs(FFT3_V2 ) >threshold);

         Phase = FFT3_V1.*conj(FFT3_V2);

         Corr = real(ifftn(Phase));
         */

        final AtomicInteger ai = new AtomicInteger(0);

        Runnable task = new Runnable()
        {
                public void run()
                {
                    int i = ai.getAndIncrement();
                    if (i == 0)
                        normalizeComplexVectorsToUnitVectors(fft1);
                    else
                        normalizeComplexVectorsToUnitVectors(fft2);
                }
        };

        MultiThreading.startTask(task,2);

        float[] fftTemp1 = fft1;
        float[] fftTemp2 = fft2;

/*
        // get the power spectrum
        float[] ps = computePowerSpectrumComplex(fft1);

        // divide complex numbers by power spectrum (weightening)
        if (inPlace)
            divide(fft1, ps, OVERWRITE);
        else
            fftTemp1 = divide(fft1, ps, DONOTOVERWRITE);

        // get the power spectrum
        ps = computePowerSpectrumComplex(fft2);

        // divide complex numbers by power spectrum (weightening)
        if (inPlace)
            divide(fft2, ps, OVERWRITE);
        else
            fftTemp2 = divide(fft2, ps, DONOTOVERWRITE);

        // divide only if power spectrum is above threshold
        for (int pos = 0; pos < ps.length/2; pos++)
            if (ps[2*pos] < 1E-5)
            {
                if (inPlace)
                    fft2[2 * pos] = fft2[2 * pos + 1] = 0;
                else
                    fftTemp2[2 * pos] = fftTemp2[2 * pos + 1] = 0;
            }*/

        // do complex conjugate
        if (inPlace)
            complexConjugate(fft2);
        else
            complexConjugate(fftTemp2);

        // multiply both complex arrays elementwise
        if (inPlace)
            multiply(fft1, fft2, OVERWRITE);
        else
            fftTemp1 = multiply(fftTemp1, fftTemp2, DONOTOVERWRITE);

        if (inPlace)
            return null;
        else
            return fftTemp1;
    }

    public static int getZeroPadingSizeForInPlace(int width)
    {
        return (width/2+1)*2;
    }

    public static void pffft1DInPlace(float[] values, int nfft)
    {
        FftReal fft = new FftReal(nfft);
        fft.realToComplex(1, values, values);
    }

    public static float[] pffft1D(float[] values, boolean scale)
    {
        int nfft = values.length;
        float[] result = new float[(nfft/2+1)*2];

        FftReal fft = new FftReal(nfft);
        fft.realToComplex(-1, values, result);

        if (scale)
            fft.scale(nfft, result);

        return result;
    }

    public static float[] pffftInv1D(float[] values, int nfft)
    {
        float[] result = new float[nfft];

        FftReal fft = new FftReal(nfft);
        fft.complexToReal(1, values, result);

        fft.scale(nfft, result);

        return result;
    }

    public static FloatArray2D pffft2DMT(final FloatArray2D values, final boolean scale)
    {
        final int height = values.height;
        final int width = values.width;
        final int complexWidth = (width/2+1)*2;

        final FloatArray2D result = new FloatArray2D(complexWidth, height);

        //do fft's in x direction
        final AtomicInteger ai = new AtomicInteger(0);
        Thread[] threads = MultiThreading.newThreads();
        final int numThreads =  threads.length;

        for (int ithread = 0; ithread < threads.length; ++ithread)
            threads[ithread] = new Thread(new Runnable()
            {
                public void run()
                {
                    int myNumber = ai.getAndIncrement();

                    float[] tempIn = new float[width];
                    float[] tempOut;
                    FftReal fft = new FftReal(width);

                    for (int y = 0; y < height; y++)
                        if (y % numThreads == myNumber)
                        {
                            tempOut = new float[complexWidth];

                            for (int x = 0; x < width; x++)
                                tempIn[x] = values.get(x, y);

                            fft.realToComplex( -1, tempIn, tempOut);

                            if (scale)
                                fft.scale(width, tempOut);

                            for (int x = 0; x < complexWidth; x++)
                                result.set(tempOut[x], x, y);
                        }
                }
            });
        MultiThreading.startAndJoin(threads);

        //do fft's in y direction
        ai.set(0);
        threads = MultiThreading.newThreads();

        for (int ithread = 0; ithread < threads.length; ++ithread)
            threads[ithread] = new Thread(new Runnable()
            {
                public void run()
                {
                    float[] tempIn = new float[height*2];
                    float[] tempOut;
                    FftComplex fftc = new FftComplex(height);

                    for (int x = ai.getAndIncrement(); x < complexWidth/2; x = ai.getAndIncrement())
                    {
                        tempOut = new float[height*2];

                        for (int y = 0; y < height; y++)
                        {
                            tempIn[y*2] = result.get(x*2,y);
                            tempIn[y*2+1] = result.get(x*2+1,y);
                        }

                        fftc.complexToComplex(-1, tempIn, tempOut);

                        for (int y = 0; y < height; y++)
                        {
                            result.set(tempOut[y*2], x*2, y);
                            result.set(tempOut[y*2+1], x*2+1, y);
                        }
                    }
                }
            });
        MultiThreading.startAndJoin(threads);

        return result;
    }

    public static FloatArray2D pffft2D(FloatArray2D values, boolean scale)
    {
        int height = values.height;
        int width = values.width;
        int complexWidth = (width/2+1)*2;

        FloatArray2D result = new FloatArray2D(complexWidth, height);

        //do fft's in x direction
        float[] tempIn = new float[width];
        float[] tempOut;

        FftReal fft = new FftReal(width);

        for (int y = 0; y < height; y++)
        {
            tempOut = new float[complexWidth];

            for (int x = 0; x < width; x++)
                tempIn[x] = values.get(x,y);

            fft.realToComplex( -1, tempIn, tempOut);

            if (scale)
                fft.scale(width, tempOut);

            for (int x = 0; x < complexWidth; x++)
                result.set(tempOut[x], x, y);
        }

        // do fft's in y-direction on the complex numbers
        tempIn = new float[height*2];

        FftComplex fftc = new FftComplex(height);

        for (int x = 0; x < complexWidth/2; x++)
        {
            tempOut = new float[height*2];

            for (int y = 0; y < height; y++)
            {
                tempIn[y*2] = result.get(x*2,y);
                tempIn[y*2+1] = result.get(x*2+1,y);
            }

            fftc.complexToComplex(-1, tempIn, tempOut);

            for (int y = 0; y < height; y++)
            {
                result.set(tempOut[y*2], x*2, y);
                result.set(tempOut[y*2+1], x*2+1, y);
            }
        }


        return result;
    }

    public static FloatArray2D pffftInv2D(FloatArray2D values, int nfft)
    {
        int height = values.height;
        int width = nfft;
        int complexWidth = (width/2+1)*2;

        FloatArray2D result = new FloatArray2D(width, height);

        // do inverse fft's in y-direction on the complex numbers
        float[] tempIn = new float[height*2];
        float[] tempOut;

        FftComplex fftc = new FftComplex(height);

        for (int x = 0; x < complexWidth/2; x++)
        {
            tempOut = new float[height*2];

            for (int y = 0; y < height; y++)
            {
                tempIn[y*2] = values.get(x*2,y);
                tempIn[y*2+1] = values.get(x*2+1,y);
            }

            fftc.complexToComplex(1, tempIn, tempOut);

            for (int y = 0; y < height; y++)
            {
                values.set(tempOut[y*2], x*2, y);
                values.set(tempOut[y*2+1], x*2+1, y);
            }
        }

        //do inverse fft's in x direction
        tempIn = new float[complexWidth];

        FftReal fft = new FftReal(width);

        for (int y = 0; y < height; y++)
        {
            tempOut = new float[width];

            for (int x = 0; x < complexWidth; x++)
                tempIn[x] = values.get(x,y);

            fft.complexToReal( 1, tempIn, tempOut);

            fft.scale(width, tempOut);

            for (int x = 0; x < width; x++)
                result.set(tempOut[x], x, y);
        }

        return result;
    }

    public static FloatArray3D pffftInv3D(FloatArray3D values, int nfft)
    {
        int depth = values.depth;
        int height = values.height;
        int width = nfft;
        int complexWidth = (width/2+1)*2;

        FloatArray3D result = new FloatArray3D(width, height, depth);

        // do inverse fft's in z-direction on the complex numbers
        float[] tempIn = new float[depth*2];
        float[] tempOut;
        FftComplex fftc = new FftComplex(depth);

        for (int y = 0; y < height; y++)
            for (int x = 0; x < complexWidth/2; x++)
            {
                tempOut = new float[depth*2];

                for (int z = 0; z < depth; z++)
                {
                    tempIn[z*2] = values.get(x*2,y,z);
                    tempIn[z*2+1] = values.get(x*2+1,y,z);
                }

                fftc.complexToComplex(1, tempIn, tempOut);

                for (int z = 0; z < depth; z++)
                {
                    values.set(tempOut[z*2], x*2, y, z);
                    values.set(tempOut[z*2+1], x*2+1, y, z);
                }
            }


        // do inverse fft's in y-direction on the complex numbers
        tempIn = new float[height*2];
        fftc = new FftComplex(height);

        for (int z = 0; z < depth; z++)
            for (int x = 0; x < complexWidth/2; x++)
            {
                tempOut = new float[height*2];

                for (int y = 0; y < height; y++)
                {
                    tempIn[y*2] = values.get(x*2,y, z);
                    tempIn[y*2+1] = values.get(x*2+1,y, z);
                }

                fftc.complexToComplex(1, tempIn, tempOut);

                for (int y = 0; y < height; y++)
                {
                    values.set(tempOut[y*2], x*2, y, z);
                    values.set(tempOut[y*2+1], x*2+1, y, z);
                }
            }

        //do inverse fft's in x direction
        tempIn = new float[complexWidth];

        FftReal fft = new FftReal(width);

        for (int z = 0; z < depth; z++)
            for (int y = 0; y < height; y++)
            {
                tempOut = new float[width];

                for (int x = 0; x < complexWidth; x++)
                    tempIn[x] = values.get(x,y,z);

                fft.complexToReal( 1, tempIn, tempOut);

                fft.scale(width, tempOut);

                for (int x = 0; x < width; x++)
                    result.set(tempOut[x], x, y, z);
            }

        return result;
    }

    public static void rearrangeFFT(FloatArray2D values)
    {
        float[] fft = values.data;
        int w = values.width;
        int h = values.height;

        int halfDimYRounded = ( int ) Math.round( h / 2d );

        float buffer[] = new float[w];
        int pos1, pos2;

        for (int y = 0; y < halfDimYRounded; y++)
        {
            // copy upper line
            pos1 = y * w;
            for (int x = 0; x < w; x++)
                buffer[x] = fft[pos1++];

            // copy lower line to upper line
            pos1 = y * w;
            pos2 = (y+halfDimYRounded) * w;
            for (int x = 0; x < w; x++)
                fft[pos1++] = fft[pos2++];

            // copy buffer to lower line
            pos1 = (y+halfDimYRounded) * w;
            for (int x = 0; x < w; x++)
                fft[pos1++] = buffer[x];
        }
    }

    public static void rearrangeFFT(FloatArray3D values)
    {
        int w = values.width;
        int h = values.height;
        int d = values.depth;

        int halfDimYRounded = ( int ) Math.round( h / 2d );
        int halfDimZRounded = ( int ) Math.round( d / 2d );

        float buffer[] = new float[h];

        // swap data in y-direction
        for ( int x = 0; x < w; x++ )
            for ( int z = 0; z < d; z++ )
            {
                // cache first "half" to buffer
                for ( int y = 0; y < h / 2; y++ )
                    buffer[ y ] = values.get(x,y,z);

                // move second "half" to first "half"
                for ( int y = 0; y < halfDimYRounded; y++ )
                    values.set(values.get(x, y + h/2, z), x, y, z);

                // move data in buffer to second "half"
                for ( int y = halfDimYRounded; y < h; y++ )
                    values.set(buffer[ y - halfDimYRounded ], x, y, z);
            }

        buffer = new float[d];

        // swap data in z-direction
        for ( int x = 0; x < w; x++ )
            for ( int y = 0; y < h; y++ )
            {
                // cache first "half" to buffer
                for ( int z = 0; z < d/2; z++ )
                    buffer[ z ] = values.get(x, y, z);

                // move second "half" to first "half"
                for ( int z = 0; z < halfDimZRounded; z++ )
                    values.set(values.get(x, y, z + d/2 ), x, y, z);

                // move data in buffer to second "half"
                for ( int z = halfDimZRounded; z<d; z++ )
                    values.set(buffer[ z - halfDimZRounded ], x, y, z);
            }

    }

    // Actually, the 1D FFTs are not done in place, but only the 2D structure
    // is not cloned for processing
    public static void pffft2DInPlace(FloatArray2D values, int nfftX, boolean scale)
    {
        int height = values.height;
        int width = nfftX;
        int complexWidth = (width/2+1)*2;

        //do fft's in x direction
        float[] tempIn = new float[width];
        float[] tempOut;

        FftReal fft = new FftReal(width);

        for (int y = 0; y < height; y++)
        {
            tempOut = new float[complexWidth];

            for (int x = 0; x < width; x++)
                tempIn[x] = values.get(x,y);

            fft.realToComplex( -1, tempIn, tempOut);

            if (scale)
                fft.scale(width, tempOut);

            for (int x = 0; x < complexWidth; x++)
                values.set(tempOut[x], x, y);
        }

        // do fft's in y-direction on the complex numbers
        tempIn = new float[height*2];

        FftComplex fftc = new FftComplex(height);

        for (int x = 0; x < complexWidth/2; x++)
        {
            tempOut = new float[height*2];

            for (int y = 0; y < height; y++)
            {
                tempIn[y*2] = values.get(x*2,y);
                tempIn[y*2+1] = values.get(x*2+1,y);
            }

            fftc.complexToComplex(-1, tempIn, tempOut);

            for (int y = 0; y < height; y++)
            {
                values.set(tempOut[y*2], x*2, y);
                values.set(tempOut[y*2+1], x*2+1, y);
            }
        }
    }

    public static FloatArray3D pffft3DMT(final FloatArray3D values, final boolean scale)
    {
        final int height = values.height;
        final int width = values.width;
        final int depth = values.depth;
        final int complexWidth = (width/2+1)*2;

        final FloatArray3D result = new FloatArray3D(complexWidth, height, depth);

        //do fft's in x direction
        float[] tempIn = new float[width];
        float[] tempOut;

        FftReal fft = new FftReal(width);

        for (int z = 0; z < depth; z++)
            for (int y = 0; y < height; y++)
            {
                tempOut = new float[complexWidth];

                for (int x = 0; x < width; x++)
                    tempIn[x] = values.get(x,y,z);

                fft.realToComplex( -1, tempIn, tempOut);

                if (scale)
                    fft.scale(width, tempOut);

                for (int x = 0; x < complexWidth; x++)
                    result.set(tempOut[x], x, y, z);
            }

        // do fft's in y-direction on the complex numbers
        tempIn = new float[height*2];

        FftComplex fftc = new FftComplex(height);

        for (int z = 0; z < depth; z++)
            for (int x = 0; x < complexWidth/2; x++)
            {
                tempOut = new float[height*2];

                for (int y = 0; y < height; y++)
                {
                    tempIn[y*2] = result.get(x*2,y,z);
                    tempIn[y*2+1] = result.get(x*2+1,y,z);
                }

                fftc.complexToComplex(-1, tempIn, tempOut);

                for (int y = 0; y < height; y++)
                {
                    result.set(tempOut[y*2], x*2, y, z);
                    result.set(tempOut[y*2+1], x*2+1, y, z);
                }
            }

        // do fft's in z-direction on the complex numbers
        tempIn = new float[depth*2];
        fftc = new FftComplex(depth);

        for (int y = 0; y < height; y++)
            for (int x = 0; x < complexWidth/2; x++)
            {
                //tempOut = new float[height*2];
                tempOut = new float[depth*2];

                for (int z = 0; z < depth; z++)
                {
                    tempIn[z*2] = result.get(x*2,y,z);
                    tempIn[z*2+1] = result.get(x*2+1,y,z);
                }

                fftc.complexToComplex(-1, tempIn, tempOut);

                for (int z = 0; z < depth; z++)
                {
                    result.set(tempOut[z*2], x*2, y, z);
                    result.set(tempOut[z*2+1], x*2+1, y, z);
                }
            }

        return result;
    }

    public static FloatArray3D pffft3D(FloatArray3D values, boolean scale)
    {
        int height = values.height;
        int width = values.width;
        int depth = values.depth;
        int complexWidth = (width/2+1)*2;

        FloatArray3D result = new FloatArray3D(complexWidth, height, depth);

        //do fft's in x direction
        float[] tempIn = new float[width];
        float[] tempOut;

        FftReal fft = new FftReal(width);

        for (int z = 0; z < depth; z++)
            for (int y = 0; y < height; y++)
            {
                tempOut = new float[complexWidth];

                for (int x = 0; x < width; x++)
                    tempIn[x] = values.get(x,y,z);

                fft.realToComplex( -1, tempIn, tempOut);

                if (scale)
                    fft.scale(width, tempOut);

                for (int x = 0; x < complexWidth; x++)
                    result.set(tempOut[x], x, y, z);
            }

        // do fft's in y-direction on the complex numbers
        tempIn = new float[height*2];

        FftComplex fftc = new FftComplex(height);

        for (int z = 0; z < depth; z++)
            for (int x = 0; x < complexWidth/2; x++)
            {
                tempOut = new float[height*2];

                for (int y = 0; y < height; y++)
                {
                    tempIn[y*2] = result.get(x*2,y,z);
                    tempIn[y*2+1] = result.get(x*2+1,y,z);
                }

                fftc.complexToComplex(-1, tempIn, tempOut);

                for (int y = 0; y < height; y++)
                {
                    result.set(tempOut[y*2], x*2, y, z);
                    result.set(tempOut[y*2+1], x*2+1, y, z);
                }
            }

        // do fft's in z-direction on the complex numbers
        tempIn = new float[depth*2];
        fftc = new FftComplex(depth);

        for (int y = 0; y < height; y++)
            for (int x = 0; x < complexWidth/2; x++)
            {
                //tempOut = new float[height*2];
                tempOut = new float[depth*2];

                for (int z = 0; z < depth; z++)
                {
                    tempIn[z*2] = result.get(x*2,y,z);
                    tempIn[z*2+1] = result.get(x*2+1,y,z);
                }

                fftc.complexToComplex(-1, tempIn, tempOut);

                for (int z = 0; z < depth; z++)
                {
                    result.set(tempOut[z*2], x*2, y, z);
                    result.set(tempOut[z*2+1], x*2+1, y, z);
                }
            }

        return result;
    }

    public static void complexConjugate(float[] complex)
    {
        int wComplex = complex.length / 2;

        for (int pos = 0; pos < wComplex; pos++)
            complex[pos * 2 + 1] = -complex[pos * 2 + 1];
    }

    public static float multiplyComplexReal(float a, float b, float c, float d)
    {
        return a*c - b*d;
    }

    public static float multiplyComplexImg(float a, float b, float c, float d)
    {
        return a*d + b*c;
    }


    public static float[] multiply(float[] complexA, float[] complexB, boolean overwriteA)
    {
        if (complexA.length != complexB.length)
            return null;

        float[] complexResult = null;

        if (!overwriteA)
            complexResult = new float[complexA.length];

        // this is the amount of complex numbers
        // the actual array size is twice as high
        int wComplex = complexA.length / 2;

        // we compute: (a + bi) * (c + di)
        float a, b, c, d;

        if (!overwriteA)
            for (int pos = 0; pos < wComplex; pos++)
            {
                a = complexA[pos * 2];
                b = complexA[pos * 2 + 1];
                c = complexB[pos * 2];
                d = complexB[pos * 2 + 1];

                // compute new real part
                complexResult[pos * 2] = multiplyComplexReal(a, b, c, d);

                // compute new imaginary part
                complexResult[pos * 2 + 1] = multiplyComplexImg(a, b, c, d);
            }
        else
            for (int pos = 0; pos < wComplex; pos++)
            {
                a = complexA[pos * 2];
                b = complexA[pos * 2 + 1];
                c = complexB[pos * 2];
                d = complexB[pos * 2 + 1];

                // compute new real part
                complexA[pos * 2] = multiplyComplexReal(a, b, c, d);

                // compute new imaginary part
                complexA[pos * 2 + 1] = multiplyComplexImg(a, b, c, d);
            }

        if (overwriteA)
            return complexA;
        else
            return complexResult;
    }


    public static float divideComplexReal(float a, float b, float c, float d)
    {
        float ccdd = (c*c + d*d);

        if (ccdd > 1E-5)
            return (a*c + b*d)/(c*c + d*d);
        else
            return 0;
    }

    public static float divideComplexImg(float a, float b, float c, float d)
    {
        float ccdd = (c*c + d*d);

        if (ccdd > 1E-5)
            return (b*c - a*d)/ccdd;
        else
            return 0;
    }

    public static float[] divide(float[] complexA, float[] complexB, boolean overwriteA)
    {
        if (complexA.length != complexB.length)
        {
            System.err.println("mpi.fruitfly.fft.pffft.divide: Vectors do not have the same size!");
            return null;
        }

        float[] complexResult = null;

        if (!overwriteA)
            complexResult = new float[complexA.length];

        // this is the amount of complex numbers
        // the actual array size is twice as high
        int wComplex = complexA.length / 2;

        // we compute: (a + bi) / (c + di)
        float a, b, c, d;

        if (!overwriteA)
            for (int pos = 0; pos < wComplex; pos++)
            {
                a = complexA[pos * 2];
                b = complexA[pos * 2 + 1];
                c = complexB[pos * 2];
                d = complexB[pos * 2 + 1];

                // compute new real part
                complexResult[pos * 2] = divideComplexReal(a,b,c,d);

                // compute new imaginary part
                complexResult[pos * 2 + 1] = divideComplexImg(a,b,c,d);
            }
        else
            for (int pos = 0; pos < wComplex; pos++)
            {
                a = complexA[pos * 2];
                b = complexA[pos * 2 + 1];
                c = complexB[pos * 2];
                d = complexB[pos * 2 + 1];

                // compute new real part
                complexA[pos * 2] = divideComplexReal(a,b,c,d);

                // compute new imaginary part
                complexA[pos * 2 + 1] = divideComplexImg(a,b,c,d);
            }



        if (overwriteA)
            return complexA;
        else
            return complexResult;
    }

}
