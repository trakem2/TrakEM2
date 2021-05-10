/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2021 Albert Cardona, Stephan Saalfeld and others.
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

import ini.trakem2.utils.Utils;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Like clojure's pmap, given a sequence of inputs obtained from an {@link Iterable},
 * this {@link Iterable} will apply a function to each input ahead of consumption
 * in a pool of threads managed by an {@link ExecutorService}.
 * 
 * Does not hold onto the head of the input sequence.
 * The sequence can be consumed only once, i.e. only a single call to {@link #iterator()} is possible.
 * 
 * This class ought to be an {@link Iterator} rather than an {@link Iterable},
 * but it is an {@link Iterable} for convenience, so that the for loop construct works.
 */
public final class ParallelMapping<I,O> implements Iterable<O>
{
	final private Iterator<I> in;
	final private int n_proc;
	final private TaskFactory<I, O> generator;
	
	public ParallelMapping(final Iterable<I> inputs, final TaskFactory<I,O> generator) {
		this(Runtime.getRuntime().availableProcessors(), inputs.iterator(), generator);
	}
	
	public ParallelMapping(final Iterator<I> inputs, final TaskFactory<I,O> generator) {
		this(Runtime.getRuntime().availableProcessors(), inputs, generator);
	}
	
	public ParallelMapping(final int n_proc, final Iterable<I> inputs, final TaskFactory<I,O> generator) {
		this(n_proc, inputs.iterator(), generator);
	}
	
	/**
	 * 
	 * @param n_proc Number of threads.
	 * @param inputs The sequence of inputs.
	 * @param generator The generator of {@link Callable} functions, one per input, each returning one output.
	 */
	public ParallelMapping(final int n_proc, final Iterator<I> inputs, final TaskFactory<I,O> generator) {
		this.in = inputs;
		this.n_proc = Process.sensible(n_proc);
		this.generator = generator;
	}
	
	@Override
	public Iterator<O> iterator() {
		// Check whether the inputs where already consumed
		if (!in.hasNext()) return null;
		
		final ExecutorService exec = Utils.newFixedThreadPool(n_proc, ParallelMapping.class.getSimpleName());
		final LinkedList<Future<O>> futures = new LinkedList<Future<O>>();
		
		return new Iterator<O>() {

			@Override
			public boolean hasNext() {
				final boolean b = !futures.isEmpty() || in.hasNext();
				if (!b) {
					exec.shutdown();
				}
				return b;
			}

			@Override
			public O next() {
				if (futures.size() < n_proc / 2) {
					for (int i=0; i<n_proc; ++i) {
						if (!in.hasNext()) {
							exec.shutdown();
							break;
						}
						futures.add(exec.submit(generator.create(in.next())));
					}
				}
				try {
					return futures.removeFirst().get();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}
}
