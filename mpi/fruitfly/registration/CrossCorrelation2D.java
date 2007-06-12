package mpi.fruitfly.registration;

import ij.io.Opener;
import ij.ImagePlus;
import ij.process.*;

import mpi.fruitfly.math.datastructures.FloatArray2D;

import static mpi.fruitfly.general.ImageArrayConverter.*;
import static mpi.fruitfly.math.General.*;
import java.awt.Point;

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
public class CrossCorrelation2D
{
    public FloatArray2D img1, img2;
    public boolean showImages = false;

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

    public double[] computeCrossCorrelation(boolean showImages)
    {

        double relMinOverlap = 0.10;

        int w1 = img1.width;
        int w2 = img2.width;

        int h1 = img1.height;
        int h2 = img2.height;

        int factor = 16;

        double maxR = -2;
        int displaceX = 0, displaceY = 0;

        int min_border_w = (int) (w1 < w2 ? w1 * relMinOverlap : w2 * relMinOverlap);
        int min_border_h = (int) (h1 < h2 ? h1 * relMinOverlap : h2 * relMinOverlap);

        //System.out.println("Y [" + (-h2 + min_border_h)/factor + " , " + (h1 - min_border_h)/factor + "]");

        for (int moveX = -w2 + min_border_w; moveX < w1 - min_border_w; moveX++)
        {
            for (int moveY = (-h2 + min_border_h)/factor; moveY < (h1 - min_border_h)/factor; moveY++)
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
