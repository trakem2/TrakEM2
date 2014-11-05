package ini.trakem2.scripting;

import ij.IJ;
import ij.ImagePlus;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.IJError;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class PatchScript {

	static private Method m = null;

	/** Run the script at path on the ImagePlus of patch. */
	static public void run(final Patch patch, final ImagePlus imp, final String path) {
		try {
			HashMap<String,Object> vars = new HashMap<String,Object>();
			vars.put("patch", patch);
			vars.put("imp", imp);
			if (null == m) {
				Class<?> c = Class.forName("common.ScriptRunner");
				m = c.getDeclaredMethod("run", String.class, Map.class);
			}
			m.invoke(null, (IJ.isWindows() ? path.replace('/', '\\') : path), vars);
		} catch (Exception e) {
			IJError.print(e);
		}
	}
}
