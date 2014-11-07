package ini.trakem2.display;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.display.graphics.GraphicsSource;
import ini.trakem2.utils.IJError;

import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

public abstract class GroupingMode implements Mode {
	
	protected final Display display;
	protected final Layer layer;

	protected final Updater updater;
	protected final Painter painter;

	protected Rectangle srcRect;
	protected double magnification;

	protected final GroupedGraphicsSource gs;

	protected final List<Patch> originalPatches;
	protected final List<PatchRange> ranges;
	protected HashMap<Paintable, ScreenPatchRange<?>> screenPatchRanges;

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
		this.screenPatchRanges = new HashMap<Paintable, ScreenPatchRange<?>>( originalPatches.size() );
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

			final HashSet<ScreenPatchRange<?>> used = new HashSet<ScreenPatchRange<?>>();

			/* fill it */

			final HashMap<Paintable, ScreenPatchRange<?>> screenPatchRanges = GroupingMode.this.screenPatchRanges; // keep a pointer to the current list

			for ( final Paintable p : ds )
			{
				final ScreenPatchRange<?> spr = screenPatchRanges.get( p );
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

	protected abstract ScreenPatchRange<?> createScreenPathRange(final PatchRange range, final Rectangle srcRect, final double magnification);

	static protected abstract class ScreenPatchRange<T> implements Paintable {

		final ImageProcessor ip;
		final ImageProcessor ipTransformed;
		final FloatProcessor mask;
		final FloatProcessor maskTransformed;
		BufferedImage transformedImage;
		static final int pad = 100;
		final byte compositeMode;

		ScreenPatchRange( final PatchRange range, final Rectangle srcRect, final double magnification )
		{
			this.compositeMode = range.list.get(0).getCompositeMode();
			final BufferedImage image =
				new BufferedImage(
						( int )( srcRect.width * magnification + 0.5 ) + 2 * pad,
						( int )( srcRect.height * magnification + 0.5 ) + 2 * pad,
						range.starts_at_bottom ? range.is_gray ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB );

			Graphics2D g = image.createGraphics();
			final AffineTransform atc = new AffineTransform();
			atc.translate(pad, pad);
			atc.scale( magnification, magnification);
			atc.translate(-srcRect.x, -srcRect.y);
			g.setTransform( atc );
			for (final Patch patch : range.list) {
				patch.paint( g, srcRect, magnification, false, 0xffffffff, patch.getLayer(), null);
			}

			ip = new ImagePlus( "", image ).getProcessor();
			ipTransformed = ip.createProcessor( ip.getWidth(), ip.getHeight() );
			ipTransformed.snapshot();
			
			if (!range.starts_at_bottom) {
				final float[] pixels = new float[ ip.getWidth() * ip.getHeight() ];
				mask = new FloatProcessor( ip.getWidth(), ip.getHeight(), image.getAlphaRaster().getPixels( 0, 0, ip.getWidth(), ip.getHeight(), pixels ), null );
				maskTransformed = new FloatProcessor( ip.getWidth(), ip.getHeight() );
				maskTransformed.snapshot();
			}
			else
			{
				mask = null;
				maskTransformed = null;
			}

			image.flush();
			transformedImage = makeImage(ip, mask);
		}

		abstract public void update(T t);

		protected BufferedImage makeImage( final ImageProcessor ip, final FloatProcessor mask )
		{
			final BufferedImage transformedImage = new BufferedImage( ip.getWidth(), ip.getHeight(), null == mask ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB );
			final Image img = ip.createImage();
			transformedImage.createGraphics().drawImage( img, 0, 0, null );
			img.flush();
			if (null != mask) {
				transformedImage.getAlphaRaster().setPixels( 0, 0, ip.getWidth(), ip.getHeight(), ( float[] )mask.getPixels() );
			}
			return transformedImage;
		}

		public void flush() {
			if (null != transformedImage) transformedImage.flush();
		}

		@Override
		public void paint( Graphics2D g, Rectangle srcRect, double magnification, boolean active, int channels, Layer active_layer, List<Layer> layers)
		{
			final AffineTransform at = g.getTransform();
			final AffineTransform atp = new AffineTransform();
			
			atp.translate( -pad, -pad );
			g.setTransform( atp );
			Composite original_composite = null;
			if (Displayable.COMPOSITE_NORMAL != compositeMode) {
				original_composite = g.getComposite();
				g.setComposite(Displayable.getComposite(compositeMode, 1.0f));
			}
			g.drawImage( transformedImage, 0, 0, null );
			if (null != original_composite) {
				g.setComposite(original_composite);
			}
			g.setTransform( at );
		}

		@Override
		public void prePaint( Graphics2D g, Rectangle srcRect, double magnification, boolean active, int channels, Layer active_layer, List<Layer> layers)
		{
			paint( g, srcRect, magnification, active, channels, active_layer, layers );			
		}
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
			
			final HashMap<Paintable, ScreenPatchRange<?>> screenPatchRanges = new HashMap<Paintable, ScreenPatchRange<?>>( originalPatches.size() );

			for (final PatchRange range : ranges) {
				if (0 == range.list.size()) continue;
				final ScreenPatchRange<?> spr = createScreenPathRange(range, r, m);
				for (Patch p : range.list) {
					screenPatchRanges.put(p, spr);
				}
			}

			// swap old for new:
			GroupingMode.this.screenPatchRanges = screenPatchRanges;

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
		private volatile boolean updateAgain = false;

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

	@Override
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

