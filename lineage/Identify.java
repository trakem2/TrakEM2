package lineage;

import java.util.HashMap;
import java.lang.reflect.Method;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.Reader;
import ini.trakem2.utils.IJError;
import ini.trakem2.display.Line3D;

public class Identify {

	static {
		try {
			Class c = Class.forName("clojure.lang.Compiler");
			Method load = c.getDeclaredMethod("load", new Class[]{Reader.class});
			load.invoke(null, new Object[]{new InputStreamReader(Identify.class.getResourceAsStream("/lineage/identify.clj"))});
			// As a side effect, inits clojure runtime
		} catch (Throwable t) {
			IJError.print(t);
		}
	}

	static public void identify(Line3D p) {
		try {
			Class RT = Class.forName("clojure.lang.RT");
			Method var = RT.getDeclaredMethod("var", new Class[]{String.class, String.class});
			Object fn = var.invoke(null, new Object[]{"lineage.identify", "identify"});
			Method invoke = Class.forName("clojure.lang.Var").getDeclaredMethod("invoke", new Class[]{Object.class});
			invoke.invoke(fn, new Object[]{p});
		} catch (Throwable e) {
			IJError.print(e);
		}
	}
}
