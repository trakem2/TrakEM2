/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2007-2009 Albert Cardona and Rodney Douglas.

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

package ini.trakem2.display;

import ini.trakem2.utils.CachingThread;
import ini.trakem2.utils.IJError;

import java.util.concurrent.atomic.AtomicBoolean;

/** To be used in combination with the AbstractRepaintThread, as a thread to create graphics offscreen.*/
public abstract class AbstractOffscreenThread extends CachingThread {

	protected volatile RepaintProperties rp = null;
	private final AtomicBoolean mustRepaint = new AtomicBoolean(false);

	AbstractOffscreenThread(String name) {
		super(name);
		setPriority(Thread.NORM_PRIORITY);
		try { setDaemon(true); } catch (Exception e) { e.printStackTrace(); }
		start();
	}

	public void setProperties(final RepaintProperties rp) {
		synchronized (this) {
			this.rp = rp;
			this.mustRepaint.set(true);
			notifyAll();
		}
	}

	public void run() {
		while (!isInterrupted()) {
			try {
				if (mustRepaint.getAndSet(false)) {
					paint();
				} else {
					synchronized (this) {
						try { wait(); } catch (InterruptedException ie) {}
					}
				}
			} catch (Exception e) {
				IJError.print(e);
			}
		}
	}
	
	public void quit() {
		interrupt();
		synchronized (this) {
			notifyAll();
		}
	}

	public void waitOnRepaintCycle() {
		while (mustRepaint.get()) {
			try { Thread.sleep(500); } catch (InterruptedException ie) {}
		}
	}

	public abstract void paint();

	protected interface RepaintProperties {}

}
