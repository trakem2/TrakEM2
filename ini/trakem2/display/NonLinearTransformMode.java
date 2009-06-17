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
import java.util.concurrent.Future;
import java.awt.BasicStroke;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import mpicbg.ij.Mapping;
import mpicbg.ij.TransformMeshMapping;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.trakem2.transform.CoordinateTransformList;
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
//			Utils.showMessage("Updating...");
			try
			{
				final MovingLeastSquaresTransform mlst = createMLST();
				final SimilarityModel2D toWorld = new SimilarityModel2D();
				toWorld.set( 1.0f / ( float )m, 0, r.x - ScreenPatchRange.pad / ( float )m, r.y - ScreenPatchRange.pad / ( float )m );
				
				final CoordinateTransformList ctl = new CoordinateTransformList();
				ctl.add( toWorld );
				ctl.add( mlst );
				ctl.add( toWorld.createInverse() );
				
				final CoordinateTransformMesh ctm = new CoordinateTransformMesh( ctl, 32, r.width * ( float )m + 2 * ScreenPatchRange.pad, r.height * ( float )m + 2 * ScreenPatchRange.pad );
				final TransformMeshMapping mapping;
				mapping = new TransformMeshMapping( ctm );
				
				for ( final ScreenPatchRange spr : screenPatchRanges.values())
				{
					spr.update( mapping );
				}
			}
			catch ( Exception e ) {}
			
			display.getCanvas().repaint( true );
