package mpicbg.trakem2.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Default Executor Provider, which creates ExecutorServices from java.util.concurrent.Executors
 *
 * @author Larry Lindsey
 */
public class DefaultExecutorProvider implements ExecutorProvider
{
    public ExecutorService getExecutor(int nThreads) {
        return Executors.newFixedThreadPool(nThreads);
    }
}
