package treeline;

import ini.trakem2.display.Treeline;
import ini.trakem2.plugin.TPlugIn;
import ini.trakem2.utils.IJError;

import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;

public class TreelineGraphEditor implements TPlugIn {
	public TreelineGraphEditor() {}

	public boolean setup(Object... args) {
		System.out.println("No setup yet for TreelineGraphEditor");
		return true;
	}

	public boolean applies(final Object ob) {
		return ob instanceof Treeline;
	}

	public Object invoke(Object... args) {
		if (null == args || args.length < 1 || null == args[0] || !(args[0] instanceof Treeline)) return null;
		try {
			Class RT = Class.forName("clojure.lang.RT");
			Method var = RT.getDeclaredMethod("var", new Class[]{String.class, String.class});
			Object fn = var.invoke(null, new Object[]{"treeline.graph_editor", "as-graph"});
			return Class.forName("clojure.lang.Var")
				.getDeclaredMethod("invoke", new Class[]{Object.class})
				.invoke(fn, new Object[]{args[0]});
		} catch (Throwable e) {
			IJError.print(e);
		}
		return null;
	}

	static {
		try {
			Thread.currentThread().setContextClassLoader(ij.IJ.getClassLoader());
			Class c = Class.forName("clojure.lang.Compiler");
			Method load = c.getDeclaredMethod("load", new Class[]{Reader.class});
			load.invoke(null, new Object[]{new InputStreamReader(TreelineGraphEditor.class.getResourceAsStream("/treeline/graph_editor.clj"))});
			// As a side effect, inits clojure runtime
		} catch (Throwable t) {
			IJError.print(t);
		}
	}

}
