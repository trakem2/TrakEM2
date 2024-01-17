/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2024 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package ini.trakem2.persistence;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.DirectoryChooser;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.Saver;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;
import mpicbg.trakem2.transform.ExportUnsignedShort;
import mpicbg.trakem2.util.Downsampler;

public class ExportMultilevelTiles
{
	/** Will overwrite if the file path exists. */
	static private Runnable makeTileRunnable(final Layer layer, final Rectangle srcRect, final double mag,
			final int c_alphas, final int type, final Class<?> clazz, final String file_path,
			final Saver saver, final int tileWidth, final int tileHeight, final boolean skip_empty_tiles,
			final boolean padding) {
		return new Runnable() {
			public void run() {
				ImagePlus imp = null;
				if (srcRect.width > 0 && srcRect.height > 0) {
					imp = layer.getProject().getLoader().getFlatImage(layer, srcRect, mag, c_alphas, type, clazz, null, true); // with quality
					// First cheap check on bounding boxes only, if it fails, check if anything actually was painted
					if (skip_empty_tiles && (layer.find(srcRect, true).isEmpty() || isEmptyTile(imp.getProcessor()))) return;
				} else {
					// Make empty black tile
					if (skip_empty_tiles) return;
					imp = new ImagePlus("", new ByteProcessor(tileWidth, tileHeight)); // black tile
				}
				// correct dimensions of cropped tiles, padding the outside with black
				if (padding && (imp.getWidth() < tileWidth || imp.getHeight() < tileHeight)) {
					final ImagePlus imp2 = new ImagePlus(imp.getTitle(), imp.getProcessor().createProcessor(tileWidth, tileHeight));
					// ensure black background for color images
					if (imp2.getType() == ImagePlus.COLOR_RGB) {
						final Roi roi = new Roi(0, 0, tileWidth, tileHeight);
						imp2.setRoi(roi);
						imp2.getProcessor().setValue(0); // black
						imp2.getProcessor().fill();
					}
					imp2.getProcessor().insert(imp.getProcessor(), 0, 0);
					imp.flush();
					imp = imp2;
				}
				saver.save(imp, file_path);
				imp.flush();
			}
		};
	}

	/** Find the closest, but larger, power of 2 number for the given edge size; the base root may be any of {1,2,3,5}. */
	static private int[] determineClosestPowerOfTwo(final int edge) {
		final int[] starter = new int[]{1, 2, 3, 5}; // I love primer numbers
		final int[] larger = new int[starter.length]; System.arraycopy(starter, 0, larger, 0, starter.length); // I hate java's obscene verbosity
		for (int i=0; i<larger.length; i++) {
			while (larger[i] < edge) {
				larger[i] *= 2;
			}
		}
		int min_larger = larger[0];
		int min_i = 0;
		for (int i=1; i<larger.length; i++) {
			if (larger[i] < min_larger) {
				min_i = i;
				min_larger = larger[i];
			}
		}
		// 'larger' is now larger or equal to 'edge', and will reduce to starter[min_i] tiles squared.
		return new int[]{min_larger, starter[min_i]};
	}
	
	/**
	 * Auxiliary method for the makePrescaledTiles to be able to export tiles into multiple configurations of directories.
	 * 
	 * Type 0: <base_dir>/<section>/<Y>_<X>_<scale_pow>.<ext>
	 * Type 1: <base_dir>/<section>/<scale_pow>/<Y>_<X>.<ext>
	 * 
	 * 
	 * NOTE: Does not include the file type extension, which is added by each ImageSaver.
	 *       The <ext> is shown above for clarity.
	 * 
	 * @param type
	 * @param dir
	 * @param section
	 * @param column
	 * @param row
	 * @return
	 */
	static private final String makeTilePath(final int type, String base_dir, final int section, final int row, final int column, final int scale_pow) {
		
		if (!base_dir.endsWith("/")) base_dir += "/";
		
		switch (type) {
			case 0:
				// <section>_<Y>_<X>_<scale_pow>.<ext>
				return base_dir + section + "/" + row + "_" + column + "_" + scale_pow;
			case 1:
				// <section>/scale_pow/<Y>_<X>.<ext>
				return base_dir + section + "/" + scale_pow + "/" + row + "_" + column;
			default:
				return null;
		}
	}
	
	static private final boolean isEmptyTile(final ImageProcessor ip) {
		if (ip instanceof ByteProcessor) {
			final byte[] b = (byte[])ip.getPixels();
			for (int i=0; i<b.length; ++i) {
				if (0 != b[i]) return false;
			}
		} else if (ip instanceof ColorProcessor) {
			final int[] c = (int[])ip.getPixels();
			for (int i=0; i<c.length; ++i) {
				if (0 != (c[i] & 0x00ffffff)) return false;
			}
		}
		return true;
	}