//			Utils.showMessage("");
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
			
			final Stroke original_stroke = g.getStroke();
			final AffineTransform original = g.getTransform();
			g.setTransform( new AffineTransform() );
			g.setStroke( new BasicStroke( 1.0f ) );
			for ( final Point p : points )
			{
				final float[] w = p.getW();
				Utils.drawPoint( g, Math.round( ( float )magnification * ( w[ 0 ] - srcRect.x ) ), Math.round( ( float )magnification * ( w[ 1 ] - srcRect.y ) ) );
			}
			
			g.setTransform( original );
			g.setStroke( original_stroke );
		}
	}

	static private class PatchRange {
		final ArrayList<Patch> list = new ArrayList<Patch>();
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

	static private class ScreenPatchRange implements Paintable {

		final ImageProcessor ip;
		final ImageProcessor ipTransformed;
		final FloatProcessor mask;
		final FloatProcessor maskTransformed;
		BufferedImage transformedImage;
		static final int pad = 100;

		ScreenPatchRange(final PatchRange range, final Rectangle srcRect, final double magnification) {
			final BufferedImage image = new BufferedImage((int)(srcRect.width * magnification + 0.5) + 2 * pad,
					                              (int)(srcRect.height * magnification + 0.5 ) + 2 * pad,
								      range.starts_at_bottom ?
								        range.is_gray ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB
									: BufferedImage.TYPE_INT_ARGB);

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

		public void update( final Mapping< ? > mapping )
		{
			ipTransformed.reset();
			mapping.map( ip, ipTransformed );
			
			if (null != mask) {
				mask.reset();
				mapping.map( mask, maskTransformed );
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

	private Collection< Point > points = new ArrayList< Point >();
	
	private Point p_clicked = null;
	
	public void mousePressed( MouseEvent me, int x_p, int y_p, double magnification )
	{
		/* find if clicked on a point */
		p_clicked = null;
		float min = Float.MAX_VALUE;
		final Point mouse = new Point( new float[]{ x_p, y_p } );
		final float a = ( float )( 64 / magnification / magnification);
		for ( final Point p : points )
		{
			final float sd = Point.squareDistance( p, mouse );
			if ( sd < min && sd < a )
			{
				p_clicked = p;
				min = sd;
			}
		}

		if ( me.isShiftDown() )
		{
			if ( null == p_clicked )
			{
				/* add one */
				try
				{
					if ( points.size() > 0 )
					{
						/* 
						 * Create a pseudo-invertible (TransformMesh) for the screen.
						 */
						final MovingLeastSquaresTransform mlst = createMLST();
						final SimilarityModel2D toWorld = new SimilarityModel2D();
						toWorld.set( 1.0f / ( float )magnification, 0, srcRect.x, srcRect.y );
						final SimilarityModel2D toScreen = toWorld.createInverse();
						
						final CoordinateTransformList ctl = new CoordinateTransformList();
						ctl.add( toWorld );
						ctl.add( mlst );
						ctl.add( toScreen );
				
						final CoordinateTransformMesh ctm = new CoordinateTransformMesh(
								ctl,
								32,
								( int )Math.ceil( srcRect.width * magnification ),
								( int )Math.ceil( srcRect.height * magnification ) );
						
						final float[] l = mouse.getL();
						toScreen.applyInPlace( l );
						ctm.applyInverseInPlace( l );
						toWorld.applyInPlace( l );
					}
					points.add( mouse );
					p_clicked = mouse;
				}
				catch ( Exception e )
				{
					Utils.log( "Could not add point" );
					e.printStackTrace();
				}
			}
			else if ( Utils.isControlDown( me ) )
			{
				// remove it
				points.remove(p_clicked);
			 	p_clicked = null;
			}
		}
	}

	public void mouseDragged( MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old )
	{
		if ( null != p_clicked )
		{
			final float[] w = p_clicked.getW();
			w[ 0 ] += x_d - x_d_old;
			w[ 1 ] += y_d - y_d_old;
			painter.update();
		}
	}
	public void mouseReleased( MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r )
	{
		// bring to screen coordinates
		mouseDragged( me, x_p, y_p, x_r, y_r, x_d, y_d );
		p_clicked = null; // so isDragging can return the right state
	}
	
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

	public boolean isDragging()
	{
		return null != p_clicked;
	}

	private final void setUndoState() {
		display.getLayerSet().addEditStep(new Displayable.DoEdits(new HashSet<Displayable>(originalPatches)).init(new String[]{"data", "at", "width", "height"}));
	}

	public boolean apply()
	{

		/* Set undo step to reflect initial state before any transformations */
		setUndoState();
		
		Bureaucrat.createAndStart( new Worker.Task( "Applying transformations" )
		{
			public void exec()
			{
				final ArrayList< Future > futures = new ArrayList< Future >();

				synchronized ( updater )
				{
					for ( final Paintable p : screenPatchRanges.keySet() )
					{
						if ( p instanceof Patch )
						{
							try
							{
								final Patch patch = ( Patch ) p;
								final Rectangle pbox = patch.getCoordinateTransformBoundingBox();
								final AffineTransform pat = patch.getAffineTransformCopy();
								pat.translate( -pbox.x, -pbox.y );
								
								final AffineModel2D toWorld = new AffineModel2D();
								toWorld.set( pat );
								
								final MovingLeastSquaresTransform mlst = createMLST();
								
								final CoordinateTransformList ctl = new CoordinateTransformList();
								ctl.add( toWorld );
								ctl.add( mlst );
								ctl.add( toWorld.createInverse() );
								
								patch.appendCoordinateTransform( ctl );
								futures.add( patch.getProject().getLoader().regenerateMipMaps( patch ) );
							}
							catch ( Exception e )
							{
								e.printStackTrace();
							}
						}
					}
				}

				/* Flush images */
				for ( ScreenPatchRange spr : new HashSet< ScreenPatchRange >( screenPatchRanges.values() ) )
				{
					spr.flush();
				}

				/* Wait until all mipmaps are regenerated */
				for ( Future fu : futures )
					try
					{
						fu.get();
					}
					catch ( Exception ie )
					{}

				// Set undo step to reflect final state after applying transformations
				setUndoState();

			}
		}, display.getProject() );

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
	
	private MovingLeastSquaresTransform createMLST() throws Exception
	{
		final Collection< PointMatch > pm = new ArrayList<PointMatch>();
		for ( Point p : points )
		{
			pm.add( new PointMatch( new Point( p.getL() ), new Point( p.getW() ) ) );
		}
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
		
		return mlst;
	}

}
