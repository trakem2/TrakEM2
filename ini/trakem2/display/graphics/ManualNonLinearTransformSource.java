package ini.trakem2.display.graphics;

import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Paintable;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Collection;
import java.awt.Composite;
import java.awt.AlphaComposite;
import java.awt.Color;

public class ManualNonLinearTransformSource implements GraphicsSource {

	private final Collection<Displayable> originals;
	private Rectangle box = null;

	public ManualNonLinearTransformSource(final Collection<Displayable> col) {
		this.originals = col;
		Rectangle tmp = new Rectangle();
		for (final Displayable d : col) {
			if (null == box) box = d.getBoundingBox();
			else box.add(d.getBoundingBox(tmp));
		}
	}

	/** Returns the list given as argument without any modification. */
	public Collection<? extends Paintable> asPaintable(final Collection<? extends Paintable> ds) {
		return ds;
	}

	public void paintOnTop(final Graphics2D g, final Display display, final Rectangle srcRect, final double magnification) {
		Composite original_composite = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
		g.setColor(Color.magenta);
		g.fillRect(box.x, box.y, box.width, box.height);
		g.setComposite(original_composite);
	}
}
