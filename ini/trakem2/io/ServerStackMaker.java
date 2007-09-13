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
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.IJError;
import java.util.concurrent.atomic.AtomicInteger;
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
 
 *
 *
*/
public class ServerStackMaker {

	private ServerSocket listener;
	private int port;
	private boolean listen = true;
	private Project project;
	private AtomicInteger count = new AtomicInteger();
	private static int MAX_JOBS = 4;

	public ServerStackMaker(Project project, int port) {
		this.project = project;
		this.port = port;
		try {
			this.listener = new ServerSocket(port);
			// wait until killed
			while (listen) {
				handleNewConnection(listener.accept());
			}
		} catch (IOException ioe) {
			ioe.printStackTrace();
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	/** Forks a new Thread to handle the new request. */
	private void handleNewConnection(final Socket socket) {
		new Thread() {
			public void run() {
				Utils.log2("Handling new connection. Current count: " + count.get());
				// waint until less than MAX_JOBS are currently in the works
				while (count.get() >= MAX_JOBS) {
					try { Thread.sleep(1000); } catch (InterruptedException e) {}
				}
				// register new job
				count.addAndGet(1);
				try {
					setPriority(Thread.NORM_PRIORITY);
					// process socket.
					BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
					PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
					// read only the first line
					String line = in.readLine();
					Utils.log2("Processing: " + line);
					Task task = parseCommand(line);
					task.execute(out);
				} catch (IOException ioe) {
					ioe.printStackTrace();
				} catch (Throwable t) {
					t.printStackTrace();
				} finally {
					// deregister
					count.decrementAndGet();
				}
			}
		}.start();
	}

	static public void main(String[] arg) {
		if (arg.length < 2) {
			Utils.log2("The number of arguments is incorrect!\nUsage:\n"
				 + "\tjava -Xmx1650m -Xincgc -classpath ij.jar:TrakEM2.jar:ImageJ_3D_Viewer.jar /path/to/project.xml <port>\n");
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
			Utils.log2("Number format exception: Invalid port number.");
			return;
		}
		Utils.log2("Creating new server for " + arg[0]);
		new ServerStackMaker(project, port);
	}


	/** Returns null if the string contains incorrect commands.*/
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
		public void execute(PrintWriter out);
	}

	private class Error implements Task {
		String error;
		Error(String error) {
			this.error = error;
		}
		public void execute(PrintWriter out) {
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
		public void execute(PrintWriter out) {
			// dummy
			out.println("OK will process box:" + r + " scale: " + scale + " layers: " + i_first_layer + "-" + i_last_layer + " align: " + align);
			out.println("dummy - here you'll get the URL to the cropped stack.");
			// TODO
			// - make the stack
			// - align it
			// - print back the URL where it will be stored at
		}
	}
}
