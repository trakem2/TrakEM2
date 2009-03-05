package ini.trakem2.utils;

import javax.swing.JSplitPane;
import javax.swing.JScrollPane;
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.BorderFactory;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.PrintStream;

/** Captures the last 10000 chars of StdOut and StdErr into two TextArea. */
public class StdOutWindow {

	static private StdOutWindow instance = null;

	private ByteArrayOutputStream baos;
	private ByteArrayOutputStream baes;
	private JTextArea aout, aerr;

	static private PrintStream default_err, default_out;

	private JFrame window = null;

	private StdOutWindow() {
		instance = this;
		JTextArea aout = new JTextArea();
		aout.setEditable(false);
		JTextArea aerr = new JTextArea();
		aerr.setEditable(false);
		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
				                  wrap(aout, "StdOut"),
						  wrap(aerr, "StdErr"));
		split.setDividerLocation(0.5);
		this.window = new JFrame("StdOut/StdErr");
		this.window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		this.window.getContentPane().add(split);
		this.baos = new MonitorableOutputStream(10000, aout);
		this.baes = new MonitorableOutputStream(10000, aerr);
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
		s.setMinimumSize(new Dimension(200,0));
		s.setPreferredSize(new Dimension(300,200));
		s.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(0,5,0,5), title));
		return s;
	}

	static private final Object lock = new Object();

	static public void start() {
		synchronized (lock) {
			if (null == instance) {

				default_out = System.out;
				default_err = System.err;

				final StdOutWindow instance = new StdOutWindow();

				System.setOut(new PrintStream(instance.baos));
				System.setErr(new PrintStream(instance.baes));

				SwingUtilities.invokeLater(new Runnable() { public void run() {
					instance.window.pack();
					Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
					Rectangle b = instance.window.getBounds();
					instance.window.setLocation( screen.width - b.width, (screen.height - b.height) );
					instance.window.setVisible(true);
				}});
			}
		}
	}

	static public void quit() {
		synchronized (lock) {
			if (null != instance) {
				System.setOut(default_out);
				System.setErr(default_err);
				instance.window.dispose();
				instance = null;
			}
		}
	}
}
