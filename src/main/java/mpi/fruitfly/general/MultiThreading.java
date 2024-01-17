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

package mpi.fruitfly.general;

/**
 * MultiThreading.
 *
 * @author Stephan Preibisch
 */

public class MultiThreading
{
    /*
    final int start = 0;
    final int end = 10;

    final AtomicInteger ai = new AtomicInteger(start);

    Thread[] threads = newThreads();
    for (int ithread = 0; ithread < threads.length; ++ithread)
    {
        threads[ithread] = new Thread(new Runnable()
        {
            public void run()
            {
                // do something....
                // for example:
                for (int i3 = ai.getAndIncrement(); i3 < end; i3 = ai.getAndIncrement())
                {
                }
            }
        });
    }
    startAndJoin(threads);
    */

    public static void startTask(Runnable run)
    {
        Thread[] threads = newThreads();

        for (int ithread = 0; ithread < threads.length; ++ithread)
            threads[ithread] = new Thread(run);

        startAndJoin(threads);
    }

    public static void startTask(Runnable run, int numThreads)
    {
        Thread[] threads = newThreads(numThreads);

        for (int ithread = 0; ithread < threads.length; ++ithread)
            threads[ithread] = new Thread(run);

        startAndJoin(threads);
    }


    public static Thread[] newThreads()
    {
      int nthread = Runtime.getRuntime().availableProcessors();
      return new Thread[nthread];
    }

    public static Thread[] newThreads(int numThreads)
    {
      return new Thread[numThreads];
    }

    public static void startAndJoin(Thread[] threads)
    {
        for (int ithread = 0; ithread < threads.length; ++ithread)
        {
            threads[ithread].setPriority(Thread.NORM_PRIORITY);
            threads[ithread].start();
        }

        try
        {
            for (int ithread = 0; ithread < threads.length; ++ithread)
                threads[ithread].join();
        } catch (InterruptedException ie)
        {
            throw new RuntimeException(ie);
        }
    }
}
