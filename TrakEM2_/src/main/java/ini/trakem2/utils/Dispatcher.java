/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2008-2009 Albert Cardona.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

public class Dispatcher {
	private final class Task implements Runnable {
		final Runnable run;
		final boolean swing;
		private Task(final Runnable run, final boolean swing) {
			this.run = run;
			this.swing = swing;
		}
		public void run() {
			try {
				if (swing) SwingUtilities.invokeAndWait(run);
				else run.run();
			} catch (InterruptedException ie) {
				Utils.log2("Sayonara!");
				// buh! If the EDT dies, JVM is shutting down.
				// If the dispatcher dies, it's been killed.
			} catch (Throwable e) {
				IJError.print(e);
			}
		}
	}

	private final ExecutorService exec;

	static public class DispatcherThreadFactory implements ThreadFactory {
		final ThreadGroup group;
		final String tag;
		public DispatcherThreadFactory(final String tag) {
			this.tag = tag;
			final SecurityManager s = System.getSecurityManager();
			this.group = (null != s) ? s.getThreadGroup() :
						   Thread.currentThread().getThreadGroup();
		}
		final public Thread newThread(final Runnable r) {
			final Thread t = new CachingThread(group, r, tag);
			if (t.isDaemon()) t.setDaemon(false);
			if (t.getPriority() != Thread.NORM_PRIORITY) t.setPriority(Thread.NORM_PRIORITY);
			return t;
		}
	}

	public Dispatcher(final String tag) {
		this.exec = new ThreadPoolExecutor(1, 1,
			      0L, TimeUnit.MILLISECONDS,
			      new LinkedBlockingQueue<Runnable>(),
			      new DispatcherThreadFactory("T2-Dispatcher" + (null != tag ? " " + tag : "")));
	}
	public Dispatcher() {
		this(null);
	}
	public void quit() {
		exec.shutdownNow();
	}
	public boolean isQuit() {
		return exec.isShutdown();
	}

	public void quitWhenDone() {
		exec.shutdown(); // orderly shutdown, in which no more tasks are accepted for execution, but remaining tasks are executed.
	}

	/** Submits the task for execution and returns immediately. */
	public void exec(final Runnable run) { exec(run, false); }
	public void execSwing(final Runnable run) { exec(run, true); }
	public void exec(final Runnable run, final boolean swing) {
		if (exec.isShutdown()) {
			Utils.log2("Dispatcher: NOT accepting more tasks!");
			return;
		}
		exec.submit(new Task(run, swing));
	}
}
