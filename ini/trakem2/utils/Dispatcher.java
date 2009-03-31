/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2008 Albert Cardona.

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

import java.util.ArrayList;
import javax.swing.SwingUtilities;

public class Dispatcher extends Thread {
	private final class Task {
		Runnable run;
		boolean swing;
		Task(Runnable run, boolean swing) {
			this.run = run;
			this.swing = swing;
		}
	}
	private final ArrayList<Task> tasks = new ArrayList<Task>();
	private final Lock lock = new Lock();
	private boolean go = true;

	public Dispatcher() {
		super("T2-Dispatcher");
		setPriority(Thread.NORM_PRIORITY);
		setDaemon(true);
		start();
	}
	public void quit() {
		this.go = false;
		synchronized (this) { notify(); }
	}
	public boolean isQuit() {
		return go;
	}

	private boolean accept = true;

	public void quitWhenDone() {
		accept = false;
		quit();
	}

	/** Submits the task for execution and returns immediately. */
	public void exec(final Runnable run) { exec(run, false); }
	public void execSwing(final Runnable run) { exec(run, true); }
	public void exec(final Runnable run, final boolean swing) {
		if (!accept) {
			Utils.log2("Dispatcher: NOT accepting more tasks!");
			return;
		}
		synchronized (lock) {
			lock.lock();
			tasks.add(new Task(run, swing));
			lock.unlock();
		}
		synchronized (this) { notify(); }
	}
	/** Executes one task at a time, in the same order in which they've been received. */
	public void run() {
		while (go || (!accept && tasks.size() > 0)) {
			try {
				synchronized (this) { wait(); }
				if (!go) return;
				while (tasks.size() > 0) {
					Task task = null;
					synchronized (lock) {
						lock.lock();
						if (tasks.size() > 0) {
							task = tasks.remove(0);
						}
						lock.unlock();
					}
					if (null == task) continue;
					try {
						if (task.swing) SwingUtilities.invokeAndWait(task.run);
						else task.run.run();
					} catch (Throwable t) { IJError.print(t); }
				}
			} catch (Throwable e) { IJError.print(e); }
		}
	}
}