	/** Generate e.g. 256x256 tiles, as many as necessary, to cover the given srcRect, starting at scale 1.0. Designed to be slow but memory-capable.
	 *
	 * Directory structure types:
	 * type 0: filename = z + "/" + row + "_" + column + "_" + s + ".jpg";
	 * type 1: filename = z + "/" + s + "/" + row + "_" + column + ".jpg";
	 *
	 * row and column run from 0 to n stepsize 1
	 * that is, row = y / ( 256 * 2^s ) and column = x / ( 256 * 2^s )
	 *
	 * z : z-level (slice)
	 * x,y: the row and column
	 * s: scale, which is 1 / (2^s), in integers: 0, 1, 2 ...
	 *
	 *  var MAX_S = Math.floor( Math.log( MAX_Y + 1 ) / Math.LN2 ) - Math.floor( Math.log( Y_TILE_SIZE ) / Math.LN2 ) - 1;
	 *
	 * The module should not be more than 5
	 * At al levels, there should be an even number of rows and columns, except for the coarsest level.
	 * The coarsest level should be at least 5x5 tiles.
	 *
	 * Best results obtained when the srcRect approaches or is a square. Black space will pad the right and bottom edges when the srcRect is not exactly a square.
	 * Only the area within the srcRect is ever included, even if actual data exists beyond.
	 * 
	 * @param strategy 0 (original images), 1 (mipmaps) or 2 (mipmaps, with multi-layer threading)
	 * @param directory_structure_type 0 or 1. See above.
	 * @param skip_empty_tiles Entirely black tiles will not be written to disk.
	 * @param use_layer_indices The folder for each layer will be that of its index in the LayerSet, which is guaranteed to be unique.
	 *
	 * @return The watcher thread, for joining purposes, or null if the dialog is canceled or preconditions are not passed.
	 * @throws IllegalArgumentException if the type is not ImagePlus.GRAY8 or Imageplus.COLOR_RGB.
	 */
	static public Bureaucrat makePrescaledTiles(final Layer[] layers, final Class<?> clazz, final Rectangle srcRect,
			final int c_alphas, final int type, String target_dir, final int strategy, final Saver saver, final int tileSide,
			final int directory_structure_type, final boolean skip_empty_tiles, final boolean use_layer_indices, final int n_threads)
	{
		// Check preconditions
		if (null == layers || 0 == layers.length) return null;
		
		switch (type) {
		case ImagePlus.GRAY8:
		case ImagePlus.COLOR_RGB:
			break;
		default:
			throw new IllegalArgumentException("Can only export for web with 8-bit or RGB");
		}
		
		// Choose target directory
		if (null == target_dir) {
			final DirectoryChooser dc = new DirectoryChooser("Choose target directory");
			target_dir = dc.getDirectory();
			if (null == target_dir) return null;
		}

		final String dir = Utils.fixDir(target_dir);
		final Worker worker;

		try {
			// Figure out layer indices, given that layers are not necessarily evenly spaced or in order
			final TreeMap<Integer,Layer> indices = new TreeMap<Integer,Layer>();
			final ArrayList<Integer> missingIndices = new ArrayList<Integer>();
			final double resolution_z_px;
			final int smallestIndex, largestIndex;
			if (1 == layers.length) {
				indices.put(0, layers[0]);
				resolution_z_px = layers[0].getZ();
				smallestIndex = 0;
				largestIndex = 0;
			} else {
				// Ensure layers are sorted by Z index and are unique pointers and unique in Z coordinate:
				final TreeMap<Double,Layer> t = new TreeMap<Double,Layer>();
				for (final Layer l1 : new HashSet<Layer>(Arrays.asList(layers))) {
					final Layer l2 = t.get(l1.getZ());
					if (null == l2) {
						t.put(l1.getZ(), l1);
					} else {
						// Ignore the layer with less objects
						if (l1.getDisplayables().size() > l2.getDisplayables().size()) {
							t.put(l1.getZ(), l1);
							Utils.log("Ignoring duplicate layer: " + l2);
						}
					}
				}

				// What is the mode thickness, measured by Z(i-1) - Z(i)?
				// (Distance between the Z of two consecutive layers)
				final HashMap<Double,Integer> counts = new HashMap<Double,Integer>();
				final Layer prev = t.get(t.firstKey());
				double modeThickness = 0;
				int modeThicknessCount = 0;
				for (final Layer la : t.tailMap(prev.getZ(), false).values()) {
					// Thickness with 3-decimal precision only
					final double d = ((int)((la.getZ() - prev.getZ()) * 1000 + 0.5)) / 1000.0 ;
					Integer c = counts.get(d);
					//
					if (null == c) c = 0;
					++c;
					counts.put(d, c);
					//
					if (c > modeThicknessCount) {
						modeThicknessCount = c;
						modeThickness = d;
					}
				}
				resolution_z_px = modeThickness * prev.getParent().getCalibration().pixelWidth; // Not pixelDepth
				// Assign an index to each layer, approximating each layer at modeThickness intervals
				for (final Layer la : t.values()) {
					indices.put((int)(la.getZ() / modeThickness + 0.5), la);
				}
				// First and last
				smallestIndex = indices.firstKey();
				largestIndex = indices.lastKey();
				Utils.logAll("indices: " + smallestIndex + ", " + largestIndex);
				// Which indices are missing?
				for (int i=smallestIndex+1; i<largestIndex; ++i) {
					if (! indices.containsKey(i)) {
						missingIndices.add(i);
					}
				}
			}

			// JSON metadata for CATMAID
			{
				final StringBuilder sb = new StringBuilder("{");
				final LayerSet ls = layers[0].getParent();
				final Calibration cal = ls.getCalibration();
				sb.append("\"volume_width_px\": ").append(srcRect.width).append(',').append('\n')
				.append("\"volume_height_px\": ").append(srcRect.height).append(',').append('\n')
				.append("\"volume_sections\": ").append(largestIndex - smallestIndex + 1).append(',').append('\n')
				.append("\"extension\": \"").append(saver.getExtension()).append('\"').append(',').append('\n')
				.append("\"resolution_x\": ").append(cal.pixelWidth).append(',').append('\n')
				.append("\"resolution_y\": ").append(cal.pixelHeight).append(',').append('\n')
				.append("\"resolution_z\": ").append(resolution_z_px).append(',').append('\n')
				.append("\"units\": \"").append(cal.getUnit()).append('"').append(',').append('\n')
				.append("\"offset_x_px\": 0,\n")
				.append("\"offset_y_px\": 0,\n")
				.append("\"offset_z_px\": ").append(indices.get(indices.firstKey()).getZ() * cal.pixelWidth / cal.pixelDepth).append(',').append('\n')
				.append("\"missing_layers\": [");
				for (final Integer i : missingIndices) sb.append(i - smallestIndex).append(',');
				sb.setLength(sb.length()-1); // remove last comma
				sb.append("]}");
				if (!Utils.saveToFile(new File(dir + "metadata.json"), sb.toString())) {
					Utils.logAll("WARNING: could not save " + dir + "metadata.json\nThe contents was:\n" + sb.toString());
				}
			}
			
			switch (strategy) {
			case 0:
				worker = exportFromOriginals(indices, smallestIndex, dir, saver, srcRect, c_alphas, type, clazz, tileSide,
						directory_structure_type, use_layer_indices, skip_empty_tiles, Math.max(1, n_threads));
				break;
			case 1:
				worker = exportFromMipMaps(indices, smallestIndex, dir, saver, srcRect, c_alphas, type, clazz, tileSide,
						directory_structure_type, use_layer_indices, skip_empty_tiles, Math.max(1, n_threads));
				break;
			case 2:
				worker = exportFromMipMapsLayerWise(indices, smallestIndex, dir, saver, srcRect, c_alphas, type, clazz, tileSide,
						directory_structure_type, use_layer_indices, skip_empty_tiles, Math.max(1, n_threads));
				break;
			default:
				Utils.log("Unknown strategy: " + strategy);
				return null;
			}

			// watcher thread
			return Bureaucrat.createAndStart(worker, layers[0].getProject());

		} catch (final Exception e) {
			IJError.print(e);
		}
		
		return null;
	}
	
