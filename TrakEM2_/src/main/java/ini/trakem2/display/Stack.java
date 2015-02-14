/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 *
 */
package ini.trakem2.display;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ini.trakem2.Project;
import ini.trakem2.persistence.XMLOptions;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import mpicbg.ij.stack.InverseTransformMapping;
import mpicbg.ij.util.Filter;
import mpicbg.models.AffineModel2D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.Boundable;
import mpicbg.models.InvertibleCoordinateTransformList;
import mpicbg.models.TranslationModel3D;
import mpicbg.trakem2.transform.InvertibleCoordinateTransform;
import mpicbg.util.Util;

/**
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class Stack extends ZDisplayable implements ImageData
{
	private String file_path;
	private double depth = -1;
	private double min;
	private double max;
	private InvertibleCoordinateTransform ict;
	final private double[] boundsMin = new double[]{ 0, 0, 0 };
	final private double[] boundsMax = new double[]{ 0, 0, 0 };
	private double ictScale = 1.0;

	/*
	 * References cached images in Loader whose unique identifier from the
	 * prespective of the stack are double:magnification and double:z.
	 */
	final private HashMap< SliceViewKey, Long > cachedImages = new HashMap< SliceViewKey, Long >();

	final private HashMap< Long, Future< Image > > futureImages = new HashMap< Long, Future<Image> >();

	private static class SliceViewKey
	{
		final double magnification;
		final double z;

		SliceViewKey( final double magnification, final double z )
		{
			this.magnification = magnification;
			this.z = z;
		}

		@Override
		final public boolean equals( final Object o )
		{
			final SliceViewKey k = ( SliceViewKey )o;
			return k.magnification == magnification && k.z == z;
		}

		@Override
		final public int hashCode()
		{
			return 0;
		}
	}

	public Stack( final Project project, final String title, final double x, final double y, final Layer initial_layer, final String file_path )
	{
		super( project, title, x, y );
		this.file_path = file_path;
		// ct ==> initial_layer;
		final ImagePlus imp = project.getLoader().fetchImagePlus( this );

		/* TODO scale regarding the Calibration and shift regarding x, y and the initial_layer */
		depth = imp.getNSlices();
		width = imp.getWidth();
		height = imp.getHeight();
		min = imp.getDisplayRangeMin();
		max = imp.getDisplayRangeMax();
		//at.translate( x, y ); // No need: the call to the super constructor already translated the affine transform.

		boundsMin[ 0 ] = 0;
		boundsMin[ 1 ] = 0;
		boundsMin[ 2 ] = initial_layer.getZ();

		boundsMax[ 0 ] = width;
		boundsMax[ 1 ] = height;
		boundsMax[ 2 ] = boundsMin[ 2 ] + depth;

		addToDatabase();
	}

	/** For cloning purposes. */
	private Stack(final Project project, final long id, final String title,
		      final AffineTransform at, final float width, final float height,
		      final float alpha, final boolean visible, final Color color, final boolean locked,
		      final double depth, final double min, final double max,
		      final double[] boundsMin, final double[] boundsMax,
		      final InvertibleCoordinateTransform ict, final double ictScale, final String file_path) {
		super(project, id, title, locked, at, 0, 0);
		this.title = title;
		this.alpha = alpha;
		this.visible = visible;
		this.color = color;
		this.boundsMin[0] = boundsMin[0];
		this.boundsMin[1] = boundsMin[1];
		this.boundsMin[2] = boundsMin[2];
		this.boundsMax[0] = boundsMax[0];
		this.boundsMax[1] = boundsMax[1];
		this.boundsMax[2] = boundsMax[2];
		this.width = width;
		this.height = height;
		this.depth = depth;
		this.min = min;
		this.max = max;
		this.ict = null == ict ? null : this.ict.copy();
		this.file_path = file_path;
	}

	/** Construct a Stack from an XML entry. */
	public Stack(final Project project, final long id, final HashMap<String,String> ht, final HashMap<Displayable,String> ht_links) {
		super(project, id, ht, ht_links);
		// parse specific fields

		for (final Map.Entry<String,String> entry : ht.entrySet()) {
			final String key = (String)entry.getKey();
			final String data = (String)entry.getValue();
			if (key.equals("min")) {
				this.min = Double.parseDouble(data);
			} else if (key.equals("max")) {
				this.max = Double.parseDouble(data);
			} else if (key.equals("file_path")) {
				this.file_path = project.getLoader().makeRelativePath(data);
			} else if (key.equals("depth")) {
				this.depth = Double.parseDouble(data);
			}
		}
		boundsMin[ 0 ] = 0;
		boundsMin[ 1 ] = 0;
		boundsMin[ 2 ] = 0;

		boundsMax[ 0 ] = width;
		boundsMax[ 1 ] = height;
		boundsMax[ 2 ] = depth;
	}

	public InvertibleCoordinateTransform getInvertibleCoordinateTransform()
	{
		return ict;
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.display.ZDisplayable#getFirstLayer()
	 */
	@Override
	public Layer getFirstLayer()
	{
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.display.ZDisplayable#intersects(java.awt.geom.Area, double, double)
	 */
	@Override
	public boolean intersects( final Area area, final double z_first, final double z_last )
	{
		// TODO Auto-generated method stub
		return false;
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.display.ZDisplayable#linkPatches()
	 */
	@Override
	public boolean linkPatches()
	{
		return false;
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.display.Displayable#clone(ini.trakem2.Project, boolean)
	 */
	@Override
	public Displayable clone( final Project pr, final boolean copy_id )
	{
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final Stack copy = new Stack( pr, nid, null != title ? title.toString() : null,
				              new AffineTransform(at), width, height,
					      alpha, visible, color, locked,
					      depth, min, max,
					      boundsMin, boundsMax,
					      ict, ictScale, file_path );
		// Copy references to cached images
		copy.cachedImages.putAll(cachedImages);
		copy.futureImages.putAll(futureImages);
		copy.addToDatabase();
		return copy;
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.display.Displayable#isDeletable()
	 */
	@Override
	public boolean isDeletable()
	{
		return 0 == width && 0 == height;
	}

	public String getFilePath(){
		return this.file_path;
	}

	/** Will decache the ImagePlus if loaded. */
	public void setFilePath(final String path) {
		this.file_path = path;
		// The source ImagePlus
		project.getLoader().decacheImagePlus(this.id);
		// The generated AWT images
		invalidateCache();
	}

	public long estimateImageFileSize()
	{
		if ( -1 == depth )
			return IJ.maxMemory() / 2;
		return (long) (width * height * depth * 4);
	}

	/**
	 * Estimate the scale of atp to apply the
	 * appropriate smoothing to the image.
	 */
	final static private double estimateAffineScale( final AffineTransform atp )
	{
		final double dxx = atp.getScaleX();
		final double dxy = atp.getShearY();
		final double dxs = dxx * dxx + dxy * dxy;

		final double dyx = atp.getShearX();
		final double dyy = atp.getScaleY();
		final double dys = dyx * dyx + dyy * dyy;

		return Math.sqrt( Math.max( dxs, dys ) );
	}

	/** Slow paint: will wait until the image is generated and cached, then paint it. */
	@Override
	public void paint(final Graphics2D g, final Rectangle srcRect, final double magnification, final boolean active, final int channels, final Layer active_layer, final List<Layer> layers) {
		MipMapImage mipMap = null;
		Future< Image > fu = null;
		final SliceViewKey sliceViewKey = new SliceViewKey( magnification, active_layer.getZ() );
		synchronized ( cachedImages )
		{
			final long imageId;
			final Long imageIdL = cachedImages.get( sliceViewKey );
			if ( imageIdL == null )
			{
				imageId = project.getLoader().getNextTempId();
				cachedImages.put( sliceViewKey, imageId );
			}
			else
			{
				/* fetch the image from cache---still, it may be that it is not there... */
				imageId = imageIdL;
				mipMap = project.getLoader().getCached( cachedImages.get( sliceViewKey ), 0 );
			}
			if ( mipMap == null )
			{
				/* image has to be generated */
				fu = fetchFutureImage( imageId, magnification, active_layer, false ); // do not trigger repaint event
			}
		}

		// Paint outside the synchronization block:
		if (null != mipMap) {
			paint(g, mipMap.image);
		} else if (null != fu) {
			final Image image;
			try {
				image = fu.get();
			} catch (final Throwable ie) {
				IJ.log("Could not paint Stack " + this);
				IJError.print(ie);
				return;
			}
			// If I put the fu.get() where image is, it fails to compile. I had to separate it!
			paint(g, image); // will wait until present
		} else {
			Utils.log2("Stack.paint ERROR: no image to paint!");
		}
	}

	final private Future< Image > fetchFutureImage(final Long imageId, final double magnification, final Layer active_layer, final boolean trigger_repaint_event) {
		synchronized ( futureImages )
		{
			Future< Image > fu = futureImages.get( imageId );
			if ( null == fu )
			{
				fu = project.getLoader().doLater( new Callable< Image >()
				{
					@Override
                    public Image call()
					{
						final InvertibleCoordinateTransformList< mpicbg.models.InvertibleCoordinateTransform > ictl = new InvertibleCoordinateTransformList< mpicbg.models.InvertibleCoordinateTransform >();
						if ( ict != null )
						{
//									Utils.log2( "ictScale of " + getTitle() + " is " + ictScale );
							ictl.add( ict );

							/* Remove boundingBox shift ict ... */
							final TranslationModel3D unShiftBounds = new TranslationModel3D();
							unShiftBounds.set( -boundsMin[ 0 ], -boundsMin[ 1 ], 0 );
							ictl.add( unShiftBounds );

							if ( ictScale != 1.0 )
							{
								final AffineModel3D unScaleXY = new AffineModel3D();
									unScaleXY.set(
											1.0 / ictScale, 0, 0, 0,
											0, 1.0 / ictScale, 0, 0,
											0, 0, 1.0, 0 );
									ictl.add( unScaleXY );
							}
						}

						/* TODO remove that scale from ict and put it into atp */

						final ImagePlus imp = project.getLoader().fetchImagePlus( Stack.this );
						final ImageProcessor ip = imp.getStack().getProcessor( 1 ).createProcessor( ( int )Math.ceil( ( boundsMax[ 0 ] - boundsMin[ 0 ] ) / ictScale ), ( int )Math.ceil( ( boundsMax[ 1 ] - boundsMin[ 1 ] ) / ictScale ) );

						//Utils.log2( "ictScale is " + ictScale );
						//Utils.log2( "rendering an image of " + ip.getWidth() + " x " + ip.getHeight() + " px" );

						final double currentZ = active_layer.getZ();

						final TranslationModel3D sliceShift = new TranslationModel3D();
						sliceShift.set( 0, 0, -currentZ );
						ictl.add( sliceShift );

						/* optimization: if ict is affine, reduce ictl into a single affine */
						final InverseTransformMapping< mpicbg.models.InvertibleCoordinateTransform > mapping;
						if ( AffineModel3D.class.isInstance( ict ) )
						{
							final AffineModel3D ictAffine = new AffineModel3D();
							boolean isAffine = true;
							for ( final mpicbg.models.InvertibleCoordinateTransform t : ictl.getList( null ) )
							{
								if ( AffineModel3D.class.isInstance( t ) )
									ictAffine.preConcatenate( ( AffineModel3D )t );
								else if ( TranslationModel3D.class.isInstance( t ) )
									ictAffine.preConcatenate( ( TranslationModel3D )t );
								else
								{
									isAffine = false;
									break;
								}
							}
							if ( isAffine )
								mapping = new InverseTransformMapping< mpicbg.models.InvertibleCoordinateTransform >( ictAffine );
							else
								mapping = new InverseTransformMapping< mpicbg.models.InvertibleCoordinateTransform >( ictl );
						}
						else
							mapping = new InverseTransformMapping< mpicbg.models.InvertibleCoordinateTransform >( ictl );
						mapping.mapInterpolated( imp.getStack(), ip );

						final double s = estimateAffineScale( new AffineTransform( at ) ); // wast: atp

						final double smoothMag = magnification * s * ictScale;
						if ( smoothMag < 1.0f )
						{
							Filter.smoothForScale( ip, smoothMag, 0.5f, 0.5f );
						}

						final Image image = ip.createImage();

						if ( null == image )
						{
							Utils.log2( "Stack.paint: null image, returning" );
							return null; // TEMPORARY from lazy
											// repaints after closing a
											// Project
						}

						project.getLoader().cacheAWT( imageId, image );

						synchronized ( futureImages )
						{
							futureImages.remove( imageId );
						}

						if ( trigger_repaint_event )
						{
							// Display.repaint( active_layer, Stack.this );
							Display.repaint( active_layer );
						}

						return image;
					}
				});
			} // else {
			// Utils.log2( "fu is not null" );
			// // We don't do anything: we wait for itself to launch a
			// repaint event
			// }

			futureImages.put( imageId, fu );
			return fu;
		}
	}

	/** Will not paint but fork a task to create an image to paint later, when not already cached. */
	@Override
	public void prePaint(
			final Graphics2D g,
			final Rectangle srcRect,
			final double magnification,
			final boolean active,
			final int channels,
			final Layer active_layer,
			final List<Layer> _ignored)
	{

		//final Image image = project.getLoader().fetchImage(this,0);
		//Utils.log2("Patch " + id + " painted image " + image);

		final double currentZ = active_layer.getZ();
		MipMapImage mipMap = null;
		synchronized ( cachedImages )
		{
			final SliceViewKey sliceViewKey = new SliceViewKey( magnification, currentZ );
			final long imageId;
			final Long imageIdL = cachedImages.get( sliceViewKey );
			if ( imageIdL == null )
			{
				imageId = project.getLoader().getNextTempId();
				cachedImages.put( sliceViewKey, imageId );
			}
			else
			{
				/* fetch the image from cache---still, it may be that it is not there... */
				imageId = imageIdL;
				mipMap = project.getLoader().getCached( cachedImages.get( sliceViewKey ), 0 );
			}
			if ( mipMap == null )
			{
				/* image has to be generated */
				fetchFutureImage( imageId, magnification, active_layer, true );
				return;
			}
		}

		if ( mipMap != null) {
			paint( g, mipMap.image );
		}
	}

	final private void paint( final Graphics2D g, final Image image )
	{
		final AffineTransform atp = new AffineTransform( this.at );

		/* Put boundShift into atp */
		final AffineTransform shiftBounds = new AffineTransform( 1, 0, 0, 1, boundsMin[ 0 ], boundsMin[ 1 ] );
		atp.concatenate( shiftBounds );

		/* If available, incorporate the involved x,y-scale of ict in the AffineTransform */
		final AffineTransform asict = new AffineTransform( ictScale, 0, 0, ictScale, 0, 0 );
		atp.concatenate( asict );

		final Composite original_composite = g.getComposite();
		// Fail gracefully for graphics cards that don't support custom composites, like ATI cards:
		try {
			g.setComposite( getComposite(getCompositeMode()) );
			g.drawImage( image, atp, null );
		} catch (final Throwable t) {
			Utils.log(new StringBuilder("Cannot paint Stack with composite type ").append(compositeModes[getCompositeMode()]).append("\nReason:\n").append(t.toString()).toString());
			g.setComposite( getComposite( COMPOSITE_NORMAL ) );
			g.drawImage( image, atp, null );
		}

		g.setComposite( original_composite );
	}

	static public final void exportDTD( final StringBuilder sb_header, final HashSet<String> hs, final String indent ) {
		final String type = "t2_stack";
		if (hs.contains(type)) return;
		hs.add( type );
		sb_header.append(indent).append("<!ELEMENT t2_stack (").append(Displayable.commonDTDChildren()).append(",(iict_transform|iict_transform_list)?)>\n");
		Displayable.exportDTD( type, sb_header, hs, indent );
		sb_header.append(indent).append(TAG_ATTR1).append(type).append(" file_path CDATA #REQUIRED>\n")
			 .append(indent).append(TAG_ATTR1).append(type).append(" depth CDATA #REQUIRED>\n");
	}

	/** Opens and closes the tag and exports data. The image is saved in the directory provided in @param any as a String. */
	@Override
	public void exportXML(final StringBuilder sb_body, final String indent, final XMLOptions options) { // TODO the Loader should handle the saving of images, not this class.
		final String in = indent + "\t";
		sb_body.append(indent).append("<t2_stack\n");

		super.exportXML(sb_body, in, options);
		final String[] RGB = Utils.getHexRGBColor(color);

		sb_body.append(in).append("file_path=\"").append(file_path).append("\"\n")
		       .append(in).append("style=\"fill-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";\"\n")
		       .append(in).append("depth=\"").append(depth).append("\"\n")
		       .append(in).append("min=\"").append(min).append("\"\n")
		       .append(in).append("max=\"").append(max).append("\"\n")
		;

		sb_body.append(indent).append(">\n");

		if (null != ict) {
			sb_body.append(ict.toXML(in)).append('\n');
		}

		super.restXML(sb_body, in, options);

		sb_body.append(indent).append("</t2_stack>\n");
	}

	@Override
	protected Rectangle getBounds( final Rectangle rect )
	{
		final AffineModel2D a = new AffineModel2D();
		a.set( at );

		final double[] rMin = new double[]{ Double.MAX_VALUE, Double.MAX_VALUE };
		final double[] rMax = new double[]{ -Double.MAX_VALUE, -Double.MAX_VALUE };

		final double[] l = new double[]{ boundsMin[ 0 ], boundsMin[ 1 ] };

		a.applyInPlace( l );
		Util.min( rMin, l );
		Util.max( rMax, l );

		l[ 0 ] = boundsMin[ 0 ];
		l[ 1 ] = boundsMax[ 1 ];
		a.applyInPlace( l );
		Util.min( rMin, l );
		Util.max( rMax, l );

		l[ 0 ] = boundsMax[ 0 ];
		l[ 1 ] = boundsMin[ 1 ];
		a.applyInPlace( l );
		Util.min( rMin, l );
		Util.max( rMax, l );

		l[ 0 ] = boundsMax[ 0 ];
		l[ 1 ] = boundsMax[ 1 ];
		a.applyInPlace( l );
		Util.min( rMin, l );
		Util.max( rMax, l );

		rect.x = ( int )rMin[ 0 ];
		rect.y = ( int )rMin[ 1 ];
		rect.width = ( int )Math.ceil( rMax[ 0 ] - rect.x );
		rect.height = ( int )Math.ceil( rMax[ 1 ] - rect.y );

		return rect;
	}

	private void update()
	{
		boundsMin[ 0 ] = 0;
		boundsMin[ 1 ] = 0;
		boundsMin[ 2 ] = 0;

		boundsMax[ 0 ] = width;
		boundsMax[ 1 ] = height;
		boundsMax[ 2 ] = depth;

		if ( ict == null )
		{
//			Utils.log2( "ict is null" );
			return;
		}
		else if ( Boundable.class.isInstance( ict ) )
		{
//			Utils.log2( ict + " is a boundable" );
			( ( Boundable )ict ).estimateBounds( boundsMin, boundsMax );
//			Utils.log2( ict + "its bounds are (" + boundsMin[ 0 ] + ", " + boundsMin[ 1 ] + ", " + boundsMin[ 2 ] + ") -> (" + boundsMax[ 0 ] + ", " + boundsMax[ 1 ] + ", " + boundsMax[ 2 ] + ")" );
		}
		else
		{
			Utils.log2( ict + " is not a boundable" );
			final ArrayList< Layer > layers = layer_set.getLayers();
			boundsMax[ 0 ] = layer_set.width;
			boundsMax[ 1 ] = layer_set.height;
			boundsMax[ 2 ] = ( layers.get( layers.size() - 1 ).getZ() - layers.get( 0 ).getZ() );
		}

		if ( ict != null )
		{
			if ( AffineModel3D.class.isInstance( ict ) )
			{
				final double[] m = ( ( AffineModel3D )ict ).getMatrix( null );

				final double dxs = m[ 0 ] * m[ 0 ] + m[ 4 ] * m[ 4 ];
				final double dys = m[ 1 ] * m[ 1 ] + m[ 5 ] * m[ 5 ];
				final double dzs = m[ 2 ] * m[ 2 ] + m[ 6 ] * m[ 6 ];

				ictScale = Math.sqrt( Math.max( dxs, Math.max( dys, dzs ) ) );
			}
		}
	}

	/**
	 * For now, just returns the bounding box---we can refine this later
	 */
	@Override
    public Polygon getPerimeter()
	{
		final Rectangle r = getBoundingBox();
		return new Polygon(
				new int[]{ r.x, r.x + r.width, r.x + r.width, r.x },
				new int[]{ r.y, r.y, r.y + r.height, r.y + r.height },
				4 );
	}

	/** For reconstruction purposes, overwrites the present InvertibleCoordinateTransform, if any, with the given one. */
	public void setInvertibleCoordinateTransformSilently( final InvertibleCoordinateTransform ict )
	{
		this.ict = ict;
		update();
	}

	private void invalidateCache()
	{
		cachedImages.clear();
	}

	public void setInvertibleCoordinateTransform( final InvertibleCoordinateTransform ict )
	{
		invalidateCache();
		setInvertibleCoordinateTransformSilently( ict );
	}

	@Override
    public void setAffineTransform( final AffineTransform at )
	{
		invalidateCache();
		super.setAffineTransform( at );
	}

	/** Avoid calling the trees: the stack exists only in the LayerSet ZDisplayable's list. */
	@Override
    public boolean remove2(final boolean check) {
		return remove(check);
	}

	@Override
	protected boolean calculateBoundingBox(final Layer la) { return true; }
}
