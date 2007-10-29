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

/** Sets a Worker thread to work, and waits until it finishes, blocking all user interface input until then, except for zoom and pan. */
public class Bureaucrat extends Thread {
	private Worker worker;
	private long onset;
	private Project project;

	/** Registers itself in the project loader job queue. */
	public Bureaucrat(Worker worker, Project project) {
		this.worker = worker;
		this.project = project;
		onset = System.currentTimeMillis();
		project.setReceivesInput(false);
		setPriority(Thread.NORM_PRIORITY);
		project.getLoader().addJob(this);
	}
	/** Sets the worker to work and monitors it until it finishes.*/
	public void goHaveBreakfast() {
		worker.start();
		start();
	}
	public void run() {
		int sandwitch = 1000; // one second, will get slower over time
		if (null != IJ.getInstance()) IJ.getInstance().toFront();
		Utils.showStatus("Started processing: " + worker.getTaskName());
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
		Utils.showStatus("Done " + worker.getTaskName());
		project.getLoader().removeJob(this);
		project.setReceivesInput(true);
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
