package mpi.fruitfly.registration;

/**
 * <p>Title: </p>
 *
 * <p>Description: </p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: </p>
 *
 * @author not attributable
 * @version 1.0
 */

import mpi.fruitfly.math.datastructures.*;
import static mpi.fruitfly.math.General.*;
import java.util.Random;
import ij.process.FloatProcessor;

public class ImageFilter
{
    private static Thread[] newThreads()
    {
      int nthread = Runtime.getRuntime().availableProcessors();
      return new Thread[nthread];
    }

    private static void startAndJoin(Thread[] threads)
    {
        for (int ithread = 0; ithread < threads.length; ++ithread)
            threads[ithread].start();
        try
        {
            for (int ithread = 0; ithread < threads.length; ++ithread)
                threads[ithread].join();
        } catch (InterruptedException ie)
        {
            throw new RuntimeException(ie);
        }
    }

    /**
     * Does Kaiser-Bessel-Windowing to prevent the fourier spectra from getting infinite numbers,
     * the border will be faded to black.
     *
     * @param FloatArray2D  image Input image stored in row-major order
     * @param inverse If true, fading to black in the middle
     * @return FloatArray2D The resulting image
     */
    public static void filterKaiserBessel(FloatArray2D img, boolean inverse)
    {
        int x, y;
        int w = img.width;
        int h = img.height;

        float twoPiDivWidth = (float)(2.0 * Math.PI) / (float)w;
        float twoPiDivHeight = (float)(2.0 * Math.PI) / (float)h;
        float kb, val;

        // pre-compute filtering values
        float[] kbx = new float[w];
        float[] kby = new float[h];

        for (x = 0; x < w; x++)
            kbx[x] = (float)(0.40243 - (0.49804 * Math.cos(twoPiDivWidth * (float) x)) +
                     (0.09831 * Math.cos(twoPiDivWidth * 2.0 * (float) x)) -
                     (0.00122 * Math.cos(twoPiDivWidth * 3.0 * (float) x)));

        for (y = 0; y < h; y++)
            kby[y] = (float)(0.40243 - (0.49804 * Math.cos(twoPiDivHeight * (float) y )) +
                     (0.09831 * Math.cos(twoPiDivHeight * 2.0 * (float) y )) -
                     (0.00122 * Math.cos(twoPiDivHeight * 3.0 * (float) y )));

        for (y = 0; y < h; y++)
            for (x = 0; x < w; x++)
            {
                val = (float) img.get(x,y);
                kb = kbx[x];

                if (inverse)
                    kb = 1.0f - kb;

                img.set(kb * val, x, y);
            }

        for (x = 0; x < w; x++)
            for (y = 0; y < h; y++)
            {
                val = (float) img.get(x,y);
                kb = kby[y];

                if (inverse)
                    kb = 1.0f - kb;

                img.set(kb * val, x, y);
            }
    }

    /**
     * This class creates a gaussian kernel
     *
     * @param sigma Standard Derivation of the gaussian function
     * @param normalize Normalize integral of gaussian function to 1 or not...
     * @return float[] The gaussian kernel
     *
     * @author   Stephan Saalfeld
     */
    public static float[] createGaussianKernel1D(float sigma, boolean normalize)
    {
        int size = 3;
        float[] gaussianKernel;

        if (sigma <= 0)
        {
         gaussianKernel = new float[3];
         gaussianKernel[1] = 1;
        }
        else
        {
         size = max(3, (int)(2*(int)(3*sigma + 0.5)+1));

         float two_sq_sigma = 2*sigma*sigma;
         gaussianKernel = new float[size];

         for (int x = size/2; x >= 0; --x)
         {
             float val = (float)Math.exp(-(float)(x*x)/two_sq_sigma);

             gaussianKernel[size/2-x] = val;
             gaussianKernel[size/2+x] = val;
         }
     }

     if (normalize)
     {
         float sum = 0;
         for (float value : gaussianKernel)
             sum += value;

         for (int i = 0; i < gaussianKernel.length; i++)
             gaussianKernel[i] /= sum;
     }


        return gaussianKernel;
    }

