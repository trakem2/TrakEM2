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

public class NonLinearTransformMode extends GroupingMode {

	protected void doPainterUpdate( final Rectangle r, final double m )
	{
		try
		{
			final CoordinateTransform mlst = createCT();
			final SimilarityModel2D toWorld = new SimilarityModel2D();
			toWorld.set( 1.0f / ( float )m, 0, r.x - ScreenPatchRange.pad / ( float )m, r.y - ScreenPatchRange.pad / ( float )m );
			
			final mpicbg.models.CoordinateTransformList< mpicbg.models.CoordinateTransform > ctl = new mpicbg.models.CoordinateTransformList< mpicbg.models.CoordinateTransform >();
			ctl.add( toWorld );
			ctl.add( mlst );
			ctl.add( toWorld.createInverse() );
			
			final CoordinateTransformMesh ctm = new CoordinateTransformMesh( ctl, 32, r.width * ( float )m + 2 * ScreenPatchRange.pad, r.height * ( float )m + 2 * ScreenPatchRange.pad );
			final TransformMeshMapping mapping;
			mapping = new TransformMeshMapping( ctm );
			
			for ( final GroupingMode.ScreenPatchRange spr : screenPatchRanges.values())
			{
				spr.update( mapping );
			}
		}
		catch ( Exception e ) {}
	}

	private class NonLinearTransformSource extends GroupingMode.GroupedGraphicsSource {
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

	protected ScreenPatchRange createScreenPathRange(final PatchRange range, final Rectangle srcRect, final double magnification) {
		return new ScreenPatchRange(range, srcRect, magnification);
	}

	static private class ScreenPatchRange implements GroupingMode.ScreenPatchRange<Mapping<?>> {

		final ImageProcessor ip;
		final ImageProcessor ipTransformed;
		final FloatProcessor mask;
		final FloatProcessor maskTransformed;
		BufferedImage transformedImage;
		static final int pad = 100;
		final byte compositeMode;

		ScreenPatchRange( final PatchRange range, final Rectangle srcRect, final double magnification )
		{
			super();
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

		@Override
		public void flush() {
			if (null != transformedImage) transformedImage.flush();
		}

		@Override
		public void paint( Graphics2D g, double magnification, boolean active, int channels, Layer active_layer )
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

		public void prePaint( Graphics2D g, double magnification, boolean active, int channels, Layer active_layer )
		{
			paint( g, magnification, active, channels, active_layer );			
		}
	}

	protected GroupingMode.GroupedGraphicsSource createGroupedGraphicSource() {
		return new NonLinearTransformSource();
	}

	public NonLinearTransformMode(final Display display, final List<Displayable> selected) {
		super(display, selected);
		ProjectToolbar.setTool(ProjectToolbar.SELECT);
		super.initThreads();
	}

	public NonLinearTransformMode(final Display display) {
		this(display, display.getSelection().getSelected());
	}

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
						final CoordinateTransform mlst = createCT();
						final SimilarityModel2D toWorld = new SimilarityModel2D();
						toWorld.set( 1.0f / ( float )magnification, 0, srcRect.x, srcRect.y );
						final SimilarityModel2D toScreen = toWorld.createInverse();
						
						final mpicbg.models.CoordinateTransformList< mpicbg.models.CoordinateTransform > ctl = new mpicbg.models.CoordinateTransformList< mpicbg.models.CoordinateTransform >();
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
	
	public boolean isDragging()
	{
		return null != p_clicked;
	}

	private final void setUndoState() {
		layer.getParent().addEditStep(new Displayable.DoEdits(new HashSet<Displayable>(originalPatches)).init(new String[]{"data", "at", "width", "height"}));
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
								final AffineTransform pat = new AffineTransform();
								pat.translate( -pbox.x, -pbox.y );
								pat.preConcatenate( patch.getAffineTransform() );
								
								final AffineModel2D toWorld = new AffineModel2D();
								toWorld.set( pat );
								
								final CoordinateTransform mlst = createCT();
								
								final CoordinateTransformList< CoordinateTransform > ctl = new CoordinateTransformList< CoordinateTransform >();
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
				for ( GroupingMode.ScreenPatchRange spr : new HashSet< GroupingMode.ScreenPatchRange >( screenPatchRanges.values() ) )
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
		}, layer.getProject() );

		super.quitThreads();

		return true;
	}

	private CoordinateTransform createCT() throws Exception
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
