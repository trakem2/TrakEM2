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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import mpicbg.models.MovingLeastSquaresTransform;

public class NonLinearTransformMode implements Mode {
	
	private class Updater extends Thread
	{
		private boolean updateAgain = false;
		
		public void run()
		{
			while ( !isInterrupted() )
			{
				final boolean b;
				synchronized ( this )
				{
					b = updateAgain;
					updateAgain = false;
				}
				if ( b )
				{
					for ( int i = 0; i < originalPatches.size(); ++i )
					{
						final Displayable o = originalPatches.get( i );
						
						final ScreenPatch sp = new ScreenPatch( ( Patch )o );
						screenPatches.put( o, sp );
					}
					display.getCanvas().repaint( true );
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
		
		void updateAgain()
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
		static final private int pad = 200;
		
		ImageProcessor ip;
		FloatProcessor mask;
		
		ScreenPatch( final Patch patch )
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
			new ImagePlus( "", mask ).show();
		}

		public void paint( Graphics2D g, double magnification, boolean active, int channels, Layer active_layer )
		{
			
		}

		public void prePaint( Graphics2D g, double magnification, boolean active, int channels, Layer active_layer )
		{
			paint( g, magnification, active, channels, active_layer );			
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
			for ( java.awt.Point p : points) {
				IJ.log( p.x + ", " + p.y );
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

	private Collection<java.awt.Point> points = new ArrayList<java.awt.Point>();

	private java.awt.Point p_clicked = null;
	
	
	
	public void mousePressed(MouseEvent me, int x_p, int y_p, double magnification) {
		// bring to screen coordinates
		x_p = display.getCanvas().screenX(x_p);
		y_p = display.getCanvas().screenY(y_p);

		// find if clicked on a point
		p_clicked = null;
		for (java.awt.Point p : points) {
			if (Math.sqrt(Math.pow(p.x - x_p, 2) + Math.pow(p.y - y_p, 2)) <= 8) {
				p_clicked = p;
				break;
			}
		}

		if (me.isShiftDown()) {
			if (null == p_clicked) {
				// add one
				p_clicked = new java.awt.Point(x_p, y_p);
				points.add(p_clicked);
			} else if (Utils.isControlDown(me)) {
				// remove it
				points.remove(p_clicked);
				p_clicked = null;
			}
		}
	}

	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		// bring to screen coordinates
		x_p = display.getCanvas().screenX(x_p);
		y_p = display.getCanvas().screenY(y_p);
	}
	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		// bring to screen coordinates
		x_p = display.getCanvas().screenX(x_p);
		y_p = display.getCanvas().screenY(y_p);
	}
	
	private void updated(Rectangle srcRect, double magnification) {
		if ( srcRect.equals( this.srcRect ) && this.magnification == magnification ) return;
		this.srcRect = srcRect;
		this.magnification = magnification;
		updater.updateAgain();
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
		return true; // TODO
	}

	public boolean apply() { return true; }
	public boolean cancel() { return true; }

	public Rectangle getRepaintBounds() { return display.getSelection().getLinkedBox(); } // TODO

}

