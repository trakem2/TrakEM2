package ini.trakem2.parallel;

import java.util.concurrent.Callable;

public abstract class TaskFactory<I,O> {

	/** Generates a Callable task for an ExecutorService to process @param input.
	 *  Unless overriden, will simply call process(input); */
	public Callable<O> create(final I input) {
		return new Callable<O>() {
			public O call() {
				return process(input);
			}
		};
	}

	/** The actual processing on the given @param input;
	 *  override to define a task to be performed over @param input.
	 *  By default does nothing and returns null. */
	public O process(final I input) { return null; }
}
