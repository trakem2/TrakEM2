package mpicbg.trakem2.concurrent;

import java.util.concurrent.ExecutorService;

/**
 * Interface to allow configurability of distributed jobs in TrakEM2
 *
 * @author Larry Lindsey
 */
public interface ExecutorProvider
{

    public ExecutorService getService(int nThreads);
    
    public ExecutorService getService(float fractionThreads);

}