    public static void normalize(FloatArray img)
    {
        // compute average
        /*float avg = 0;
        for (float value : img.data)
            avg += value;

        avg /= (float)img.data.length;*/

        float avg = computeTurkeyBiMedian(img.data, 5);

        // compute stdev
        float stDev = 0;
        for (float value : img.data)
            stDev += (value - avg) * (value - avg);

        stDev /= (float)(img.data.length - 1);
        stDev = (float)Math.sqrt(stDev);

        float min = Float.MAX_VALUE;
        for (int i = 0; i < img.data.length; i++)
        {
            img.data[i] = (img.data[i] - avg) / stDev;
            if (img.data[i] < min)
                min = img.data[i];
        }

        for (int i = 0; i < img.data.length; i++)
            img.data[i] -= min;

    }

    public static FloatArray2D createGaussianKernel2D(float sigma, boolean normalize)
    {
        int size = 3;
        FloatArray2D gaussianKernel;

        if (sigma <= 0)
        {
         gaussianKernel = new FloatArray2D(3, 3);
         gaussianKernel.data[4] = 1;
        }
        else
        {
         size = max(3, (int)(2*(int)(3*sigma + 0.5)+1));

         float two_sq_sigma = 2*sigma*sigma;
         gaussianKernel = new FloatArray2D(size, size);

         for (int y = size/2; y >= 0; --y)
         {
             for (int x = size/2; x >= 0; --x)
             {
              float val = (float)Math.exp(-(float)(y*y+x*x)/two_sq_sigma);

              gaussianKernel.set(val, size/2-x, size/2-y);
              gaussianKernel.set(val, size/2-x, size/2+y);
              gaussianKernel.set(val, size/2+x, size/2-y);
              gaussianKernel.set(val, size/2+x, size/2+y);
             }
         }
        }

        if (normalize)
        {
            float sum = 0;
            for (float value : gaussianKernel.data)
                sum += value;

            for (int i = 0; i < gaussianKernel.data.length; i++)
                gaussianKernel.data[i] /= sum;
        }


        return gaussianKernel;
    }

	/*
	** create a normalized gaussian impulse with appropriate size and offset center
	*/
	static public FloatArray2D create_gaussian_kernel_2D_offset(float sigma, float offset_x, float offset_y,
					     boolean normalize)
	{
	    int size = 3;
	    FloatArray2D gaussian_kernel;
	    if (sigma == 0)
	    {
		gaussian_kernel = new FloatArray2D(3 ,3);
		gaussian_kernel.data[4] = 1;
	    }
	    else
	    {
		size = max(3, (int)(2*Math.round(3*sigma)+1));
		float two_sq_sigma = 2*sigma*sigma;
	//      float normalization_factor = 1.0/(float)M_PI/two_sq_sigma;
		gaussian_kernel = new FloatArray2D(size, size);
		for (int x = size-1; x >= 0; --x)
		{
		    float fx = (float)(x-size/2);
		    for (int y = size-1; y >= 0; --y)
		    {
			float fy = (float)(y-size/2);
			float val = (float)(Math.exp(-(Math.pow(fx-offset_x, 2)+Math.pow(fy-offset_y, 2))/two_sq_sigma));
			gaussian_kernel.set(val, x, y);
		    }
		}
	    }
	    if (normalize) 
		{
		    float sum = 0;
		    for (float value : gaussian_kernel.data)
			sum += value;

		    for (int i = 0; i < gaussian_kernel.data.length; i++)
			gaussian_kernel.data[i] /= sum;
		}

	    return gaussian_kernel;
	}

