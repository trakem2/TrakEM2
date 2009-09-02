/**
 * 
 */
package ini.trakem2.display;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ini.trakem2.Project;
import ini.trakem2.display.graphics.ARGBComposite;
import ini.trakem2.display.graphics.AddARGBComposite;
import ini.trakem2.display.graphics.ColorYCbCrComposite;
import ini.trakem2.display.graphics.DifferenceARGBComposite;
import ini.trakem2.display.graphics.MultiplyARGBComposite;
import ini.trakem2.display.graphics.SubtractARGBComposite;
import ini.trakem2.utils.M;
import ini.trakem2.utils.Utils;

import java.awt.AlphaComposite;
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
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
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
 * @author saalfeld
 *
 */
public class Stack extends ZDisplayable
{
	private String file_path;
	private double depth = -1;
	private double min;
	private double max;
	private InvertibleCoordinateTransform ict;
	final private float[] boundsMin = new float[]{ 0, 0, 0 };
	final private float[] boundsMax = new float[]{ 0, 0, 0 };
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
		final public boolean equals( Object o )
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
	
	public Stack( final Project project, final String title, double x, double y, Layer initial_layer, final String file_path )
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
		
		boundsMin[ 0 ] = ( float )x;
		boundsMin[ 1 ] = ( float )y;
		boundsMin[ 2 ] = ( float )initial_layer.getZ();
		
		boundsMax[ 0 ] = ( float )( x + width );
		boundsMax[ 1 ] = ( float )( y + height );
		boundsMax[ 2 ] = ( float )( boundsMin[ 2 ] + depth );
		
