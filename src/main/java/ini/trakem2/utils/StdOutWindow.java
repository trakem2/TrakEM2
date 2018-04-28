package ini.trakem2.utils;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Captures the last 10000 chars of StdOut and StdErr into two TextArea. */
public class StdOutWindow {

	static private final StdOutWindow instance = new StdOutWindow();

	static private PrintStream default_err, default_out;

	private JFrame window = null;

	private StdOutWindow() {}

	private void init() {
		JTextArea aout = new JTextArea();
		JTextArea aerr = new JTextArea();
		aout.setEditable(false);
		aerr.setEditable(false);
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
				                  wrap(aout, "StdOut"),
						  wrap(aerr, "StdErr"));
		split.setDividerLocation(0.7);
		this.window = new JFrame("System log");
		this.window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		this.window.getContentPane().add(split);
		window.pack();
		Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
		Rectangle b = window.getBounds();
		window.setLocation( screen.width - b.width, (screen.height - b.height) );
		window.setVisible(true);
		System.setOut(new PrintStream(new MonitorableOutputStream(10000, aout)));
		System.setErr(new PrintStream(new MonitorableOutputStream(10000, aerr)));
	}

	private class MonitorableOutputStream extends ByteArrayOutputStream {
		final JTextArea a;
		MonitorableOutputStream(int size, JTextArea a) {
			super(size);
			this.a = a;
		}
		@Override
		public synchronized void write(int b) {
			super.write(b);
			a.setText(toString());
		}
		@Override
		public synchronized void write(byte b[], int off, int len) {
			super.write(b, off, len);
			a.setText(toString());
		}
	}

	private Component wrap(Component c, String title) {
		JScrollPane s = new JScrollPane(c);
		s.setBackground(Color.white);
		s.setMinimumSize(new Dimension(400,15));
		s.setPreferredSize(new Dimension(400,200));
		s.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(0,5,0,5), title));
		return s;
	}

	static public void start() {
		synchronized (instance) {
			if (null != instance.window) return;

			StdOutWindow.default_out = System.out;
			StdOutWindow.default_err = System.err;

			SwingUtilities.invokeLater(new Runnable() { public void run() {
				try {
					instance.init();
				} catch (Exception e) {
					ij.IJ.log("error: " + e.toString());
					e.printStackTrace();
				}
			}});
		}
	}

	static public void quit() {
		synchronized (instance) {
			System.setOut(default_out);
			System.setErr(default_err);
			StdOutWindow.default_out = null;
			StdOutWindow.default_err = null;
			instance.window.dispose();
			instance.window = null;
		}
	}
}