	static public Worker exportFromMipMaps(
			final TreeMap<Integer, Layer> indices,
			final int smallestIndex,
			final String dir,
			final Saver saver,
			final Rectangle srcRect,
			final int c_alphas,
			final int type, Class<?> clazz,
			final int tileSide,
			final int directory_structure_type,
			final boolean use_layer_indices,
			final boolean skip_empty_tiles,
			final int n_threads)
	{
		return new Worker("Creating prescaled tiles from mipmaps")
		{
			private void cleanUp() {
				finishedWorking();
			}

			@Override
            public void run() {
				startedWorking();
				
				final int n_procs = Math.max(1, n_threads);
				final ExecutorService exec = Utils.newFixedThreadPool(Math.max(1, n_threads), "export-for-web::mipmaps");
				final LinkedList<Future<?>> futures = new LinkedList<Future<?>>();
				
				try {
					// start with the highest scale level
					final int[] best = determineClosestPowerOfTwo(srcRect.width > srcRect.height ? srcRect.width : srcRect.height);
					final int edge_length = best[0];
					final int n_edge_tiles = edge_length / tileSide;
					Utils.log2("srcRect: " + srcRect);
					Utils.log2("edge_length, n_edge_tiles, best[1] " + best[0] + ", " + n_edge_tiles + ", " + best[1]);


					// thumbnail dimensions
					final double ratio = srcRect.width / (double)srcRect.height;
					final double thumb_scale;
					if (ratio >= 1) {
						// width is larger or equal than height
						thumb_scale = 192.0 / srcRect.width;
					} else {
						thumb_scale = 192.0 / srcRect.height;
					}
					
					for (final Map.Entry<Integer,Layer> entry : indices.entrySet()) {
						if (this.quit) {
							finishedWorking();
							return;
						}
						final Layer layer = entry.getValue();
						final int index = use_layer_indices ? layer.getParent().indexOf(layer) : entry.getKey() - smallestIndex;

						// 1 - create a directory 'z' named as the layer's index
						if (!Utils.ensure(dir + index)) {
							cleanUp();
							Utils.log("Cannot write to the desired directory: " + dir + index + "/");
							return;
						}

						// 3 - fill directory with tiles
						if (edge_length < tileSide) { // edge_length is the largest length of the tileSide x tileSide tile map that covers an area equal or larger than the desired srcRect (because all tiles have to be tileSide x tileSide in size)
							// create single tile per layer
							makeTileRunnable(layer, srcRect, 1.0, c_alphas, type, clazz, makeTilePath(directory_structure_type, dir, index, 0, 0, 0), saver, tileSide, tileSide, skip_empty_tiles, true).run();
						} else {
							// From mipmaps
							double scale = 1;
							int scale_pow = 0;
							int n_et = n_edge_tiles; // cached for local modifications in the loop, works as loop controler
							while (n_et >= best[1]) { // best[1] is the minimal root found, i.e. 1,2,3,4,5 from which then powers of two were taken to make up for the edge_length
								final int tile_side = (int)(tileSide / scale); // 0 < scale <= 1, so no precision lost
								for (int row=0; row<n_et; row++) {
									for (int col=0; col<n_et; col++) {
										final int i_tile = row * n_et + col;
										Utils.showProgress(i_tile /  (double)(n_et * n_et));

										if (0 == i_tile % 100) {
											layer.getProject().getLoader().releaseToFit(tile_side * tile_side * 4 * 2); // RGB int[] images
										}

										if (this.quit) {
											cleanUp();
											return;
										}
										final Rectangle tile_src = new Rectangle(srcRect.x + tile_side*col,
												srcRect.y + tile_side*row,
												tile_side,
												tile_side); // in absolute coords, magnification later.
										// crop bounds
										if (tile_src.x + tile_src.width > srcRect.x + srcRect.width) tile_src.width = srcRect.x + srcRect.width - tile_src.x;
										if (tile_src.y + tile_src.height > srcRect.y + srcRect.height) tile_src.height = srcRect.y + srcRect.height - tile_src.y;
										// negative tile sizes will be made into black tiles
										// (negative dimensions occur for tiles beyond the edges of srcRect, since the grid of tiles has to be of equal number of rows and cols)
										
										// Avoid filling up RAM with awaiting tasks
										while (futures.size() > n_procs * 10) {
											if (this.quit) {
												cleanup();
												return;
											}
											futures.pop().get();
										}
										final Runnable task = makeTileRunnable(layer, tile_src, scale, c_alphas, type, clazz, makeTilePath(directory_structure_type, dir, index, row, col, scale_pow), saver, tileSide, tileSide, skip_empty_tiles, true);
										futures.add(exec.submit(task));
									}
								}
								scale_pow++;
								scale = 1 / Math.pow(2, scale_pow); // works as magnification
								n_et /= 2;
							}
							
							// Create layer thumbnail, max 192x192
							futures.add(exec.submit(makeTileRunnable(layer, srcRect, thumb_scale, c_alphas, type, clazz, dir + index + "/small", saver, 192, 192, false, false)));
							
						}
					}
					
					Utils.wait(futures);
				} catch (final Exception e) {
					IJError.print(e);
				} finally {
					exec.shutdown();
					Utils.showProgress(1);
				}
				cleanUp();
				finishedWorking();

			}// end of run method
		};
	}
	