    public static FloatArray3D createGaussianKernel3D(float sigma, boolean normalize)
    {
        int size = 3;
        FloatArray3D gaussianKernel;

        if (sigma <= 0)
        {
         gaussianKernel = new FloatArray3D(3, 3, 3);
         gaussianKernel.set(1f, 1, 1, 1);
        }
        else
        {
         size = max(3, (int)(2*(int)(3*sigma + 0.5)+1));

         float two_sq_sigma = 2*sigma*sigma;
         gaussianKernel = new FloatArray3D(size, size, size);

         for (int z = size/2; z >= 0; --z)
         {
             for (int y = size / 2; y >= 0; --y)
             {
                 for (int x = size / 2; x >= 0; --x)
                 {
                     float val = (float) Math.exp( -(float) (z * z + y * y + x * x) / two_sq_sigma);

                     gaussianKernel.set(val, size / 2 - x, size / 2 - y, size / 2 - z);

                     gaussianKernel.set(val, size / 2 - x, size / 2 - y, size / 2 + z);
                     gaussianKernel.set(val, size / 2 - x, size / 2 + y, size / 2 - z);
                     gaussianKernel.set(val, size / 2 + x, size / 2 - y, size / 2 - z);

                     gaussianKernel.set(val, size / 2 + x, size / 2 + y, size / 2 - z);
                     gaussianKernel.set(val, size / 2 + x, size / 2 - y, size / 2 + z);
                     gaussianKernel.set(val, size / 2 - x, size / 2 + y, size / 2 + z);

                     gaussianKernel.set(val, size / 2 + x, size / 2 + y, size / 2 + z);
                 }
             }
         }
        }

        if (normalize)
        {
            float sum = 0;
            for (float value : gaussianKernel.data)
                sum += value;

            for (int i = 0; i < gaussianKernel.data.length; i++)
                gaussianKernel.data[i] /= sum;
        }


        return gaussianKernel;
    }

    public static FloatArray2D computeIncreasingGaussianX(FloatArray2D input, float stDevStart, float stDevEnd)
    {
        FloatArray2D output = new FloatArray2D(input.width, input.height);

        int width = input.width;
        float changeFilterSize = (float)(stDevEnd - stDevStart)/(float)width;
        float sigma;
        int filterSize;

        float avg;

        for (int x = 0; x < input.width; x++)
        {
            sigma = stDevStart + changeFilterSize * (float) x;
            FloatArray2D kernel = createGaussianKernel2D(sigma, true);
            filterSize = kernel.width;

            for (int y = 0; y < input.height; y++)
            {
                avg = 0;

                for (int fx = -filterSize / 2; fx <= filterSize / 2; fx++)
                    for (int fy = -filterSize / 2; fy <= filterSize / 2; fy++)
                    {
                        try
                        {
                            avg += input.get(x + fx, y + fy) * kernel.get(fx + filterSize/2, fy + filterSize/2);
                        }
                        catch (Exception e)
                        {}
                        ;

                    }

                output.set(avg, x, y);
            }
        }
        return output;
    }

    public static FloatArray2D computeGaussian(FloatArray2D input, float sigma)
    {
        FloatArray2D output = new FloatArray2D(input.width, input.height);

        float avg, kernelsum;

        FloatArray2D kernel = createGaussianKernel2D(sigma, true);
        int filterSize = kernel.width;

        for (int x = 0; x < input.width; x++)
        {
            for (int y = 0; y < input.height; y++)
            {
                avg = 0;
                kernelsum = 0;

                for (int fx = -filterSize / 2; fx <= filterSize / 2; fx++)
                    for (int fy = -filterSize / 2; fy <= filterSize / 2; fy++)
                    {
                        try
                        {
                            avg += input.get(x + fx, y + fy) * kernel.get(fx + filterSize/2, fy + filterSize/2);
                            kernelsum += kernel.get(fx + filterSize/2, fy + filterSize/2);
                        }
                        catch (Exception e)
                        {};

                    }

                output.set(avg/kernelsum, x, y);
            }
        }
        return output;
    }

    public static FloatArray3D computeGaussian(FloatArray3D input, float sigma)
    {
        FloatArray3D output = new FloatArray3D(input.width, input.height, input.depth);

        float avg, kernelsum;

        FloatArray3D kernel = createGaussianKernel3D(sigma, true);
        int filterSize = kernel.width;

        for (int x = 0; x < input.width; x++)
        {
            for (int y = 0; y < input.height; y++)
            {
                for (int z = 0; z < input.depth; z++)
                {
                    avg = 0;
                    kernelsum = 0;

                    for (int fx = -filterSize / 2; fx <= filterSize / 2; fx++)
                        for (int fy = -filterSize / 2; fy <= filterSize / 2; fy++)
                            for (int fz = -filterSize / 2; fz <= filterSize / 2; fz++)
                            {
                                try
                                {
                                    avg += input.get(x + fx, y + fy, z + fz) * kernel.get(fx + filterSize / 2, fy + filterSize / 2, fz + filterSize / 2);
                                    kernelsum += kernel.get(fx + filterSize / 2, fy + filterSize / 2, fz + filterSize / 2);
                                } catch (Exception e)
                                {}
                                ;

                            }

                    output.set(avg / kernelsum, x, y, z);
                }
            }
        }
        return output;
    }

