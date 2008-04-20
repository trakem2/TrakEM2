/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005,2006 Albert Cardona and Rodney Douglas.

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

import ij.IJ;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.Utils;

/** Sets a Worker thread to work, and waits until it finishes, blocking all user interface input until then, except for zoom and pan, for all given projects. */
public class Bureaucrat extends Thread {
	private Worker worker;
	private long onset;
	private Project[] project;
	private boolean started = false;

	/** Registers itself in the project loader job queue. */
	public Bureaucrat(Worker worker, Project project) {
		this(worker, new Project[]{project});
	}
	public Bureaucrat(Worker worker, Project[] project) {
		this.worker = worker;
		this.project = project;
		onset = System.currentTimeMillis();
		for (int i=0; i<project.length; i++) {
			project[i].setReceivesInput(false);
			project[i].getLoader().addJob(this);
		}
		setPriority(Thread.NORM_PRIORITY);
	}

	/** Sets the worker to work and monitors it until it finishes.*/
	public void goHaveBreakfast() {
		worker.start();
		while (!worker.hasStarted()) {
			try { Thread.currentThread().sleep(50); } catch (InterruptedException ie) { ie.printStackTrace(); }
		}
		start();
		while (!started) {
			try { Thread.currentThread().sleep(50); } catch (InterruptedException ie) { ie.printStackTrace(); }
		}
	}
	private void cleanup() {
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
			if (worker.hasQuitted()) {
				cleanup();
				return;
			}
		}
		ControlWindow.startWaitingCursor();
		int sandwitch = 1000; // one second, will get slower over time
		Utils.showStatus("Started processing: " + worker.getTaskName(), !worker.onBackground());
		while (worker.isWorking() && !worker.hasQuitted()) {
			try { Thread.sleep(sandwitch); } catch (InterruptedException ie) {}
			float elapsed_seconds = (System.currentTimeMillis() - onset) / 1000.0f;
			Utils.showStatus("Processing... " + worker.getTaskName() + " - " + (elapsed_seconds < 60 ?
								(int)elapsed_seconds + " seconds" :
								(int)(elapsed_seconds / 60) + "' " + (int)(elapsed_seconds % 60) + "''"), false); // don't steal focus
			// increase sandwitch length progressively
			if (ControlWindow.isGUIEnabled()) {
				if (elapsed_seconds > 180) sandwitch = 10000;
				else if (elapsed_seconds > 60) sandwitch = 3000;
			} else {
				sandwitch = 60000; // every minute
			}
		}
		ControlWindow.endWaitingCursor();
		Utils.showStatus("Done " + worker.getTaskName(), !worker.onBackground());
		cleanup();
	}
	/** Returns the task the worker is currently executing, which may change over time. */
	public String getTaskName() {
		return worker.getTaskName();
	}
	/** Waits until worker finishes before returning. */
	public void quit() {
		worker.quit();
		try {
			Utils.log("Waiting for worker to quit...");
			worker.join();
			Utils.log("Worker quitted.");
		} catch (InterruptedException ie) {} // wait until worker finishes
	}
	public boolean isActive() {
		return worker.isWorking();
	}
	public Thread getWorker() {
		return worker;
	}
}
