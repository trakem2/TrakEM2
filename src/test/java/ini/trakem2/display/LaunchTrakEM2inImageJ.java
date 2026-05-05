package ini.trakem2.display;

import java.io.File;

import ij.ImageJ;
import ini.trakem2.Project;

public class LaunchTrakEM2inImageJ {
	
	static public void main(String[] args) {
		final ImageJ ij = new ImageJ();
		
		final String folder = "/home/albert/Desktop/t2/trakem2-java-21/";
		if (new File(folder + "test.xml").exists()) {
			final Project project = Project.openFSProject(folder + "test.xml", true);
		} else {
			final Project project = Project.newFSProject("blank", null, "/home/albert/Desktop/t2/trakem2-java-21/", true);
		}
	}
}
