package lineage;

import ij.gui.GenericDialog;
import ini.trakem2.display.Line3D;
import ini.trakem2.plugin.TPlugIn;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;

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
		try {
			Class<?> RT = Class.forName("clojure.lang.RT");
			Method var = RT.getDeclaredMethod("var", new Class[]{String.class, String.class});
			Object fn = var.invoke(null, new Object[]{"lineage.identify", "identify"});
			Class<?>[] cc = new Class[args.length];
			for (int i=0; i<cc.length; i++) cc[i] = Object.class;
			Method invoke = Class.forName("clojure.lang.Var").getDeclaredMethod("invoke", cc);
			return invoke.invoke(fn, args);
		} catch (Throwable e) {
			IJError.print(e);
		}
		return null;
	}

	static private class Params {

		private final String[] libs = {"Drosophila-3rd-instar"};

		private double delta = 1.0;
		private int lib_index = 0;
		private boolean direct = true;
		private boolean substring = false;

		synchronized public Params clone() {
			Params p = new Params();
			p.delta = delta;
			p.lib_index = lib_index;
			p.direct = direct;
			p.substring = substring;
			return p;
		}

		synchronized private boolean setup() {
			GenericDialog gd = new GenericDialog("Setup NIT");
			gd.addNumericField("delta:", delta, 2);
			gd.addCheckbox("direct", direct);
			gd.addCheckbox("substring", substring);
			gd.addChoice("Library: ", libs, libs[0]);
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
	}

	/** Store start up values. New instances take the current values. */
	static private Params PARAMS = new Params();

	private Params params = PARAMS.clone();

	public boolean setup(Object... args) {
		return params.setup();
	}

	public Bureaucrat identify(final Line3D pipe) {
		return Bureaucrat.createAndStart(new Worker.Task("Identifying " + pipe) {
			public void exec() {
				if (null == pipe) return;
				Params p = params.clone(); 
				identify(pipe, p.libs[p.lib_index], p.delta, p.direct, p.substring);
			}
		}, pipe.getProject());
	}

	public Object invoke(Object... args) {
		if (null == args || args.length < 1 || null == args[0] || !(args[0] instanceof Line3D)) return null;
		Line3D pipe = (Line3D) args[0];
		Params p = params.clone();
		return identify(pipe, p.libs[p.lib_index], p.delta, p.direct, p.substring);
	}

	public boolean applies(final Object ob) {
		return null != ob && ob instanceof Line3D;
	}
}