	static private class ExportLayerTiles implements Runnable {

		private Layer layer;
		private int index;
		private String dir;
		private Rectangle srcRect;
		private int type;
		private int c_alphas;
		private int[] best;
		private Area area_srcRect;
		private boolean skip_empty_tiles;
		private int n_edge_tiles;
		private int tileSide;
		private int directory_structure_type;
		private Saver saver;

		private ExportLayerTiles(
				final Layer layer,
				final int index,
				final String dir,
				final Rectangle srcRect,
				final int type, // ImagePlus.GRAY8 or COLOR_RGB
				final int c_alphas,
				final int[] best,
				final Area area_srcRect,
				final boolean skip_empty_tiles,
				final int n_edge_tiles,
				final int tileSide,
				final int directory_structure_type,
				final Saver saver
				)
		{
			this.layer = layer;
			this.index = index;
			this.dir = dir;
			this.srcRect = srcRect;
			this.type = type;
			this.c_alphas = c_alphas;
			this.best = best;
			this.area_srcRect = area_srcRect;
			this.skip_empty_tiles = skip_empty_tiles;
			this.n_edge_tiles = n_edge_tiles;
			this.tileSide = tileSide;
			this.directory_structure_type = directory_structure_type;
			this.saver = saver;
		}
		
		/** Will flush the prior_snapshot. */
		private final ImageProcessor strategySnapshot(
				final ImageProcessor prior_snapshot,
				final List<Patch> patches,
				final double scale,
				final int scale_pow)
		{
			// Acquire image of whole srcRect
			final ImageProcessor snapshot;
			if (null != prior_snapshot) {
				snapshot = Downsampler.downsampleImageProcessor(prior_snapshot);
				prior_snapshot.setPixels(null); // flush
			} else {
				snapshot = layer.getProject().getLoader().getFlatImage(layer, srcRect, scale, c_alphas, type, Patch.class, patches, false, Color.black).getProcessor();
			}
			// Iterate tiles
			final Rectangle tile_src = new Rectangle(0, 0, tileSide, tileSide);
			for (int i = 0, row = 0; i < snapshot.getHeight(); i += tileSide, ++row) {
				for (int j = 0, col = 0; j < snapshot.getWidth(); j += tileSide, ++col) {
					final String path = makeTilePath(directory_structure_type, dir, index, row, col, scale_pow);
					// The srcRect for the tile
					tile_src.x = tileSide * col;
					tile_src.y = tileSide * row;
					snapshot.setRoi(tile_src);
					ImageProcessor ip = snapshot.crop();
					// Adjust dimensions: necessary for croppings over the edges of the snapshot
					if (ip.getWidth() < tileSide || ip.getHeight() < tileSide) {
						ImageProcessor ip2 = ip.createProcessor(tileSide, tileSide);
						ip2.insert(ip, 0, 0);
						ip.setPixels(null); // flush
						ip = ip2;
						ip2 = null;
					}
					if (skip_empty_tiles && isEmptyTile(ip)) continue;
					ImagePlus imp = new ImagePlus(path.substring(path.lastIndexOf("/")), ip);
					saver.save(imp, path);
					imp.flush();
					ip = null;
					imp = null;
				}
			}
			return snapshot;
		}
		
