package mpi.fruitfly.general;

/**
 * <p>Title: file parsing</p>
 *
 * <p>Description: Class for parsing different file types needed</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: mpi-cbg</p>
 *
 * @author Stephan Preibisch
 * @version 1.0
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Iterator;

import static mpi.fruitfly.general.fileAccess.*;
import ij.IJ;


public class fileParsing
{
    public static float[][] parseFilter(String fileName)
    {
        // open file
        BufferedReader in = openFileRead(fileName);
        if (in == null)
            return null;

        // read file
        ArrayList file = new ArrayList();
        try
        {
            while (in.ready()) file.add(in.readLine());
        }
        catch (Exception e)
        {
            System.err.println("mpi.fruitfly.general.readFilter("+fileName+"): " + e);
            return null;
        }

        // parse data
        if (file.size() == 0)
        {
            System.err.println("mpi.fruitfly.general.readFilter("+fileName+"): " + "Filter-file is empty.");
            return null;
        }

        String line[];
        Iterator<String> i = file.iterator();
        ArrayList data = new ArrayList();

        int maxX  = 0;

        while (i.hasNext())
        {
            try
            {
                line = i.next().trim().split(" ");
                if (line.length > maxX)
                    maxX = line.length;
                data.add(line);
            }
            catch (Exception e)
            {
                System.err.println("mpi.fruitfly.general.readFilter("+fileName+"): " + e);
                return null;
            }
        }

        if (maxX == 0)
        {
            System.err.println("mpi.fruitfly.general.readFilter(" + fileName + "): " + "Filter-file has no entries.");
            return null;
        }

        float[][] filter = new float[maxX][file.size()];
        Iterator<String[]> j = data.iterator();
        int y = 0;

        while (j.hasNext())
        {
            try
            {
                line = j.next();
                for  (int x = 0; x < line.length; x++)
                    filter[x][y] = Float.parseFloat(line[x]);
                y++;
            }
            catch (Exception e)
            {
                System.err.println("mpi.fruitfly.general.readFilter("+fileName+"), line "+(y+1)+": " + e);
                return null;
            }
        }

        if (IJ.debugMode)
        {
            for (y = 0; y < filter[0].length; y++)
            {
                String numbers = "";
                for (int x = 0; x < filter.length; x++)
                    numbers += filter[x][y] + " ";
                IJ.log(numbers);
            }

        }

        return filter;
    }
}
