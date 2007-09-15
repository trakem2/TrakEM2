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

package ini.trakem2.io;

import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;
import java.io.*;
import java.net.*;
import java.awt.Rectangle;


/* Listens on the given port for commands asking to generate a stack of flat images
 * for the given rectangle area.
 *
 * To launch a new server for a specific project, do as in the example:
 
 $ java -Xmx512m -classpath ../ij.jar:./TrakEM2_.jar:./TrakEM2-src/ImageJ_3D_Viewer.jar:./edu_mines_jtk.jar:./TrakEM2-src/Jama-1.0.2.jar:./TrakEM2-src/AmiraMesh_.jar:./TrakEM2-src/jzlib-1.0.7.jar:. ini.trakem2.io.ServerStackMaker /home/albert/Desktop/ministack4.xml 5000

 Then from a server call:

GET /0/0/400/400/0.25/0/3/true/ HTTP/1.0

which is equivalent to:
http:/www.yourserver.com/0/0/400/400/0.25/0/3/true/

and which returns the URL where the generated file will be, or an error message.
 

TODO: need a system to delete the generated tar.gz archives after a few hours or minutes
 *
 *
*/
public class ServerStackMaker {

	private ServerSocket listener;
	private int port;
	private boolean listen = true;
	private Project project;
	private String base_url;
	private String stack_dir;
	private AtomicInteger count = new AtomicInteger();
	private static int MAX_JOBS = 4;
	private static long MAX_STACK_SIZE = 400 * 1024 * 1024; // 400 Mb

