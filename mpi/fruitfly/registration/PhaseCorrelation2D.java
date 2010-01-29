package mpi.fruitfly.registration;

/**
 * <p>Title: PhaseCorrelation2D</p>
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
import ij.process.ImageProcessor;
import ij.process.FloatProcessor;
import ij.ImagePlus;
import ij.io.Opener;

import java.awt.Point;
/*import Jampack.Zsvd;
import Jampack.Z;
import Jampack.Zmat;
import Jampack.Zdiagmat;*/

//import mpi.fruitfly.fft.jfftwWrapper;
import mpi.fruitfly.math.Quicksortable;
import mpi.fruitfly.math.datastructures.FloatArray2D;
import mpi.fruitfly.fft.pffft;

//import static mpi.fruitfly.general.fileParsing.*;
import static mpi.fruitfly.general.fileAccess.*;
//import static mpi.fruitfly.general.ArrayConverter.*;
import static mpi.fruitfly.general.ImageArrayConverter.*;
//import static mpi.fruitfly.general.configurationParser.*;
import static mpi.fruitfly.math.General.*;
//import static mpi.fruitfly.fft.jfftwWrapper.*;
import static mpi.fruitfly.fft.pffft.*;
import static mpi.fruitfly.registration.ImageFilter.*;

import ij.IJ;
import edu.mines.jtk.dsp.FftComplex;
import edu.mines.jtk.dsp.FftReal;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import mpi.fruitfly.general.MultiThreading;

