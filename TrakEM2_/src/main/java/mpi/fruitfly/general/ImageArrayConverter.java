package mpi.fruitfly.general;

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

import static mpi.fruitfly.math.General.max;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.awt.Point;

import mpi.fruitfly.math.datastructures.FloatArray;
import mpi.fruitfly.math.datastructures.FloatArray2D;
import mpi.fruitfly.math.datastructures.FloatArray3D;
import mpi.fruitfly.math.datastructures.FloatArray4D;


public class ImageArrayConverter
{
    public static boolean CUTOFF_VALUES = true;
    public static boolean NORM_VALUES = false;

    public static ImagePlus FloatArrayToImagePlus(FloatArray2D image, String name, float min, float max)
    {
        ImagePlus imp = IJ.createImage(name,"32-Bit Black", image.width, image.height, 1);
        FloatProcessor ip = (FloatProcessor)imp.getProcessor();
        FloatArrayToFloatProcessor(ip, image);

        if (min == max)
            ip.resetMinAndMax();
        else
            ip.setMinAndMax(min, max);

        imp.updateAndDraw();

        return imp;
    }

    public static ImagePlus FloatArrayToStack(FloatArray3D image, String name, float min, float max)
    {
        int width = image.width;
        int height = image.height;
        int nstacks = image.depth;

        ImageStack stack = new ImageStack(width, height);

        for (int slice = 0; slice < nstacks; slice++)
        {
            ImagePlus impResult = IJ.createImage("Result", "32-Bit Black", width, height, 1);
            ImageProcessor ipResult = impResult.getProcessor();
            float[] sliceImg = new float[width * height];

            for (int x = 0; x < width; x++)
                for (int y = 0; y < height; y++)
                    sliceImg[y * width + x] = image.get(x,y,slice);

            ipResult.setPixels(sliceImg);

            if (min == max)
                ipResult.resetMinAndMax();
            else
                ipResult.setMinAndMax(min, max);

            stack.addSlice("Slice " + slice, ipResult);
        }

         return new ImagePlus(name, stack);
    }

    public static ImagePlus DoubleArrayToStack(double[] image, int width, int height, int nstacks, String name, float min, float max)
    {
        ImageStack stack = new ImageStack(width, height);

        for (int slice = 0; slice < nstacks; slice++)
        {
            ImagePlus impResult = IJ.createImage("Result", "32-Bit Black", width, height, 1);
            ImageProcessor ipResult = impResult.getProcessor();
            float[] sliceImg = new float[width * height];

            for (int x = 0; x < width; x++)
                for (int y = 0; y < height; y++)
                    sliceImg[y * width + x] = (float)image[x + width * (y + slice * height)];

            ipResult.setPixels(sliceImg);

            if (min == max)
                ipResult.resetMinAndMax();
            else
                ipResult.setMinAndMax(min, max);

            stack.addSlice("Slice " + slice, ipResult);
        }

         return new ImagePlus(name, stack);
    }

    public static FloatArray3D StackToFloatArrayZeroPadding(ImageStack stack, int wZP, int hZP, int dZP)
    {
        Object[] imageStack = stack.getImageArray();

        int width = stack.getWidth();
        int height = stack.getHeight();
        int nstacks = stack.getSize();

        int offsetX = (wZP - width)/2;
        int offsetY = (hZP - height)/2;
        int offsetZ = (dZP - nstacks)/2;

        if (imageStack == null || imageStack.length == 0)
        {
            System.out.println("Image Stack is empty.");
            return null;
        }

        if (imageStack[0] instanceof int[])
        {
            System.out.println("RGB images supported at the moment.");
            return null;
        }


        //float[][][] pixels = new float[wZP][hZP][dZP];
        FloatArray3D pixels = new FloatArray3D(wZP,hZP,dZP);

        int count, pos;
        int stepY = pixels.getPos(0,1,0) - width;

        if (imageStack[0] instanceof byte[])
        {
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                byte[] pixelTmp = (byte[]) imageStack[countSlice];
                count = 0;
                pos = pixels.getPos(offsetX, offsetY, offsetZ + countSlice);

                for (int y = 0; y < height; y++)
                {
                    for (int x = 0; x < width; x++)
                    {
                        //pixels.data[pixels.getPos(x + offsetX, y + offsetY, countSlice + offsetZ)] = (float) (pixelTmp[count++] & 0xff);
                        pixels.data[pos++] = (float) (pixelTmp[count++] & 0xff);
                    }
                    pos += stepY;
                }
            }
        }
        else if (imageStack[0] instanceof short[])
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                short[] pixelTmp = (short[])imageStack[countSlice];
                count = 0;
                pos = pixels.getPos(offsetX, offsetY, offsetZ + countSlice);

