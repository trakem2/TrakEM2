/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2007 Albert Cardona and Rodney Douglas.

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

import java.awt.Component;
import java.awt.Rectangle;
import java.util.LinkedList;
import java.util.ArrayList;
import javax.swing.SwingUtilities;
import ini.trakem2.utils.Lock;
import ini.trakem2.utils.Utils;

public abstract class AbstractRepaintThread extends Thread {

	final private Lock lock_event = new Lock();
	final private Lock lock_paint = new Lock();
	final private Lock lock_offs = new Lock();
	private boolean quit = false;
	private java.util.List<AbstractOffscreenThread> offs = new LinkedList<AbstractOffscreenThread>();
	private java.util.List<PaintEvent> events = new LinkedList<PaintEvent>();
	private Component target;

	public AbstractRepaintThread(Component target) {
		this.target = target;
		setPriority(Thread.NORM_PRIORITY);
		start();
	}

	private class PaintEvent {
		Rectangle clipRect;
		boolean update_graphics;
		PaintEvent(Rectangle clipRect, boolean update_graphics) {
			this.clipRect = clipRect;
			this.update_graphics = update_graphics; // java is sooo verbose... this class is just a tuple!
		}
	}

	/** Queue a new request for painting, updating offscreen graphics. */
	public final void paint(final Rectangle clipRect) {
		paint(clipRect, true);
	}

	/** Queue a new request for painting. */
	public void paint(final Rectangle clipRect, final boolean update_graphics) {
		//Utils.printCaller(this, 7);
		// queue the event
		synchronized (lock_event) {
			lock_event.lock();
			events.add(new PaintEvent(clipRect, update_graphics));
			lock_event.unlock();
		}
		// signal a repaint request
		synchronized (lock_paint) {
			lock_paint.notifyAll();
		}
	}

	/** Will gracefully kill this thread by breaking its infinite wait-for-event loop, and also call cancel on all registered offscreen threads. */
	public void quit() {
		this.quit = true;
		// notify and finish
		synchronized (lock_paint) {
			lock_paint.notifyAll();
		}
	}

	/** Cancel all offscreen threads. */
	protected void cancelOffs() {
		// cancel previous offscreen threads (will finish only if they have run for long enough)
		synchronized (lock_offs) {
			lock_offs.lock();
			try {
				int size = offs.size();
				while (size > 0) {
					AbstractOffscreenThread off = offs.remove(0);
					off.cancel();
					size--;
				}
			} catch (Exception e) {
				e.printStackTrace(); // should never happen
			}
			lock_offs.unlock();
		}
	}

	/** Add a new offscreen thread to the list, for its eventual cancelation if necessary. */
	protected void add(AbstractOffscreenThread off) {
		synchronized (lock_offs) {
			lock_offs.lock();
			try { offs.add(off); } catch (Exception ee) { ee.printStackTrace(); }
			lock_offs.unlock();
		}
	}

	public void run() {
		while (!quit) {
			try {
				// wait until anyone issues a repaint event
				synchronized (lock_paint) {
					try { lock_paint.wait(); } catch (InterruptedException ie) {}
				}

				if (quit) {
					cancelOffs(); // here and not in the quit() method, so that if the quit keyword is modified by any means, registered offscreen threads will still be canceled.
					return; // finish
				}

				// wait a bit to catch fast subsequent events
				try { Thread.sleep(10); } catch (InterruptedException ie) {}

				// obtain all events up to now and clear the event queue
				PaintEvent[] pe = null;
				synchronized (lock_event) {
					lock_event.lock();
					pe = new PaintEvent[events.size()];
					events.toArray(pe);
					events.clear();
					lock_event.unlock();
				}
				if (null == pe || 0 == pe.length) {
					Utils.log2("No repaint events (?)");
					continue;
				}

				// obtain repaint parameters from merged events
				Rectangle clipRect = pe[0].clipRect;
				boolean update_graphics = pe[0].update_graphics;
				for (int i=1; i<pe.length; i++) {
					if (null != clipRect) {
						if (null == pe[i].clipRect) clipRect = null; // all
						else clipRect.add(pe[i].clipRect);
					} // else 'null' clipRect means repaint the entire canvas
					if (!update_graphics) update_graphics = pe[i].update_graphics;
				}

				// issue an offscreen thread if necessary
				if (update_graphics) {
					handleUpdateGraphics(target, clipRect);
				}

				// repaint
				final Rectangle clipRect_ = clipRect; // we luv java
				// should be better, but it's worse with SwingUtilities
				//SwingUtilities.invokeLater(new Runnable() {
				//	public void run() {
						if (null == clipRect_) target.repaint(0, 0, 0, target.getWidth(), target.getHeight()); // using super.repaint() causes infinite thread loops in the IBM-1.4.2-ppc
						else target.repaint(0, clipRect_.x, clipRect_.y, clipRect_.width, clipRect_.height);
				//	}
				//});
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/** Child classes need to extend this method for handling the need of recreating offscreen images. */
	abstract protected void handleUpdateGraphics(Component target, Rectangle clipRect);

	/** Waits until all offscreen threads are finished. */
	public void waitForOffs() {
		final ArrayList<Thread> al = new ArrayList<Thread>();
		synchronized (lock_offs) {
			lock_offs.lock();
			al.addAll(offs);
			lock_offs.unlock();
		}
		for (Thread t : al) try { t.join(); } catch (InterruptedException e) {}
	}
}
