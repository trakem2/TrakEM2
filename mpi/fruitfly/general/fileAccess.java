package mpi.fruitfly.general;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.io.FileReader;

/**
 * <p>Title: file access</p>
 *
 * <p>Description: Class that manages general file access open, read, write</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: mpi-cbg</p>
 *
 * @author Stephan Preibisch
 * @version 1.0
 */

import ij.*;
import ij.process.ImageProcessor;
import ij.io.Opener;
import java.io.File;
import ij.io.FileSaver;

public class fileAccess
{
    public static void saveStack(ImageStack stack, String fileName, int numberLength)
    {
        for (int i = 1; i <= stack.getSize(); i++)
            new FileSaver(new ImagePlus("Image "+i,stack.getProcessor(i))).saveAsTiff(fileName + genNumberString(i, numberLength) + ".tif");
    }

    public static ImagePlus openCompleteStack(String imageStackDir, String imageStackName, String imageStackExt, int numberLength, int startingNumber)
    {
        Opener open = new Opener();

        ImagePlus impTmp = open.openImage(imageStackDir + imageStackName + genNumberString(startingNumber, numberLength) + imageStackExt);
        ImageStack stack = new ImageStack(impTmp.getWidth(), impTmp.getHeight());

        File f = new File(imageStackDir);
        int countFiles = startingNumber;
        String fileName = "";

        for (File entry : f.listFiles())
            if (entry.toString().startsWith(imageStackDir + imageStackName) && entry.toString().endsWith(imageStackExt))
            {
                fileName = imageStackDir + imageStackName + genNumberString(countFiles++, numberLength) + imageStackExt;

                try
                {
                    stack.addSlice(fileName, open.openImage(fileName).getProcessor());
                }
                catch (Exception e)
                {
                    countFiles--;
                };

            }

        System.out.println(countFiles + " files read.");

        return new ImagePlus(imageStackName, stack);
    }

    public static ImagePlus openStack(String imageStackDir, String imageStackName, String imageStackExt, int numberLength, int startingNumber, int endingNumber)
    {
        Opener open = new Opener();

        ImagePlus impTmp = open.openImage(imageStackDir + imageStackName + genNumberString(startingNumber, numberLength) + imageStackExt);
        ImageStack stack = new ImageStack(impTmp.getWidth(), impTmp.getHeight());

        File f = new File(imageStackDir);
        String fileName = "";
        int countFiles = 0;

        for (int num = startingNumber; num <= endingNumber; num++)
        {
            fileName = imageStackDir + imageStackName + genNumberString(num, numberLength) + imageStackExt;

            try
            {
               // System.out.print("Reading " + fileName + " ... ");

                stack.addSlice(fileName, open.openImage(fileName).getProcessor());
                countFiles++;

                //System.out.println("done");

            } catch (Exception e)
            {

            };
        }


        System.out.println(countFiles + " files read.");

        return new ImagePlus(imageStackName, stack);
    }

    private static String genNumberString(int number, int length)
    {
        String result = "";

        result = "" + number;
        while (result.length()  < length)
            result = "0" + result;

        return result;
    }

    public static BufferedReader openFileRead(String fileName)
    {
      BufferedReader inputFile;
      try
      {
        inputFile = new BufferedReader(new FileReader(fileName));
      }
      catch (IOException e)
      {
        System.err.println("mpi.fruitfly.general.openFileRead(): " + e);
        inputFile = null;
      }
      return(inputFile);
    }

    public static PrintWriter openFileWrite(String fileName)
    {
      PrintWriter outputFile;
      try
      {
        outputFile = new PrintWriter(new FileWriter(fileName));
      }
      catch (IOException e)
      {
        System.err.println("mpi.fruitfly.general.openFileWrite(): " + e);
        outputFile = null;
      }
      return(outputFile);
    }
}