	public ServerStackMaker(Project project, int port, String stack_dir) {
		this.project = project;
		this.port = port;
		this.stack_dir = stack_dir;
		try {
			this.listener = new ServerSocket(port);
			this.base_url = listener.getInetAddress().getCanonicalHostName();
			Utils.log2("Server listening on URL " + base_url);
			// wait until killed
			while (listen) {
				handleNewConnection(listener.accept()); // blocks here until there is an incomming connection
			}
		} catch (IOException ioe) {
			ioe.printStackTrace();
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	/** Forks a new Thread to handle the new request. */
	private void handleNewConnection(final Socket socket) {
		Utils.log2("New connection. Current count: " + count.get());
		new Connection(socket).start();
	}

	private class Connection extends Thread {

		private Socket socket;
		private BufferedReader in = null;
		private PrintWriter out = null;

		Connection(Socket socket) {
			setPriority(Thread.NORM_PRIORITY);
			this.socket = socket;
			// process socket.
			try {
				this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				this.out = new PrintWriter(socket.getOutputStream(), true);
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}
		}

		public void run() {
			if (null == in || null == out) return;
			try {
				// register new job
				count.addAndGet(1);
				// read the first line
				String line = in.readLine();
				Utils.log2("Processing: " + line);
				Task task = parseCommand(line);
				task.execute(out);
				socket.close();
			} catch (Throwable t) {
				t.printStackTrace();
			} finally {
				// deregister
				count.decrementAndGet();
			}
		}
	}

	static public void main(String[] arg) {
		if (IJ.isWindows()) {
			Utils.log2("This server will not run under Windows.");
			return;
		}
		if (arg.length < 3) {
			Utils.log2("The number of arguments is incorrect!\nUsage:\n"
				 + "\tjava -Xmx1650m -Xincgc -classpath ij.jar:TrakEM2.jar:ImageJ_3D_Viewer.jar /path/to/project.xml <port> /path/to/stacks/dir/\n");
			return;
		}
		if (!new File(arg[0]).exists() || !arg[0].toLowerCase().endsWith(".xml")) {
			Utils.log2("Incorrect file path to a project file.");
			return;
		}
		ControlWindow.setGUIEnabled(false);
		final Project project = Project.openFSProject(arg[0]);
		if (null == project) {
			Utils.log2("The given XML file does not contain a valid project.");
			return;
		}
		int port = 5000;
		try {
			port = Integer.parseInt(arg[1]);
			if (port < 1) throw new NumberFormatException("Negative port number");
		} catch (NumberFormatException nfe) {
			Utils.log2("!! Invalid port number:\n" + nfe);
			return;
		}
		String stack_dir = arg[2];
		File file = new File(stack_dir);
		if (!file.exists() || !file.isDirectory()) {
			Utils.log2("The given stacks directory is invalid.");
			return;
		}
		if (!stack_dir.endsWith("/")) stack_dir += "/";
		Utils.log2("Creating new server for " + arg[0]);
		new ServerStackMaker(project, port, stack_dir);
	}


	/** Returns an Error Task if the string contains incorrect commands.*/
	private Task parseCommand(String command) {
		// check proper command string
		if (!command.startsWith("GET /") || !command.contains("HTTP/")) {
			return new Error("Invalid command request: " + command + "\nWrong format.\nExpected: GET /x/y/width/height/scale/index_first_layer/index_last_layer/ HTTP/1.0");
		}
		// remove double spaces
		while (-1 != command.indexOf("  ")) {
			command.replaceAll("  ", " ");
		}
		// cut
		command = command.substring(command.indexOf('/')+1, command.indexOf("/ HTTP"));
		String[] com = command.split("/");
		if (com.length < 8) {
			return new Error("Invalid command request: " + command + "\nReceived command with invalid number of arguments.\n"
				 + "Expected:\n"
				 + "\t- x coordinate of the desired area\n"
				 + "\t- y idem.\n"
				 + "\t- width idem.\n"
				 + "\t- height idem.\n"
				 + "\t- scale, in floating point: 0.25 is 25%\n"
				 + "\t- index of the first layer (starting at zero)\n"
				 + "\t- index of the last layer, inclusive\n"
				 + "\t- true or false, whether to align or not");
		}
		int i_first = 0;
		int i_last = 0;
		try {
			i_first = Integer.parseInt(com[5]);
			i_last =  Integer.parseInt(com[6]);
			if (i_last < i_first) {
				return new Error("Last layer index must be smaller than first layer index.");
			}
		} catch (NumberFormatException nfe) {
			return new Error("Number format error in the layer indices.");
		}
		int x, y, width, height;
		try {
			x = Integer.parseInt(com[0]);
			y = Integer.parseInt(com[1]);
			width = Integer.parseInt(com[2]);
			height = Integer.parseInt(com[3]);
			if (x < 0 || y < 0 || width <= 0 || height <= 0) {
				return new Error("Incorrect target rectangle.");
			}
		} catch (NumberFormatException e) {
			return new Error("Number format error in the rectangle values.");
		}
		float scale = 1.0f;
		try {
			scale = Float.parseFloat(com[4]);
			if (scale > 1.0) {
				return new Error("Can't accept a scale larger than 1.0");
			}
			if (scale < 0) {
				return new Error("Can't accept negative scale.");
			}
		} catch (NumberFormatException nfe) {
			return new Error("Number format error in the scale");
		}
		boolean align = Boolean.valueOf(com[7]);

		return new Target(new Rectangle(x, y, width, height), scale, i_first, i_last, align);
	}

	private interface Task {
		public void execute(PrintWriter out) throws Exception;
	}

	private class Error implements Task {
		String error;
		Error(String error) {
			this.error = error;
		}
		public void execute(PrintWriter out) throws Exception {
			out.println(error);
		}
	}

	private class Target implements Task {
		Rectangle r;
		float scale;
		int i_first_layer;
		int i_last_layer;
		boolean align;
		Target(Rectangle r, float scale, int i_first_layer, int i_last_layer, boolean align) {
			this.r = r;
			this.scale = scale;
			this.i_first_layer = i_first_layer;
			this.i_last_layer = i_last_layer;
			this.align = align;
		}
		public void execute(PrintWriter out) throws Exception {
			// check preconditions: how big will the final file be?
			final long size = (long)((i_last_layer - i_first_layer + 1) * r.width * r.height * scale);
			if (size > MAX_STACK_SIZE) {
				out.println("ERROR maximum file size exceeded. Try to scale it more.");
				return;
			}
			// 
			out.println("OK will process box:" + r + " scale: " + scale + " layers: " + i_first_layer + "-" + i_last_layer + " align: " + align);
			//
			// - make the stack
			// - align it
			// - print back the URL where it will be stored at

			// waint until less than MAX_JOBS are currently in the works
			while (count.get() >= MAX_JOBS) {
				Utils.log2("Jobs in queue: " + (count.get() - MAX_JOBS));
				try { Thread.sleep(1000); } catch (InterruptedException e) {}
			}
			// generate an empty directory to store a flat image for each layer
			final String task_title = Thread.currentThread().getId() + "_"
				             + r.x + "_"
					     + r.y + "_"
					     + r.width + "_"
					     + r.height + "_"
					     + scale + "_"
					     + i_first_layer + "_"
					     + i_last_layer + "_"
					     + align;
			final File fdir = new File(stack_dir + task_title);
			fdir.mkdir();

			final ArrayList list = new ArrayList();
			list.addAll(project.getRootLayerSet().getLayers().subList(i_first_layer, i_last_layer+1)); // making sure it's a copy
			// remove empty layers
			for (Iterator it = list.iterator(); it.hasNext(); ) {
				Layer la = (Layer)it.next();
				if (0 == la.getDisplayables(Patch.class).size()) it.remove();
			}
			final Layer[] layer = new Layer[list.size()];
			list.toArray(layer);
			// generate a flat image for each layer
			for (int i=0; i<layer.length; i++) {
				ImagePlus imp = project.getLoader().getFlatImage(layer[i], this.r, this.scale, 1, ImagePlus.GRAY8, Patch.class, null, true);
				if (null == imp) {
					Utils.log2("WARNING: skipping null image for layer " + layer[i] + " for task " + task_title);
					continue;
				}
				new ij.io.FileSaver(imp).saveAsTiff(stack_dir + task_title + "/" + imp.getTitle() + ".tif");
				imp.flush();
				imp = null;
			}
			if (align) {
				registerSlices(fdir);
			}
			// Create a compressed archive
			//   - 'task_title' is also the name of the subdirectory containing the images
			//   - the second argument is the environmental variables, which I set to null so that the parent's process vars are inherited
			//   - the third argument is the desired working directory; in this case, the stack_dir where the temporary directories are created.
			Process process = Runtime.getRuntime().exec("/bin/tar cvzf " + task_title + ".tar.gz " + task_title, null, new File(stack_dir));

			BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while (null != (line = br.readLine())) {
				// watch and wait. When no more input comes, it's done
				Utils.log2(line);
				try { Thread.sleep(200); } catch (InterruptedException ie) {}
			}
			// check that the archive file exists
			File targz = new File(stack_dir + task_title + ".tar.gz");
			if (!targz.exists()) {
				String error = "ERROR: could not generate the tar.gz archive.";
				out.println(error);
				Utils.log2(error);
			} else {
				// success: notify back
				out.println("http://" + base_url + "/" + stack_dir + task_title + ".tar.gz");
			}
			//
			// Cleanup:
			//
			// Attempt to remove the temporary directory and its contents
			// This line would do it:
			//Runtime.getRuntime().exec("cd " + stack_dir + " && rm -rf " + task_title);
			// ... but the java way lets me catch errors
			// (and trusting java not to mess up a 'rm -rf' is too much for my well being)
			final String[] files = fdir.list(new FilenameFilter() {
				public boolean accept(File dir, String name) {
					if (name.equals(".") || name.equals("..")) return false;
					return true;
				}
			});
			for (int i=0; i<files.length; i++) {
				try {
					new File(stack_dir + task_title + "/" + files[i]).delete();
				} catch (Exception e) {
					Utils.log2("Could not delete file " + files[i] + " from directory " + task_title);
				}
			}
			try {
				// can only be deleted if empty
				fdir.delete();
			} catch (Exception e) {
				Utils.log2("Could not delete temporary directory " + stack_dir + task_title);
			}
		}
	}

	/** Takes all image files from the given folder as a stack, makes a project with it, aligns the slices, and then overwrites the images with the new ones. */
	static private void registerSlices(File source_dir) {
		// 1 - create a project
		// 2 - import the folder as a stack
		// 3 - register
		// 4 - generate flat images, overwritting them
		// 5 - delele tmp stack
		// 6 - return new stack
	}
}