		private void strategyPatches(
				final List<Patch> patches,
				final Map<Patch,Set<Patch>> overlaps,
				final double scale,
				final int scale_pow)
		{
			// Tile side at this scale level, in 100% scale coordinates
			// (So if tileSide starts at 1024 for level 0, will be 2048 for level 1, 4096 for level 2, etc.)
			final int tile_side = (int)(tileSide / scale); // 0 < scale <= 1, so no precision lost
			// Keep track of completed tiles
			final HashSet<II> done = new HashSet<II>();
			if (patches.size() > 0) {
				// Area over 1 GB: generate tiles Patch-wise
				// Process one Patch at a time, continue with the Patch instances that overlap with it
				// to minimize the loading of mipmaps
				final LinkedList<Patch> stack = new LinkedList<Patch>();
				final HashSet<Patch> pending = new HashSet<Patch>(patches);
				stack.add(patches.get(0));
				pending.remove(patches.get(0));
				while (stack.size() > 0) {
					final Patch patch = stack.removeFirst(); // pop
					// Patch bounds relative to the srcRect
					final Rectangle bounds = patch.getBoundingBox();
					bounds.x -= srcRect.x;
					bounds.y -= srcRect.y;
					// Coordinates in "grid" tile indices within the srcRect
					final int gx0 = Math.max(0,                           bounds.x                  / tile_side),
							  gy0 = Math.max(0,                           bounds.y                  / tile_side),
							  gx1 = Math.min(srcRect.width  / tile_side, (bounds.x + bounds.width)  / tile_side),
							  gy1 = Math.min(srcRect.height / tile_side, (bounds.y + bounds.height) / tile_side);
					// Iterate in tile coordinate space over the Patch bounds
					for (int row = gy0; row <= gy1; ++row) {
						for (int col = gx0; col <= gx1; ++col) {
							// Make tile at indices [row, col] if not done yet
							final II coord = new II(row, col);
							if (done.contains(coord)) continue;
							// The srcRect for the tile
							final Rectangle tile_src = new Rectangle(
									srcRect.x + tile_side * col,
									srcRect.y + tile_side * row,
									tile_side,
									tile_side); // in absolute coords, magnification later.
							// Crop bounds to within srcRect (tile will be enlarged prior to saving, padding with black)
							if (tile_src.x + tile_src.width > srcRect.x + srcRect.width) tile_src.width = srcRect.x + srcRect.width - tile_src.x;
							if (tile_src.y + tile_src.height > srcRect.y + srcRect.height) tile_src.height = srcRect.y + srcRect.height - tile_src.y;
							// Write tile
							final String path = makeTilePath(directory_structure_type, dir, index, row, col, scale_pow);
							//System.out.println("   writing tile for " + tile_src + " with path " + path.substring(path.lastIndexOf("/") + 1));
							makeTileRunnable(layer, tile_src, scale, c_alphas, type, Patch.class,
									path, saver, tileSide, tileSide, skip_empty_tiles, true)
							.run();
							done.add(coord);
						}
					}
					// Continue from overlapping Patch instances, if any haven't been processed yet
					for (final Patch p : overlaps.get(patch)) {
						if (pending.remove(p)) stack.add(p);
					}
					// Some Patch instances may not overlap any others
					if (stack.isEmpty() && !pending.isEmpty()) {
						final Iterator<Patch> it = pending.iterator();
						stack.add(it.next());
						it.remove();
					}
				}
			}
			// Write missing black tiles if requested
			if (!skip_empty_tiles) {
				Path first_path = null;
				for (int i = 0, row = 0; i<srcRect.height; i += tile_side, ++row) {
					for (int j = 0, col = 0; j<srcRect.width; j += tile_side, ++col) {
						final II coord = new II(row, col);
						if (done.contains(coord)) continue;
						// Else, write black tile
						final String path = makeTilePath(directory_structure_type, dir, index, row, col, scale_pow) + saver.getExtension();
						if (null == first_path) {
							first_path = new File(path).toPath();
							final ImagePlus black = new ImagePlus("black", new ByteProcessor(tileSide, tileSide));
							saver.save(black, path);
							black.flush();
						} else {
							try {
								Files.copy(first_path, new File(path).toPath(), StandardCopyOption.REPLACE_EXISTING);
							} catch (IOException e1) {
								e1.printStackTrace();
							}
						}
					}
				}
			}
		}
		
		@Override
		public void run() {
			try {
				// Visible Patch instances
				final List<Patch> patches = layer.getPatches(true);
				// Filter: keep those that intersect the srcRect
				for (final Iterator<Patch> it = patches.iterator(); it.hasNext(); ) {
					if (!M.intersects(new Area(it.next().getPerimeter(1, 1, 1, 1)), area_srcRect)) {
						it.remove();
					}
				}
				
				if (0 == patches.size() && skip_empty_tiles) {
					// Done with this Layer
					Utils.log2("Skipping empty layer " + layer + " at index " + index);
					return;
				}

				final Map<Patch, Set<Patch>> overlaps = getOverlaps(patches);

				// When under 1 GB, use whole-srcRect snapshots
				ImageProcessor snapshot = null;

				// Variables that change at every scale level
				double scale = 1;
				int scale_pow = 0;
				int n_et = n_edge_tiles; // cached for local modifications in the loop, works as loop controller
				
				// Every iteration generates tile for one level, starting at 0
				while (n_et >= best[1]) { // best[1] is the minimal root found, i.e. 1,2,3,4,5 from which then powers of two were taken to make up for the edge_length
					// Check if area under 1 GB: if so, switch strategy
					if (null != snapshot || (srcRect.width * scale) * (srcRect.height * scale) < Math.pow(2,  30)) { // careful with integer overflows
						// Dispose of data no longer needed
						overlaps.clear();
						// Snapshot whole srcRect and go from there
						snapshot = strategySnapshot(snapshot, patches, scale, scale_pow);
					} else {
						// Patch-wise
						strategyPatches(patches, overlaps, scale, scale_pow);
					}
					// Remove unneeded mipmaps from the cache: the ones from the level before this one
					// (The ones from this level may be used to draw the snapshot when changing strategies)
					if (scale_pow > 0) {
						for (final Patch patch : patches) {
							patch.getProject().getLoader().removeCached(patch.getId(), scale_pow -1);
						}
					}
					// Prepare next scale level
					scale_pow++;
					scale = 1 / Math.pow(2, scale_pow); // works as magnification
					n_et /= 2;
				}
				
				// Flush last snapshot
				if (null != snapshot) snapshot.setPixels(null);

				// Remove unneeded mipmaps from the cache: all for this Layer if any left to throw out
				for (final Patch patch : patches) {
					patch.getProject().getLoader().removeCached(patch.getId());
				}
				
				System.out.println("COMPLETED layer at index " + index);
			} catch (Throwable t) {
				System.out.println("FAILED at exporting tiles for web for layer " + layer + " at index " + index);
				t.printStackTrace();
			}
		}
	}
	
