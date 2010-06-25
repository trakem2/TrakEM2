package ini.trakem2.parallel;

import ini.trakem2.utils.Utils;
import java.util.LinkedList;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class Process {

	static private final int MIN_AHEAD = 4;
	static public final int NUM_PROCESSORS = Runtime.getRuntime().availableProcessors();

	static private final int sensible(final int nproc) {
		return Math.max(1, Math.min(nproc, NUM_PROCESSORS + 2));
	}

	/** Takes a Collection of inputs, applies a function to each created by the generator,
	 *  and places their output in outputs in the same order as each input was retrieved from inputs. */
	static public final <I,O> void progressive(final Iterable<I> inputs, final TaskFactory<I,O> generator, final Collection<O> outputs) throws Exception {
		progressive(inputs, generator, outputs, NUM_PROCESSORS);
	}
	/** Takes a Collection of inputs, applies a function to each created by the generator,
	 *  and places their output in outputs in the same order as each input was retrieved from inputs. */
	static public final <I,O> void progressive(final Iterable<I> inputs, final TaskFactory<I,O> generator, final Collection<O> outputs, final int n_proc) throws Exception {
		final int nproc = sensible(n_proc);
		final ExecutorService exec = Utils.newFixedThreadPool(nproc, "Process.progressive");
		try {
			final LinkedList<Future<O>> fus = new LinkedList<Future<O>>();
			final int ahead = Math.max(nproc + nproc, MIN_AHEAD);
			for (final I input : inputs) {
				fus.add(exec.submit(generator.create(input)));
				while (fus.size() > ahead) {
					// wait
					outputs.add(fus.removeFirst().get());
				}
			}
			// wait for remaining, if any
			for (final Future<O> fu : fus) {
				if (null != fu) outputs.add(fu.get());
				else outputs.add(null);
			}
		} finally {
			exec.shutdown();
		}
	}

	/** Takes a Collection of inputs, applies a function to each created by the generator. */
	static public final <I,O> void progressive(final Iterable<I> inputs, final TaskFactory<I,O> generator) throws Exception {
		progressive(inputs, generator, NUM_PROCESSORS);
	}
	static public final <I,O> void progressive(final Iterable<I> inputs, final TaskFactory<I,O> generator, final int n_proc) throws Exception {
		final int nproc = sensible(n_proc);
		final ExecutorService exec = Utils.newFixedThreadPool(nproc, "Process.progressive");
		try {
			final LinkedList<Future<O>> fus = new LinkedList<Future<O>>();
			final int ahead = Math.max(nproc + nproc, MIN_AHEAD);
			for (final I input : inputs) {
				fus.add(exec.submit(generator.create(input)));
				while (fus.size() > ahead) {
					fus.removeFirst().get();
				}
			}
			for (final Future<O> fu : fus) if (null != fu) fu.get();
		} finally {
			exec.shutdown();
		}
	}
}
