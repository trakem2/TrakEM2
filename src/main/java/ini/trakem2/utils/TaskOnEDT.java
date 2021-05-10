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
package ini.trakem2.utils;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class TaskOnEDT<V> implements Future<V> {

	private V result;
	private Callable<V> fn;
	private AtomicBoolean started = new AtomicBoolean(false);
	
	/** The task @param fn should not be threaded; this class is intended to run
	 *  small snippets of code under the event dispatch thread, while still being
	 *  able to retrieve the result of the computation. */
	public TaskOnEDT(final Callable<V> fn) {
		this.fn = fn;
	}

	/** Will only prevent execution, not interrupt it if its happening.
	 *  @return true if the task didn't start yet. */
	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		synchronized (this) {
			fn = null;
			return !started.get();
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public V get() throws InterruptedException, ExecutionException {
		if (null != result) return result;
		final V[] v = (V[])new Object[1];
		final ExecutionException[] ee = new ExecutionException[1];
		final AtomicBoolean launched = new AtomicBoolean(false);
		Utils.invokeLater(new Runnable() {
			public void run() {
				launched.set(true);
				synchronized (v) {
					final Callable<V> c;
					synchronized (TaskOnEDT.this) {
						c = fn;
						if (null == c) return;
						started.set(true);
					}
					try {
						v[0] = c.call();
					} catch (Throwable t) {
						ee[0] = new ExecutionException(t);
					}
				}
			}
		});
		// Wait until the event dispatch thread runs the Runnable.
		// (Or it gets run immediately if get() is called from within the event dispatch thread.)
		while (!launched.get()) try { Thread.sleep(5); } catch (InterruptedException ie) {}
		// Block until the computation is done
		synchronized (v) {
			if (null != ee[0]) throw ee[0];
			result = v[0];
			return result;
		}
	}

	@Override
	public V get(long timeout, TimeUnit unit) throws InterruptedException,
			ExecutionException, TimeoutException {
		new Thread() {
			{ setPriority(Thread.NORM_PRIORITY); }
			public void run() {
				try {
					result = get();
				} catch (Throwable t) {
					IJError.print(t);
				}
			}
		}.start();
		final long end = System.currentTimeMillis() + unit.toMillis(timeout);
		final long period = timeout < 200 ? timeout : 200;
		while (null == result && System.currentTimeMillis() < end) {
			Thread.sleep(period);
		}
		return result;
	}

	@Override
	public boolean isCancelled() {
		synchronized (this) {
			return null == fn;
		}
	}

	@Override
	public boolean isDone() {
		return null != result;
	}
}