    public static FloatArray3D computeGaussianFast(FloatArray3D input, float sigma)
    {
        FloatArray3D output = new FloatArray3D(input.width, input.height, input.depth);

        float avg, kernelsum;

        float[] kernel = createGaussianKernel1D(sigma, true);

        int filterSize = kernel.length;

        // fold in x
        for (int x = 0; x < input.width; x++)
            for (int y = 0; y < input.height; y++)
                for (int z = 0; z < input.depth; z++)
                {
                    avg = 0;
                    kernelsum = 0;

                    for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                    {
                        if (x+f >= 0 && x+f < input.width)
                        {
                            avg += input.get(x + f, y, z) * kernel[f + filterSize / 2];
                            kernelsum += kernel[f + filterSize / 2];
                        }
                    }

                    output.set(avg / kernelsum, x, y, z);

                }

        // fold in y
        for (int x = 0; x < input.width; x++)
            for (int z = 0; z < input.depth; z++)
            {
                float[] temp = new float[input.height];

                for (int y = 0; y < input.height; y++)
                {
                    avg = 0;
                    kernelsum = 0;

                    for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                    {
                        if (y+f >= 0 && y+f < input.height)
                        {
                            avg += output.get(x, y + f, z) * kernel[f + filterSize / 2];
                            kernelsum += kernel[f + filterSize / 2];
                        }
                    }
                    temp[y] = avg / kernelsum;
                }

                for (int y = 0; y < input.height; y++)
                    output.set(temp[y], x, y, z);
            }

        // fold in z
        for (int x = 0; x < input.width; x++)
            for (int y = 0; y < input.height; y++)
            {
                float[] temp = new float[input.depth];

                for (int z = 0; z < input.depth; z++)
                {
                    avg = 0;
                    kernelsum = 0;

                    for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                    {
                        if (z+f >= 0 && z+f < input.depth)
                        {
                            avg += output.get(x, y, z + f) * kernel[f + filterSize / 2];
                            kernelsum += kernel[f + filterSize / 2];
                        }
                    }
                    temp[z] = avg / kernelsum;
                }

                for (int z = 0; z < input.depth; z++)
                    output.set(temp[z], x, y, z);

            }
        return output;
    }

    public static FloatArray2D computeGaussianFastMirror(FloatArray2D input, float sigma)
    {
        FloatArray2D output = new FloatArray2D(input.width, input.height);

        float avg, kernelsum = 0;
        float[] kernel = createGaussianKernel1D(sigma, true);
        int filterSize = kernel.length;

        // get kernel sum
        for (double value : kernel)
            kernelsum += value;

        // fold in x
        for (int x = 0; x < input.width; x++)
            for (int y = 0; y < input.height; y++)
                {
                    avg = 0;

                    if (x -filterSize / 2 >= 0 && x + filterSize / 2 < input.width)
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                            avg += input.get(x + f, y) * kernel[f + filterSize / 2];
                    else
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                        {
                            avg += input.getMirror(x + f, y) * kernel[f + filterSize / 2];

                            /*if (x+f >= input.width)
                                avg += input.get(2*input.width - x - f - 1, y) * kernel[f + filterSize / 2];
                            else if (x + f < 0)
                                avg += input.get(-(x + f) - 1, y) * kernel[f + filterSize / 2];
                            else
                                avg += input.get(x + f, y) * kernel[f + filterSize / 2];
                             */
                        }

                    output.set(avg / kernelsum, x, y);

                }

        // fold in y
        for (int x = 0; x < input.width; x++)
            {
                float[] temp = new float[input.height];

                for (int y = 0; y < input.height; y++)
                {
                    avg = 0;

                    if (y -filterSize / 2 >= 0 && y + filterSize / 2 < input.height)
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                            avg += output.get(x, y + f) * kernel[f + filterSize / 2];
                     else
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                        {
                            avg += output.getMirror(x, y + f) * kernel[f + filterSize / 2];

                            /*if (y+f >= input.height)
                                avg += output.get(x, 2*input.height - y - f - 1) * kernel[f + filterSize / 2];
                            else if (y + f < 0)
                                avg += output.get(x, -(y + f) - 1) * kernel[f + filterSize / 2];
                            else
                                avg += output.get(x, y + f) * kernel[f + filterSize / 2];*/
                        }

                    temp[y] = avg / kernelsum;
                }

                for (int y = 0; y < input.height; y++)
                    output.set(temp[y], x, y);
            }

        return output;
    }

