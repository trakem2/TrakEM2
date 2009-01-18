package ini.trakem2.display;

import ini.trakem2.utils.History;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.awt.geom.AffineTransform;

public class TransformationStep implements History.Step<Displayable> {
	final HashMap<Displayable,AffineTransform> ht;
	TransformationStep(final HashMap<Displayable,AffineTransform> ht) {
		this.ht = ht;
	}
	public List<Displayable> remove(final long id) {
		final List<Displayable> al = new ArrayList<Displayable>();
		for (Iterator<Displayable> it = ht.keySet().iterator(); it.hasNext(); ) {
			final Displayable d = it.next();
			if (d.getId() == id) {
				it.remove();
			}
			al.add(d);
		}
		return al;
	}
	public boolean isEmpty() {
		return ht.isEmpty();
	}
}
