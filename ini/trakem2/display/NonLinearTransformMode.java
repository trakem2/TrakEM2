package ini.trakem2.display;

import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Paintable;
import ini.trakem2.display.graphics.GraphicsSource;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.BufferedImage;

import mpicbg.ij.Mapping;
import mpicbg.ij.TransformMapping;
import mpicbg.ij.TransformMeshMapping;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.MovingLeastSquaresTransform;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

public class NonLinearTransformMode implements Mode {
	
	private class Updater extends SimpleThread
	{
		void doit( final Rectangle r, final double m )
		{
			for ( int i = 0; i < originalPatches.size(); ++i )
			{
				final Displayable o = originalPatches.get( i );
				
				final ScreenPatch sp = new ScreenPatch( ( Patch )o, r, m );
				screenPatches.put( o, sp );
			}
			painter.update();
		}
	}
	
	private class Painter extends SimpleThread
	{
		void doit( final Rectangle r, final double m )
		{
			try
			{
				final Collection< PointMatch > pm = new ArrayList<PointMatch>();
				for ( Map.Entry<P,P> e : points.entrySet() )
				{
					final P p = e.getValue();
					final P q = e.getKey();
					pm.add( new PointMatch(
							new Point( new float[]{ p.x + ScreenPatch.pad, p.y + ScreenPatch.pad } ),
							new Point( new float[]{ q.x + ScreenPatch.pad, q.y + ScreenPatch.pad } ) ) );
				}
				final TransformMeshMapping mapping;
				synchronized ( updater )
				{
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
					mlst.setMatches( pm );
					final CoordinateTransformMesh ctm = new CoordinateTransformMesh( mlst, 32, r.width * ( float )m + 2 * ScreenPatch.pad, r.height * ( float )m + 2 * ScreenPatch.pad );
					mapping = new TransformMeshMapping( ctm );
				}
				
				for ( final ScreenPatch sp : screenPatches.values() )
				{
					sp.update( mapping );
				}
			}
			catch ( Exception e ) {}
			
			display.getCanvas().repaint( true );
		}
	}
	
	private abstract class SimpleThread extends Thread
	{
		private boolean updateAgain = false;
		
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
	}
	
	private class ScreenPatch implements Paintable
	{
		static final int pad = 100;
		
		ImageProcessor ip;
		FloatProcessor mask;
		
		BufferedImage transformedImage;
		
		ScreenPatch( final Patch patch, final Rectangle srcRect, final double magnification )
		{
			final BufferedImage image = display.getCanvas().getGraphicsConfiguration().createCompatibleImage( ( int )( srcRect.width * magnification + 0.5 ) + 2 * pad, ( int )( srcRect.height * magnification + 0.5 ) + 2 * pad, Transparency.TRANSLUCENT );
			Graphics2D g = image.createGraphics();
			final AffineTransform atc = new AffineTransform();
			atc.translate(pad, pad);
			atc.scale( magnification, magnification);
			atc.translate(-srcRect.x, -srcRect.y);
			g.setTransform( atc );
			patch.paint( g, magnification, false, 0xffffffff, patch.getLayer() );
			
			ip = new ImagePlus( patch.getTitle(), image ).getProcessor();
			
			final float[] pixels = new float[ ip.getWidth() * ip.getHeight() ];
			mask = new FloatProcessor( ip.getWidth(), ip.getHeight(), image.getAlphaRaster().getPixels( 0, 0, ip.getWidth(), ip.getHeight(), pixels ), null );
			
			mask.setMinAndMax( 0, 255 );
			
			transformedImage = makeImage( ip, mask );
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
		
		public void update( final Mapping< ? > mapping )
		{
			final ImageProcessor ipTransformed = ip.createProcessor( ip.getWidth(), ip.getHeight() );
			mapping.mapInterpolated( ip, ipTransformed );
			
			final FloatProcessor maskTransformed = ( FloatProcessor )mask.createProcessor( mask.getWidth(), mask.getHeight() );
			mapping.mapInterpolated( mask, maskTransformed );
			
			transformedImage = makeImage( ipTransformed, maskTransformed );
		}
		
		private BufferedImage makeImage( final ImageProcessor ip, final FloatProcessor mask )
		{
			final BufferedImage transformedImage = new BufferedImage( ip.getWidth(), ip.getHeight(), BufferedImage.TYPE_INT_ARGB );
			transformedImage.createGraphics().drawImage( ip.createImage(), 0, 0, null );
			transformedImage.getAlphaRaster().setPixels( 0, 0, ip.getWidth(), ip.getHeight(), ( float[] )mask.getPixels() );
			return transformedImage;
		}
		
	}
	
	private class NonLinearTransformSource implements GraphicsSource {

		/** Returns the list given as argument without any modification. */
		public List<? extends Paintable> asPaintable(final List<? extends Paintable> ds) {
			final List<Paintable> newList = new ArrayList< Paintable >();
			
			/* fill it */
			for ( final Paintable p : ds )
			{
				final ScreenPatch sp = screenPatches.get( p );
				if ( sp == null )
					newList.add(p);
				else
				{
					newList.add( sp );
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

	private Display display;
	
	private Rectangle srcRect;
	private double magnification;
	
	private NonLinearTransformSource gs;
	
	final private Updater updater;
	final private Painter painter;
	
	private final List<Patch> originalPatches;
	private final HashMap<Paintable, ScreenPatch> screenPatches;
	
	public NonLinearTransformMode(final Display display, final List<Displayable> selected) {
		ProjectToolbar.setTool(ProjectToolbar.SELECT);
		this.display = display;
		this.srcRect = ( Rectangle )display.getCanvas().getSrcRect().clone();
		this.magnification = display.getCanvas().getMagnification();
		this.originalPatches = new ArrayList<Patch>();
		for ( final Displayable o : selected )
			if ( o instanceof Patch ) originalPatches.add( ( Patch )o );
		this.screenPatches = new HashMap<Paintable, ScreenPatch>( originalPatches.size() );
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
			painter.update();
		}
	}
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		// bring to screen coordinates
		mouseDragged(me, x_p, y_p, x_r, y_r, x_d, y_d);
		p_clicked = null; // so isDragging can return the right state
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

	public boolean apply() { return true; }
	public boolean cancel() { return true; }

	public Rectangle getRepaintBounds() { return display.getSelection().getLinkedBox(); } // TODO

}