    /**
     * This class does the gaussian filtering of an image. On the edges of
     * the image it does mirror the pixels. It also uses the seperability of
     * the gaussian convolution.
     *
     * @param input FloatProcessor which should be folded (will not be touched)
     * @param sigma Standard Derivation of the gaussian function
     * @return FloatProcessor The folded image
     *
     * @author   Stephan Preibisch
     */
    public static FloatProcessor computeGaussianFastMirror(FloatProcessor input, float sigma)
    {
        int width = input.getWidth();
        int height = input.getHeight();
        FloatProcessor output = new FloatProcessor(width, height);

        float avg, kernelsum = 0;
        float[] kernel = createGaussianKernel1D(sigma, true);
        int filterSize = kernel.length;

        // get kernel sum
        for (double value : kernel)
            kernelsum += value;

        // fold in x
        for (int x = 0; x < width; x++)
            for (int y = 0; y <height; y++)
                {
                    avg = 0;

                    if (x -filterSize / 2 >= 0 && x + filterSize / 2 < width)
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                            avg += input.get(x + f, y) * kernel[f + filterSize / 2];
                    else
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                        {
                            if (x+f >= width)
                                avg += input.get(2*width - x - f - 1, y) * kernel[f + filterSize / 2];
                            else if (x + f < 0)
                                avg += input.get(-(x + f) - 1, y) * kernel[f + filterSize / 2];
                            else
                                avg += input.get(x + f, y) * kernel[f + filterSize / 2];

                        }

                    output.putPixelValue(x, y, avg / kernelsum);
                }

        // fold in y
        for (int x = 0; x < width; x++)
            {
                float[] temp = new float[height];

                for (int y = 0; y < height; y++)
                {
                    avg = 0;

                    if (y -filterSize / 2 >= 0 && y + filterSize / 2 < height)
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                            avg += output.get(x, y + f) * kernel[f + filterSize / 2];
                     else
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                        {
                            if (y+f >= height)
                                avg += output.get(x, 2*height - y - f - 1) * kernel[f + filterSize / 2];
                            else if (y + f < 0)
                                avg += output.get(x, -(y + f) - 1) * kernel[f + filterSize / 2];
                            else
                                avg += output.get(x, y + f) * kernel[f + filterSize / 2];
                        }

                    temp[y] = avg / kernelsum;
                }

                for (int y = 0; y < height; y++)
                    output.putPixelValue(x, y, temp[y]);
            }

        return output;
    }

