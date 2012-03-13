package lineage;

import ij.Prefs;
import ij.gui.GenericDialog;
import ini.trakem2.display.Line3D;
import ini.trakem2.plugin.TPlugIn;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.FieldMapView;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Identify implements TPlugIn {

	public Identify() {}

	static {
		try {
			Thread.currentThread().setContextClassLoader(ij.IJ.getClassLoader());
			Class<?> c = Class.forName("clojure.lang.Compiler");
			Method load = c.getDeclaredMethod("load", new Class[]{Reader.class});
			load.invoke(null, new Object[]{new InputStreamReader(Identify.class.getResourceAsStream("/lineage/identify.clj"))});
			// As a side effect, inits clojure runtime
		} catch (Throwable t) {
			IJError.print(t);
		}
	}

	/**
	 * Takes 2 arg (the Pipe or Polyline and the lib-name)
	 * or 5 args: Line3D pipe, String lib-name, double delta, boolean direct and boolean substring
	 */
	static public Object identify(Object... args) {
		return invokeClojureFunction("lineage.identify", "identify", args);
	}
	
	/**
	 * @param namespace
	 * @param fnName
	 * @param args Should not be null. Use an empty array if necessary.
	 * @return
	 */
	static public Object invokeClojureFunction(final String namespace, final String fnName, final Object... args) {
		try {
			Class<?> RT = Class.forName("clojure.lang.RT");
			Method var = RT.getDeclaredMethod("var", new Class[]{String.class, String.class});
			Object fn = var.invoke(null, new Object[]{namespace, fnName});
			Class<?>[] cc = new Class[args.length];
			for (int i=0; i<cc.length; i++) cc[i] = Object.class;
			Method invoke = Class.forName("clojure.lang.Var").getDeclaredMethod("invoke", cc);
			return invoke.invoke(fn, args);
		} catch (Throwable e) {
			IJError.print(e);
		}
		return null;
	}
	

	static private class Library extends FieldMapView {
		@SuppressWarnings("unused")
		private String title,
		               filepath,
		               reference;

		private Library(String title, String filepath, String reference) {
			this.title = title;
			this.filepath = filepath;
			this.reference = reference;
		}
	}
	
	static private class Params {

		private final List<Library> libs; // = {"Drosophila-3rd-instar"};
		private final long modificationTime;
		private double delta = 1.0;
		private int lib_index = 0;
		private boolean direct = true;
		private boolean substring = false;

		private Params() {
			Tuple b = init();
			this.libs = b.libraries;
			this.modificationTime = b.lastModified;
		}
		
		private final class Tuple {
			private final long lastModified;
			private final List<Library> libraries;
			Tuple(long modificationTime, List<Library> libs) {
				this.lastModified = modificationTime;
				this.libraries = libs;
			}
		}
		
		private final File fileNITLibraries() {
			final String pluginsDir = Utils.fixDir(Prefs.get("plugins.dir",
					Utils.fixDir(System.getProperty("user.dir") + "/plugins")));
			File libsList = new File(pluginsDir + "NIT-libraries.txt");
			if (!libsList.exists()) {
				throw new RuntimeException("Could not find file " + libsList.getAbsolutePath());
			}
			if (!libsList.canRead()) {
				throw new RuntimeException("Could not read file " + libsList.getAbsolutePath());
			}
			return libsList;
		}
		
		private final Tuple init() {
			// Read in the file defining the libraries
			final ArrayList<Library> l = new ArrayList<Library>();
			final File libsList = fileNITLibraries();
			final long lastModified = libsList.lastModified();
			final String[] lines = Utils.openTextFileLines(libsList.getAbsolutePath());
					
			//
			for (int i=0; i<lines.length; ++i) {
				String line = lines[i].trim();
				if (0 == line.length()) continue;
				if (line.startsWith("#")) continue;
				int comment = line.indexOf('#');
				if (-1 != comment) line = line.substring(0, comment).trim();
				if (0 == line.length()) continue;
				String[] t = line.split("\\t+"); // split at one or more tabs
				if (t.length < 3) {
					throw new RuntimeException("Errors in Library file: improper entry at line " + (i+1) + ": '" + lines[i] + "'");
				}
				for (int k=0; k<t.length; ++k) t[k] = t[k].trim();
				// Check that the file path is readable
				File f = new File(t[1]);
				if (!f.exists()) {
					// Try relative to NIT-libraries.txt
					f = new File(Utils.fixDir(libsList.getParent()) + t[1]);
					Utils.logAll("File f is " + f.getAbsolutePath() + " and t[1]: " + t[1]);
					if (!f.exists()) {
						Utils.logAll("Could not find file for NIT library: " + t[1]);
						continue;
					}
				}
				if (!f.canRead()) {
					Utils.logAll("Incorrect permissions, cannot read file for NIT library at " + t[1]);
					continue;
				}
				l.add(new Library(t[0], f.getAbsolutePath(), t[2]));
				Utils.log("Library: " + t[0] + " | " + f.getAbsolutePath() + " | " + t[2]);
			}
			Utils.logAll("T2-NIT: loaded " + l.size() + " librar" + (0 == l.size() ? "y" : "ies"));

			return new Tuple(lastModified, Collections.unmodifiableList(l));
		}

		/** Will reload the libraries if necessary. */
		private Params(final Params p) {
			final File libsList = fileNITLibraries();
			if (libsList.lastModified() > p.modificationTime) {
				Utils.logAll("NIT-libraries.txt has been modified: libraries will be reloaded");
				invokeClojureFunction("lineage.identify", "forget-libs", new Object[]{});
				Tuple b = init();
				this.libs = b.libraries;
				this.modificationTime = b.lastModified;
			} else {
				this.libs = p.libs;
				this.delta = p.delta;
				this.lib_index = p.lib_index;
				this.direct = p.direct;
				this.substring = p.substring;
				this.modificationTime = p.modificationTime;
			}
		}

		@Override
		synchronized public Params clone() {
			return new Params(this);
		}

		synchronized private boolean setup() {
			GenericDialog gd = new GenericDialog("Setup NIT");
			gd.addNumericField("delta:", delta, 2);
			gd.addCheckbox("direct", direct);
			gd.addCheckbox("substring", substring);
			String[] libNames = new String[libs.size()];
			for (int i=0; i<libs.size(); ++i) {
				libNames[i] = libs.get(i).title;
			}
			gd.addChoice("Library: ", libNames, libNames[lib_index]);
			gd.showDialog();
			if (gd.wasCanceled()) return false;
			double d = gd.getNextNumber();
			if (Double.isNaN(d)) {
				Utils.log("Invalid delta value!");
				return false;
			}
			if (d < 0) d = 1.0;
			delta = d;
			lib_index = gd.getNextChoiceIndex();
			direct = gd.getNextBoolean();
			substring = gd.getNextBoolean();
			return true;
		}
		
		private boolean isOutOfDate() {
			File f = fileNITLibraries();
			return f.lastModified() > modificationTime;
		}
	}

	/** Store start up values. New instances take the current values. */
	static private Params PARAMS = new Params();

	private Params params = PARAMS.clone();

	public Bureaucrat identify(final Line3D pipe) {
		return Bureaucrat.createAndStart(new Worker.Task("Identifying " + pipe) {
			public void exec() {
				if (null == pipe) return;
				Params p = params.clone();
				identify(pipe, p.libs.get(p.lib_index), p.delta, p.direct, p.substring);
			}
		}, pipe.getProject());
	}

	@Override
	public boolean setup(Object... args) {
		if (params.isOutOfDate()) params = new Params(params);
		return params.setup();
	}

	@Override
	public Object invoke(Object... args) {
		if (null == args || args.length < 1 || null == args[0] || !(args[0] instanceof Line3D)) return null;
		Line3D pipe = (Line3D) args[0];
		Params p = params.clone();
		return identify(pipe, p.libs.get(p.lib_index), p.delta, p.direct, p.substring);
	}

	@Override
	public boolean applies(final Object ob) {
		return null != ob && ob instanceof Line3D;
	}
}
