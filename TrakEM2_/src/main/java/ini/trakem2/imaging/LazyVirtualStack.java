package ini.trakem2.imaging;

import ij.VirtualStack;
import ij.process.ImageProcessor;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;


public class LazyVirtualStack extends VirtualStack {
	private final List<Callable<ImageProcessor>> tasks = new ArrayList<Callable<ImageProcessor>>();
	private int initial_size;
	public LazyVirtualStack(final int width, final int height, final int initial_size) {
		super();
		Utils.setField(this, ij.ImageStack.class, "width", width);
		Utils.setField(this, ij.ImageStack.class, "height", width);
	}

	public void addSlice(String name) {
		throw new UnsupportedOperationException("LazyVirtualStack accepts Callable<ImageProcessor> slices only.");
	}
	public void deleteSlice(int i) {
		throw new UnsupportedOperationException("LazyVirtualStack: can't remove slices.");
	}
	public void addSlice(final Callable<ImageProcessor> task) {
		tasks.add(task);
	}
	public ImageProcessor getProcessor(final int n) {
		try {
			return tasks.get(n-1).call();
		} catch (Exception e) {
			IJError.print(e);
		}
		return null;
	}
	public int getSize() {
		return Math.max(initial_size, tasks.size());
	}
}
