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
package ini.trakem2.parallel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Default Executor Provider, which creates ExecutorServices from java.util.concurrent.Executors
 *
 * @author Larry Lindsey
 */
public class DefaultExecutorProvider extends ExecutorProvider
{

    public ExecutorService getService(int nThreads)
    {
        int nCpu = Runtime.getRuntime().availableProcessors();
        int poolSize = nCpu / nThreads;
        return Executors.newFixedThreadPool(poolSize < 1 ? 1 : poolSize);
    }

    public ExecutorService getService(float fractionThreads)
    {
        int nThreads = (int)(fractionThreads * (float)Runtime.getRuntime().availableProcessors());
        return getService(nThreads);
    }
}
