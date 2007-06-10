package mpi.fruitfly.registration;

import ij.process.ImageProcessor;
import ij.process.FloatProcessor;
import ij.ImagePlus;
import ij.io.Opener;

import java.awt.Point;
import java.io.PrintWriter;
import java.awt.Color;

import mpi.fruitfly.math.Quicksortable;
import mpi.fruitfly.math.datastructures.FloatArray2D;
import mpi.fruitfly.fft.pffft;

import static mpi.fruitfly.general.fileParsing.*;
import static mpi.fruitfly.general.fileAccess.*;
import static mpi.fruitfly.general.ArrayConverter.*;
import static mpi.fruitfly.general.ImageArrayConverter.*;
import static mpi.fruitfly.math.General.*;
import static mpi.fruitfly.fft.pffft.*;
import static mpi.fruitfly.registration.ImageFilter.*;

import ij.IJ;
import edu.mines.jtk.dsp.FftComplex;
import edu.mines.jtk.dsp.FftReal;



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

public class PhaseCorrelation2D
{
    ///private int imgW1, imgW2, imgH1, imgH2;
    private int heightZP, widthZP;
    private boolean kaiserBessel, normalize, showImages;

    private ImagePlus phaseCorrelationImg, orgImg1, orgImg2;
    private float[] pixels;
    private Point[] location;
    private Point bestTranslation;

    public class CrossCorrelationResult implements Quicksortable
    {
        public Point shift, shiftPos;
        public int overlappingPixels;
        public double SSQ, R;
        public ImagePlus overlapImp, errorMapImp, img1, img2;

        public double getQuicksortValue()
        {
            return 1-R;
        }
    }

    public PhaseCorrelation2D(String image1, String image2, int checkImages, boolean kaiserBessel, boolean normalize, boolean showImages)
    {
        // load images
        ImagePlus img1 = new Opener().openImage(image1);
        ImagePlus img2 = new Opener().openImage(image2);

        this.kaiserBessel = kaiserBessel;
        this.normalize = normalize;
        this.showImages = showImages;

        if (showImages)
        {
            img1.show();
            img2.show();
        }

        computePhaseCorrelation(img1, img2, checkImages);
    }

/*    public PhaseCorrelation2D(double[] img1, int w1, int h1, double[] img2, int w2, int h2, boolean kaiserBessel, boolean showImages)
    {
        this.kaiserBessel = kaiserBessel;
        this.showImages = showImages;

        computePhaseCorrelation(Rotation2D.getImagePlus("image1", img1, w1, h1, showImages), Rotation2D.getImagePlus("image2", img2, w2, h2, showImages));
    }
*/
    public PhaseCorrelation2D(ImagePlus img1, ImagePlus img2, int checkImages, boolean kaiserBessel,  boolean normalize, boolean showImages)
    {
        this.kaiserBessel = kaiserBessel;
        this.showImages = showImages;
        this.normalize = normalize;

        computePhaseCorrelation(img1, img2, checkImages);
    }

    public PhaseCorrelation2D(ImageProcessor imp1, ImageProcessor imp2, int checkImages, boolean kaiserBessel,  boolean normalize, boolean showImages)
    {
        this.kaiserBessel = kaiserBessel;
        this.showImages = showImages;
        this.normalize = normalize;

        computePhaseCorrelation(new ImagePlus("1", imp1), new ImagePlus("2", imp2), checkImages);
    }