    public static FloatArray3D computeGaussianFastMirror(FloatArray3D input, float sigma)
    {
        FloatArray3D output = new FloatArray3D(input.width, input.height, input.depth);

        float avg, kernelsum = 0;
        float[] kernel = createGaussianKernel1D(sigma, true);
        int filterSize = kernel.length;

        // get kernel sum
        for (double value : kernel)
            kernelsum += value;

        // fold in x
        for (int x = 0; x < input.width; x++)
            for (int y = 0; y < input.height; y++)
                for (int z = 0; z < input.depth; z++)
                {
                    avg = 0;

                    if (x -filterSize / 2 >= 0 && x + filterSize / 2 < input.width)
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                            avg += input.get(x + f, y, z) * kernel[f + filterSize / 2];
                    else
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                        {
                            if (x+f >= input.width)
                                avg += input.get(2*input.width - x - f - 1, y, z) * kernel[f + filterSize / 2];
                            else if (x + f < 0)
                                avg += input.get(-(x + f) - 1, y, z) * kernel[f + filterSize / 2];
                            else
                                avg += input.get(x + f, y, z) * kernel[f + filterSize / 2];

                        }

                    output.set(avg / kernelsum, x, y, z);

                }

        // fold in y
        for (int x = 0; x < input.width; x++)
            for (int z = 0; z < input.depth; z++)
            {
                float[] temp = new float[input.height];

                for (int y = 0; y < input.height; y++)
                {
                    avg = 0;

                    if (y -filterSize / 2 >= 0 && y + filterSize / 2 < input.height)
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                            avg += output.get(x, y + f, z) * kernel[f + filterSize / 2];
                     else
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                        {
                            if (y+f >= input.height)
                                avg += output.get(x, 2*input.height - y - f - 1, z) * kernel[f + filterSize / 2];
                            else if (y + f < 0)
                                avg += output.get(x, -(y + f) - 1,  z) * kernel[f + filterSize / 2];
                            else
                                avg += output.get(x, y + f, z) * kernel[f + filterSize / 2];
                        }

                    temp[y] = avg / kernelsum;
                }

                for (int y = 0; y < input.height; y++)
                    output.set(temp[y], x, y, z);
            }

        // fold in z
        for (int x = 0; x < input.width; x++)
            for (int y = 0; y < input.height; y++)
            {
                float[] temp = new float[input.depth];

                for (int z = 0; z < input.depth; z++)
                {
                    avg = 0;

                    if (z -filterSize / 2 >= 0 && z + filterSize / 2 < input.depth)
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                            avg += output.get(x, y, z + f) * kernel[f + filterSize / 2];
                     else
                        for (int f = -filterSize / 2; f <= filterSize / 2; f++)
                        {
                            if (z+f >= input.depth)
                                avg += output.get(x, y, 2*input.depth - z - f - 1) * kernel[f + filterSize / 2];
                            else if (z + f < 0)
                                avg += output.get(x, y, -(z + f) - 1) * kernel[f + filterSize / 2];
                            else
                                avg += output.get(x, y, z + f) * kernel[f + filterSize / 2];
                        }

                    temp[z] = avg / kernelsum;
                }

                for (int z = 0; z < input.depth; z++)
                    output.set(temp[z], x, y, z);

            }
        return output;
    }

    public static FloatArray2D distortSamplingX(FloatArray2D input)
    {
        FloatArray2D output = new FloatArray2D(input.width, input.height);

        int filterSize = 3;
        float avg;

        Random rnd = new Random(353245632);

        for (int x = 0; x < input.width; x++)
        {
            FloatArray2D kernel = new FloatArray2D(3,1);

            float random = (rnd.nextFloat()-0.5f)*2;
            float val1, val2, val3;

            if (random < 0)
            {
                val1 = -random;
                val2 = 1+random;
                val3 = 0;
            }
            else
            {
                val3 = random;
                val2 = 1-random;
                val1 = 0;
            }

            kernel.set(val1, 0, 0);
            kernel.set(val2, 1, 0);
            kernel.set(val3, 2, 0);

            for (int y = 0; y < input.height; y++)
            {
                avg = 0;

                for (int fx = -filterSize / 2; fx <= filterSize / 2; fx++)
                {
                    try
                    {
                        avg += input.get(x + fx, y) * kernel.get(fx + filterSize / 2, 0);
                    } catch (Exception e)
                    {}
                    ;
                }

                output.set(avg, x, y);
            }
        }
        return output;
    }

    public static FloatArray2D distortSamplingY(FloatArray2D input)
    {
        FloatArray2D output = new FloatArray2D(input.width, input.height);

        int filterSize = 3;
        float avg;

        Random rnd = new Random(7893469);

        for (int y = 0; y < input.height; y++)
        {
            FloatArray2D kernel = new FloatArray2D(1,3);

            float random = (rnd.nextFloat()-0.5f)*2;
            float val1, val2, val3;

            if (random < 0)
            {
                val1 = -random;
                val2 = 1+random;
                val3 = 0;
            }
            else
            {
                val3 = random;
                val2 = 1-random;
                val1 = 0;
            }

            kernel.set(val1, 0, 0);
            kernel.set(val2, 0, 1);
            kernel.set(val3, 0, 2);

            for (int x = 0; x < input.width; x++)
            {
                avg = 0;

                for (int fy = -filterSize / 2; fy <= filterSize / 2; fy++)
                {
                    try
                    {
                        avg += input.get(x, y + fy) * kernel.get(0, fy + filterSize / 2);
                    } catch (Exception e)
                    {}
                    ;
                }

                output.set(avg, x, y);
            }
        }
        return output;
    }

