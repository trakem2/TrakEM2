package ini.trakem2.display;

import java.awt.Component;
import java.awt.Rectangle;
import ini.trakem2.utils.Lock;
import java.util.LinkedList;
import java.util.ArrayList;

public abstract class AbstractRepaintThread extends Thread {

	final private Lock lock_event = new Lock();
	final private Lock lock_paint = new Lock();
	final protected Lock lock_offs = new Lock();
	private boolean quit = false;
	protected java.util.List<Thread> offs = new LinkedList<Thread>();
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
	public final void paint(Rectangle clipRect) {
		paint(clipRect, true);
	}

	/** Queue a new request for painting. */
	public void paint(Rectangle clipRect, boolean update_graphics) {
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

	public void quit() {
		this.quit = true;
		// notify and finish
		synchronized (lock_paint) {
			lock_paint.notifyAll();
		}
	}

	/** Cancel all offscreen threads. */
	abstract protected void cancelOffs();

	public void run() {
		while (!quit) {
			// wait until anyone issues a repaint event
			synchronized (lock_paint) {
				try { lock_paint.wait(); } catch (InterruptedException ie) {}
			}

			if (quit) {
				cancelOffs();
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
			if (null == clipRect) target.repaint(0, 0, 0, target.getWidth(), target.getHeight()); // using super.repaint() causes infinite thread loops in the IBM-1.4.2-ppc
			else target.repaint(0, clipRect.x, clipRect.y, clipRect.width, clipRect.height);
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