	/** When I/O limited, optimize parallel mipmap loading (so that no Thread is waiting on any other Thread to finish loading a mipmap)
	 *  by generating tiles Patch-wise, processing one Patch at a time, and one Layer per Thread. */
	static public Worker exportFromMipMapsLayerWise (
			final TreeMap<Integer, Layer> indices,
			final int smallestIndex,
			final String dir,
			final Saver saver,
			final Rectangle srcRect,
			final int c_alphas,
			final int type, Class<?> clazz,
			final int tileSide,
			final int directory_structure_type,
			final boolean use_layer_indices,
			final boolean skip_empty_tiles,
			final int n_threads)
	{
		return new Worker("Creating prescaled tiles from mipmaps layer-wise")
		{
			private void cleanUp() {
				finishedWorking();
			}

			@Override
            public void run() {
				startedWorking();
				
				/* // DEBUG memory leak
				indices.firstEntry().getValue().getProject().getLoader().releaseAll();
				System.gc();
				final long startMem = Runtime.getRuntime().freeMemory();
				System.out.println("INITIAL MEM: " + startMem);
				*/
				
				try {
					// When using mipmaps, run in parallel (uses same number of threads as for generating mipmaps)
					final int n_procs = Math.max(1, n_threads);
					final ExecutorService exec = Utils.newFixedThreadPool(Math.max(1, n_threads), "export-for-web::mipmaps-layer-wise", false);
					final LinkedList<Future<?>> futures = new LinkedList<Future<?>>();

					// Dimensions by number of tiles at scale 1.0
					final int[] best = determineClosestPowerOfTwo(srcRect.width > srcRect.height ? srcRect.width : srcRect.height);
					final int edge_length = best[0];
					final int n_edge_tiles = edge_length / tileSide;

					final Area area_srcRect = new Area(srcRect);

					for (final Map.Entry<Integer, Layer> e : indices.entrySet()) {
						final Layer layer = e.getValue();
						final int index = use_layer_indices ? layer.getParent().indexOf(layer) : (e.getKey() - smallestIndex); // for writing the folder name
						futures.add(exec.submit(new ExportLayerTiles(layer, index, dir, srcRect, type, c_alphas,
								best, area_srcRect, skip_empty_tiles, n_edge_tiles, tileSide, directory_structure_type, saver)));
						
						while (futures.size() > n_procs * 10) {
							futures.pop().get();
						}
					}

					Utils.wait(futures);
					exec.shutdown();

				} catch (Throwable t) {
					IJError.print(t);
					t.printStackTrace();
				} finally {
					cleanUp();
					finishedWorking();
				}
				
				/* TEST
				indices.firstEntry().getValue().getProject().getLoader().releaseAll();
				System.gc();
				final long endMem = Runtime.getRuntime().freeMemory();
				System.out.println("ENDING MEM: " + endMem + ", INC: " + (endMem - startMem));
				*/

			}// end of run method
		};
	}
	
	static private final class II {
		final private int a, b;
		public II(final int a, final int b) {
			this.a = a;
			this.b = b;
		}
		@Override
		public final int hashCode() {
			int hash = 17;
			hash = hash * 31 + a;
			hash = hash * 31 + b;
			return hash;
		}
		@Override
		public final boolean equals(final Object o) {
			if (o.getClass() == II.class) {
				final II other = (II)o;
				return other.a == this.a && other.b == this.b;
			}
			return false;
		}
	}
	
	/** Overlapping by bounding box, that is, roughly. */
	static private final Map<Patch, Set<Patch>> getOverlaps(final List<Patch> patches) {
		final Map<Patch, Set<Patch>> table = new HashMap<Patch, Set<Patch>>();
		for (final Patch p : patches) {
			table.put(p, new HashSet<Patch>());
		}
		for (int i=0; i<patches.size(); i++) {
			final Patch p1 = patches.get(i);
			final Area b1 = new Area(p1.getPerimeter(10, 10, 10, 10)); // padded out by 10 px
			final Set<Patch> s1 = table.get(p1);
			for (int j=i+1; j<patches.size(); ++j) {
				final Patch p2 = patches.get(j);
				// Very sloppy
				//if (p1.intersects(p2, true)) {
				//	s1.add(p2);
				//	table.get(p2).add(p1);
				//}
				// Less sloppy, still fast. Will be very accurate in most cases
				final Area b2 = new Area(p2.getPerimeter(10, 10, 10, 10)); // padded out by 10 px
				if (M.intersects(b1, b2)) {
					s1.add(p2);
					table.get(p2).add(p1);
				}
			}
		}
		return table;
	}
	
