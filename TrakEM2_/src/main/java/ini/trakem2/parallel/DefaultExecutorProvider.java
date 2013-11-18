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