		addToDatabase();
	}

	/** Construct a Stack from an XML entry. */
	public Stack(Project project, long id, HashMap ht, HashMap ht_links) {
		super(project, id, ht, ht_links);
		// parse specific fields
		
		final Iterator it = ht.entrySet().iterator();
		while (it.hasNext()) {
			final Map.Entry entry = (Map.Entry)it.next();
			final String key = (String)entry.getKey();
			final String data = (String)entry.getValue();
			if (key.equals("min")) {
				this.min = Double.parseDouble(data);
			} else if (key.equals("max")) {
				this.max = Double.parseDouble(data);
			} else if (key.equals("file_path")) {
				this.file_path = data;
			} else if (key.equals("depth")) {
				this.depth = Double.parseDouble(data);
			}
		}
		boundsMin[ 0 ] = 0;
		boundsMin[ 1 ] = 0;
		boundsMin[ 2 ] = 0;
		
		boundsMax[ 0 ] = ( float )width;
		boundsMax[ 1 ] = ( float )height;
		boundsMax[ 2 ] = ( float )depth;
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
	public boolean intersects( Area area, double z_first, double z_last )
	{
		// TODO Auto-generated method stub
		return false;
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.display.ZDisplayable#linkPatches()
	 */
	@Override
	public void linkPatches()
	{
	// TODO Auto-generated method stub

	}

	/* (non-Javadoc)
	 * @see ini.trakem2.display.Displayable#clone(ini.trakem2.Project, boolean)
	 */
	@Override
	public Displayable clone( Project pr, boolean copy_id )
	{
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see ini.trakem2.display.Displayable#isDeletable()
	 */
	@Override
	public boolean isDeletable()
	{
		// TODO Auto-generated method stub
		return false;
	}
	
	public String getFilePath(){
		return this.file_path;
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
	final static private float estimateAffineScale( final AffineTransform atp )
	{
		float d = 0;
		
		/* add */
		final float aX = ( float )atp.getScaleX() + ( float )atp.getShearX();
		final float aY = ( float )atp.getShearY() + ( float )atp.getScaleY();
		d = Math.max( d, aX * aX + aY * aY );
		/* subtract */
		final float sX = ( float )atp.getScaleX() - ( float )atp.getShearX();
		final float sY = ( float )atp.getShearY() - ( float )atp.getScaleY();
		d = Math.max( d, sX * sX + sY * sY );
		
		return Util.SQRT1 / ( float )Math.sqrt( d );
		
	}
	
	@Override
	public void paint(
			final Graphics2D g,
			final double magnification,
			final boolean active,
			final int channels,
			final Layer active_layer )
	{

		final AffineTransform atp = new AffineTransform( this.at );
		
		//final Image image = project.getLoader().fetchImage(this,0);
		//Utils.log2("Patch " + id + " painted image " + image);
		
		final double currentZ = active_layer.getZ();
		Image image = null;
		synchronized ( cachedImages )
		{
			final SliceViewKey sliceViewKey = new SliceViewKey( magnification, currentZ );
			final long imageId;
			Long imageIdL = cachedImages.get( sliceViewKey );
			if ( imageIdL == null )
			{
				imageId = project.getLoader().getNextTempId();
				cachedImages.put( sliceViewKey, imageId );
			}
			else
			{
				/* fetch the image from cache---still, it may be that it is not there... */
				imageId = imageIdL;
				image = project.getLoader().getCached( cachedImages.get( sliceViewKey ), 0 );
			}
			
			if ( image == null )
			{
				/* image has to be generated */
				synchronized ( futureImages )
				{
					Future< Image > fu = futureImages.get( imageId );
					if ( null == fu )
					{
						futureImages.put( imageId, project.getLoader().doLater( new Callable< Image >()
						{
							public Image call()
							{
								final InvertibleCoordinateTransformList< mpicbg.models.InvertibleCoordinateTransform > ictl = new InvertibleCoordinateTransformList< mpicbg.models.InvertibleCoordinateTransform >();
								if ( ict != null )
								{
									Utils.log2( "ictScale of " + getTitle() + " is " + ictScale );
									ictl.add( ict );
									
									/* Remove boundingBox shift ict ... */
									final TranslationModel3D unShiftBounds = new TranslationModel3D();
									unShiftBounds.set( -boundsMin[ 0 ], -boundsMin[ 1 ], 0 );
									ictl.add( unShiftBounds );
									
									if ( ictScale != 1.0 )
									{
										final AffineModel3D unScaleXY = new AffineModel3D();
											unScaleXY.set(
													1.0f / ( float )ictScale, 0, 0, 0,
													0, 1.0f / ( float )ictScale, 0, 0,
													0, 0, 1.0f, 0 );
											ictl.add( unScaleXY );
									}
								}
								
								/* TODO remove that scale from ict and put it into atp */
								
								final ImagePlus imp = project.getLoader().fetchImagePlus( Stack.this );
								final ImageProcessor ip = imp.getStack().getProcessor( 1 ).createProcessor( ( int )Math.ceil( ( boundsMax[ 0 ] - boundsMin[ 0 ] ) / ictScale ), ( int )Math.ceil( ( boundsMax[ 1 ] - boundsMin[ 1 ] ) / ictScale ) );

								final TranslationModel3D sliceShift = new TranslationModel3D();
								sliceShift.set( 0, 0, ( float )-currentZ );
								ictl.add( sliceShift );

								final InverseTransformMapping< InvertibleCoordinateTransformList< mpicbg.models.InvertibleCoordinateTransform > > mapping = new InverseTransformMapping< InvertibleCoordinateTransformList< mpicbg.models.InvertibleCoordinateTransform > >( ictl );
								mapping.mapInterpolated( imp.getStack(), ip );
								
								final float s = estimateAffineScale( atp );

								final float smoothMag = ( float )magnification / s;
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

								// Display.repaint( active_layer, Stack.this );
							  	Display.repaint( active_layer );

								return image;
							}
						} ) );
					} // else {
					// Utils.log2( "fu is not null" );
					// // We don't do anything: we wait for itself to launch a
					// repaint event
					// }
				}
			}
		}

		if ( image != null) {
			/* Put boundShift into atp */
			final AffineTransform shiftBounds = new AffineTransform( 1, 0, 0, 1, boundsMin[ 0 ], boundsMin[ 1 ] );
			atp.concatenate( shiftBounds );
				
			/* If available, incorporate the involved x,y-scale of ict in the AffineTransform */
			final AffineTransform asict = new AffineTransform( ictScale, 0, 0, ictScale, 0, 0 );
			atp.concatenate( asict );
			
			final Composite original_composite = g.getComposite();
			g.setComposite( getComposite() );
			g.drawImage( image, atp, null );
			g.setComposite( original_composite );
			
		}
	}
	
	static public final void exportDTD( final StringBuffer sb_header, final HashSet hs, final String indent ) {
		String type = "t2_stack";
		if (hs.contains(type)) return;
		hs.add( type );
		sb_header.append(indent).append("<!ELEMENT t2_stack (").append(Displayable.commonDTDChildren()).append(",(iict_transform|iict_transform_list)?)>\n");
		Displayable.exportDTD( type, sb_header, hs, indent );
		sb_header.append(indent).append(TAG_ATTR1).append(type).append(" file_path CDATA #REQUIRED>\n")
			 .append(indent).append(TAG_ATTR1).append(type).append(" depth CDATA #REQUIRED>\n");
	}
	
	/** Opens and closes the tag and exports data. The image is saved in the directory provided in @param any as a String. */
	public void exportXML(StringBuffer sb_body, String indent, Object any) { // TODO the Loader should handle the saving of images, not this class.
		String in = indent + "\t";
		sb_body.append(indent).append("<t2_stack\n");
		String rel_path = null;

		super.exportXML(sb_body, in, any);
		String[] RGB = Utils.getHexRGBColor(color);

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

		super.restXML(sb_body, in, any);

		sb_body.append(indent).append("</t2_stack>\n");
	}
	
	@Override
	protected Rectangle getBounds( final Rectangle rect )
	{
		final AffineModel2D a = new AffineModel2D();
		a.set( at );
		
		final float[] rMin = new float[]{ Float.MAX_VALUE, Float.MAX_VALUE };
		final float[] rMax = new float[]{ -Float.MAX_VALUE, -Float.MAX_VALUE };
		
		final float[] l = new float[]{ boundsMin[ 0 ], boundsMin[ 1 ] };
		
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
		
		boundsMax[ 0 ] = ( float )width;
		boundsMax[ 1 ] = ( float )height;
		boundsMax[ 2 ] = ( float )depth;
		
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
			boundsMax[ 0 ] = ( float )layer_set.width;
			boundsMax[ 1 ] = ( float )layer_set.height;
			boundsMax[ 2 ] = ( float )( layers.get( layers.size() - 1 ).getZ() - layers.get( 0 ).getZ() );
		}
		
		if ( ict != null )
		{
			if ( AffineModel3D.class.isInstance( ict ) )
			{
				float d = 0;
				final float[] m = ( ( AffineModel3D )ict ).getMatrix( null );
				
				/* add x and y */
				final float axyX = m[ 0 ] + m[ 1 ];
				final float axyY = m[ 4 ] + m[ 5 ];
				d = Math.max( d, axyX * axyX + axyY * axyY );
				/* subtract x and y */
				final float sxyX = m[ 0 ] - m[ 1 ];
				final float sxyY = m[ 4 ] - m[ 5 ];
				d = Math.max( d, sxyX * sxyX + sxyY * sxyY );
				
				/* add x and z */
				final float axzX = m[ 0 ] + m[ 2 ];
				final float axzY = m[ 4 ] + m[ 6 ];
				d = Math.max( d, axzX * axzX + axzY * axzY );
				/* subtract x and z */
				final float sxzX = m[ 0 ] - m[ 2 ];
				final float sxzY = m[ 4 ] - m[ 6 ];
				d = Math.max( d, sxzX * sxzX + sxzY * sxzY );
				
				/* add y and z */
				final float ayzX = m[ 1 ] + m[ 2 ];
				final float ayzY = m[ 5 ] + m[ 6 ];
				d = Math.max( d, ayzX * ayzX + ayzY * ayzY );
				/* subtract y and z */
				final float syzX = m[ 1 ] - m[ 2 ];
				final float syzY = m[ 5 ] - m[ 6 ];
				d = Math.max( d, syzX * syzX + syzY * syzY );
				
				ictScale = ( float )Math.sqrt( d ) / Util.SQRT1;
			}
		}
	}
	
	/**
	 * For now, just returns the bounding box---we can refine this later
	 */
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
		Utils.log2( "insering the ict" );
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
	
	public void setAffineTransform( final AffineTransform at )
	{
		invalidateCache();
		super.setAffineTransform( at );
	}

	/** Avoid calling the trees: the stack exists only in the LayerSet ZDisplayable's list. */
	public boolean remove2(boolean check) {
		return remove(check);
	}
}
