/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt)

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

import ini.trakem2.ControlWindow;
import ini.trakem2.Project;

import java.util.ArrayList;

/** Sets a Worker thread to work, and waits until it finishes, blocking all user interface input until then, except for zoom and pan, for all given projects. */
public class Bureaucrat extends Thread {
	final private Worker worker;
	final private Thread worker_thread;
	final private long onset;
	final private Project[] project;
	private boolean started = false;
	/** A list of tasks to run when the Worker finishes--but not when it quits. */
	private ArrayList<Runnable> post_tasks = new ArrayList<Runnable>();

	/** Registers itself in the project loader job queue. */
	private Bureaucrat(ThreadGroup tg, Worker worker, Project project) {
		this(tg, worker, new Project[]{project});
	}
	private Bureaucrat(ThreadGroup tg, Worker worker, Project[] project) {
		super(tg, "T2-Bureaucrat");
		setPriority(Thread.NORM_PRIORITY);
		this.worker = worker;
		this.worker_thread = new CachingThread(tg, worker, worker.getThreadName());
		this.worker_thread.setPriority(NORM_PRIORITY);
		worker.setThread(worker_thread);
		this.project = project;
		onset = System.currentTimeMillis();
		for (int i=0; i<project.length; i++) {
			project[i].setReceivesInput(false);
			project[i].getLoader().addJob(this);
		}
	}

	/** Creates but does not start the Bureaucrat thread. */
	static public Bureaucrat create(Worker worker, Project project) {
		return create (worker, new Project[]{project});
	}

	/** Creates but does not start the Bureaucrat thread. */
	static public Bureaucrat create(Worker worker, Project[] project) {
		ThreadGroup tg = new ThreadGroup("T2-Bureaucrat for " + worker.getTaskName());
		return new Bureaucrat(tg, worker, project);
	}

	/** Creates and start the Bureaucrat thread. */
	static public Bureaucrat createAndStart(Worker worker, Project project) {
		return createAndStart(worker, new Project[]{project});
	}

	/** Creates and start the Bureaucrat thread. */
	static public Bureaucrat createAndStart(Worker worker, Project[] project) {
		ThreadGroup tg = new ThreadGroup("T2-Bureaucrat for " + worker.getTaskName());
		tg.setMaxPriority(Thread.NORM_PRIORITY);
		Bureaucrat burro = new Bureaucrat(tg, worker, project);
		burro.goHaveBreakfast();
		return burro;
	}