    public static FloatArray3D computeSobelFilter(FloatArray3D input)
    {
        FloatArray3D output = new FloatArray3D(input.width, input.height, input.depth);

        float sobelX, sobelY, sobelZ;
        float v1, v2, v3, v4, v5, v6, v7, v8, v9, v10;

        for (int z = 1; z < input.depth -1 ; z++)
            for (int y = 1; y < input.height -1 ; y++)
                for (int x = 1; x < input.width -1; x++)
                {
                    // Sobel in X
                    v1 = input.get(x-1,y,z-1);
                    v2 = input.get(x+1,y,z-1);

                    v3 = input.get(x-1,y-1,z);
                    v4 = input.get(x-1,y,  z);
                    v5 = input.get(x-1,y+1,z);

                    v6 = input.get(x+1,y-1,z);
                    v7 = input.get(x+1,y,  z);
                    v8 = input.get(x+1,y+1,z);

                    v9 = input.get(x-1,y,z+1);
                    v10 = input.get(x+1,y,z+1);

                    sobelX = v1    + (-v2)   +
                             v3    + (2*v4)  + v5 +
                             (-v6) + (-2*v7) + (-v8) +
                             v9    + (-v10);

                    // Sobel in Y
                    v1 = input.get(x,y-1,z-1);
                    v2 = input.get(x,y+1,z-1);

                    v3 = input.get(x-1,y-1,z);
                    v4 = input.get(x,  y-1,z);
                    v5 = input.get(x+1,y-1,z);

                    v6 = input.get(x-1,y+1,z);
                    v7 = input.get(x  ,y+1,z);
                    v8 = input.get(x+1,y+1,z);

                    v9 =  input.get(x,y-1,z+1);
                    v10 = input.get(x,y+1,z+1);

                    sobelY = v1    + (-v2)   +
                             v3    + (2*v4)  + v5 +
                             (-v6) + (-2*v7) + (-v8) +
                             v9    + (-v10);


                    // Sobel in Z
                    v1 = input.get(x  ,y-1,z-1);
                    v2 = input.get(x-1,y,  z-1);
                    v3 = input.get(x  ,y,  z-1);
                    v4 = input.get(x+1,y,  z-1);
                    v5 = input.get(x  ,y+1,z-1);

                    v6 =  input.get(x  ,y-1,z+1);
                    v7 =  input.get(x-1,y,  z+1);
                    v8 =  input.get(x  ,y,  z+1);
                    v9 =  input.get(x+1,y,  z+1);
                    v10 = input.get(x  ,y+1,z+1);

                    sobelZ =         v1      +
                             v2    + (2*v3)  + v4    +
                                     v5      +
                                     (-v6)   +
                             (-v7) + (-2*v8) + (-v9) +
                                     (-v10);


                    output.set((float)Math.sqrt(Math.pow(sobelX,2) + Math.pow(sobelY,2)  + Math.pow(sobelZ,2)), x, y, z);
                }

        return output;
    }

    public static FloatArray3D computeBinaryFilter3(FloatArray3D input, float threshold)
    {
        FloatArray3D output = new FloatArray3D(input.width, input.height, input.depth);

        for (int z = 1; z < input.depth - 1; z++)
            for (int y = 1; y < input.height - 1; y++)
                for (int x = 1; x < input.width - 1; x++)
                {
                    float avg = 0;

                    for (int xs = x -1; xs <= x + 1; xs++)
                        for (int ys = y -1; ys <= y + 1; ys++)
                            for (int zs = z -1; zs <= z + 1; zs++)
                                avg += input.get(xs, ys, zs);

                    avg /= 27f;

                    if (avg > threshold)
                        output.set(1, x, y, z);
                    else
                        output.set(0, x, y, z);
                }

        return output;
    }

    public static FloatArray3D computeBinaryPlusSobelFilter3(FloatArray3D input, float threshold)
    {
        FloatArray3D output = computeSobelFilter(input);

        // find maximum
        float max = 0;
        float min = Float.MAX_VALUE;

        for (float value : output.data)
        {
            if (value > max)
                max = value;

            if (value < min)
                min = value;
        }

        // norm to 0...1
        for (int i = 0; i < output.data.length; i++)
            output.data[i] = (output.data[i]-min)/(max-min)*2;

        // add
        for (int z = 1; z < input.depth - 1; z++)
            for (int y = 1; y < input.height - 1; y++)
                for (int x = 1; x < input.width - 1; x++)
                {
                    float avg = 0;

                    for (int xs = x -1; xs <= x + 1; xs++)
                        for (int ys = y -1; ys <= y + 1; ys++)
                            for (int zs = z -1; zs <= z + 1; zs++)
                                avg += input.get(xs, ys, zs);

                    avg /= 27f;

                    if (avg > threshold)
                        output.set(output.get(x,y,z) + 1, x, y, z);
                }

        return output;
    }