	static public Worker exportFromOriginals(
			final TreeMap<Integer, Layer> indices,
			final int smallestIndex,
			final String dir,
			final Saver saver,
			final Rectangle srcRect,
			final int c_alphas,
			final int type, Class<?> clazz,
			final int tileSide,
			final int directory_structure_type,
			final boolean use_layer_indices,
			final boolean skip_empty_tiles,
			final int n_threads)
	{	
		return new Worker("Creating prescaled tiles from original images") {
			private void cleanUp() {
				finishedWorking();
			}
			@Override
            public void run() {
				startedWorking();
				try {
					
					// start with the highest scale level
					final int[] best = determineClosestPowerOfTwo(srcRect.width > srcRect.height ? srcRect.width : srcRect.height);
					final int edge_length = best[0];
					final int n_edge_tiles = edge_length / tileSide;
					Utils.log2("srcRect: " + srcRect);
					Utils.log2("edge_length, n_edge_tiles, best[1] " + best[0] + ", " + n_edge_tiles + ", " + best[1]);


					// thumbnail dimensions
					final double ratio = srcRect.width / (double)srcRect.height;
					final double thumb_scale;
					if (ratio >= 1) {
						// width is larger or equal than height
						thumb_scale = 192.0 / srcRect.width;
					} else {
						thumb_scale = 192.0 / srcRect.height;
					}
					
					for (final Map.Entry<Integer,Layer> entry : indices.entrySet()) {
						if (this.quit) {
							finishedWorking();
							return;
						}
						final Layer layer = entry.getValue();
						final int index = use_layer_indices ? layer.getParent().indexOf(layer) : entry.getKey() - smallestIndex;

						// 1 - create a directory 'z' named as the layer's index
						if (!Utils.ensure(dir + index)) {
							cleanUp();
							Utils.log("Cannot write to the desired directory: " + dir + index + "/");
							return;
						}

						// 3 - fill directory with tiles
						if (edge_length < tileSide) { // edge_length is the largest length of the tileSide x tileSide tile map that covers an area equal or larger than the desired srcRect (because all tiles have to be tileSide x tileSide in size)
							// create single tile per layer
							makeTileRunnable(layer, srcRect, 1.0, c_alphas, type, clazz, makeTilePath(directory_structure_type, dir, index, 0, 0, 0), saver, tileSide, tileSide, skip_empty_tiles, true).run();
						} else {
							// Create pyramid of tiles
							// Create layer thumbnail, max 192x192
							{
								ImagePlus thumb = layer.getProject().getLoader().getFlatImage(layer, srcRect, thumb_scale, c_alphas, type, clazz, true);
								saver.save(thumb, dir + index + "/small");
								Loader.flush(thumb);
								thumb = null;
							}

							Utils.log("Exporting from web using original images");
							// Create a giant 8-bit image of the whole layer from original images
							double scale = 1;

							Utils.log("Export srcRect: " + srcRect);

							// WARNING: the snapshot will most likely be smaller than the virtual square image being chopped into tiles

							ImageProcessor snapshot = null;

							if (ImagePlus.COLOR_RGB == type) {
								Utils.log("WARNING: ignoring alpha masks for 'use original images' and 'RGB color' options");
								snapshot = Patch.makeFlatImage(type, layer, srcRect, scale, layer.getPatches(true), Color.black, true);
							} else if (ImagePlus.GRAY8 == type) {
								// Respect alpha masks and display range:
								Utils.log("WARNING: ignoring scale for 'use original images' and '8-bit' options");
								snapshot = ExportUnsignedShort.makeFlatImage(layer.getPatches(true), srcRect, 0).convertToByte(true);
							} else {
								Utils.log("ERROR: don't know how to generate mipmaps for type '" + type + "'");
								cleanUp();
								return;
							}

							int scale_pow = 0;
							int n_et = n_edge_tiles;
							final ExecutorService exe = Utils.newFixedThreadPool(Math.max(1, n_threads), "export-for-web::original-images");
							final ArrayList<Future<?>> fus = new ArrayList<Future<?>>();
							try {
								while (n_et >= best[1]) {
									final int snapWidth = snapshot.getWidth();
									final int snapHeight = snapshot.getHeight();
									final ImageProcessor source = snapshot;
									for (int row=0; row<n_et; row++) {
										for (int col=0; col<n_et; col++) {

											final String path = makeTilePath(directory_structure_type, dir, index, row, col, scale_pow);
											// new StringBuilder(tile_dir).append(row).append('_').append(col).append('_').append(scale_pow).toString();
											final int tileXStart = col * tileSide;
											final int tileYStart = row * tileSide;
											final int pixelOffset = tileYStart * snapWidth + tileXStart;

											fus.add(exe.submit(new Callable<Boolean>() {
												@Override
												public Boolean call() {
													if (ImagePlus.GRAY8 == type) {
														final byte[] pixels = (byte[]) source.getPixels();
														final byte[] p = new byte[tileSide * tileSide];

														for (int y=0, sourceIndex=pixelOffset; y < tileSide && tileYStart + y < snapHeight; sourceIndex = pixelOffset + y * snapWidth, y++) {
															final int offsetL = y * tileSide;
															for (int x=0; x < tileSide && tileXStart + x < snapWidth; sourceIndex++, x++) {
																p[offsetL + x] = pixels[sourceIndex];
															}
														}
														final ByteProcessor bp = new ByteProcessor(tileSide, tileSide, p, Loader.GRAY_LUT);
														if (!skip_empty_tiles || !isEmptyTile(bp)) {
															return saver.save(new ImagePlus(path, bp), path);
														}
													} else {
														final int[] pixels = (int[]) source.getPixels();
														final int[] p = new int[tileSide * tileSide];

														for (int y=0, sourceIndex=pixelOffset; y < tileSide && tileYStart + y < snapHeight; sourceIndex = pixelOffset + y * snapWidth, y++) {
															final int offsetL = y * tileSide;
															for (int x=0; x < tileSide && tileXStart + x < snapWidth; sourceIndex++, x++) {
																p[offsetL + x] = pixels[sourceIndex];
															}
														}
														final ColorProcessor cp = new ColorProcessor(tileSide, tileSide, p);
														if (!skip_empty_tiles || !isEmptyTile(cp)) {
															return saver.save(new ImagePlus(path, cp), path);
														}
													}
													return false;
												}
											}));
										}
									}
									//
									scale_pow++;
									scale = 1 / Math.pow(2, scale_pow); // works as magnification
									n_et /= 2;
									//
									Utils.wait(fus);
									fus.clear();
									// Scale snapshot in half with area averaging
									final ImageProcessor nextSnapshot;
									if (ImagePlus.GRAY8 == type) {
										nextSnapshot = new ByteProcessor((int)(srcRect.width * scale), (int)(srcRect.height * scale));
										final byte[] p1 = (byte[]) snapshot.getPixels();
										final byte[] p2 = (byte[]) nextSnapshot.getPixels();
										final int width1 = snapshot.getWidth();
										final int width2 = nextSnapshot.getWidth();
										final int height2 = nextSnapshot.getHeight();
										int i = 0;
										for (int y1=0, y2=0; y2 < height2; y1 += 2, y2++) {
											final int offset1a = y1 * width1;
											final int offset1b = (y1 + 1) * width1;
											for (int x1=0, x2=0; x2 < width2; x1 += 2, x2++) {
												p2[i++] = (byte)( (   (p1[offset1a + x1] & 0xff) + (p1[offset1a + x1 + 1] & 0xff)
														+ (p1[offset1b + x1] & 0xff) + (p1[offset1b + x1 + 1] & 0xff) ) /4 );
											}
										}
									} else {
										nextSnapshot = new ColorProcessor((int)(srcRect.width * scale), (int)(srcRect.height * scale));
										final int[] p1 = (int[]) snapshot.getPixels();
										final int[] p2 = (int[]) nextSnapshot.getPixels();
										final int width1 = snapshot.getWidth();
										final int width2 = nextSnapshot.getWidth();
										final int height2 = nextSnapshot.getHeight();
										int i = 0;
										for (int y1=0, y2=0; y2 < height2; y1 += 2, y2++) {
											final int offset1a = y1 * width1;
											final int offset1b = (y1 + 1) * width1;
											for (int x1=0, x2=0; x2 < width2; x1 += 2, x2++) {
												final int ka = p1[offset1a + x1],
														kb = p1[offset1a + x1 + 1],
														kc = p1[offset1b + x1],
														kd = p1[offset1b + x1 + 1];
												// Average each channel independently
												p2[i++] =
														(((   ((ka >> 16) & 0xff)        // red
																+ ((kb >> 16) & 0xff)
																+ ((kc >> 16) & 0xff)
																+ ((kd >> 16) & 0xff) ) / 4) << 16)
														+ (((   ((ka >> 8) & 0xff)         // green
																+ ((kb >> 8) & 0xff)
																+ ((kc >> 8) & 0xff)
																+ ((kd >> 8) & 0xff) ) / 4) << 8)
														+ (   (ka & 0xff)                // blue
																+ (kb & 0xff)
																+ (kc & 0xff)
																+ (kd & 0xff) ) / 4;
											}
										}
									}
									// Assign for next iteration
									snapshot = nextSnapshot;

									// Scale snapshot with a TransformMesh
									/*
							AffineModel2D aff = new AffineModel2D();
							aff.set(0.5f, 0, 0, 0.5f, 0, 0);
							ImageProcessor scaledSnapshot = new ByteProcessor((int)(snapshot.getWidth() * scale), (int)(snapshot.getHeight() * scale));
							final CoordinateTransformMesh mesh = new CoordinateTransformMesh( aff, 32, snapshot.getWidth(), snapshot.getHeight() );
							final mpicbg.ij.TransformMeshMapping<CoordinateTransformMesh> mapping = new mpicbg.ij.TransformMeshMapping<CoordinateTransformMesh>( mesh );
							mapping.mapInterpolated(snapshot, scaledSnapshot, Runtime.getRuntime().availableProcessors());
							// Assign for next iteration
							snapshot = scaledSnapshot;
							snapshotPixels = (byte[]) scaledSnapshot.getPixels();
									 */
								}
							} catch (final Throwable t) {
								IJError.print(t);
							} finally {
								exe.shutdown();
							}
						}
					}
				} catch (final Exception e) {
					IJError.print(e);
				} finally {
					Utils.showProgress(1);
				}
				cleanUp();
				finishedWorking();

			}// end of run method
		};
	}
}
