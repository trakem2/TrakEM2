package mpicbg.trakem2.concurrent;

import java.util.concurrent.ExecutorService;

/**
 * Access to a centralized thread pool.
 * 
 * @author Larry Lindsey
 */
public class ThreadPool
{
    
    private static ExecutorProvider provider = new DefaultExecutorProvider();
    
    public static ExecutorService getExecutorService(final int nThreads)
    {
        return provider.getService(nThreads);
    }
    
    public static ExecutorService getExecutorService(final float fractionThreads)
    {
        return provider.getService(fractionThreads);
    }
    
    public static void setProvider(final ExecutorProvider ep)
    {
        provider = ep;
    }

    public static ExecutorProvider getProvider()
    {
        return provider;
    }
    
}
