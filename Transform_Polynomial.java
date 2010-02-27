import lenscorrection.NonLinearTransform;
import lenscorrection.PolynomialModel2D;
import mpicbg.ij.InverseTransformMapping;
import mpicbg.ij.Mapping;
import mpicbg.ij.TransformMeshMapping;
import mpicbg.ij.util.Util;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformMesh;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.MovingLeastSquaresTransform;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;

import ij.plugin.*;
import ij.gui.*;
import ij.*;
import ij.process.*;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;

/**
 * Use two sets of {@link PointRoi landmarks} selected in two images to map
 * one image to the other.
 * 
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.2b
 */
public class Transform_Polynomial implements PlugIn
{
	final static private DecimalFormat decimalFormat = new DecimalFormat();
	final static private DecimalFormatSymbols decimalFormatSymbols = new DecimalFormatSymbols();
	
	static private int order = 3;
	static private float lambda = 0.01f;
	
	final static private String[] modelClasses = new String[]{ "Translation", "Rigid", "Similarity", "Affine" };
	static private int modelClassIndex = 3;
	
	static int meshResolution = 32;
	
	static private boolean interpolate = true;
	
	protected ImagePlus source;
	protected ImagePlus template;
	
	public Transform_Polynomial()
	{
		decimalFormatSymbols.setGroupingSeparator( ',' );
		decimalFormatSymbols.setDecimalSeparator( '.' );
		decimalFormat.setDecimalFormatSymbols( decimalFormatSymbols );
		decimalFormat.setMaximumFractionDigits( 3 );
		decimalFormat.setMinimumFractionDigits( 3 );		
	}
	
	final static protected void transform(
			final CoordinateTransform transform,
			final ImageProcessor source,
			final ImageProcessor target )
	{
		for ( int y = 0; y < target.getHeight(); ++y )
		{
			for ( int x = 0; x < target.getWidth(); ++x )
			{
				float[] t = new float[]{ x, y };
				transform.applyInPlace( t );
				target.putPixel( x, y, source.getPixel( ( int )t[ 0 ], ( int )t[ 1 ] ) );
			}
		}	
	}
	
	final static protected void transformInterpolated(
			final CoordinateTransform transform,
			final ImageProcessor source,
			final ImageProcessor target )
	{
		for ( int y = 0; y < target.getHeight(); ++y )
		{
			for ( int x = 0; x < target.getWidth(); ++x )
			{
				float[] t = new float[]{ x, y };
				transform.applyInPlace( t );
				target.putPixel( x, y, source.getPixelInterpolated( t[ 0 ], t[ 1 ] ) );
			}
		}	
	}
	
	final public void run( String args )
	{
		final ArrayList< PointMatch > matches = new ArrayList< PointMatch >();
		
		if ( !setup() ) return;
		
		final ImagePlus target = template.createImagePlus();
		
		final ImageProcessor ipSource = source.getProcessor();
		final ImageProcessor ipTarget = source.getProcessor().createProcessor( template.getWidth(), template.getHeight() );
		
		/* Collect the PointRois from both images and make PointMatches of them. */
		final List< Point > sourcePoints = Util.pointRoiToPoints( ( PointRoi )source.getRoi() );
		final List< Point > templatePoints = Util.pointRoiToPoints( ( PointRoi )template.getRoi() );
		
		final int numMatches = Math.min( sourcePoints.size(), templatePoints.size() );
		
		for ( int i = 0; i < numMatches; ++i )
			matches.add( new PointMatch( sourcePoints.get( i ), templatePoints.get( i ) ) );
		
		final TransformMeshMapping< CoordinateTransformMesh > mapping;
		
		final PolynomialModel2D t = new PolynomialModel2D();
		try
		{
			switch ( modelClassIndex )
			{
			case 0:
				t.setAffine( TranslationModel2D.class );
				break;
			case 1:
				t.setAffine( RigidModel2D.class );
				break;
			case 2:
				t.setAffine( SimilarityModel2D.class );
				break;
			case 3:
				t.setAffine( AffineModel2D.class );
				break;
			default:
				return;
			}
		}
		catch ( Exception e ) { return; }
		t.setLambda( lambda );
		t.setOrder( order );
		
		try
		{
			t.fit( matches );
			mapping = new TransformMeshMapping< CoordinateTransformMesh >( new CoordinateTransformMesh( t, meshResolution, source.getWidth(), source.getHeight() ) );
		}
		catch ( NotEnoughDataPointsException e )
		{
			IJ.showMessage( "Not enough landmarks selected to find a transformation model." );
			return;
		}
		catch ( IllDefinedDataPointsException e )
		{
			IJ.showMessage( "The set of landmarks is ill-defined in terms of the desired transformation." );
			return;
		}
		
		if ( interpolate )
			mapping.mapInterpolated( ipSource, ipTarget );
		else
			mapping.map( ipSource, ipTarget );
		
		target.setProcessor( "Transformed" + source.getTitle(), ipTarget );
		target.show();
	}
	
	final protected boolean setup()
	{
		if ( IJ.versionLessThan( "1.40c" ) ) return false;
		
		final int[] ids = WindowManager.getIDList();
		if ( ids == null || ids.length < 2 )
		{
			IJ.showMessage( "You should have at least two images open." );
			return false;
		}
		
		final ArrayList< String > titlesList = new ArrayList< String >();
		final ArrayList< Integer > idsList = new ArrayList< Integer >();
		String currentTitle = null;
		for ( int i = 0; i < ids.length; ++i )
		{
			final ImagePlus imp = WindowManager.getImage( ids[ i ] );
			final Roi roi = imp.getRoi();
			if ( roi != null && roi.getType() == Roi.POINT )
			{
				titlesList.add( imp.getTitle() );
				idsList.add( ids[ i ] );
				if ( imp == WindowManager.getCurrentImage() )
					currentTitle = imp.getTitle();
			}	
		}
		
		if ( titlesList.size() < 2 )
		{
			IJ.showMessage( "You should have at least two images with selected landmark correspondences open." );
			return false;
		}
		final String[] titles = new String[ titlesList.size() ];
		titlesList.toArray( titles );
		
		if ( currentTitle == null )
			currentTitle = titles[ 0 ];
		final GenericDialog gd = new GenericDialog( "Transform" );
		
		gd.addChoice( "source_image", titles, currentTitle );
		gd.addChoice( "template_image", titles, currentTitle.equals( titles[ 0 ] ) ? titles[ 1 ] : titles[ 0 ] );
		gd.addNumericField( "order", order, 0 );
		gd.addNumericField( "lambda", lambda, 2 );
		gd.addChoice( "transformation_class", modelClasses, modelClasses[ modelClassIndex ] );
		gd.addNumericField( "mesh_resolution", meshResolution, 0 );
		gd.addCheckbox( "interpolate", interpolate );
		gd.showDialog();
		
		if ( gd.wasCanceled() ) return false;
		
		source = WindowManager.getImage( idsList.get( gd.getNextChoiceIndex() ) );
		template = WindowManager.getImage( idsList.get( gd.getNextChoiceIndex() ) );
		order = ( int )gd.getNextNumber();
		lambda = ( float )gd.getNextNumber();
		modelClassIndex = gd.getNextChoiceIndex();
		meshResolution = ( int )gd.getNextNumber();
		interpolate = gd.getNextBoolean();
		
		return true;		
	}
}