import java.util.ArrayList;
import java.util.Iterator;



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

    private class Point2D
    {
        public int x = 0, y = 0;
        public float value;

        public Point2D(int x, int y)
        {
            this.x = x;
            this.y = y;
        }

        public Point2D(int x, int y, float value)
        {
            this.x = x;
            this.y = y;
            this.value = value;
        }
    }

    ///private int imgW1, imgW2, imgH1, imgH2;
    private int heightZP, widthZP;
    private boolean kaiserBessel, normalize, showImages;
    private int checkImages;
    
    private ImagePlus phaseCorrelationImg, orgImg1, orgImg2;
    private float[] pixels;
    private Point[] location;
    private Point bestTranslation;
    private CrossCorrelationResult bestResult;

    public class CrossCorrelationResult implements Quicksortable
    {
        public Point shift;
        public int overlappingPixels;
        public double SSQ, R, PCMValue;
        public ImagePlus overlapImp, errorMapImp;

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
        this.checkImages = checkImages;

        if (showImages)
        {
            img1.show();
            img2.show();
        }

        orgImg1 = img1;
        orgImg2 = img2;

        //computePhaseCorrelation(img1, img2, checkImages);
    }

    public PhaseCorrelation2D(ImagePlus img1, ImagePlus img2, int checkImages, boolean kaiserBessel,  boolean normalize, boolean showImages)
    {
        this.kaiserBessel = kaiserBessel;
        this.showImages = showImages;
        this.normalize = normalize;
        this.checkImages = checkImages;

        orgImg1 = img1;
        orgImg2 = img2;
        //computePhaseCorrelation(img1, img2, checkImages);
    }

    public PhaseCorrelation2D(ImageProcessor imp1, ImageProcessor imp2, int checkImages, boolean kaiserBessel,  boolean normalize, boolean showImages)
    {
        this.kaiserBessel = kaiserBessel;
        this.showImages = showImages;
        this.normalize = normalize;
        this.checkImages = checkImages;

        orgImg1 = new ImagePlus("1", imp1);
        orgImg2 = new ImagePlus("2", imp2);
        //computePhaseCorrelation(new ImagePlus("1", imp1), new ImagePlus("2", imp2), checkImages);
    }

    public Point computePhaseCorrelation()
    {
        // get image dimensions
        int imgH1 = orgImg1.getHeight();
        int imgH2 = orgImg2.getHeight();
        int imgW1 = orgImg1.getWidth();
        int imgW2 = orgImg2.getWidth();

        //System.out.println(imgW1 + " " + imgH1 + " " + imgW2 + " " + imgH2 );

        int extW1 = 0;
        int extH1 = 0;
        int extW2 = 0;
        int extH2 = 0;

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
            extW1 = imgW1/4;
            extH1 = imgH1/4;
            extW2 = imgW2/4;
            extH2 = imgH2/4;


            // add an even number so that both sides extend equally
            if (extW1 % 2 != 0) extW1++;
            if (extH1 % 2 != 0) extH1++;
            if (extW2 % 2 != 0) extW2++;
            if (extH2 % 2 != 0) extH2++;

            //System.out.println(extW1 + " " + extH1 + " " + extW2 + " " + extH2 );

            // extend images
            image1 = extendImageMirror(image1, extW1, extH1);
            image2 = extendImageMirror(image2, extW2, extH2);

            exponentialWindow(image1);
            exponentialWindow(image2);
            //filterKaiserBessel(image1, false);
            //filterKaiserBessel(image2, false);

            if (showImages)
            {
                FloatArrayToImagePlus(image1, "1", 0, 0).show();
                FloatArrayToImagePlus(image2, "2", 0, 0).show();
            }

            imgW1 += extW1;
            imgH1 += extH1;
            imgW2 += extW2;
            imgH2 += extH2;
        }

        // get zero padding size
        getZeroPaddingSizePrimeFast(max(imgW1, imgW2), max(imgH1, imgH2));

        // zero-pad the images
        image1 = zeroPad(image1, widthZP, heightZP);
        image2 = zeroPad(image2, widthZP, heightZP);

        // compute the phase correlation matrix
        phaseCorrelationImg = computePhaseCorrelationMatrixFloat(image1, image2);
        if (showImages)
            phaseCorrelationImg.show();

        // find peaks
        findPeak((FloatProcessor)phaseCorrelationImg.getProcessor(), imgW1, imgH1, imgW2, imgH2, extW1, extH1, extW2, extH2);

        // evaluate peaks by cross correlation
        CrossCorrelationResult[] result = testCrossCorrelation(checkImages, true && showImages, false && showImages);

        if (showImages)
            for (int i = 0; i < result.length/4; i++)
            {
                if (result[i].overlapImp != null)
                    result[i].overlapImp.show();

                //result[i].errorMapImp.show();
                if (result[i].overlapImp != null)
                    System.out.println(result[i].shift.x + " " + result[i].shift.y + " " +  result[i].SSQ + " " + result[i].R);
            }

        bestTranslation = result[0].shift;
        bestResult = result[0];

        return bestTranslation;
    }


	public static FloatArray2D extendImageMirror(FloatArray2D input, int extW, int extH)
	    {
		int imgW = input.width;
		int imgH = input.height;
	 
		// zero pad the images
		FloatArray2D extended = zeroPad(input, imgW + extW, imgH + extH);
	 
		// fill extended areas with the mirroring information
		for (int x = 1; x <= extW/2; x++)
		{
		    for (int y = 1; y <= extH / 2; y++)
		    {
			// left upper corner
			extended.set(input.getMirror( -x, -y), extW / 2 - x, extH / 2 - y);
		    }
	 
		    for (int y = 1; y <= extH / 2 + extH%2; y++)
		    {
			// left lower corner
			extended.set(input.getMirror( -x, imgH + y - 1), extW / 2 - x, extended.height - extH / 2 + y - 1 - extH%2);
		    }
		}
	 
		for (int x = 1; x <= extW/2 + extW%2; x++)
		{
		    for (int y = 1; y <= extH / 2; y++)
		    {
			// right upper corner
			extended.set(input.getMirror(imgW + x - 1, -y), extended.width - extW / 2 + x - 1 - extW % 2, extH / 2 - y);
		    }
		    for (int y = 1; y <= extH / 2 + extW%2; y++)
		    {
	 
			// right lower corner
			extended.set(input.getMirror(imgW + x - 1, imgH + y - 1), extended.width - extW / 2 + x - 1 - extW % 2, extended.height - extH / 2 + y - 1 - extH%2);
		    }
		}
	 
		for (int y = 0; y < imgH; y++)
		    for (int x = 1; x <= extW/2; x++)
		    {
			// left lane
			extended.set(input.getMirror(-x, -y), extW / 2 - x, y + extH / 2);
		    }
	 
		for (int y = 0; y < imgH; y++)
		    for (int x = 1; x <= extW/2 + extW%2; x++)
		    {
			// right lane
			extended.set(input.getMirror(imgW + x - 1, -y), extended.width - extW/2 + x - 1 - extW%2, y + extH / 2);
		    }
	 
		for (int x = 0; x < imgW; x++)
		    for (int y = 1; y <= extH/2; y++)
		    {
			// upper lane
			extended.set(input.getMirror(-x, -y), x + extW / 2, extH / 2 - y);
		    }
	 
		for (int x = 0; x < imgW; x++)
		    for (int y = 1; y <= extH/2 + extH%2; y++)
		    {
			// lower lane        location = new Point[pixels.length];

			extended.set(input.getMirror(-x, imgH + y - 1), x + extW / 2,  extended.height - extH/2 + y - 1 - extH%2);
		    }
	 
		return extended;
	    }


    public CrossCorrelationResult[] testCrossCorrelation(int numBestHits, final boolean createOverlappingImages, final boolean createErrorMap)
    {
        final CrossCorrelationResult result[] = new CrossCorrelationResult[numBestHits*4];
        int count = 0;
        int w = phaseCorrelationImg.getWidth();
        int h = phaseCorrelationImg.getHeight();

        final int numCases = 4;
        final Point[] points = new Point[numCases];

        for (int hit = 0; count < numBestHits*4; hit++)
        {
            points[0] = location[location.length - 1 - hit];
            if (!((points[0].x == 0 || points[0].y == 0) && !this.kaiserBessel))
            {
                //Point shift2, shift3, shift4;

                if (points[0].x < 0)
                    points[1] = new Point(points[0].x + w, points[0].y);
                else
                    points[1] = new Point(points[0].x - w, points[0].y);

                if (points[0].y < 0)
                    points[2] = new Point(points[0].x, points[0].y + h);
                else
                    points[2] = new Point(points[0].x, points[0].y - h);

                points[3] = new Point(points[1].x, points[2].y);

                final AtomicInteger entry = new AtomicInteger(count);
                final AtomicInteger shift = new AtomicInteger(0);

                Runnable task = new Runnable()
                {
                        public void run()
                        {
                            int myEntry = entry.getAndIncrement();
                            int myShift = shift.getAndIncrement();

                            result[myEntry] = testCrossCorrelation(points[myShift], createOverlappingImages, createErrorMap);
                        }
                };

                MultiThreading.startTask(task, numCases);

                count += numCases;
                /*result[count++] = testCrossCorrelation(shift1, createOverlappingImages, createErrorMap);
                result[count++] = testCrossCorrelation(shift2, createOverlappingImages, createErrorMap);
                result[count++] = testCrossCorrelation(shift3, createOverlappingImages, createErrorMap);
                result[count++] = testCrossCorrelation(shift4, createOverlappingImages, createErrorMap);*/
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

        FloatProcessor overlapFP = null, errorMapFP = null;

        int sx = shift.x;
        int sy = shift.y;

        //int imgW = max(w1, w2) + max(0, Math.abs(sx) - Math.abs((w1 - w2)/* /2*/));
        //int imgH = max(h1, h2) + max(0, Math.abs(sy) - Math.abs((h1 - h2)/* /2*/));

        int imgW, imgH;

        if (sx >= 0)
            imgW = max(w1, w2 + sx);
        else
            imgW = max(w1 - sx, w2); // equals max(w1 + Math.abs(sx), w2);

        if (sy >= 0)
            imgH = max(h1, h2 + sy);
        else
            imgH = max(h1 - sy, h2);

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

        // if less than 1% is overlapping
        if (count <= (min(w1, w2) * min (h1, h2)) * 0.01)
        {
            result.R = 0;
            result.SSQ = Float.MAX_VALUE;
            result.overlappingPixels = count;
            result.shift = new Point(0, 0);

            if (createOverlappingImages)
                overlapFP.resetMinAndMax();

            if (createErrorMap)
                errorMapFP.resetMinAndMax();

            //result.img1.close();

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

    public CrossCorrelationResult getResult()
    {
        return bestResult;
    }

    public Point getTranslation()
    {
        return bestTranslation;
    }
    /*
    
    // OLD VERSION OF findPeak
    
    private Point findPeak(FloatProcessor fp, int imgW1, int imgH1, int imgW2, int imgH2, int extW1, int extH1, int extW2, int extH2)
    {
        pixels = (float[]) fp.getPixelsCopy();
        location = new Point[pixels.length];

        int count = 0;
        int w = fp.getWidth();
        int h = fp.getHeight();
        int xs, ys, xt, yt;

        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
            {
                // find relative to the left upper corners of both images
                xt = x + (imgW1 - imgW2) / 2 - (extW1 - extW2) / 2;

                if (xt >= w / 2)
                    xs = xt - w;
                else
                    xs = xt;

                yt = y + (imgH1 - imgH2) / 2 - (extH1 - extH2) / 2;

                if (yt >= h / 2)
                    ys = yt - h;
                else
                    ys = yt;

                location[count++] = new Point(xs, ys);
            }

        quicksort(pixels, location, 0, pixels.length - 1);

        //for (int i = 1; i < 5; i++)
        //  System.out.println(location[pixels.length - i].x + " x " + location[pixels.length - i].y + " = " + pixels[pixels.length - i]);

        //System.out.println(location[pixels.length - 1].x + " " + location[pixels.length - 1].y);
        return location[pixels.length - 1];
    }
    
    */

    private Point findPeak(FloatProcessor fp, int imgW1, int imgH1, int imgW2, int imgH2, int extW1, int extH1, int extW2, int extH2)
    {
        pixels = (float[])fp.getPixelsCopy();

        int count = 0;
        int w = fp.getWidth();
        int h = fp.getHeight();
        int xs, ys, xt, yt;
        float value;

        FloatArray2D invPCM = new FloatArray2D(pixels, w, h);

        ArrayList< Point2D > peaks = new ArrayList< Point2D >();

        for (int j = 0; j < checkImages; j++)
            peaks.add(new Point2D(0, 0, -Float.MAX_VALUE));

        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (isLocalMaximum(invPCM, x, y))
                {
                    value = invPCM.get(x, y);
                    Point2D insert = null;
                    int insertPos = -1;

                    Iterator i = peaks.iterator();
                    boolean wasBigger = true;

                    while (i.hasNext() && wasBigger)
                    {
                        if (value > ((Point2D) i.next()).value)
                        {
                            if (insert == null)
                                insert = new Point2D(0, 0, value);

                            insertPos++;
                        }
                        else
                            wasBigger = false;
                    }

                    if (insertPos >= 0)
                        peaks.add(insertPos + 1, insert);

                    // remove lowest peak
                    if (peaks.size() > checkImages)
                        peaks.remove(0);

                    if (insert != null)
                    {
                        // find relative to the left upper front corners of both images
                        xt = x + (imgW1 - imgW2) / 2 - (extW1 - extW2) / 2;

                        if (xt >= w / 2)
                        {
                            xs = xt - w;
                        }
                        else
                            xs = xt;

                        yt = y + (imgH1 - imgH2) / 2 - (extH1 - extH2) / 2;

                        if (yt >= h / 2)
                        {
                            ys = yt - h;
                        }
                        else
                            ys = yt;

                        insert.x = xs;
                        insert.y = ys;
                    }
                }

	  location = new Point[peaks.size()];
	  
	  for (int i = 0; i < location.length; i++)
	  {
	  	Point2D peak = peaks.get(i);
	  	location[location.length - i - 1] = new Point(peak.x, peak.y);
	  }
	  
	  return location[location.length - 1];
    }

    private boolean isLocalMaximum(FloatArray2D invPCM, int x, int y)
    {
        int width = invPCM.width;
        int height = invPCM.height;

        boolean isMax = true;
        float value = invPCM.get(x, y);

        if (x > 0 && y > 0 && x < width - 1 && y < height - 1)
        {
            for (int xs = x - 1; xs <= x + 1 && isMax; xs++)
                for (int ys = y - 1; ys <= y + 1 && isMax; ys++)
                    if (!(x == xs && y == ys))
                        if (invPCM.get(xs, ys) > value)
                            isMax = false;
        }
        else
        {
            int xt, yt;

            for (int xs = x - 1; xs <= x + 1 && isMax; xs++)
                for (int ys = y - 1; ys <= y + 1 && isMax; ys++)
                    if (!(x == xs && y == ys))
                    {
                        xt = xs;
                        yt = ys;

                        if (xt == -1) xt = width - 1;
                        if (yt == -1) yt = height - 1;

                        if (xt == width) xt = 0;
                        if (yt == height) yt = 0;

                        if (invPCM.get(xt, yt) > value)
                            isMax = false;
                    }
        }

        return isMax;
    }


    private ImagePlus computePhaseCorrelationMatrixFloat(FloatArray2D img1, FloatArray2D img2)
    {
        //
        // do the fft for both images
        //

        int width = img1.width;

        FloatArray2D fft1 = pffft2DMT(img1, false);
        FloatArray2D fft2 = pffft2DMT(img2, false);

        //
        // Do Phase Correlation
        //

        FloatArray2D pcm = new FloatArray2D(pffft.computePhaseCorrelationMatrix(fft1.data, fft2.data, false), fft1.width, fft1.height);

        //return Rotation2D.getImagePlus("Inv of PCM", pffftInv2D(pcm, width), showImages);
        return FloatArrayToImagePlus(pffftInv2D(pcm, width), "Inv of PCM", 0, 0);
    }


/*    private ImagePlus computePhaseCorrelationMatrix(FloatProcessor fp1, FloatProcessor fp2)
    {
        //
        // do the fft for both images
        //

        int width = fp1.getWidth();
        int height = fp2.getHeight();
        int spectraWidth = jfftwWrapper.getSpectraWidth(width);

        matlab code

         threshold = 1E-5;

         FFT3_V1 = fftn(V1);
         FFT3_V2 = fftn(V2);

         FFT3_V1  = FFT3_V1 ./abs(FFT3_V1 );
         FFT3_V2  = FFT3_V2 ./abs(FFT3_V2 ).*(abs(FFT3_V2 ) >threshold);

         Phase = FFT3_V1.*conj(FFT3_V2);

         Corr = real(ifftn(Phase));


        // do forward fft
        double[] FFT3_V1 = null;
        double[] FFT3_V2 = null;
        FFT3_V1 = fftSimple(width, height, ImageToDoubleArray1D(fp1), jfftwWrapper.SCALE);
        FFT3_V2 = fftSimple(width, height, ImageToDoubleArray1D(fp2), jfftwWrapper.SCALE);


        // get the power spectrum
        double[] ps1 = computePowerSpectrum(FFT3_V1);
        double[] ps2 = computePowerSpectrum(FFT3_V2);

        // divide complex numbers by power spectrum (weightening)
        FFT3_V1 = divide(FFT3_V1, realToComplex(ps1, ps1), DONTOVERWRITE);
        FFT3_V2 = divide(FFT3_V2, realToComplex(ps2, ps2), DONTOVERWRITE);

        // divide only if power spectrum is above threshold
        for (int pos = 0; pos < ps2.length; pos++)
            if (ps2[pos] < 1E-5)
                FFT3_V2[2 * pos] = FFT3_V2[2 * pos + 1] = 0;

        // do complex conjugate
        complexConjugate(FFT3_V2);

        // multiply both complex arrays elementwise
        double phaseCorrelationMatrix[] = multiply(FFT3_V1, FFT3_V2, DONTOVERWRITE);




        // do forward fft
        double[] complex1 = jfftwWrapper.fftSimple(width, height, ImageToDoubleArray1D(fp1), jfftwWrapper.SCALE);
        double[] complex2 = jfftwWrapper.fftSimple(width, height, ImageToDoubleArray1D(fp2), jfftwWrapper.SCALE);

        //
        // transform the second one into its complex conjugate
        //
        jfftwWrapper.complexConjugate(complex2, width, height);

        //
        // now compute phase correlation matrix
        //

        // first multiply the complex images
        //double[] complexMultiplication = jfftwWrapper.multiply(complex1, complex2, width, height, jfftwWrapper.DONTOVERWRITE);
        double[] complexMultiplication = jfftwWrapper.multiply(complex1, complex2, jfftwWrapper.DONTOVERWRITE);

        // now get the power spectrum of the multiplication
        //double[] powerSpectrumMult = jfftwWrapper.computePowerSpectrum(width, height, complexMultiplication);
        double[] powerSpectrumMult = jfftwWrapper.computePowerSpectrum(complexMultiplication);

        // then divide mulitplied complex image by the power spectrum of the multiplicated complex image
        //double complexPowerOfMult[] = jfftwWrapper.realToComplex(powerSpectrumMult, powerSpectrumMult, width, height);
        //double phaseCorrelationMatrix[] = jfftwWrapper.divide(complexMultiplication, complexPowerOfMult, width, height, jfftwWrapper.DONTOVERWRITE);
        double complexPowerOfMult[] = jfftwWrapper.realToComplex(powerSpectrumMult, powerSpectrumMult);
        double phaseCorrelationMatrix[] = jfftwWrapper.divide(complexMultiplication, complexPowerOfMult, jfftwWrapper.DONTOVERWRITE);


        //double[] phaseSpectrum = jfftwWrapper.computePhaseSpectrum(width, height, phaseCorrelationMatrix);
        //double[] powerSpectrum = jfftwWrapper.computePowerSpectrum(width, height, phaseCorrelationMatrix);

        double[] phaseSpectrum = jfftwWrapper.computePhaseSpectrum(phaseCorrelationMatrix);
        double[] powerSpectrum = jfftwWrapper.computePowerSpectrum(phaseCorrelationMatrix);

        gLogArray(phaseSpectrum, jfftwWrapper.getSpectraWidth(width), height, 2);
        ImagePlus impPhase = Rotation2D.getImagePlus("complexMultiplication Phase Spectrum", phaseSpectrum, spectraWidth, height, false);
        ImagePlus impPower = Rotation2D.getImagePlus("complexMultiplication Power Spectrum", powerSpectrum, spectraWidth, height, false);
        logImage((FloatProcessor)impPower.getProcessor());

        if (showImages)
        {
            impPhase.show();
            impPower.show();
        }

        // now do SVD on the complex fourier matrix
        try
        {
            // get complex number components in form usable for Jampack
            double[][] realPart = jfftwWrapper.getRealPart2DArrayMatrix(width, height, phaseCorrelationMatrix);
            double[][] imgPart = jfftwWrapper.getImgPart2DArrayMatrix(width, height, phaseCorrelationMatrix);

            // create matrix with complex numbers
            Zmat Q = new Zmat(realPart, imgPart);

            // compute SVD with complex matrix Q
            Zsvd svd = new Zsvd(Q);

            // get results of SVD
            Zmat U = svd.U;
            Zdiagmat S = svd.S;
            Zmat V = svd.V;

            double[] leftSingular = new double[U.nr * U.nc];
            for (int y = 0; y < U.nr; y++)
                for (int x = 0; x < U.nc; x++)
                    leftSingular[x + y * U.nc] = getPhase(U.get(y+1, x+1)); // row column

            Rotation2D.getImagePlus("Left Singular Matrix", leftSingular, U.nc, U.nr, true);

            double[] singular = new double[min(U.nr, V.nr) * min(U.nc, V.nc)];
            for (int y = 0; y < min(U.nr, V.nr); y++)
                    singular[y + y*min(U.nc, V.nc)] = S.get(y+1).re; // row column

            Rotation2D.getImagePlus("Singular Matrix", singular, min(U.nc, V.nc), min(U.nr, V.nr), true);


            double[] rightSingular = new double[V.nr * V.nc];
            for (int y = 0; y < V.nr; y++)
                for (int x = 0; x < V.nc; x++)
                    rightSingular[x + y*V.nc] = getPhase(V.get(y+1, x+1)); // row column

            Rotation2D.getImagePlus("Right Singular Matrix", rightSingular, V.nc, V.nr, true);


            PrintWriter out = openFileWrite("FitLine_U.txt");
            out.println("x" + "\t" + "wrapped" + "\t" + "unwrapped");

            double[] phaseVector = getPhaseVector(U,1);
            double[] phaseVectorUnwrapped = unwrapPhaseVector(phaseVector);

            for (int row = 0; row < U.nr; row++)
                out.println(row + "\t" +  phaseVector[row] + "\t" +  phaseVectorUnwrapped[row]);

            out.close();

            out = openFileWrite("FitLine_V.txt");
            out.println("x" + "\t" + "wrapped" + "\t" + "unwrapped");

            phaseVector = getPhaseVector(V,1);
            phaseVectorUnwrapped = unwrapPhaseVector(phaseVector);

            for (int row = 0; row < V.nr; row++)
                out.println(row + "\t" +  phaseVector[row] + "\t" +  phaseVectorUnwrapped[row]);

            out.close();

        }
        catch (Exception e)
        {
            System.err.println(e);
            e.printStackTrace();
        }

        // now do SVD on the phase spectrum
        Matrix Q = new Matrix(oneDimArrayToTwoDimArrayInvert(phaseSpectrum, spectraWidth, height));
        SingularValueDecomposition svd = new SingularValueDecomposition(Q);

        Matrix U = svd.getU(); //U Left Singular Matrix
        Matrix S = svd.getS(); //W
        Matrix V = svd.getV(); //VT Right Singular Matrix

        // display left and right singular maxtrix

        double[] leftSingular = new double[U.getRowDimension() * U.getColumnDimension()];
        for (int y = 0; y < U.getRowDimension(); y++)
            for (int x = 0; x < U.getColumnDimension(); x++)
                leftSingular[x + y*U.getColumnDimension()] = U.get(y, x); // row column

        Registration2D.getImagePlus("Left Singular Matrix", leftSingular, U.getColumnDimension(), U.getRowDimension(), true);


        double[] singular = new double[S.getRowDimension() * S.getColumnDimension()];
        for (int y = 0; y < S.getRowDimension(); y++)
            for (int x = 0; x < S.getColumnDimension(); x++)
                singular[x + y*S.getColumnDimension()] = S.get(y, x); // row column

        Registration2D.getImagePlus("Singular Matrix", singular, S.getColumnDimension(), S.getRowDimension(), true);

        double[] rightSingular = new double[V.getRowDimension() * V.getColumnDimension()];
        for (int y = 0; y < V.getRowDimension(); y++)
            for (int x = 0; x < V.getColumnDimension(); x++)
                rightSingular[x + y*V.getColumnDimension()] = V.get(y, x); // row column

        Registration2D.getImagePlus("Right Singular Matrix", rightSingular, V.getColumnDimension(), V.getRowDimension(), true);

        // inverse fft
        double[] phaseCorrelationMatrixImage = jfftwWrapper.fftInvSimple(width, height, phaseCorrelationMatrix);

        return Rotation2D.getImagePlus("Phase Correlation Matrix Image", phaseCorrelationMatrixImage, width, height, showImages);
    }

    private double[] unwrapPhaseVector(double[] phaseVector)
    {
        double lastPhaseUnwrapped = phaseVector[0];
        double thisPhase;

        int offset2PI = 0;

        double[] unwrappedPhaseVector = new double[phaseVector.length];

        for (int pos = 0; pos < phaseVector.length; pos++)
        {
            thisPhase = phaseVector[pos] + (Math.PI*2D*offset2PI);

            // if the difference in phase is more than pi
            // we have to add or subtract 2 pi
            if (Math.abs(thisPhase - lastPhaseUnwrapped) > Math.PI)
            {
                if (lastPhaseUnwrapped > thisPhase)
                {
                    // + 2pi because it should increase
                    thisPhase += Math.PI * 2;

                    // Now from all following phases subract 2*PI more
                    offset2PI++;
                }
                else
                {
                    // - 2pi because it should decrease
                    thisPhase -= Math.PI * 2;

                    // Now from all following phases subract 2*PI more
                    offset2PI--;
                }
            }
            unwrappedPhaseVector[pos] = lastPhaseUnwrapped = thisPhase;
        }
        return unwrappedPhaseVector;
    }

    private double[] getPhaseVector(Zmat matrix, int col)
    {
        double[] phaseVector = new double[matrix.nr];

        for (int row = 1; row <= matrix.nc; row++)
            phaseVector[row-1] = getPhase(matrix.get(row,col));

        return phaseVector;
    }

    private double getPhase(Z complexNumber)
    {
        double phase = 0;

        if (complexNumber.re != 0.0 && complexNumber.im != 0)
            phase = Math.atan2(complexNumber.im, complexNumber.re);

        if (phase < 0)
            phase = (Math.PI * 2) + phase;

        return phase;
    }*/

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
}
