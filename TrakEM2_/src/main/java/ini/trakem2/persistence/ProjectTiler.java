package ini.trakem2.persistence;

import ij.ImagePlus;
import ij.io.FileSaver;
import ini.trakem2.Project;
import ini.trakem2.display.DLabel;
import ini.trakem2.display.Display;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.parallel.CountingTaskFactory;
import ini.trakem2.parallel.Process;
import ini.trakem2.tree.LayerThing;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.tree.TemplateThing;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import mpicbg.trakem2.transform.ExportUnsignedShort;
import mpicbg.trakem2.transform.ExportedTile;

public class ProjectTiler {

	/** Take a {@link Project}, a size for the image tiles, and a target directory,
	 * and create a new copy of the current project in that folder but with the underlying
	 * images converted to tiles with a translation-only transform (saved as zipped TIFFs,
	 * with extension ".tif.zip").
	 * The new, returned {@link Project} represents the given project but with much
	 * simpler transformations (just translation) for the images and a defined size for
	 * the latter, which helps a lot regarding storage space of the XML (and parsing and
	 * saving time) and performance when browsing layers (keep in mind that, for a 32k x 32k image,
	 * at 100% zoom one would have to load a 32k x 32k image and render just a tiny bit
	 * of it). The copied Project preserves the ID of the {@link Layer}s of the original
	 * {@link Project}, as well as the dimensions; this means the copy is a sibling of
	 * the original, and it is possible to send segmentations from one to the other "as is"
	 * (directly, without having to transform along with the images which would not be possible). 
	 * 
	 * Image files are stored as 
	 * 
	 * The non-image objects of the given project are copied into the new project as well.
	 * 
	 * @param srcProject The project to create a sibling of.
	 * @param targetDirectory The directory in which to create all the necessary data and mipmap folders for the new Project.
	 * @param tileWidth The width of the tiles to create for the data of the new project.
	 * @param tileHeight The height of the tiles.
	 * @param exportImageType Any of {@link ImagePlus#GRAY8}, {@link ImagePlus#GRAY16} or {@link ImagePlus#COLOR_RGB}, otherwise an {@link IllegalArgumentException} is thrown.
	 * @param onlyVisibleImages Whether to consider visible images only.
	 * @param nExportThreads Number of layers to export in parallel. Use a small number when original images are huge (such as larger than 4096 x 4096 pixels).
	 * @param createMipMaps Whether to generate the mipmaps when done or not.
	 * 
	 * @throws Exception IllegalArgumentException When {@param exportImageType} is not {@link ImagePlus#GRAY16} or {@link ImagePlus#COLOR_RGB}, or when the directory exists and cannot be written to.
	 */
	static final public Project createRetiledSibling(
			final Project srcProject,
			final String targetDirectory,
			final int tileWidth,
			final int tileHeight,
			final int exportImageType,
			final boolean onlyVisibleImages,
			final int nExportThreads,
			final boolean createMipMaps)
		throws Exception {
		
		// Validate exportImageType
		switch (exportImageType) {
		case ImagePlus.GRAY8:
		case ImagePlus.GRAY16:
		case ImagePlus.COLOR_RGB:
			break;
		default:
			throw new IllegalArgumentException("Can only accept GRAY8, GRAY16 or COLOR_RGB as values for 'exportImageType'!");
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
		final String targetDir = Utils.fixDir(targetDirectory);
		
		// Create "data" directory
		final String dataDir = new StringBuilder(targetDir).append("data/").toString();
		final File fDataDir = new File(dataDir);
		if (fDataDir.exists() && (!fDataDir.isDirectory() || !fDataDir.canWrite())) {
			throw new IllegalArgumentException("Cannot create or write to 'data' directory in the targetDirectory at: " + targetDir);
		} else {
			fDataDir.mkdir();
		}

		// Create new Project, plain, without any automatic creation of a Layer or a Display
		final Project newProject = Project.newFSProject("blank", null, targetDir, false);
		final LayerSet newLayerSet = newProject.getRootLayerSet();
		newLayerSet.setCalibration(srcProject.getRootLayerSet().getCalibrationCopy());
		
		if (!createMipMaps) {
			Utils.log("MipMaps are DISABLED:\n --> When done, right-click and choose 'Display - Properties...' and enable mipmaps,\n     and then run 'Project - Regenerate all mipmaps'\n");
			newProject.getLoader().setMipMapsRegeneration(false);
			Utils.log("mipmaps enabled? " + newProject.getLoader().isMipMapsRegenerationEnabled());
		}

		// Copy the Template Tree of types
		newProject.resetRootTemplateThing(srcProject.getRootTemplateThing().clone(newProject, true), null);
		for (final TemplateThing tt : newProject.getRootTemplateThing().getUniqueTypes(new HashMap<String,TemplateThing>()).values()) {
			newProject.addUniqueType(tt);
		}

		// Clone layers with the exact same IDs, so that the two projects are siblings at the layer-level:
		// (Being siblings allows for treelines, arealists, etc. to be transferred from one to another "as is").
		final List<Layer> srcLayers = srcProject.getRootLayerSet().getLayers();
		final List<Layer> newLayers = new ArrayList<Layer>();
		for (final Layer srcLayer : srcLayers) {
			final Layer newLayer = new Layer(newProject, srcLayer.getId(), srcLayer.getZ(), srcLayer.getThickness());
			newLayer.addToDatabase(); // to update the ID generator in FSLoader
			newLayerSet.add(newLayer);
			newLayers.add(newLayer);
			newProject.getRootLayerThing().addChild(new LayerThing(newProject.getRootLayerThing().getChildTemplate("layer"), newProject, newLayer));
		}
		newProject.getLayerTree().rebuild();
		
		// Update the LayerSet
		newLayerSet.setDimensions(srcProject.getRootLayerSet().getLayerWidth(), srcProject.getRootLayerSet().getLayerHeight(), LayerSet.NORTHWEST);
		Display.updateLayerScroller(newLayerSet);
		Display.update(newLayerSet);

		// Copy template from the src Project
		// (It's done after creating layers so the IDs will not collide with those of the Layers)
		newProject.resetRootTemplateThing(srcProject.getRootTemplateThing().clone(newProject, false), null);

		// Export tiles as new Patch instances, creating new image files in disk
		final int numThreads = Math.max(1, Math.min(nExportThreads, Runtime.getRuntime().availableProcessors()));
		int i = 0;
		for (final Layer srcLayer : srcLayers) {
			Utils.log("Processing layer " + (i + 1) + "/" + srcLayers.size() + " -- " + new Date());
			final int layerIndex = i++;
			// Create subDirectory
			final String dir = dataDir + "/" + layerIndex + "/";
			new File(dir).mkdir();
			// Create a new Layer with the same Z and thickness
			final Layer newLayer = newLayers.get(layerIndex);
			// Export layer tiles
			final ArrayList<Patch> patches = new ArrayList<Patch>();
			if (ImagePlus.GRAY16 == exportImageType) {
				Process.progressive(
						ExportUnsignedShort.exportTiles(srcLayer, tileWidth, tileHeight, onlyVisibleImages),
						new CountingTaskFactory<Callable<ExportedTile>, Patch>() {
							public Patch process(final Callable<ExportedTile> c, final int index) {
								try {
									// Create the tile
									final ExportedTile t = c.call();
									// Store the file
									final String title = layerIndex + "-" + index;
									final String path = dir + title + ".tif.zip";
									final ImagePlus imp = new ImagePlus(title, t.sp);
									if (!new FileSaver(imp).saveAsZip(path)) {
										throw new Exception("Could not save tile: " + path);
									}
									// Create a Patch
									final Patch patch = new Patch(newProject, title, t.x, t.y, imp);
									patch.setLocked(true);
									newProject.getLoader().addedPatchFrom(path, patch);
									return patch;
								} catch (Exception e) {
									IJError.print(e);
									return null;
								}
							}
						},
						patches,
						numThreads);
			} else {
				// GRAY8 or COLOR_RGB: created from mipmaps
				Process.progressive(
						tileSequence(srcLayer, tileWidth, tileHeight, onlyVisibleImages),
						new CountingTaskFactory<Rectangle, Patch>() {
							@Override
							public Patch process(final Rectangle bounds, final int index) {
								try {
									// Create the tile
									final ImagePlus imp = srcLayer.getProject().getLoader().getFlatImage(srcLayer, bounds, 1.0, -1, exportImageType, Patch.class, null, false, Color.black);
									final String title = layerIndex + "-" + index;
									imp.setTitle(title);
									final String path = dir + title + ".tif.zip";
									if (!new FileSaver(imp).saveAsZip(path)) {
										throw new Exception("Could not save tile: " + path);
									}
									// Create a Patch
									final Patch patch = new Patch(newProject, title, bounds.x, bounds.y, imp);
									patch.setLocked(true);
									newProject.getLoader().addedPatchFrom(path, patch);
									return patch;
								} catch (Exception e) {
									IJError.print(e);
									return null;
								}
							}
						},
						patches,
						numThreads);
			}
			// Add all Patches to the new Layer
			for (final Patch p : patches) {
				newLayer.add(p);
			}
		}

		// Copy all segmentations "As is"
		final ProjectThing root = srcProject.getRootProjectThing();
		if (null != root.getChildren() && !root.getChildren().isEmpty()) {
			final ProjectThing source_pt = srcProject.getRootProjectThing().getChildren().get(0);
			final int transfer_mode = 0; // "As is"
			final ProjectThing landing_parent = newProject.getRootProjectThing();
			srcProject.getProjectTree().rawSendToSiblingProject(source_pt, transfer_mode, newProject, landing_parent);
		}
		
		// Copy all floating text labels
		i = 0;
		for (final Layer srcLayer : srcLayers) {
			for (final DLabel srcLabel : srcLayer.getAll(DLabel.class)) {
				newLayers.get(i++).add(srcLabel.clone(newProject, false));
			}
		}
		
		if (createMipMaps) {
			final LinkedList<Future<?>> fus = new LinkedList<Future<?>>();
			final int batch = Runtime.getRuntime().availableProcessors();
			for (final Layer newLayer : newLayers) {
				for (final Patch p : newLayer.getAll(Patch.class)) {
					fus.add(p.updateMipMaps());
					// Don't build-up too much
					if (fus.size() > batch * 3) {
						while (fus.size() > batch) {
							try {
								fus.removeFirst().get();
							} catch (Exception e) {
								IJError.print(e);
							}
						}
					}
				}
			}
			Utils.wait(fus);
		}
		
		// Save:
		newProject.saveAs(targetDir + "exported.xml", false);
		
		return newProject;
	}
	
	/** Return a lazy sequence of Rectangle instances, each specifying a tile that contains at least
	 * parts of one Patch. Empty tiles are NOT returned.
	 * 
	 * @param srcLayer
	 * @param tileWidth
	 * @param tileHeight
	 * @param onlyVisibleImages
	 * @return
	 */
	static public final Iterable<Rectangle> tileSequence(final Layer srcLayer, final int tileWidth, final int tileHeight, final boolean onlyVisibleImages) {
		return new Iterable<Rectangle>()
		{
			@Override
			public Iterator<Rectangle> iterator()
			{	
				return new Iterator<Rectangle>()
				{
					final Rectangle box = srcLayer.getMinimalBoundingBox(Patch.class, onlyVisibleImages)
                                          .intersection(srcLayer.getParent().get2DBounds());
					final int nRows = (int)Math.ceil(box.width / (double)tileWidth);
					final int nCols = (int)Math.ceil(box.height / (double)tileHeight);
					//
					Rectangle tileBounds = null;
					int row = 0,
					    col = 0;
					{
						// Constructor. Get ready to answer "hasNext()"
						findNext();
					}
					private void findNext() {
						tileBounds = null;
						while (true)
						{
							if (nRows == row) {
								// End of domain
								tileBounds = null;
								break;
							}
							tileBounds = new Rectangle(box.x + col * tileWidth, box.y + row * tileHeight, tileWidth, tileHeight);
							final boolean content = srcLayer.find(Patch.class, tileBounds, onlyVisibleImages).size() > 0;

							// Prepare next iteration
							col += 1;
							if (nCols == col) {
								col = 0;
								row += 1;
							}

							if (content) {
								// Ready for next iteration
								break;
							}
						}
					}
					@Override
					public boolean hasNext() {
						return null != tileBounds;
					}
					@Override
					public Rectangle next() {
						// Capture state locally
						final Rectangle r = new Rectangle(tileBounds);
						// Advance
						findNext();
						//
						return r;
					}
					@Override
					public void remove() {
						throw new UnsupportedOperationException();
					}
				};
			}
		};
	}
}
