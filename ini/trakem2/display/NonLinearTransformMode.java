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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.awt.BasicStroke;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import mpicbg.ij.Mapping;
import mpicbg.ij.TransformMeshMapping;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

public class NonLinearTransformMode implements Mode {
	
	private class Updater extends SimpleThread
	{
		void doit( final Rectangle r, final double m )
		{
			// 1 - Create the list of patch ranges

			// A list of Displayable to paint within the current srcRect
			List<Displayable> to_paint = new ArrayList<Displayable>(display.getLayer().find(srcRect, true));

			PatchRange current_range = new PatchRange();
			ranges.clear();
			ranges.add(current_range);

			int last_i = -Integer.MAX_VALUE;

			for (final Patch p : originalPatches) {
				final int i = to_paint.indexOf(p);
				if (0 == i) {
					current_range.setAsBottom();
					current_range.addConsecutive(p);
				} else if (1 == i - last_i) {
					current_range.addConsecutive(p);
				} else {
					current_range = new PatchRange();
					ranges.add(current_range);
					current_range.addConsecutive(p);
				}
				last_i = i;
			}

			// 2 - Create the list of ScreenPatchRange, which are Paintable

			screenPatchRanges.clear();

			for (final PatchRange range : ranges) {
				final ScreenPatchRange spr = new ScreenPatchRange(range, r, m);
				for (Patch p : range.list) {
					screenPatchRanges.put(p, spr);
				}
			}

			painter.update();
		}
	}
	
	private class Painter extends SimpleThread
	{
		void doit( final Rectangle r, final double m )
		{
			Utils.showMessage("Updating...");
			try
			{
				final Collection< PointMatch > pm = new ArrayList<PointMatch>();
				for ( Map.Entry<P,P> e : points.entrySet() )
				{
					final P p = e.getValue();
					final P q = e.getKey();
					pm.add( new PointMatch(
							new Point( new float[]{ p.x + ScreenPatchRange.pad, p.y + ScreenPatchRange.pad } ),
							new Point( new float[]{ q.x + ScreenPatchRange.pad, q.y + ScreenPatchRange.pad } ) ) );
				}
				final TransformMeshMapping mapping;
				synchronized ( updater )
				{
					/*
					 * TODO replace this with the desired parameters of the transformation
					 */
					final MovingLeastSquaresTransform mlst = new MovingLeastSquaresTransform();
					mlst.setAlpha( 1.0f );
					Class< ? extends AbstractAffineModel2D< ? > > c = AffineModel2D.class;
					switch (points.size()) {
						case 1:
							c = TranslationModel2D.class;
							break;
						case 2:
							c = SimilarityModel2D.class;
							break;
						default:
							break;
					}
					mlst.setModel( c );
					mlst.setMatches( pm );
					final CoordinateTransformMesh ctm = new CoordinateTransformMesh( mlst, 32, r.width * ( float )m + 2 * ScreenPatchRange.pad, r.height * ( float )m + 2 * ScreenPatchRange.pad );
					mapping = new TransformMeshMapping( ctm );
				}
				
				for ( final ScreenPatchRange spr : screenPatchRanges.values())
				{
					spr.update( mapping );
				}
			}
			catch ( Exception e ) {}
			
			display.getCanvas().repaint( true );
			Utils.showMessage("");
		}
	}
	
	private abstract class SimpleThread extends Thread
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
	
	private class NonLinearTransformSource implements GraphicsSource {

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

		public void paintOnTop(final Graphics2D g, final Display display, final Rectangle srcRect, final double magnification) {
			
			final AffineTransform original = g.getTransform();
			g.setTransform(new AffineTransform());
			g.setStroke( new BasicStroke( 1.0f ) );
			for ( P p : points.keySet()) {
				//IJ.log( p.x + ", " + p.y );
				Utils.drawPoint( g, p.x, p.y );
			}
			g.setTransform(original);
		}
	}

	static private class PatchRange {
		final ArrayList<Patch> list = new ArrayList<Patch>();
		boolean starts_at_bottom = false;

		void addConsecutive(Patch p) {
			list.add(p);
		}
		void setAsBottom() {
			this.starts_at_bottom = true;
		}
	}

	static private class ScreenPatchRange implements Paintable {