    private void computePhaseCorrelation(ImagePlus orgImg1, ImagePlus orgImg2, int checkImages)
    {
        // get image dimensions
        int imgH1 = orgImg1.getHeight();
        int imgH2 = orgImg2.getHeight();
        int imgW1 = orgImg1.getWidth();
        int imgW2 = orgImg2.getWidth();

        // save original images
        this.orgImg1 = orgImg1;
        this.orgImg2 = orgImg2;

        // get zero padding size (to square image)
        getZeroPaddingSizePrimeFast(max(imgW1, imgW2), max(imgH1, imgH2));

        FloatArray2D image1 = ImageToFloatArray2D(orgImg1.getProcessor());
        FloatArray2D image2 = ImageToFloatArray2D(orgImg2.getProcessor());

        // normalize images
        if (normalize)
        {
            normalize(image1);
            normalize(image2);
        }

        // kaiser bessel filtering
        if (kaiserBessel)
        {
            filterKaiserBessel(image1, false);
            filterKaiserBessel(image2, false);
        }

        // zero-pad the images
        image1 = zeroPad(image1, widthZP, heightZP);
        image2 = zeroPad(image2, widthZP, heightZP);

        // compute the phase correlation matrix
        phaseCorrelationImg = computePhaseCorrelationMatrixFloat(image1, image2);

        // find peak
        findPeak((FloatProcessor)phaseCorrelationImg.getProcessor());

        CrossCorrelationResult[] result = testCrossCorrelation(checkImages, true && showImages, true && showImages);

        if (showImages)
            for (int i = 0; i < result.length; i++)
            {
                result[i].overlapImp.show();
                result[i].errorMapImp.show();
                System.out.println(result[i].shift.x + " " + result[i].shift.y + " " +  result[i].SSQ + " " + result[i].R);
            }

        bestTranslation = result[0].shift;

    }

    public CrossCorrelationResult[] testCrossCorrelation(int numBestHits, boolean createOverlappingImages, boolean createErrorMap)
    {
        CrossCorrelationResult result[] = new CrossCorrelationResult[numBestHits*4];
        int count = 0;
        int w = phaseCorrelationImg.getWidth();
        int h = phaseCorrelationImg.getHeight();

        for (int hit = 0; count < numBestHits*4; hit++)
        {
            Point shift1 = location[location.length - 1 - hit];
            if (!(shift1.x == 0 || shift1.y == 0))
            {
                Point shift2, shift3, shift4;

                if (shift1.x < 0)
                    shift2 = new Point(shift1.x + w, shift1.y);
                else
                    shift2 = new Point(shift1.x - w, shift1.y);

                if (shift1.y < 0)
                    shift3 = new Point(shift1.x, shift1.y + h);
                else
                    shift3 = new Point(shift1.x, shift1.y - h);

                shift4 = new Point(shift2.x, shift3.y);

                /*if (x >= w/2)
                    xs = x - w;
                else
                    xs = x;

                if (y >= h/2)
                    ys = y - h;
                else
                    ys = y;*/

                result[count++] = testCrossCorrelation(shift1, createOverlappingImages, createErrorMap);
                result[count++] = testCrossCorrelation(shift2, createOverlappingImages, createErrorMap);
                result[count++] = testCrossCorrelation(shift3, createOverlappingImages, createErrorMap);
                result[count++] = testCrossCorrelation(shift4, createOverlappingImages, createErrorMap);
            }
        }

        quicksort(result, 0, result.length - 1);

        return result;
    }