                for (int y = 0; y < height; y++)
                {
                    for (int x = 0; x < width; x++)
                    {
                        //pixels.data[pixels.getPos(x + offsetX, y + offsetY, countSlice + offsetZ)] = (float) (pixelTmp[count++] & 0xffff);
                        pixels.data[pos++] =  (float) (pixelTmp[count++] & 0xffff);
                    }
                    pos += stepY;
                }
            }
        else // instance of float[]
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                float[] pixelTmp = (float[])imageStack[countSlice];
                count = 0;
                pos = pixels.getPos(offsetX, offsetY, offsetZ + countSlice);

                for (int y = 0; y < height; y++)
                {
                    for (int x = 0; x < width; x++)
                    {
                        //pixels.data[pixels.getPos(x + offsetX, y + offsetY, countSlice + offsetZ)] = pixelTmp[count++];
                        pixels.data[pos++] = pixelTmp[count++];
                    }
                    pos += stepY;
                }
            }

        return pixels;
    }

    public static FloatArray4D StackToFloatArrayComplexZeroPadding(ImageStack stack, int wZP, int hZP, int dZP)
    {
        Object[] imageStack = stack.getImageArray();

        int width = stack.getWidth();
        int height = stack.getHeight();
        int nstacks = stack.getSize();

        int offsetX = (wZP - width)/2;
        int offsetY = (hZP - height)/2;
        int offsetZ = (dZP - nstacks)/2;

        if (imageStack == null || imageStack.length == 0)
        {
            System.out.println("Image Stack is empty.");
            return null;
        }

        if (imageStack[0] instanceof int[])
        {
            System.out.println("RGB images supported at the moment.");
            return null;
        }


        //float[][][] pixels = new float[wZP][hZP][dZP];
        FloatArray4D pixels = new FloatArray4D(wZP,hZP,dZP,2);

        int count, pos;
        int stepY = pixels.getPos(0,1,0,0) - width;


        if (imageStack[0] instanceof byte[])
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                byte[] pixelTmp = (byte[])imageStack[countSlice];
                count = 0;
                pos = pixels.getPos(offsetX, offsetY, offsetZ + countSlice,0);

                for (int y = 0; y < height; y++)
                {
                    for (int x = 0; x < width; x++)
                    {
                        //pixels.data[pixels.getPos(x + offsetX, y + offsetY, countSlice + offsetZ, 0)] = (float) (pixelTmp[count++] & 0xff);
                        pixels.data[pos++] = (float) (pixelTmp[count++] & 0xff);
                    }
                    pos += stepY;
                }
            }
        else if (imageStack[0] instanceof short[])
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                short[] pixelTmp = (short[])imageStack[countSlice];
                count = 0;
                pos = pixels.getPos(offsetX, offsetY, offsetZ + countSlice,0);

                for (int y = 0; y < height; y++)
                {
                    for (int x = 0; x < width; x++)
                    {
                        //pixels.data[pixels.getPos(x + offsetX, y + offsetY, countSlice + offsetZ, 0)] = (float) (pixelTmp[count++] & 0xffff);
                        pixels.data[pos++] = (float) (pixelTmp[count++] & 0xffff);
                    }
                    pos += stepY;
                }
            }
        else // instance of float[]
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                float[] pixelTmp = (float[])imageStack[countSlice];
                count = 0;
                pos = pixels.getPos(offsetX, offsetY, offsetZ + countSlice,0);

                for (int y = 0; y < height; y++)
                {
                    for (int x = 0; x < width; x++)
                    {
                        //pixels.data[pixels.getPos(x + offsetX, y + offsetY, countSlice + offsetZ, 0)] = pixelTmp[count++];
                        pixels.data[pos++] = pixelTmp[count++];
                    }
                    pos += stepY;
                }
            }

        return pixels;
    }

    public static double[] StackToDoubleArrayZeroPadding(ImageStack stack, int wZP, int hZP, int dZP)
    {
        Object[] imageStack = stack.getImageArray();

        int width = stack.getWidth();
        int height = stack.getHeight();
        int nstacks = stack.getSize();

        int offsetX = (wZP - width)/2;
        int offsetY = (hZP - height)/2;
        int offsetZ = (dZP - nstacks)/2;

        if (imageStack == null || imageStack.length == 0)
        {
            System.out.println("Image Stack is empty.");
            return null;
        }

        if (imageStack[0] instanceof int[])
        {
            System.out.println("RGB images supported at the moment.");
            return null;
        }


        double[] pixels = new double[wZP * hZP * dZP];
        int count;

        if (imageStack[0] instanceof byte[])
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                byte[] pixelTmp = (byte[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels[(x+offsetX) + width * ((y+offsetY) + (countSlice+offsetZ) * height)] = (pixelTmp[count++] & 0xff);
            }
        else if (imageStack[0] instanceof short[])
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                short[] pixelTmp = (short[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels[(x+offsetX) + width * ((y+offsetY) + (countSlice+offsetZ) * height)] = (pixelTmp[count++] & 0xffff);
            }
        else // instance of float[]
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                float[] pixelTmp = (float[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels[(x+offsetX) + wZP * ((y+offsetY) + (countSlice+offsetZ) * hZP)] = pixelTmp[count++];
            }

        return pixels;
    }

    //id + nd * (id-1 + nd-1 * (... + n2 * i1))
    public static double[] StackToDoubleArray1D(ImageStack stack)
    {
        Object[] imageStack = stack.getImageArray();
        int width = stack.getWidth();
        int height = stack.getHeight();
        int nstacks = stack.getSize();

        if (imageStack == null || imageStack.length == 0)
        {
            System.out.println("Image Stack is empty.");
            return null;
        }

        if (imageStack[0] instanceof int[])
        {
            System.out.println("RGB images supported at the moment.");
            return null;
        }

        double[] pixels = new double[width * height * nstacks];
        int count;


        if (imageStack[0] instanceof byte[])
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                byte[] pixelTmp = (byte[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels[x + width * (y + countSlice * height)] = (pixelTmp[count++] & 0xff);
            }
        else if (imageStack[0] instanceof short[])
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                short[] pixelTmp = (short[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels[x + width * (y + countSlice * height)] = (float)(pixelTmp[count++] & 0xffff);
            }
        else // instance of float[]
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                float[] pixelTmp = (float[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels[x + width * (y + countSlice * height)] = pixelTmp[count++];
            }


        return pixels;
    }

    public static FloatArray3D StackToFloatArray(ImageStack stack)
    {
        Object[] imageStack = stack.getImageArray();
        int width = stack.getWidth();
        int height = stack.getHeight();
        int nstacks = stack.getSize();

        if (imageStack == null || imageStack.length == 0)
        {
            System.out.println("Image Stack is empty.");
            return null;
        }

        if (imageStack[0] instanceof int[])
        {
            System.out.println("RGB images supported at the moment.");
            return null;
        }

        FloatArray3D pixels = new FloatArray3D(width, height, nstacks);
        //float[][][] pixels = new float[width][height][nstacks];
        int count;


        if (imageStack[0] instanceof byte[])
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                byte[] pixelTmp = (byte[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels.data[pixels.getPos(x,y,countSlice)] = (float)(pixelTmp[count++] & 0xff);
            }
        else if (imageStack[0] instanceof short[])
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                short[] pixelTmp = (short[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels.data[pixels.getPos(x,y,countSlice)] = (float)(pixelTmp[count++] & 0xffff);
            }
        else // instance of float[]
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                float[] pixelTmp = (float[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels.data[pixels.getPos(x,y,countSlice)] = pixelTmp[count++];
            }


        return pixels;
    }

    public static float[][][] StackToFloatArrayDeprecated(ImageStack stack)
    {
        Object[] imageStack = stack.getImageArray();
        int width = stack.getWidth();
        int height = stack.getHeight();
        int nstacks = stack.getSize();

        if (imageStack == null || imageStack.length == 0)
        {
            System.out.println("Image Stack is empty.");
            return null;
        }

        if (imageStack[0] instanceof int[])
        {
            System.out.println("RGB images supported at the moment.");
            return null;
        }

        float[][][] pixels = new float[width][height][nstacks];
        int count;


        if (imageStack[0] instanceof byte[])
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                byte[] pixelTmp = (byte[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels[x][y][countSlice] = (float)(pixelTmp[count++] & 0xff);
            }
        else if (imageStack[0] instanceof short[])
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                short[] pixelTmp = (short[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels[x][y][countSlice] = (float)(pixelTmp[count++] & 0xffff);
            }
        else // instance of float[]
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                float[] pixelTmp = (float[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels[x][y][countSlice] = pixelTmp[count++];
            }


        return pixels;
    }

    public static FloatArray4D StackToFloatArrayComplex(ImageStack stack)
    {
        Object[] imageStack = stack.getImageArray();
        int width = stack.getWidth();
        int height = stack.getHeight();
        int nstacks = stack.getSize();

        if (imageStack == null || imageStack.length == 0)
        {
            System.out.println("Image Stack is empty.");
            return null;
        }

        if (imageStack[0] instanceof int[])
        {
            System.out.println("RGB images supported at the moment.");
            return null;
        }

        FloatArray4D pixels = new FloatArray4D(width, height, nstacks, 2);
        //float[][][] pixels = new float[width][height][nstacks];
        int count;


        if (imageStack[0] instanceof byte[])
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                byte[] pixelTmp = (byte[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels.data[pixels.getPos(x,y,countSlice,0)] = (float)(pixelTmp[count++] & 0xff);
            }
        else if (imageStack[0] instanceof short[])
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                short[] pixelTmp = (short[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels.data[pixels.getPos(x,y,countSlice,0)] = (float)(pixelTmp[count++] & 0xffff);
            }
        else // instance of float[]
            for (int countSlice = 0; countSlice < nstacks; countSlice++)
            {
                float[] pixelTmp = (float[])imageStack[countSlice];
                count = 0;

                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        pixels.data[pixels.getPos(x,y,countSlice,0)] = pixelTmp[count++];
            }


        return pixels;
    }

    public static int[][] ImageToIntArray(ImageProcessor ip)
    {
        int[][] image;
        Object pixelArray = ip.getPixels();
        int count = 0;

        if (ip instanceof ByteProcessor)
        {
            image = new int[ip.getWidth()][ip.getHeight()];
            byte[] pixels = (byte[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image[x][y] = pixels[count++] & 0xff;
        }
        else if (ip instanceof ShortProcessor)
        {
            image = new int[ip.getWidth()][ip.getHeight()];
            short[] pixels = (short[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image[x][y] = pixels[count++] & 0xffff;
        }
        else if (ip instanceof FloatProcessor)
        {
            image = new int[ip.getWidth()][ip.getHeight()];
            float[] pixels = (float[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image[x][y] = (int)pixels[count++];
        }
        else //RGB
        {
            image = new int[ip.getWidth()][ip.getHeight()];
            int[] pixels = (int[])pixelArray;

            // still unknown how to do...

            /*
            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                        image[x][y] = pixels[count++];// & 0xffffff;
            */
        }

        return image;
    }

    public static float[][] ImageToFloatArray2DDeprecated(ImageProcessor ip)
    {
        float[][] image;
        Object pixelArray = ip.getPixels();
        int count = 0;

        if (ip instanceof ByteProcessor)
        {
            image = new float[ip.getWidth()][ip.getHeight()];
            byte[] pixels = (byte[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image[x][y] = pixels[count++] & 0xff;
        }
        else if (ip instanceof ShortProcessor)
        {
            image = new float[ip.getWidth()][ip.getHeight()];
            short[] pixels = (short[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image[x][y] = pixels[count++] & 0xffff;
        }
        else if (ip instanceof FloatProcessor)
        {
            image = new float[ip.getWidth()][ip.getHeight()];
            float[] pixels = (float[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image[x][y] = pixels[count++];
        }
        else //RGB
        {
            image = new float[ip.getWidth()][ip.getHeight()];
            int[] pixels = (int[])pixelArray;

            // still unknown how to do...

            /*
            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                        image[x][y] = pixels[count++];// & 0xffffff;
            */
        }

        return image;
    }

    public static double[] ImageToDoubleArray1D(ImageProcessor ip)
    {
        double[] image;
        Object pixelArray = ip.getPixels();
        int count = 0;

        if (ip instanceof ByteProcessor)
        {
            image = new double[ip.getWidth() * ip.getHeight()];
            byte[] pixels = (byte[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image[count] = pixels[count++] & 0xff;
        }
        else if (ip instanceof ShortProcessor)
        {
            image = new double[ip.getWidth() * ip.getHeight()];
            short[] pixels = (short[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image[count] = pixels[count++] & 0xffff;
        }
        else if (ip instanceof FloatProcessor)
        {
            image = new double[ip.getWidth() * ip.getHeight()];
            float[] pixels = (float[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image[count] = pixels[count++];
        }
        else //RGB
        {
            image = new double[ip.getWidth() * ip.getHeight()];
            int[] pixels = (int[])pixelArray;

            // still unknown how to do...

            /*
            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                        image[x][y] = pixels[count++];// & 0xffffff;
            */
        }

        return image;
    }

    public static FloatArray2D ImageToFloatArray2D(ImageProcessor ip)
    {
        FloatArray2D image;
        Object pixelArray = ip.getPixels();
        int count = 0;

        if (ip instanceof ByteProcessor)
        {
            image = new FloatArray2D(ip.getWidth(),  ip.getHeight());
            byte[] pixels = (byte[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image.data[count] = pixels[count++] & 0xff;
        }
        else if (ip instanceof ShortProcessor)
        {
            image = new FloatArray2D(ip.getWidth(),  ip.getHeight());
            short[] pixels = (short[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image.data[count] = pixels[count++] & 0xffff;
        }
        else if (ip instanceof FloatProcessor)
        {
            image = new FloatArray2D(ip.getWidth(),  ip.getHeight());
            float[] pixels = (float[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image.data[count] = pixels[count++];
        }
        else //RGB
        {
            image = new FloatArray2D(ip.getWidth(),  ip.getHeight());
            int[] pixels = (int[])pixelArray;

            // still unknown how to do...

            /*
            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                        image[x][y] = pixels[count++];// & 0xffffff;
            */
        }

        return image;
    }

    public static void normFloatArray(FloatArray img)
    {
        float min = img.data[0];
        float max = img.data[0];

        for (float f : img.data)
        {
            if (f > max)
                max = f;

            if (f < min)
                min = f;
        }

        for (int i = 0; i < img.data.length; i++)
            img.data[i] = (img.data[i] - min)/(max-min);
    }

    public static int DRAWTYPE_OVERLAP = 0;
    public static int DRAWTYPE_ERRORMAP = 1;

    public static FloatArray2D drawTranslatedImages(FloatArray2D img1, FloatArray2D img2, Point shift, int drawType)
    {
        int w1 = img1.width;
        int h1 = img1.height;

        int w2 = img2.width;
        int h2 = img2.height;

        boolean createOverlappingImages = (drawType == DRAWTYPE_OVERLAP);
        boolean createErrorMap = (drawType == DRAWTYPE_ERRORMAP);

        int sx = shift.x;
        int sy = shift.y;

        int imgW = max(w1, w2) + max(0, Math.abs(sx) - Math.abs((w1 - w2)));
        int imgH = max(h1, h2) + max(0, Math.abs(sy) - Math.abs((h1 - h2)));

        FloatArray2D outputImg = new FloatArray2D(imgW, imgH);

        int offsetImg1X = max(0, -sx); // + max(0, max(0, -sx) - max(0, (w1 - w2)/2));
        int offsetImg1Y = max(0, -sy); // + max(0, max(0, -sy) - max(0, (h1 - h2)/2));
        int offsetImg2X = max(0, sx); // + max(0, max(0, sx) - max(0, (w2 - w1)/2));
        int offsetImg2Y = max(0, sy); // + max(0, max(0, sy) - max(0, (h2 - h1)/2));

        float pixel1, pixel2;

        // iterate over whole image
        for (int y = 0; y < imgH; y++)
            for (int x = 0; x < imgW; x++)
            {
                pixel1 = img1.getZero(x - offsetImg1X, y - offsetImg1Y);
                pixel2 = img2.getZero(x - offsetImg2X, y - offsetImg2Y);

                if (createOverlappingImages)
                    outputImg.set( (pixel1 + pixel2) / 2f, x, y);

                // compute errors only if the images overlap
                if (x >= offsetImg1X && x >= offsetImg2X &&
                    x < offsetImg1X + w1 && x < offsetImg2X + w2 &&
                    y >= offsetImg1Y && y >= offsetImg2Y &&
                    y < offsetImg1Y + h1 && y < offsetImg2Y + h2)
                {
                    if (createErrorMap)
                        outputImg.set( (pixel1 + pixel2) / 2f, x, y);
                }
            }

        return outputImg;
    }

    public static FloatArray2D zeroPad(FloatArray2D ip, int width, int height)
    {
        FloatArray2D image = new FloatArray2D(width,  height);

        int offsetX = (width - ip.width)/2;
        int offsetY = (height - ip.height)/2;

        if (offsetX < 0)
        {
            System.err.println("mpi.fruitfly.general.ImageArrayConverter.ZeroPad(): Zero-Padding size in X smaller than image! " + width + " < " + ip.width);
            return null;
        }

        if (offsetY < 0)
        {
            System.err.println("mpi.fruitfly.general.ImageArrayConverter.ZeroPad(): Zero-Padding size in Y smaller than image! " + height + " < " + ip.height);
            return null;
        }

        int count = 0;

        for (int y = 0; y < ip.height; y++)
            for (int x = 0; x < ip.width; x++)
                image.set(ip.data[count++], x+offsetX, y+offsetY);

        return image;
    }


    public static FloatArray2D ImageToFloatArray2DZeroPadding(ImageProcessor ip, int width, int height)
    {
        FloatArray2D image = new FloatArray2D(width,  height);
        Object pixelArray = ip.getPixels();
        int count = 0;

        int offsetX = (width - ip.getWidth())/2;
        int offsetY = (height - ip.getHeight())/2;

        if (offsetX < 0)
        {
            System.err.println("mpi.fruitfly.general.ImageArrayConverter.ImageToFloatArray2DZeroPadding(): Zero-Padding size in X smaller than image! " + width + " < " + ip.getWidth());
            return null;
        }

        if (offsetY < 0)
        {
            System.err.println("mpi.fruitfly.general.ImageArrayConverter.ImageToFloatArray2DZeroPadding(): Zero-Padding size in Y smaller than image! " + height + " < " + ip.getHeight());
            return null;
        }

        if (ip instanceof ByteProcessor)
        {
            byte[] pixels = (byte[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image.set(pixels[count++] & 0xff, x+offsetX, y+offsetY);
        }
        else if (ip instanceof ShortProcessor)
        {
            short[] pixels = (short[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image.set(pixels[count++] & 0xffff, x+offsetX, y+offsetY);
        }
        else if (ip instanceof FloatProcessor)
        {
            float[] pixels = (float[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image.set(pixels[count++], x+offsetX, y+offsetY);
        }
        else //RGB
        {
            int[] pixels = (int[])pixelArray;

            // still unknown how to do...

            /*
            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                        image[x][y] = pixels[count++];// & 0xffffff;
            */
        }

        return image;
    }


    public static double[] ImageToDoubleArray1DZeroPadding(ImageProcessor ip, int width, int height)
    {
        double[] image;
        Object pixelArray = ip.getPixels();
        int count = 0;

        int offsetX = (width - ip.getWidth())/2;
        int offsetY = (height - ip.getHeight())/2;

        if (offsetX < 0)
        {
            System.err.println("mpi.fruitfly.general.ImageArrayConverter.ImageToDoubleArray1DZeroPadding(): Zero-Padding size in X smaller than image! " + width + " < " + ip.getWidth());
            return null;
        }

        if (offsetY < 0)
        {
            System.err.println("mpi.fruitfly.general.ImageArrayConverter.ImageToDoubleArray1DZeroPadding(): Zero-Padding size in Y smaller than image! " + height + " < " + ip.getHeight());
            return null;
        }

        if (ip instanceof ByteProcessor)
        {
            image = new double[width * height];
            byte[] pixels = (byte[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image[(y+offsetY)*width + x + offsetX] = pixels[count++] & 0xff;
        }
        else if (ip instanceof ShortProcessor)
        {
            image = new double[width * height];
            short[] pixels = (short[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image[(y+offsetY)*width + x + offsetX] = pixels[count++] & 0xffff;
        }
        else if (ip instanceof FloatProcessor)
        {
            image = new double[width * height];
            float[] pixels = (float[])pixelArray;

            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                    image[(y+offsetY)*width + x + offsetX] = pixels[count++];
        }
        else //RGB
        {
            image = new double[width * height];
            int[] pixels = (int[])pixelArray;

            // still unknown how to do...

            /*
            for (int y = 0; y < ip.getHeight(); y++)
                for (int x = 0; x < ip.getWidth(); x++)
                        image[x][y] = pixels[count++];// & 0xffffff;
            */
        }

        return image;
    }

    public static void ArrayToByteProcessor(ImageProcessor ip, int[][] pixels)
    {
        byte[] data = new byte[pixels.length * pixels[0].length];

        int count = 0;
        for (int y = 0; y < pixels[0].length; y++)
            for (int x = 0; x < pixels.length; x++)
                data[count++] = (byte)(pixels[x][y] & 0xff);

        ip.setPixels(data);
    }

    public static void ArrayToByteProcessor(ImageProcessor ip, float[][] pixels)
    {
        byte[] data = new byte[pixels.length * pixels[0].length];

        int count = 0;
        for (int y = 0; y < pixels[0].length; y++)
            for (int x = 0; x < pixels.length; x++)
                data[count++] = (byte)(((int)pixels[x][y]) & 0xff);

        ip.setPixels(data);
    }

    public static void ArrayToFloatProcessor(ImageProcessor ip, double[] pixels, int width, int height)
    {
        float[] data = new float[width * height];

        int count = 0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                data[count] = (float)pixels[count++];

        ip.setPixels(data);
        ip.resetMinAndMax();
    }

    public static void ArrayToFloatProcessor(ImageProcessor ip, float[] pixels, int width, int height)
    {
        float[] data = new float[width * height];

        int count = 0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                data[count] = (float)pixels[count++];

        ip.setPixels(data);
        ip.resetMinAndMax();
    }

    public static void FloatArrayToFloatProcessor(ImageProcessor ip, FloatArray2D pixels)
    {
        float[] data = new float[pixels.width * pixels.height];

        int count = 0;
        for (int y = 0; y < pixels.height; y++)
            for (int x = 0; x < pixels.width; x++)
                data[count] = pixels.data[count++];

        ip.setPixels(data);
        ip.resetMinAndMax();
    }

    public static void normPixelValuesToByte(int[][] pixels, boolean cutoff)
    {
        int max = 0, min = 255;

        // check minmal and maximal values or cut of values that are higher or lower than 255 resp. 0
        for (int y = 0; y < pixels[0].length; y++)
            for (int x = 0; x < pixels.length; x++)
            {
                if (cutoff)
                {
                    if (pixels[x][y] < 0)
                        pixels[x][y] = 0;

                    if (pixels[x][y] > 255)
                        pixels[x][y] = 255;
                }
                else
                {
                    if (pixels[x][y] < min)
                        min = pixels[x][y];

                    if (pixels[x][y] > max)
                        max = pixels[x][y];
                }
            }

        if (cutoff)
            return;


        // if they do not match bytevalues we have to do something
        if (max > 255 || min < 0)
        {
            double factor;

            factor = (max-min) / 255.0;

            for (int y = 0; y < pixels[0].length; y++)
                for (int x = 0; x < pixels.length; x++)
                    pixels[x][y] = (int)((pixels[x][y] - min) / factor);
        }
    }

    public static void normPixelValuesToByte(float[][] pixels, boolean cutoff)
    {
        float max = 0, min = 255;

        // check minmal and maximal values or cut of values that are higher or lower than 255 resp. 0
        for (int y = 0; y < pixels[0].length; y++)
            for (int x = 0; x < pixels.length; x++)
            {
                if (cutoff)
                {
                    if (pixels[x][y] < 0)
                        pixels[x][y] = 0;

                    if (pixels[x][y] > 255)
                        pixels[x][y] = 255;
                }
                else
                {
                    if (pixels[x][y] < min)
                        min = pixels[x][y];

                    if (pixels[x][y] > max)
                        max = pixels[x][y];
                }
            }

        if (cutoff)
            return;


        // if they do not match bytevalues we have to do something
        if (max > 255 || min < 0)
        {
            double factor;

            factor = (max-min) / 255.0;

            for (int y = 0; y < pixels[0].length; y++)
                for (int x = 0; x < pixels.length; x++)
                    pixels[x][y] = (int)((pixels[x][y] - min) / factor);
        }
    }

    public static void normPixelValuesToByte(float[][][] pixels, boolean cutoff)
    {
        float max = 0, min = 255;

        // check minmal and maximal values or cut of values that are higher or lower than 255 resp. 0
        for (int z = 0; z < pixels[0][0].length; z++)
            for (int y = 0; y < pixels[0].length; y++)
                for (int x = 0; x < pixels.length; x++)
                {
                    if (cutoff)
                    {
                        if (pixels[x][y][z] < 0)
                            pixels[x][y][z] = 0;

                        if (pixels[x][y][z] > 255)
                            pixels[x][y][z] = 255;
                    }
                    else
                    {
                        if (pixels[x][y][z] < min)
                            min = pixels[x][y][z];

                        if (pixels[x][y][z] > max)
                            max = pixels[x][y][z];
                    }
                }

        if (cutoff)
            return;


        // if they do not match bytevalues we have to do something
        if (max > 255 || min < 0)
        {
            double factor;

            factor = (max-min) / 255.0;

            for (int z = 0; z < pixels[0][0].length; z++)
                for (int y = 0; y < pixels[0].length; y++)
                    for (int x = 0; x < pixels.length; x++)
                        pixels[x][y][z] = (int)((pixels[x][y][z] - min) / factor);
        }
    }

}
