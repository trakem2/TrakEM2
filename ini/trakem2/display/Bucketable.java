package ini.trakem2.display;

import java.util.ArrayList;
import java.util.HashMap;
import ini.trakem2.Project;

public interface Bucketable {
	public ArrayList<? extends Displayable> getDisplayableList();
	public HashMap<Displayable, ArrayList<Bucket>> getBucketMap();
	public void updateBucket(Displayable d);
	public Project getProject();
}
