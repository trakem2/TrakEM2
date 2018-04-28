package ini.trakem2.display;

public class Coordinate<T> {
	public double x;
	public double y;
	public Layer layer;
	public T object;

	public Coordinate(double x, double y, Layer layer, T object) {
		this.x = x;
		this.y = y;
		this.layer = layer;
		this.object = object;
	}
}
