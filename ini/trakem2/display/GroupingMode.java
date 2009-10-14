package ini.trakem2.display;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Paintable;
import ini.trakem2.display.graphics.GraphicsSource;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Worker;
import ini.trakem2.utils.IJError;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.awt.BasicStroke;
import java.awt.Image;
import java.awt.Composite;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import mpicbg.ij.Mapping;
import mpicbg.ij.TransformMeshMapping;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform;
import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;

public abstract class GroupingMode implements Mode {
	
	protected final Display display;
	protected final Layer layer;

	protected final Updater updater;
	protected final Painter painter;

	protected Rectangle srcRect;
	protected double magnification;

	protected final GroupedGraphicsSource gs;

	protected final List<Patch> originalPatches;
	private final List<PatchRange> ranges;
	protected final HashMap<Paintable, ScreenPatchRange> screenPatchRanges;

	public GroupingMode(final Display display, final List<Displayable> selected) {
		this.display = display;
		this.layer = display.getLayer();
		this.srcRect = ( Rectangle )display.getCanvas().getSrcRect().clone();
		this.magnification = display.getCanvas().getMagnification();
		this.originalPatches = new ArrayList<Patch>();
		this.ranges = new ArrayList<PatchRange>();
		final TreeMap<Integer,Patch> m = new TreeMap<Integer,Patch>();
		for (final Displayable d : selected) {
			if (d.getClass() == Patch.class) {
				m.put(layer.indexOf(d), (Patch) d);
			}
		}
		originalPatches.addAll(m.values());
		this.screenPatchRanges = new HashMap<Paintable, ScreenPatchRange>( originalPatches.size() );
		this.gs = createGroupedGraphicSource();
		this.updater = new Updater();
		this.painter = new Painter();
	}

	final protected void initThreads() {
		updater.start();
		painter.start();

		updater.update(); // will call painter.update()
	}

	final protected void quitThreads() {
		painter.quit();
		updater.quit();
	}

	protected abstract GroupedGraphicsSource createGroupedGraphicSource();

	protected abstract class GroupedGraphicsSource implements GraphicsSource {
		/** Returns the list given as argument without any modification. */
		public List<? extends Paintable> asPaintable(final List<? extends Paintable> ds) {
			final List<Paintable> newList = new ArrayList< Paintable >();

			final HashSet<ScreenPatchRange> used = new HashSet<ScreenPatchRange>();

			/* fill it */
			for ( final Paintable p : ds )
			{
				final ScreenPatchRange spr = screenPatchRanges.get( p );
				if ( spr == null )
					newList.add(p);
				else if (!used.contains(spr))
				{
					used.add(spr);
					newList.add( spr );
				}
			}
			return newList;
		}
	}

	static protected class PatchRange {
		final ArrayList< Patch > list = new ArrayList<Patch>();
		boolean starts_at_bottom = false;
		boolean is_gray = true;

		void addConsecutive(Patch p) {
			list.add(p);
			if (is_gray) {
				switch (p.getType()) {
					case ImagePlus.COLOR_RGB:
					case ImagePlus.COLOR_256:
						is_gray = false;
						break;
				}
			}
		}
		void setAsBottom() {
			this.starts_at_bottom = true;
		}
	}

	protected abstract ScreenPatchRange createScreenPathRange(final PatchRange range, final Rectangle srcRect, final double magnification);


	protected interface ScreenPatchRange<T> extends Paintable {
		public abstract void update( final T ob );
		public void flush();
	}

	/** Creates a list of patch ranges and calls painter.update() */
	private class Updater extends SimpleThread
	{
		void doit( final Rectangle r, final double m )
		{
			// 1 - Create the list of patch ranges

			// A list of Displayable to paint within the current srcRect
			final ArrayList<Displayable> to_paint = new ArrayList<Displayable>(layer.find(srcRect, true));

			PatchRange current_range = new PatchRange();
			ranges.clear();
			ranges.add(current_range);

			int last_i = -Integer.MAX_VALUE;

			byte last_b = Displayable.COMPOSITE_NORMAL;

			for (final Patch p : originalPatches) {
				final int i = to_paint.indexOf(p);
				final byte b = p.getCompositeMode();
				if (0 == i) {
					current_range.setAsBottom();
					current_range.addConsecutive(p);
				} else if (1 == i - last_i && b == Displayable.COMPOSITE_NORMAL && last_b == Displayable.COMPOSITE_NORMAL) {
					current_range.addConsecutive(p);
				} else {
					current_range = new PatchRange();
					ranges.add(current_range);
					current_range.addConsecutive(p);
				}
				last_i = i;
				last_b = b;
			}

			// 2 - Create the list of ScreenPatchRange, which are Paintable

			screenPatchRanges.clear();

			for (final PatchRange range : ranges) {
				if (0 == range.list.size()) continue;
				final ScreenPatchRange spr = createScreenPathRange(range, r, m);
				for (Patch p : range.list) {
					screenPatchRanges.put(p, spr);
				}
			}

			painter.update();
		}
	}

	/** @param r The srcRect
	 *  @param m The magnification */
	abstract protected void doPainterUpdate(Rectangle r, double m);

	protected class Painter extends SimpleThread
	{
		void doit( final Rectangle r, final double m )
		{
			try {
				doPainterUpdate(r, m);
			} catch (Throwable e) {
				IJError.print(e);
			}
			display.getCanvas().repaint( true );
		}
	}

	protected abstract class SimpleThread extends Thread
	{
		private boolean updateAgain = false;

		SimpleThread() {
			setPriority(Thread.NORM_PRIORITY);
			try { setDaemon(true); } catch (Exception e) {}
		}
		
		public void run()
		{
			while ( !isInterrupted() )
			{
				final boolean b;
				final Rectangle r;
				final double m;
				synchronized ( this )
				{
					b = updateAgain;
					updateAgain = false;
					r = ( Rectangle )srcRect.clone();
					m = magnification;
				}
				if ( b )
				{
					doit( r, m );
				}
				
				synchronized ( this )
				{
					try
					{
						if ( !updateAgain ) wait();
					}
					catch ( InterruptedException e ){}
				}
			}
		}
		
		abstract void doit( final Rectangle r, final double m );
		
		void update()
		{
			synchronized ( this )
			{
				updateAgain = true;
				notify();
			}
		}

		void quit()
		{
			interrupt();
			synchronized ( this )
			{
				updateAgain = false;
				notify();
			}
		}
	}

	public boolean canChangeLayer() { return false; }
	public boolean canZoom() { return true; }
	public boolean canPan() { return true; }

	private void updated( final Rectangle srcRect, double magnification )
	{
		if (
				this.srcRect.x == srcRect.x &&
				this.srcRect.y == srcRect.y &&
				this.srcRect.width == srcRect.width &&
				this.srcRect.height == srcRect.height &&
				this.magnification == magnification )
			return;
		
		this.srcRect = (Rectangle) srcRect.clone();
		this.magnification = magnification;
		
		updater.update();
	}

	public void srcRectUpdated( Rectangle srcRect, double magnification )
	{
		updated( srcRect, magnification );
	}
	public void magnificationUpdated( Rectangle srcRect, double magnification )
	{
		updated( srcRect, magnification );
	}

	public void redoOneStep() {}

	public void undoOneStep() {}

	public boolean cancel() {
		painter.quit();
		updater.quit();
		return true;
	}

	public Rectangle getRepaintBounds() { return (Rectangle) srcRect.clone(); }

	public GraphicsSource getGraphicsSource() {
		return gs;
	}

}