		ImageProcessor ip;
		FloatProcessor mask;
		BufferedImage transformedImage;
		static final int pad = 100;

		ScreenPatchRange(final PatchRange range, final Rectangle srcRect, final double magnification) {
			final BufferedImage image = new BufferedImage((int)(srcRect.width * magnification + 0.5) + 2 * pad,
					                              (int)(srcRect.height * magnification + 0.5 ) + 2 * pad,
								      range.starts_at_bottom ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			final AffineTransform atc = new AffineTransform();
			atc.translate(pad, pad);
			atc.scale( magnification, magnification);
			atc.translate(-srcRect.x, -srcRect.y);
			g.setTransform( atc );
			for (final Patch patch : range.list) {
				patch.paint( g, magnification, false, 0xffffffff, patch.getLayer() );
			}

			ip = new ImagePlus( "", image ).getProcessor();

			if (!range.starts_at_bottom) {
				final float[] pixels = new float[ ip.getWidth() * ip.getHeight() ];
				mask = new FloatProcessor( ip.getWidth(), ip.getHeight(), image.getAlphaRaster().getPixels( 0, 0, ip.getWidth(), ip.getHeight(), pixels ), null );
				
				mask.setMinAndMax( 0, 255 );
			}

			image.flush();
			transformedImage = makeImage(ip, mask);
		}

		public void update( final Mapping< ? > mapping )
		{
			final ImageProcessor ipTransformed = ip.createProcessor( ip.getWidth(), ip.getHeight() );
			mapping.mapInterpolated( ip, ipTransformed );
			
			FloatProcessor maskTransformed = null;
			if (null != mask) {
				maskTransformed = ( FloatProcessor )mask.createProcessor( mask.getWidth(), mask.getHeight() );
				mapping.mapInterpolated( mask, maskTransformed );
			}
			
			if (null != transformedImage) transformedImage.flush();
			transformedImage = makeImage( ipTransformed, maskTransformed );
		}

		private BufferedImage makeImage( final ImageProcessor ip, final FloatProcessor mask )
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

		void flush() {
			if (null != transformedImage) transformedImage.flush();
		}

		public void paint( Graphics2D g, double magnification, boolean active, int channels, Layer active_layer )
		{
			final AffineTransform at = g.getTransform();
			final AffineTransform atp = new AffineTransform();
			
			atp.translate( -pad, -pad );
			g.setTransform( atp );
			g.drawImage( transformedImage, 0, 0, null );
			g.setTransform( at );
		}

		public void prePaint( Graphics2D g, double magnification, boolean active, int channels, Layer active_layer )
		{
			paint( g, magnification, active, channels, active_layer );			
		}
	}


	private Display display;
	
	private Rectangle srcRect;
	private double magnification;
	
	private NonLinearTransformSource gs;
	
	final private Updater updater;
	final private Painter painter;
	
	private final List<Patch> originalPatches;
	private final List<PatchRange> ranges;
	private final HashMap<Paintable, ScreenPatchRange> screenPatchRanges;
	
	public NonLinearTransformMode(final Display display, final List<Displayable> selected) {
		ProjectToolbar.setTool(ProjectToolbar.SELECT);
		this.display = display;
		this.srcRect = ( Rectangle )display.getCanvas().getSrcRect().clone();
		this.magnification = display.getCanvas().getMagnification();
		this.originalPatches = new ArrayList<Patch>();
		this.ranges = new ArrayList<PatchRange>();

		for (final Displayable d : selected) {
			if (d instanceof Patch) originalPatches.add( (Patch) d );
		}

		this.screenPatchRanges = new HashMap<Paintable, ScreenPatchRange>( originalPatches.size() );
		this.gs = new NonLinearTransformSource();

		this.updater = new Updater();
		updater.start();
		this.painter = new Painter();
		painter.start();

		updater.update(); // will call painter.update()
	}

	public NonLinearTransformMode(final Display display) {
		this(display, display.getSelection().getSelected());
	}

	public GraphicsSource getGraphicsSource() {
		return gs;
	}

	public boolean canChangeLayer() { return false; }
	public boolean canZoom() { return true; }
	public boolean canPan() { return true; }

	/* transformed points are key, originals are value */
	private HashMap<P,P> points = new HashMap<P,P>();
	
	private P p_clicked = null;
	
