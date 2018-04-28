package ini.trakem2.parallel;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class CountingTaskFactory<I,O> extends TaskFactory<I,O> {

	private AtomicInteger count = new AtomicInteger(0);
	
	/** Generates a Callable task for an ExecutorService to process @param input.
	 *  Unless overriden, will simply call process(input);
	 */
	@Override
	public Callable<O> create(final I input) {
		return new Callable<O>() {
			public O call() {
				return process(input, count.getAndIncrement());
			}
		};
	}

	/** The actual processing on the given @param input;
	 *  override to define a task to be performed over @param input.
	 *  By default does nothing and returns null. */
	public O process(final I input, final int index) { return null; }
}