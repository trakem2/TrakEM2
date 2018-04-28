package ini.trakem2.display;

import ini.trakem2.Project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public interface Bucketable {
	public ArrayList<? extends Displayable> getDisplayableList();
	public HashMap<Displayable, HashSet<Bucket>> getBucketMap(Layer layer);
	public void updateBucket(Displayable d, Layer layer);
	public Project getProject();
	public float getLayerWidth();
	public float getLayerHeight();
}
