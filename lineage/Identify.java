package lineage;

import java.util.HashMap;
import java.lang.reflect.Method;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.Reader;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Worker;
import ini.trakem2.display.Line3D;
import ij.gui.GenericDialog;

public class Identify {

	static {
		try {
			Thread.currentThread().setContextClassLoader(ij.IJ.getClassLoader());
			Class c = Class.forName("clojure.lang.Compiler");
			Method load = c.getDeclaredMethod("load", new Class[]{Reader.class});
			load.invoke(null, new Object[]{new InputStreamReader(Identify.class.getResourceAsStream("/lineage/identify.clj"))});
			// As a side effect, inits clojure runtime
		} catch (Throwable t) {
			IJError.print(t);
		}
	}

	/**
	 * Takes 2 arg (the Pipe or Polyline and the lib-name)
	 * or 4 args: Line3D pipe, String lib-name, double delta, boolean direct and boolean substring
	 */
	static public void identify(Object... args) {
		try {
			Class RT = Class.forName("clojure.lang.RT");
			Method var = RT.getDeclaredMethod("var", new Class[]{String.class, String.class});
			Object fn = var.invoke(null, new Object[]{"lineage.identify", "identify"});
			Class[] cc = new Class[args.length];
			for (int i=0; i<cc.length; i++) cc[i] = Object.class;
			Method invoke = Class.forName("clojure.lang.Var").getDeclaredMethod("invoke", cc);
			invoke.invoke(fn, args);
		} catch (Throwable e) {
			IJError.print(e);
		}
	}

	static private double delta = 1.0;
	static private final String[] libs = {"Drosophila-3rd-instar"};
	static private int lib_index = 0;
	static private boolean direct = true;
	static private boolean substring = false;

	static public boolean setup() {
		GenericDialog gd = new GenericDialog("Setup Identify");
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

	static public Bureaucrat identify(final Line3D pipe) {
		return Bureaucrat.createAndStart(new Worker.Task("Identifying " + pipe) {
			public void exec() {
				if (null == pipe) return;
				identify(pipe, libs[lib_index], delta, direct, substring);
			}
		}, pipe.getProject());
	}
}
