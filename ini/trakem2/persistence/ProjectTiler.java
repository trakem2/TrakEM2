package ini.trakem2.persistence;

import ij.ImagePlus;
import ij.io.FileSaver;
import ij.process.ShortProcessor;
import ini.trakem2.Project;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.parallel.CountingTaskFactory;
import ini.trakem2.parallel.Process;
import ini.trakem2.utils.IJError;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import mpicbg.trakem2.transform.ExportUnsignedShortLayer;
import mpicbg.trakem2.util.Triple;

public class ProjectTiler {

	/** Take a {@link Project}, a size for the image tiles, and a target directory,
	 * and create a new copy of the current project in that folder but with the underlying
	 * images converted to tiles with a translation-only transform.
	 * The purpose of this newProject is to represent the given project but with much
	 * simpler transformations for the images and a defined size for the latter,
	 * which helps a lot regarding storage space of the XML (and parsing and saving time)
	 * and performance when browsing layers (keep in mind that, for a 32k x 32k image,
	 * at 100% zoom one would have to load a 32k x 32k image and render just a tiny bit
	 * of it).
	 * 
	 * The non-image objects of the given project are copied into the new project as well.
	 * 
	 * @param srcProject The 
	 * @param targetDirectory The directory in which to create all the necessary data and mipmap folders for the new Project.
	 * @param tileSide The size of the tiles to create for the data of the new project.
	 * @param exportImageType Either {@link ImagePlus#GRAY16} or {@link ImagePlus#COLOR_RGB}, otherwise an {@link IllegalArgumentException} is thrown.
	 * @param nThreads Number of layers to export in parallel. Use a small number when original images are huge (such as larger than 4096 x 4096 pixels).
	 * 
	 * @throws Exception IllegalArgumentException When {@param exportImageType} is not {@link ImagePlus#GRAY16} or {@link ImagePlus#COLOR_RGB}, or when the directory exists and cannot be written to.
	 */
	static final public Project flatten(
			final Project srcProject,
			final String targetDirectory,
			final int tileSide,
			final int exportImageType,
			final boolean onlyVisibleImages,
			final int nThreads)
		throws Exception {
		
		// Validate exportImageType
		switch (exportImageType) {
		case ImagePlus.GRAY16:
		case ImagePlus.COLOR_RGB:
			break;
		default:
			throw new IllegalArgumentException("Can only accept GRAY16 or COLOR_RGB as values for 'exportImageType'!");
		}

		// Validate targetDirectory
		final File fdir = new File(targetDirectory);
		if (fdir.exists()) {
			if (!fdir.isDirectory() || !fdir.canWrite())
				throw new IllegalArgumentException("Invalid directory: not a directory or cannot write to: " + targetDirectory);
		} else {
			if (!fdir.mkdirs()) {
				throw new IllegalArgumentException("Cannot create directory at: " + targetDirectory);
			}
		}
		
		// Create data directory
		final String dataDir = new StringBuilder(targetDirectory.replace('\\', '/')).append('/').append("data/").toString();
		final File fDataDir = new File(dataDir);
		if (fDataDir.exists() && (!fDataDir.isDirectory() || !fDataDir.canWrite())) {
			throw new IllegalArgumentException("Cannot create data directory in the targetDirectory at: " + dataDir);
		}
		
		// Create new Project
		final Project newProject = Project.newFSProject(null, null, dataDir);
		// Copy template from the src Project
		newProject.resetRootTemplateThing(srcProject.getRootTemplateThing().clone(newProject, false), null);
		final LayerSet newLayerSet = newProject.getRootLayerSet();

		// Export tiles as new Patch instances, creating new PNG files in disk
		final List<Layer> srcLayers = srcProject.getRootLayerSet().getLayers();
		int i = 0;
		for (final Layer srcLayer : srcLayers) {
			final int layerIndex = i++;
			// Create subDirectory
			final String dir = dataDir + "/" + i + "/";
			new File(dir).mkdir();
			// Create a new Layer with the same Z and thickness
			final Layer newLayer = newLayerSet.getLayer(srcLayer.getZ(), srcLayer.getThickness(), true);
			// Export layer tiles
			final ArrayList<Patch> patches = new ArrayList<Patch>();
			Process.progressive(
					ExportUnsignedShortLayer.exportTiles(srcLayer, tileSide, tileSide, onlyVisibleImages),
					new CountingTaskFactory<Callable<Triple<ShortProcessor,Integer,Integer>>, Patch>() {
						public Patch process(final Callable<Triple<ShortProcessor,Integer,Integer>> c, final int index) {
							try {
								// Create the tile
								final Triple<ShortProcessor,Integer,Integer> t = c.call();
								// Store the file
								final String title = layerIndex + "-" + index;
								final String path = dir + title + ".png";
								final ImagePlus imp = new ImagePlus(title, t.a);
								if (!new FileSaver(imp).saveAsPng(path)) {
									throw new Exception("Could not save tile: " + path);
								}
								// Create a Patch
								return new Patch(newProject, title, t.b, t.c, imp);
							} catch (Exception e) {
								IJError.print(e);
								return null;
							}
						}
					},
					patches,
					Math.max(1, Math.min(nThreads, Runtime.getRuntime().availableProcessors())));
			// Add all Patches to the new Layer
			for (final Patch p : patches) {
				newLayer.add(p);
			}
		}
		// Enlarge new LayerSet to fit the images
		newLayerSet.setMinimumDimensions();

		// Copy all segmentations "as is"
		// TODO
		// TODO warn about Profile not being copied
		
		// Copy all floating text labels
		// TODO
		
		return null;
	}
}
