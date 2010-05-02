package ini.trakem2.parallel;

import ini.trakem2.utils.Utils;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Process {

	static private final int MIN_AHEAD = 4;

	/** Takes a Collection of inputs, applies a function to each created by the generator, and places their output in output. */
	static public final <I,O> void progressive(final Collection<I> inputs, final TaskFactory<I,O> generator, final Collection<O> outputs) throws Exception {
		final int nproc = Runtime.getRuntime().availableProcessors();
		final ExecutorService exec = Executors.newFixedThreadPool(nproc);
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
	static public final <I,O> void progressive(final Collection<I> inputs, final TaskFactory<I,O> generator) throws Exception {
		final int nproc = Runtime.getRuntime().availableProcessors();
		final ExecutorService exec = Executors.newFixedThreadPool(nproc);
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