    public CrossCorrelationResult testCrossCorrelation(Point shift, boolean createOverlappingImages, boolean createErrorMap)
    {
        CrossCorrelationResult result = new CrossCorrelationResult();

        int w1 = orgImg1.getWidth();
        int h1 = orgImg1.getHeight();

        int w2 = orgImg2.getWidth();
        int h2 = orgImg2.getHeight();

        FloatProcessor fp1 = (FloatProcessor) orgImg1.getProcessor();
        FloatProcessor fp2 = (FloatProcessor) orgImg2.getProcessor();

        FloatProcessor overlapFP = null, errorMapFP = null, imgfp1 = null, imgfp2 = null;

        int sx = shift.x;
        int sy = shift.y;

        int imgW = max(w1, w2) + max(0, Math.abs(sx) - Math.abs((w1 - w2)/* /2*/));
        int imgH = max(h1, h2) + max(0, Math.abs(sy) - Math.abs((h1 - h2)/* /2*/));

        /*int offsetImg1X = max(0, (w2 - w1)/2 - sx);// + max(0, max(0, -sx) - max(0, (w1 - w2)/2));
        int offsetImg1Y = max(0, (h2 - h1)/2 - sy);// + max(0, max(0, -sy) - max(0, (h1 - h2)/2));
        int offsetImg2X = max(0, (w1 - w2)/2 + sx);// + max(0, max(0, sx) - max(0, (w2 - w1)/2));
        int offsetImg2Y = max(0, (h1 - h2)/2 + sy);// + max(0, max(0, sy) - max(0, (h2 - h1)/2));
        */

        int offsetImg1X = max(0, -sx);// + max(0, max(0, -sx) - max(0, (w1 - w2)/2));
        int offsetImg1Y = max(0, -sy);// + max(0, max(0, -sy) - max(0, (h1 - h2)/2));
        int offsetImg2X = max(0, sx);// + max(0, max(0, sx) - max(0, (w2 - w1)/2));
        int offsetImg2Y = max(0, sy);// + max(0, max(0, sy) - max(0, (h2 - h1)/2));

        if (createOverlappingImages)
        {
            result.overlapImp = IJ.createImage("Overlay for " + sx + " x " + sy, "32-Bit Black", imgW, imgH, 1);
            overlapFP = (FloatProcessor) result.overlapImp.getProcessor();

            result.img1 = IJ.createImage("Img1 for " + sx + " x " + sy, "32-Bit Black", imgW, imgH, 1);
            imgfp1 = (FloatProcessor) result.img1.getProcessor();

            result.img2 = IJ.createImage("Img2 for " + sx + " x " + sy, "32-Bit Black", imgW, imgH, 1);
            imgfp2 = (FloatProcessor) result.img2.getProcessor();
        }

        if (createErrorMap)
        {
            result.errorMapImp = IJ.createImage("Error map for " + sx + " x " + sy, "32-Bit Black", imgW, imgH, 1);
            errorMapFP = (FloatProcessor) result.errorMapImp.getProcessor();
        }

        //System.out.println(createErrorMap + " " + createOverlappingImages);

        int count = 0;
        float pixel1, pixel2;

        // iterate over whole image
        // first the average

        double avg1 = 0, avg2 = 0;

        for (int y = 0; y < imgH; y++)
            for (int x = 0; x < imgW; x++)
            {
                pixel1 = fp1.getPixelValue(x - offsetImg1X, y - offsetImg1Y);
                pixel2 = fp2.getPixelValue(x - offsetImg2X, y - offsetImg2Y);

                if (createOverlappingImages)
                {
                    overlapFP.putPixelValue(x, y, (pixel1 + pixel2) / 2D);

                    imgfp1.putPixelValue(x, y, pixel1);
                    imgfp2.putPixelValue(x, y, pixel2);
                }

                // compute errors only if the images overlap
                if (x >= offsetImg1X && x >= offsetImg2X &&
                    x < offsetImg1X + w1 && x < offsetImg2X + w2 &&
                    y >= offsetImg1Y && y >= offsetImg2Y &&
                    y < offsetImg1Y + h1 && y < offsetImg2Y + h2)
                {
                    if (createErrorMap)
                        errorMapFP.putPixelValue(x, y, Math.pow(pixel1 - pixel2,2));

                    avg1 += pixel1;
                    avg2 += pixel2;
                    count++;
                }
            }

        if (count < 2000)
        {
            result.R = 0;
            result.SSQ = Float.MAX_VALUE;
            result.overlappingPixels = count;
            result.shift = shift;
            //System.out.println("Smaller 2000");
            return result;
        }

        avg1 /= (double)count;
        avg2 /= (double)count;

        double var1 = 0, var2 = 0;
        double coVar = 0;

        double dist1, dist2;

        double SSQ = 0;
        double pixelSSQ;
        count = 0;

        for (int y = 0; y < imgH; y++)
            for (int x = 0; x < imgW; x++)
            {
                pixel1 = fp1.getPixelValue(x - offsetImg1X, y - offsetImg1Y);
                pixel2 = fp2.getPixelValue(x - offsetImg2X, y - offsetImg2Y);

                // compute errors only if the images overlap
                if (x >= offsetImg1X && x >= offsetImg2X &&
                    x < offsetImg1X + w1 && x < offsetImg2X + w2 &&
                    y >= offsetImg1Y && y >= offsetImg2Y &&
                    y < offsetImg1Y + h1 && y < offsetImg2Y + h2)
                {
                    pixelSSQ = Math.pow(pixel1 - pixel2, 2);
                    SSQ += pixelSSQ;
                    count++;

                    dist1 = pixel1 - avg1;
                    dist2 = pixel2 - avg2;

                    coVar += dist1 * dist2;
                    var1 += Math.pow(dist1, 2);
                    var2 += Math.pow(dist2, 2);
                }
            }

        SSQ /= (double) count;
        var1 /= (double)count;
        var2 /= (double)count;
        coVar /= (double)count;

        double stDev1 = Math.sqrt(var1);
        double stDev2 = Math.sqrt(var2);

        // compute correlation coeffienct
        result.R = coVar / (stDev1  * stDev2);
        result.SSQ = SSQ;
        result.overlappingPixels = count;
        result.shift = shift;

        if (createOverlappingImages)
            overlapFP.resetMinAndMax();

        if (createErrorMap)
            errorMapFP.resetMinAndMax();

        return result;
    }

