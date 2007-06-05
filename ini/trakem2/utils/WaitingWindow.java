package ini.trakem2.utils;

import java.awt.Frame;
import java.awt.Label;
import java.awt.GridLayout;
import java.util.Timer;
import java.util.TimerTask;

// doesn't work as expected

public class WaitingWindow {

	static private long start;
	static private Timer timer;
	static private Task task = null;
	static private Frame frame = null;
	static private boolean working = false;
	static private Object lock = null;

	private WaitingWindow() {}

	static public void start() {
		start("Waiting...");
	}

	static public void start(String msg) {
		if (null == lock) lock = new Object();
		start = System.currentTimeMillis();
		if (null == task) task = new WaitingWindow.Task(msg);
		else task.setMessage(msg);
		if (null == timer) {
			timer = new Timer();
			timer.schedule(task, 1000, 1000); // one second delay, one second period
		}
	}

	static public void setMessage(String msg) {
		task.setMessage(msg);
	}

	static public void cancel() {
		timer.cancel();
	}

	static private class Task extends TimerTask {

		private Label msg_label;
		private Label time_label;

		Task(String msg) {
			msg_label = new Label(msg);
		}

		public void setMessage(String msg) {
			msg_label.setText(msg);
		}

		public void run() {
			System.out.println("ww task started");
			try {
				synchronized (lock) {
					while (working) {
						try { lock.wait(); } catch (InterruptedException ie) {}
					}
					working = true;
					
					if (null == frame) createGUI();
					else if (!frame.isVisible()) frame.setVisible(true);
					// measure elapsed time
					long elapsed = System.currentTimeMillis() - start;
					String time_msg = null;
					if (elapsed < 60000) time_msg = (elapsed / 1000) + " s";
					else time_msg = (elapsed / 60000) + " min " + ((elapsed % 60000) / 1000) + " s";
					time_label.setText("Elapsed time: " + time_msg);
					
					working = false;
					lock.notifyAll();
				}
			} catch (Exception e) {
				if (working) {
					working = false;
					lock.notifyAll();
				}
			}
		}

		public boolean cancel() {
			System.out.println("canceling ww");
			try {
				synchronized (lock) {
					while (working) {
						try { lock.wait(); } catch (InterruptedException ie) {}
					}
					working = true;

					if (null != frame) {
						frame.setVisible(false);
						frame.dispose();
						frame = null;
					}

					working = false;
					lock.notifyAll();
				}
			} catch (Exception e) {
				if (working) {
					working = false;
					lock.notifyAll();
				}
			}
			return true;
		}

		private void createGUI() {
			frame = new Frame("Working...");
			frame.setLayout(new GridLayout(2, 1));
			frame.add(msg_label);
			time_label = new Label("Elapsed time: 1 s");
			frame.add(time_label);
			frame.pack();
			frame.show();
		}
	}
}
