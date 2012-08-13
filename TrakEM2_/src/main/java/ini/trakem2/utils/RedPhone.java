package ini.trakem2.utils;

import ini.trakem2.Project;
import ini.trakem2.persistence.FSLoader;
import ini.trakem2.persistence.Loader;
import ini.trakem2.persistence.XMLOptions;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Send any input to port 29391 and TrakEM2 will
 * try to save all {@link FSLoader} projects to XML files.
 * 
 * For example:
 *  $ telnet localhost 29391
 *  > save
 *  Saving...
 *  Saved project[1] to: /path/to/file.xml
 *  >
 *  
 * Commands:
 * - save : saves the project to its own XML file, or to an automatic location if it was never saved before; prints the file name.
 * - saveas : saves to a new XML file (never overwriting the existing XML file); prints that file name.
 * - stream : outputs the XML file into the remote terminal.
 * - quit : disconnect.
 */
public class RedPhone {

	/** May change up to 31000, searching for a port not in use;
	 * the port that will be used is printed. */
	public int port = 29391;
	ServerSocket server = null;
	final Set<Socket> openConnections = Collections.synchronizedSet(new HashSet<Socket>());
	private ThreadGroup group = new ThreadGroup("RedPhone");
	final private Object WRITE = new Object();

	synchronized public void quit() {
		if (null != server) {
			try {
				group.interrupt();
				// Wait until the last round of writing commands completes
				synchronized (WRITE) {
					server.close();
					for (Socket s : openConnections.toArray(new Socket[0])) {
						s.close();
					}
				}
			} catch (IOException e) {
				System.out.println("Error closing RedPhone socket:");
				e.printStackTrace();
			}
		}
	}

	synchronized public void start() {
		while (true) {
			try {
				server = new ServerSocket(port);
				server.setReuseAddress(true);
				break;
			} catch (IOException e) {
				port++;
				if (port > 31000) {
					System.out.println("Red phone not running: no port available");
					return;
				}
			}
		}
		System.out.println("Red phone at port " + port);
		
		new Thread(group, "RedPhone-server") {
			{
				setPriority(Thread.NORM_PRIORITY);
			}
			@Override
			public void run() {
				final HashMap<String,Action> cmds = new HashMap<String,Action>();
				cmds.put("save", new Save());
				cmds.put("saveas", new SaveAs());
				cmds.put("stream", new Stream());
				//
				while (!isInterrupted()) {
					Socket s = null;
					try {
						s = server.accept(); // blocks until there is input
						System.out.println("Connected.");
						new Connection(s, cmds).start();
					} catch (IOException e) {
						if (!isInterrupted()) e.printStackTrace();
						System.out.println("RedPhone hang up!");
					}/* finally {
						// Don't close the Socket s, would close the server.
					}*/
				}
			}
		}.start();
	}
	
	private class Connection extends Thread
	{
		private final Socket s;
		private final Map<String,Action> cmds;
		
		Connection(final Socket s, final Map<String,Action> cmds) {
			super(group, "RedPhone-connection");
			this.s = s;
			this.cmds = cmds;
			openConnections.add(s);
			setPriority(Thread.NORM_PRIORITY);
		}
		@Override
		public void run() {
			try {
				final IO io = new IO(s);

				while (!isInterrupted()) {
					String command = io.readOneLine();
					System.out.println("RedPhone read command: " + command);
					synchronized (WRITE) {
						if (null == command || "quit".equals(command.trim().toLowerCase())) {
							io.writeLine("OK bye!");
							s.close();
							openConnections.remove(s);
							return;
						}

						command = command.trim().toLowerCase();

						// Validate
						if (0 == command.length()) {
							continue;
						}

						final Action action = cmds.get(command);
						if (null == action) {
							io.writeLine("Do not know command: " + command);
							continue;
						}
						//
						try {
							int count = 0;
							for (final Project p : Project.getProjects()) {
								final Loader l = p.getLoader();
								if (l instanceof FSLoader) {
									action.execute(p, (FSLoader)l, io, count);
								} else {
									final String answer = "Could not save project[" + count + "]: not an XML-based project.";
									System.out.println(answer);
									io.writeLine(answer);
								}
								count += 1;
							}
						} catch (IOException ioe) {
							ioe.printStackTrace();
							if (!s.isClosed()) ioe.printStackTrace(new PrintWriter(s.getOutputStream()));
						}
					}
				}
			} catch (IOException ioe) {
				System.out.println("Red phone hang up!");
				if (!isInterrupted()) {
					ioe.printStackTrace();
					if (!s.isClosed()) try {
						ioe.printStackTrace(new PrintWriter(s.getOutputStream()));
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}
	
	private abstract class Action
	{	
		protected abstract void exec(Project p, FSLoader fl, IO io, int count) throws IOException;
		
		public void execute(Project p, FSLoader fl, IO io, int count) {
			try {
				exec(p, fl, io, count);
			} catch (IOException ioe) {
				ioe.printStackTrace();
				try {
					ioe.printStackTrace(new PrintWriter(io.out));
				} catch (Throwable t) {
					t.printStackTrace();
				}
			}
		}
	}
	
	private class Save extends Action
	{
		@Override
		protected void exec(Project p, FSLoader fl, IO io, int count)
				throws IOException {
			io.writeLine("Saving...");
			String path = fl.getProjectXMLPath();
			if (null == path) {
				new SaveAs().exec(p, fl, io, count);
				return;
			}
			io.writeLine("Saved: " + p.save());
		}
	}
	
	private class SaveAs extends Action
	{
		@Override
		protected void exec(Project p, FSLoader fl, IO io, int count) throws IOException {
			final String path = handlePath(fl.getProjectXMLPath());
			io.writeLine("Saving...");
			p.saveAs(path, false);
			final String answer = "Saved project[" + count + "] to: " + path;
			System.out.println(answer);
			io.writeLine(answer);
		}
	}
	
	private class Stream extends Action
	{
		@Override
		protected void exec(Project p, FSLoader fl, IO io, int count)
				throws IOException {
			io.writeLine("Streaming: " + fl.getProjectXMLPath());
			try {
				XMLOptions options = new XMLOptions();
				options.export_images = false;
				options.patches_dir = null;
				options.include_coordinate_transform = true;
				fl.writeXMLTo(p, io.out, options);
			} catch (Exception e) {
				e.printStackTrace();
				io.writeLine("Could not stream project " + fl.getProjectXMLPath());
			}
		}
	}
	
	
	private String handlePath(String path) {
		if (null == path) {
			String dir = System.getProperty("user.dir");
			int count = 1;
			do {
				path = dir + "/trakem2-recovered-" + count + ".xml";
				count += 1;
			} while (new File(path).exists());
			return path;
		}
		int count = 1;
		String p = path;
		while (new File(p).exists()) {
			p = path + "-" + count + ".xml";
			count += 1;
		}
		return p;
	}

	/** Note: closing the Socket also closes the underlying streams. */
	private final class IO {
		private final BufferedReader in;
		private final Writer out;
		IO(final Socket s) throws IOException {
			this.in = new BufferedReader(new InputStreamReader(s.getInputStream()));
			this.out = new OutputStreamWriter(new BufferedOutputStream(s.getOutputStream()));
		}
		final String readOneLine() throws IOException {
			return in.readLine();
		}
		final void writeLine(final String answer) throws IOException {
			out.append(answer);
			out.append('\n');
			out.flush();
		}
	}
}