    public ImagePlus getPhaseCorrelationImage()
    {
        return phaseCorrelationImg;
    }

    public Point getTranslation()
    {
        return bestTranslation;
    }


    private Point findPeak(FloatProcessor fp)
    {
        pixels = (float[])fp.getPixelsCopy();
        location = new Point[pixels.length];


        int count = 0;
        int w = fp.getWidth();
        int h = fp.getHeight();
        int xs, ys;

        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
            {
                if (x >= w/2)
                    xs = x - w;
                else
                    xs = x;

                if (y >= h/2)
                    ys = y - h;
                else
                    ys = y;

                location[count++] = new Point(xs, ys);
            }


        quicksort(pixels, location, 0, pixels.length - 1);

        //for (int i = 1; i < 5; i++)
          //  System.out.println(location[pixels.length - i].x + " x " + location[pixels.length - i].y + " = " + pixels[pixels.length - i]);

        return location[pixels.length - 1];
    }

    private ImagePlus computePhaseCorrelationMatrixFloat(FloatArray2D img1, FloatArray2D img2)
    {
        //
        // do the fft for both images
        //

        int width = img1.width;

        FloatArray2D fft1 = pffft2D(img1, false);
        FloatArray2D fft2 = pffft2D(img2, false);

        //
        // Do Phase Correlation
        //

        FloatArray2D pcm = new FloatArray2D(pffft.computePhaseCorrelationMatrix(fft1.data, fft2.data, false), fft1.width, fft1.height);

        //return Rotation2D.getImagePlus("Inv of PCM", pffftInv2D(pcm, width), showImages);
	return FloatArrayToImagePlus(pffftInv2D(pcm, width), "Inv of PCM", 0, 0);
    }

    private void logImage(FloatProcessor fp)
    {
        for (int x = 0; x < fp.getWidth(); x++)
            for (int y = 0; y < fp.getHeight(); y++)
                fp.putPixelValue(x,y, Math.log10(fp.getPixelValue(x,y)));

        fp.resetMinAndMax();
    }

    private void gLogImage(FloatProcessor fp, double c)
    {
        for (int x = 0; x < fp.getWidth(); x++)
            for (int y = 0; y < fp.getHeight(); y++)
                fp.putPixelValue(x, y, gLog(fp.getPixelValue(x, y), c));

        fp.resetMinAndMax();
    }

    private void gLogArray(double[] array, int width, int height, double c)
    {
        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++)
                array[x + y*width] = gLog(array[x + y*width], c);
    }

    private void getZeroPaddingSizePrimeFast(int width, int height)
    {
        width = FftReal.nfftFast(width);
        height = FftComplex.nfftFast(height);

        this.heightZP = height;
        this.widthZP = width;
    }

    private void getZeroPaddingSizePrimeSmall(int width, int height)
    {
        width = FftReal.nfftSmall(width);
        height = FftComplex.nfftSmall(height);

        this.heightZP = height;
        this.widthZP = width;
    }

    private void getZeroPaddingSize(int width, int height)
    {
        // always use the next larger number which is power of 2 for FFT
        width += offsetToPowerOf2(width);
        height += offsetToPowerOf2(height);

        if (width > height)
            height = width;
        else
            width = height;

        this.heightZP = height;
        this.widthZP = width;
    }

    private void convertToFloat(ImagePlus img)
    {
            // Convert to Float Processor if it is none
            ImageProcessor ip = img.getProcessor();

            if (!(ip instanceof FloatProcessor))
            {
                ip = ip.convertToFloat();
                img.setProcessor(null, ip);
                img.updateAndDraw();
            }
    }

}
