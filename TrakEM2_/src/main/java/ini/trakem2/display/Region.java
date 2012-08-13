package ini.trakem2.display;

import java.awt.Rectangle;

public class Region<T> {
	public Rectangle r;
	public Layer layer;
	public T object;
	
	public Region(Rectangle r, Layer layer, T object) {
		this.r = r;
		this.layer = layer;
		this.object = object;
	}
}