	public void mousePressed(MouseEvent me, int x_p, int y_p, double magnification) {
		// bring to screen coordinates
		x_p = display.getCanvas().screenX(x_p);
		y_p = display.getCanvas().screenY(y_p);

		// find if clicked on a point
		// TODO iterate all and find the closest point instead
		p_clicked = null;
		for (P p : points.keySet()) {
			if (Math.sqrt(Math.pow(p.x - x_p, 2) + Math.pow(p.y - y_p, 2)) <= 8) {
				p_clicked = p;
				break;
			}
		}

		if (me.isShiftDown()) {
			if (null == p_clicked) {
				// add one
				try {
					if (0 == points.size()) {
						p_clicked = new P(x_p, y_p);
						points.put(p_clicked, new P(x_p, y_p));
					} else {
						Class c = AffineModel2D.class;
						switch (points.size()) {
							case 1:
								c = TranslationModel2D.class;
								break;
							case 2:
								c = SimilarityModel2D.class;
								break;
							default:
								break;
						}
						final MovingLeastSquaresTransform mlst = new MovingLeastSquaresTransform();
						mlst.setAlpha(1.0f);
						mlst.setModel( c );
						final Collection< PointMatch > pm = new ArrayList<PointMatch>();
						for ( Map.Entry<P,P> e : points.entrySet() )
						{
							final P p = e.getValue();
							final P q = e.getKey();
							pm.add( new PointMatch(
									new Point( new float[]{ p.x, p.y } ),
									new Point( new float[]{ q.x, q.y } ) ) );
						}
						mlst.setMatches(pm);
						final CoordinateTransformMesh ctm = new CoordinateTransformMesh( mlst, 32,
								srcRect.width * ( float )magnification,
								srcRect.height * ( float )magnification);
						final float[] fc = new float[]{x_p, y_p};
						ctm.applyInverseInPlace( fc );
						
						p_clicked = new P( x_p, y_p);
						points.put(p_clicked, new P( (int) fc[0], (int) fc[1]));
					}
				} catch (Exception e) {
					Utils.log("Could not add point");
					e.printStackTrace();
				}
			} else if (Utils.isControlDown(me)) {
				// remove it
				//IJ.log("removing " + p_clicked);
				//IJ.log("removed: " + points.remove(p_clicked));
				p_clicked = null;
			}
		}
	}

	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		// bring to screen coordinates
		x_d = display.getCanvas().screenX(x_d);
		y_d = display.getCanvas().screenY(y_d);
		x_d_old = display.getCanvas().screenX(x_d_old);
		y_d_old = display.getCanvas().screenY(y_d_old);

