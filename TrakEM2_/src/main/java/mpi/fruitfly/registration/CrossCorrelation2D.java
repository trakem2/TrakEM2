package mpi.fruitfly.registration;

/**
 * <p>Title: CrossCorrelation2D</p>
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
import static mpi.fruitfly.general.ImageArrayConverter.DRAWTYPE_OVERLAP;
import static mpi.fruitfly.general.ImageArrayConverter.FloatArrayToImagePlus;
import static mpi.fruitfly.general.ImageArrayConverter.ImageToFloatArray2D;
import static mpi.fruitfly.general.ImageArrayConverter.drawTranslatedImages;
import static mpi.fruitfly.math.General.min;
import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ImageProcessor;

import java.awt.Point;
import java.util.concurrent.atomic.AtomicInteger;

import mpi.fruitfly.general.MultiThreading;
import mpi.fruitfly.math.datastructures.FloatArray2D;

public class CrossCorrelation2D
{
    public FloatArray2D img1, img2;
    public boolean showImages = false;

    double maxR = -2;
    int displaceX = 0, displaceY = 0;
    final Double regCoef = new Double(-2);

    public CrossCorrelation2D(String image1, String image2, boolean showImages)
    {
        // load images
        ImagePlus img1 = new Opener().openImage(image1);
        ImagePlus img2 = new Opener().openImage(image2);

        if (showImages)
        {
            img1.show();
            img2.show();
        }

        ImageProcessor ip1 = img1.getProcessor();
        ImageProcessor ip2 = img2.getProcessor();

        this.img1 = ImageToFloatArray2D(ip1);
        this.img2 = ImageToFloatArray2D(ip2);
        this.showImages = showImages;

        //computeCrossCorrelation(ImageToFloatArray2D(ip1), ImageToFloatArray2D(ip2), showImages);
    }

    public CrossCorrelation2D(ImageProcessor ip1, ImageProcessor ip2, boolean showImages)
    {
        if (showImages)
        {
            ImagePlus img1 = new ImagePlus("Image 1", ip1);
            ImagePlus img2 = new ImagePlus("Image 2", ip2);

            img1.show();
            img2.show();
        }

        this.img1 = ImageToFloatArray2D(ip1);
        this.img2 = ImageToFloatArray2D(ip2);
        this.showImages = showImages;
        //computeCrossCorrelation(ImageToFloatArray2D(ip1), ImageToFloatArray2D(ip2), showImages);
    }

    /**
     * Computes a translational registration with the help of the cross correlation measure. <br>
     * Limits the overlap to 30% and restricts the shift furthermore by a factor you can tell him
     * (this is useful if you f. ex. know that the vertical shift is much less than the horizontal).
     *
     * NOTE: Works multithreaded
     *
     * @param relMinOverlapX double - if you want to scan for less possible translations seen from a direct overlay,
     * give the relative factor here (e.g. 0.3 means DONOT scan the outer 30%)
     * NOTE: Below 0.05 does not really make sense as you then compare only very few pixels (even one) on the edges
     * which gives then an R of 1 (perfect match)
     * @param relMinOverlapY double - if you want to scan for less possible translations seen from a direct overlay,
     * give the relative factor here (e.g. 0.3 means DONOT scan the outer 30%)
     * NOTE: Below 0.05 does not really make sense as you then compare only very few pixels (even one) on the edges
     * which gives then an R of 1 (perfect match)
     * @param showImages boolean - Show the result of the cross correlation translation
     * @return double[] return a double array containing {displaceX, displaceY, R}
     */
    public double[] computeCrossCorrelationMT(final double relMinOverlapX, final double relMinOverlapY, final boolean showImages)
    {
        //final double relMinOverlap = 0.30;

        final int w1 = img1.width;
        final int w2 = img2.width;

        final int h1 = img1.height;
        final int h2 = img2.height;

        final int min_border_w = (int) (w1 < w2 ? w1 * relMinOverlapX + 0.5 : w2 * relMinOverlapX + 0.5);
        final int min_border_h = (int) (h1 < h2 ? h1 * relMinOverlapY + 0.5 : h2 * relMinOverlapY + 0.5);

        /*System.out.println(w1 + " " + h1 + " " + w2 + " " + h2);
        System.out.println(min_border_w + " " + min_border_h);
        System.out.println("Y [" + (-h2 + min_border_h) + " , " + (h1 - min_border_h) + "]");*/

        final AtomicInteger ai = new AtomicInteger(-w1 + min_border_w);

        Thread[] threads = MultiThreading.newThreads();

        for (int ithread = 0; ithread < threads.length; ++ithread)
            threads[ithread] = new Thread(new Runnable()
            {
                public void run()
                {
                    for (int moveX = ai.getAndIncrement(); moveX < w2 - min_border_w; moveX = ai.getAndIncrement())
                    {
                        for (int moveY = -h1 + min_border_h; moveY < h2 - min_border_h; moveY++)
                        {
                            // compute average
                            double avg1 = 0, avg2 = 0;
                            int count = 0;
                            double value1, value2;

                            // iterate over the area which overlaps

                            // if moveX < 0 we have to start just at -moveX because otherwise x2 is negative....
                            // same for moveX > 0 and the right side

                            //for (int x1 = 0; x1 < w1; x1++)
                            for (int x1 = -min(0, moveX); x1 < min(w1, w2 - moveX); x1++)
                            {
                                int x2 = x1 + moveX;

                                /*if (x2 < 0 || x2 > w2 - 1)
                                    continue;*/

                                //for (int y1 = 0; y1 < h1; y1++)
                                for (int y1 = -min(0, moveY); y1 < min(h1, h2 - moveY); y1++)
                                {
                                    int y2 = y1 + moveY;

                                    /*if (y2 < 0 || y2 > h2 - 1)
                                        continue;*/

                                    value1 = img1.get(x1, y1);
                                    if (value1 == -1)
                                        continue;

                                    value2 = img2.get(x2, y2);
                                    if (value2 == -1)
                                        continue;

                                    avg1 += value1;
                                    avg2 += value2;
                                    count++;

                                }
                            }

                            if (0 == count)continue;

                            avg1 /= (double) count;
                            avg2 /= (double) count;

                            double var1 = 0, var2 = 0;
                            double coVar = 0;
                            double dist1, dist2;

                            // compute variances and co-variance
                            //for (int x1 = 0; x1 < w1; x1++)
                            for (int x1 = -min(0, moveX); x1 < min(w1, w2 - moveX); x1++)
                            {
                                int x2 = x1 + moveX;

                                /*if (x2 < 0 || x2 > w2 - 1)
                                    continue;*/

                                //for (int y1 = 0; y1 < h1; y1++)
                                for (int y1 = -min(0, moveY); y1 < min(h1, h2 - moveY); y1++)
                                {
                                    int y2 = y1 + moveY;

                                    /*if (y2 < 0 || y2 > h2 - 1)
                                        continue;*/

                                    value1 = img1.get(x1, y1);
                                    if (value1 == -1)
                                        continue;

                                    value2 = img2.get(x2, y2);
                                    if (value2 == -1)
                                        continue;

                                    dist1 = value1 - avg1;
                                    dist2 = value2 - avg2;

                                    coVar += dist1 * dist2;
                                    var1 += dist1 * dist1;
                                    var2 += dist2 * dist2;
                                }
                            }

                            var1 /= (double) count;
                            var2 /= (double) count;
                            coVar /= (double) count;

                            double stDev1 = Math.sqrt(var1);
                            double stDev2 = Math.sqrt(var2);

                            // compute correlation coeffienct
                            double R = coVar / (stDev1 * stDev2);

                            compareAndSetR(R, moveX, moveY);

                            if (R < -1 || R > 1)
                            {
                                System.out.println("BIG ERROR! R =" + R);
                            }
                        }
                        //System.out.println(moveX + " [" + (-w2 + min_border_w) + ", " + (w1 - min_border_w) + "] + best R: " + maxR + " " + displaceX + " " + displaceY);
                    }

                }
            });
        MultiThreading.startAndJoin(threads);

        if (showImages)
        {
            System.out.println( -displaceX + " " + -displaceY + " " + maxR);
            FloatArray2D result = drawTranslatedImages(img1, img2, new Point( -displaceX, -displaceY), DRAWTYPE_OVERLAP);
            FloatArrayToImagePlus(result, "result", 0, 0).show();
        }

        return new double[]
                { -displaceX, -displaceY, maxR};
    }

    /**
     * Synchronized method to compare local correlation coefficient to the globally best one and updating
     * the global one if necessary
     *
     * @param R double Correlation Coefficient
     * @param moveX int Shift in X direction
     * @param moveY int Shift in Y direction
     */
    private synchronized void compareAndSetR(final double R, final int moveX, final int moveY)
    {
        if (R > maxR)
        {
            maxR = R;
            displaceX = moveX;
            displaceY = moveY;
        }

    }

    /**
     * Computes a translational registration with the help of the cross correlation measure. <br>
     * Limits the overlap to 30% and restricts the vertical shift furthermore by a factor of 16.
     *
     * (NOTE: this method is only single threaded, use computeCrossCorrelationMT instead)
     *
     * @deprecated This method is only single threaded, use computeCrossCorrelationMT instead
     *
     * @param relMinOverlapX double - if you want to scan for less possible translations seen from a direct overlay,
     * give the relative factor here (e.g. 0.3 means DONOT scan the outer 30%)
     * NOTE: Below 0.05 does not really make sense as you then compare only very few pixels (even one) on the edges
     * which gives then an R of 1 (perfect match)
     * @param relMinOverlapY double - if you want to scan for less possible translations seen from a direct overlay,
     * give the relative factor here (e.g. 0.3 means DONOT scan the outer 30%)
     * NOTE: Below 0.05 does not really make sense as you then compare only very few pixels (even one) on the edges
     * which gives then an R of 1 (perfect match)
     * @param showImages boolean - Show the result of the cross correlation translation
     * @return double[] return a double array containing {displaceX, displaceY, R}
     */
    @Deprecated
    public double[] computeCrossCorrelation(final double relMinOverlapX, final double relMinOverlapY, boolean showImages)
    {
        double maxR = -2;
        int displaceX = 0, displaceY = 0;

        int w1 = img1.width;
        int w2 = img2.width;

        int h1 = img1.height;
        int h2 = img2.height;

        //int factorY = 16;

        final int min_border_w = (int) (w1 < w2 ? w1 * relMinOverlapX + 0.5 : w2 * relMinOverlapX + 0.5);
        final int min_border_h = (int) (h1 < h2 ? h1 * relMinOverlapY + 0.5 : h2 * relMinOverlapY + 0.5);

        //System.out.println("Y [" + (-h2 + min_border_h)/factor + " , " + (h1 - min_border_h)/factor + "]");

        for (int moveX = (-w1 + min_border_w); moveX < (w2 - min_border_w); moveX++)
        {
            for (int moveY = (-h1 + min_border_h); moveY < (h2 - min_border_h); moveY++)
            {
                // compute average
                double avg1 = 0, avg2 = 0;
                int count = 0;
                double value1, value2;

                // iterate over the area which overlaps

                // if moveX < 0 we have to start just at -moveX because otherwise x2 is negative....
                // same for moveX > 0 and the right side

                //for (int x1 = 0; x1 < w1; x1++)
                for (int x1 = -min(0, moveX); x1 < min(w1, w2 - moveX); x1++)
                {
                    int x2 = x1 + moveX;

                    /*if (x2 < 0 || x2 > w2 - 1)
                        continue;*/

                    //for (int y1 = 0; y1 < h1; y1++)
                    for (int y1 = -min(0, moveY); y1 < min(h1, h2 - moveY); y1++)
                    {
                        int y2 = y1 + moveY;

                        /*if (y2 < 0 || y2 > h2 - 1)
                            continue;*/

                        value1 = img1.get(x1, y1);
                        if (value1 == -1)
                            continue;

                        value2 = img2.get(x2, y2);
                        if (value2 == -1)
                            continue;

                        avg1 += value1;
                        avg2 += value2;
                        count++;

                    }
                }

                //System.exit(0);

                if (0 == count) continue;

                avg1 /= (double) count;
                avg2 /= (double) count;

                double var1 = 0, var2 = 0;
                double coVar = 0;
                double dist1, dist2;

                // compute variances and co-variance
                //for (int x1 = 0; x1 < w1; x1++)
                for (int x1 = -min(0, moveX); x1 < min(w1, w2 - moveX); x1++)
                {
                    int x2 = x1 + moveX;

                    /*if (x2 < 0 || x2 > w2 - 1)
                        continue;*/

                    //for (int y1 = 0; y1 < h1; y1++)
                    for (int y1 = -min(0, moveY); y1 < min(h1, h2 - moveY); y1++)
                    {
                        int y2 = y1 + moveY;

                        /*if (y2 < 0 || y2 > h2 - 1)
                            continue;*/

                        value1 = img1.get(x1, y1);
                        if (value1 == -1)
                            continue;

                        value2 = img2.get(x2, y2);
                        if (value2 == -1)
                            continue;

                        dist1 = value1 - avg1;
                        dist2 = value2 - avg2;

                        coVar += dist1 * dist2;
                        var1 += dist1 * dist1;
                        var2 += dist2 * dist2;
                    }
                }

                var1 /= (double) count;
                var2 /= (double) count;
                coVar /= (double) count;

                double stDev1 = Math.sqrt(var1);
                double stDev2 = Math.sqrt(var2);

                // compute correlation coeffienct
                double R = coVar / (stDev1 * stDev2);

                if (R > maxR)
                {
                    maxR = R;
                    displaceX = moveX;
                    displaceY = moveY;
                }

                if (R < -1 || R > 1)
                {
                    System.out.println("BIG ERROR! R =" + R);
                }
            }
            //System.out.println(moveX + " [" + (-w2 + min_border_w) + ", " + (w1 - min_border_w) + "] + best R: " + maxR);
        }

        if (showImages)
        {
            System.out.println( -displaceX + " " + -displaceY);
            FloatArray2D result = drawTranslatedImages(img1, img2, new Point( -displaceX, -displaceY), DRAWTYPE_OVERLAP);
            FloatArrayToImagePlus(result, "result", 0, 0).show();
        }

        return new double[]{-displaceX, -displaceY, maxR};

    }
}