	/** Starts the Bureaucrat thread: sets the worker to work and monitors it until it finishes.*/
	public void goHaveBreakfast() {
		worker_thread.start();
		// Make sure we start AFTER the worker has started.
		while (!worker.hasStarted()) {
			try { Thread.sleep(50); } catch (InterruptedException ie) { ie.printStackTrace(); }
		}
		start();
		// Make sure we return AFTER having started.
		while (!started) {
			try { Thread.sleep(50); } catch (InterruptedException ie) { ie.printStackTrace(); }
		}
	}
	private void cleanup() {
		Utils.showProgress(1); // cleanup all possible interruptions
		for (int i=0; i<project.length; i++) {
			project[i].getLoader().removeJob(this);
			project[i].setReceivesInput(true);
		}
	}
	public void run() {
		started = true;
		// wait until worker starts
		while (!worker.isWorking()) {
			try { Thread.sleep(50); } catch (InterruptedException ie) {}
			if (worker.hasQuitted() || worker_thread.isInterrupted()) {
				//Utils.log("Cleaning up...");
				worker.cleanup2();
				cleanup();
				//Utils.log("...done.");
				return;
			}
		}
		ControlWindow.startWaitingCursor();
		int sandwitch = ControlWindow.isGUIEnabled() ? 100 : 5000; // 0.1 second or 5
		Utils.showStatus("Started processing: " + worker.getTaskName(), false); // don't steal focus, ever
		final StringBuilder sb = new StringBuilder("Processing... ").append(worker.getTaskName()).append(" - ");
		final int base_len = sb.length();
		while (worker.isWorking() && !worker.hasQuitted()) {
			try { Thread.sleep(sandwitch); } catch (InterruptedException ie) {}
			float elapsed_seconds = (System.currentTimeMillis() - onset) / 1000.0f;
			if (elapsed_seconds < 60) {
				sb.append((int)elapsed_seconds).append(" seconds");
			} else {
				sb.append((int)(elapsed_seconds / 60)).append("' ").append((int)(elapsed_seconds % 60)).append("''");
			}
			Utils.showStatus(sb.toString(), false); // don't steal focus
			// Increment up to 1 second
			if (sandwitch < 1000) sandwitch += 100;
			// reset:
			sb.setLength(base_len);
		}
		ControlWindow.endWaitingCursor();
		final long elapsed = System.currentTimeMillis() - onset;
		final String done = "Done " + worker.getTaskName() + " (" + Utils.cutNumber(elapsed/1000.0, 2) + "s approx.)";
		Utils.showStatus(done, false); // don't steal focus;
		Utils.log2(done);

		try {
			if (null != post_tasks) {
				for (final Runnable r : post_tasks) {
					try {
						r.run();
					} catch (Throwable t) {
						IJError.print(t);
					}
				}
			}
		} catch(Throwable t) {
			IJError.print(t);
		}
		cleanup();
	}
	/** Returns the task the worker is currently executing, which may change over time. */
	public String getTaskName() {
		return worker.getTaskName();
	}
	/** Waits until worker finishes before returning.
	 *  Calls quit() on the Worker and interrupt() on each threads in this ThreadGroup and subgroups. */
	public void quit() {
		try {

			// Cancel post tasks:
			synchronized (post_tasks) {
				post_tasks.clear();
				post_tasks = null;
			}

			Utils.log2("ThreadGroup is " + getThreadGroup());

			Utils.log2("ThreadGroup active thread count: " + getThreadGroup().activeCount());
			Utils.log2("ThreadGroup active group count: " + getThreadGroup().activeGroupCount());

			Thread[] active = new Thread[getThreadGroup().activeCount()];
			int count = getThreadGroup().enumerate(active);
			Utils.log2("Active threads: " + count);
			for (int i=0; i < count; i++) {
				Utils.log2("Active thread: " + active[i]);
			}

			// Set flag to each thread and thread in subgroup to quit:
			worker.quit();
			getThreadGroup().interrupt();

		} catch (Exception e) {
			IJError.print(e);
		}
		// wait until worker finishes
		try {
			Utils.log("Waiting for worker to quit...");
			worker_thread.join();
			Utils.log("Worker quitted.");
		} catch (InterruptedException ie) {
			IJError.print(ie);
		}
		// wait for all others in a separate thread, then clear progress bar
		Thread.yield();
		final ThreadGroup tg = getThreadGroup();
		if (null == tg) {
			Utils.log2("All threads related to the task died.");
			return; // will be null if all threads of the former group have died
		}
		new Thread() { public void run() {
			try {
				// Reasonable effort to join all threads
				Thread[] t = new Thread[tg.activeCount() * 2];
				int len = tg.enumerate(t);
				for (int i=0; i<len && i<t.length; i++) {
					Utils.log2("Joining thread: " + t[i]);
					try { t[i].join(); } catch (InterruptedException ie) {}
					Utils.log2("... thread died: " + t[i]);
				}
			} catch (Exception e) {
				IJError.print(e);
			} finally {
				Utils.showProgress(1);
			}
		}}.start();
	}
	public boolean isActive() {
		return worker.isWorking();
	}
	public Worker getWorker() {
		return worker;
	}
	/** Add a task to run after the Worker has finished or quit. Does not accept more tasks once the Worker no longer runs. */
	public boolean addPostTask(final Runnable task) {
		if (worker.hasQuitted() || null == post_tasks) return false;
		synchronized (post_tasks) {
			this.post_tasks.add(task);
		}
		return true;
	}
}
