package ini.trakem2.display;

import java.awt.Point;

public class FallLine {

	final Display display;
	final ZDisplayable source;

	Object previous;
	Object next;

	Layer current;
	Layer target;
	Point position;

	public FallLine(Display display, ZDisplayable source) {
		this.display = display;
		this.source = source;
		this.target = this.current = display.getLayer();
	}

	public Layer getLayer() { return current; }

	public boolean isSource(ZDisplayable zd) { return source == zd; }

	public void go(Layer target) {
		this.current = this.target;
		this.target = target;
		source.setPosition(this);
	}
}