		if (null != p_clicked) {
			p_clicked.x += x_d - x_d_old;
			p_clicked.y += y_d - y_d_old;
			//painter.update();
		}
	}
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		// bring to screen coordinates
		mouseDragged(me, x_p, y_p, x_r, y_r, x_d, y_d);
		p_clicked = null; // so isDragging can return the right state
		painter.update();
	}
	
	private void updated(Rectangle srcRect, double magnification) {
		synchronized ( updater )
		{
			if ( this.srcRect.x == srcRect.x && this.srcRect.y == srcRect.y
			  && this.srcRect.width == srcRect.width && this.srcRect.height == srcRect.height
			  && this.magnification == magnification ) return;

			for ( Map.Entry<P, P> e : points.entrySet() )
			{
				final P p = e.getKey();
				final P q = e.getValue();
				double x = p.x;
				x /= this.magnification;
				x += this.srcRect.x - srcRect.x;
				x *= magnification;
				p.x = ( int )Math.round( x );
				double y = p.y;
				y /= this.magnification;
				y += this.srcRect.y - srcRect.y;
				y *= magnification;
				p.y = ( int )Math.round( y );
				x = q.x;
				x /= this.magnification;
				x += this.srcRect.x - srcRect.x;
				x *= magnification;
				q.x = ( int )Math.round( x );
				y = q.y;
				y /= this.magnification;
				y += this.srcRect.y - srcRect.y;
				y *= magnification;
				q.y = ( int )Math.round( y );
			}
			this.srcRect = (Rectangle) srcRect.clone();
			this.magnification = magnification;
		}
		updater.update();
	}

	private class P {
		int x, y;
		P(int x, int y)
		{
			this.x = x;
			this.y = y;
		}
	}

	public void srcRectUpdated(Rectangle srcRect, double magnification) {
		updated(srcRect, magnification);
	}
	public void magnificationUpdated(Rectangle srcRect, double magnification) {
		updated(srcRect, magnification);
	}

	public void redoOneStep() {}

	public void undoOneStep() {}

	public boolean isDragging() {
		return null != p_clicked;
	}

	private final void setUndoState() {
		display.getLayerSet().addEditStep(new Displayable.DoEdits(new HashSet<Displayable>(originalPatches)).init(new String[]{"data", "at", "width", "height"}));
	}

	public boolean apply() {

		// Set undo step to reflect initial state before any transformations
		setUndoState();

		Bureaucrat.createAndStart(new Worker.Task("Applying transformations") { public void exec() {

		final ArrayList<Future> futures = new ArrayList<Future>();

		/* bring all points into world space */
		final Collection< PointMatch > worldPointMatches = new ArrayList< PointMatch >( points.size() );

		synchronized ( updater )
		{
			final Rectangle r = new Rectangle( srcRect );
			final double m = magnification;
			for ( Map.Entry< P, P > e : points.entrySet() )
			{
				final P p = e.getKey();
				final P q = e.getValue();
				
				final float[] l = new float[]{ q.x, q.y };
				final float[] w = new float[]{ p.x, p.y };
				
				l[ 0 ] = ( float )( l[ 0 ] / m ) + r.x;
				l[ 1 ] = ( float )( l[ 1 ] / m ) + r.y;
				
				w[ 0 ] = ( float )( w[ 0 ] / m ) + r.x;
				w[ 1 ] = ( float )( w[ 1 ] / m ) + r.y;
				
				worldPointMatches.add(
						new PointMatch(
								new Point( l ), new Point( w ) ) );
			}

			for ( final Paintable p : screenPatchRanges.keySet() )
			{
				if ( p instanceof Patch )
				{
					try
					{
						final Patch patch = ( Patch )p;
						final AffineTransform atp = patch.getAffineTransform().createInverse();
						final Rectangle bbox = patch.getCoordinateTransformBoundingBox();
						
						/*
						 * TODO replace this with the desired parameters of the transformation
						 */
						final MovingLeastSquaresTransform mlst = new MovingLeastSquaresTransform();
						mlst.setAlpha( 1.0f );
						Class c = AffineModel2D.class;
						switch (points.size()) {
							case 1:
								c = TranslationModel2D.class;
								break;
							case 2:
								c = SimilarityModel2D.class;
								break;
							default:
								break;
						}
						mlst.setModel( c );
						
						/* prepare pointmatches that are local to the patch */
						final Collection< PointMatch > localMatches = new ArrayList< PointMatch >( worldPointMatches.size() );
						for ( final PointMatch pm : worldPointMatches )
						{
							final float[] l = pm.getP1().getW().clone();
							final float[] w = pm.getP2().getW().clone();
							
							atp.transform( l, 0, l, 0, 1 );
							atp.transform( w, 0, w, 0, 1 );
							
							l[ 0 ] -= bbox.x;
							l[ 1 ] -= bbox.y;
							w[ 0 ] -= bbox.x;
							w[ 1 ] -= bbox.y;
							
							localMatches.add(
									new PointMatch(
											new Point( l ), new Point( w ) ) );
						}
						mlst.setMatches( localMatches );
						patch.appendCoordinateTransform( mlst );
						futures.add( patch.getProject().getLoader().regenerateMipMaps( patch ) );
					}
					catch ( Exception e )
					{
						e.printStackTrace();
					}
				}
			}
		}

		// flush images
		for (ScreenPatchRange spr : new HashSet<ScreenPatchRange>(screenPatchRanges.values())) {
			spr.flush();
		}

		// Wait until all mipmaps are regenerated
		for (Future fu : futures) try {
			fu.get();
		} catch (Exception ie) {}

		// Set undo step to reflect final state after applying transformations
		setUndoState();

		}}, display.getProject());

		painter.quit();
		updater.quit();

		return true;
	}
	public boolean cancel() {
		painter.quit();
		updater.quit();
		return true;
	}

	public Rectangle getRepaintBounds() { return (Rectangle) srcRect.clone(); }

}