    public static FloatArray2D computeLaPlaceFilter3(FloatArray2D input)
    {
        FloatArray2D output = new FloatArray2D(input.width, input.height);

        float derivX, derivY;
        float x1, x2, x3;
        float y1, y2, y3;

        for (int y = 1; y < input.height -1 ; y++)
            for (int x = 1; x < input.width -1; x++)
            {
                x1 = input.get(x-1,y);
                x2 = input.get(x,y);
                x3 = input.get(x+1,y);

                derivX = x1 - 2*x2 + x3;

                y1 = input.get(x,y-1);
                y2 = input.get(x,y);
                y3 = input.get(x,y+1);

                derivY = y1 - 2*y2 + y3;

                output.set((float)Math.sqrt(Math.pow(derivX,2) + Math.pow(derivY,2)), x, y);
            }

        return output;
    }

    /*public static void computeLaPlaceFilterInPlace3(FloatArray2D input)
    {

        float buffer = max(input.height, input.width);

        float derivX, derivY;
        float x1, x2, x3;
        float y1, y2, y3;

        for (int diag = 1; diag < input.width + input.height - 1; diag++)
        {

        }

        for (int y = 1; y < input.height -1 ; y++)
            for (int x = 1; x < input.width -1; x++)
            {
                x1 = input.get(x-1,y);
                x2 = input.get(x,y);
                x3 = input.get(x+1,y);

                derivX = x1 - 2*x2 + x3;

                y1 = input.get(x,y-1);
                y2 = input.get(x,y);
                y3 = input.get(x,y+1);

                derivY = y1 - 2*y2 + y3;

                //output.set((float)Math.sqrt(Math.pow(derivX,2) + Math.pow(derivY,2)), x, y);
            }

        return;
    }*/

    public static FloatArray2D computeLaPlaceFilter5(FloatArray2D input)
    {
        FloatArray2D output = new FloatArray2D(input.width, input.height);

        float derivX, derivY;
        float x1, x3, x5;
        float y1, y3, y5;

        for (int y = 2; y < input.height -2 ; y++)
            for (int x = 2; x < input.width -2; x++)
            {
                x1 = input.get(x-2,y);
                x3 = input.get(x,y);
                x5 = input.get(x+2,y);

                derivX = x1 - 2*x3 + x5;

                y1 = input.get(x,y-2);
                y3 = input.get(x,y);
                y5 = input.get(x,y+2);

                derivY = y1 - 2*y3 + y5;

                output.set((float)Math.sqrt(Math.pow(derivX,2) + Math.pow(derivY,2)), x, y);
            }

        return output;
    }

    public static FloatArray3D computeLaPlaceFilter5(FloatArray3D input)
    {
        FloatArray3D output = new FloatArray3D(input.width, input.height, input.depth);

        float derivX, derivY, derivZ;
        float x1, x3, x5;
        float y1, y3, y5;
        float z1, z3, z5;

        for (int z = 2; z < input.depth -2 ; z++)
            for (int y = 2; y < input.height -2 ; y++)
                for (int x = 2; x < input.width -2; x++)
                {
                    x1 = input.get(x-2,y,z);
                    x3 = input.get(x,y,z);
                    x5 = input.get(x+2,y,z);

                    derivX = x1 - 2*x3 + x5;

                    y1 = input.get(x,y-2,z);
                    y3 = input.get(x,y,z);
                    y5 = input.get(x,y+2,z);

                    derivY = y1 - 2*y3 + y5;

                    z1 = input.get(x,y,z-2);
                    z3 = input.get(x,y,z);
                    z5 = input.get(x,y,z+2);

                    derivZ = z1 - 2*z3 + z5;

                    output.set((float)Math.sqrt(Math.pow(derivX,2) + Math.pow(derivY,2)  + Math.pow(derivZ,2)), x, y, z);
                }

        return output;
    }
}
