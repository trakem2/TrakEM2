/**
 * 
 */
package mpicbg.trakem2.align;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashSet;

import mpicbg.ij.visualization.PointVis;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import ij.IJ;
import ij.ImagePlus;
import ij.process.ColorProcessor;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.display.graphics.AddARGBComposite;
import ini.trakem2.display.graphics.SubtractInverseARGBComposite;

public class TileConfiguration extends mpicbg.models.TileConfiguration
{
	@Override
	public void optimize(
			final float maxAllowedError,
			final int maxIterations,
			final int maxPlateauwidth ) throws NotEnoughDataPointsException, IllDefinedDataPointsException 
	{
		println( "Optimizing..." );
		
		final ErrorStatistic observer = new ErrorStatistic( maxPlateauwidth + 1 );
		
		int i = 0;
		
		boolean proceed = i < maxIterations;
		
		/* initialize the configuration with the current model of each tile */
		apply();
		updateErrors();
		observer.add( error );
		
		println( "i mean min max" );
		
		while ( proceed )
		{
			visualizeOptimizationIteration( i );
			
			for ( final Tile< ? > tile : tiles )
			{
				if ( fixedTiles.contains( tile ) ) continue;
				tile.fitModel();
				tile.apply();
			}
			updateErrors();
			observer.add( error );
			
			if ( i > maxPlateauwidth )
			{
				proceed = error > maxAllowedError;
				
				int d = maxPlateauwidth;
				while ( !proceed && d >= 1 )
				{
					try
					{
						proceed |= Math.abs( observer.getWideSlope( d ) ) > 0.0001;
					}
					catch ( Exception e ) { e.printStackTrace(); }
					d /= 2;
				}
			}
			
			println( new StringBuffer( i + " " ).append( error ).append( " " ).append( minError ).append( " " ).append( maxError ).toString() );
			
			proceed &= ++i < maxIterations;
		}
		
		println( new StringBuffer( "Successfully optimized configuration of " ).append( tiles.size() ).append( " tiles after " ).append( i ).append( " iterations:" ).toString() );
		println( new StringBuffer( "  average displacement: " ).append( decimalFormat.format( error ) ).append( "px" ).toString() );
		println( new StringBuffer( "  minimal displacement: " ).append( decimalFormat.format( minError ) ).append( "px" ).toString() );
		println( new StringBuffer( "  maximal displacement: " ).append( decimalFormat.format( maxError ) ).append( "px" ).toString() );
	}
	
	@Override
	protected void println( String s ){ IJ.log( s ); }
	
	public void visualizeOptimizationIteration( final int i )
	{
		final int width = 512;
		final int height = 512;
		
		final double displayWidth = ( ( AbstractAffineTile2D< ? > )tiles.iterator().next() ).getPatch().getLayerSet().getLayerWidth();
		final double displayHeight = ( ( AbstractAffineTile2D< ? > )tiles.iterator().next() ).getPatch().getLayerSet().getLayerHeight();
		
		final double magnification = Math.min( width / displayWidth, height / displayHeight );
		
		/* calculate bounding box */
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		for ( final Tile< ? > t : tiles )
		{
			final AbstractAffineTile2D< ? > at = ( AbstractAffineTile2D< ? > )t;
			
			final Rectangle b = at.getPatch().getBoundingBox();
			minX = Math.min( minX, b.x );
			maxX = Math.max( maxX, b.x + b.width );
			minY = Math.min( minY, b.y );
			maxY = Math.max( maxY, b.y + b.height );
			
			at.getPatch().setAffineTransform( at.createAffine() );
		}
		
		int lastLayerIndex = 0;
		final LayerSet layerSet = ( ( AbstractAffineTile2D< ? > )tiles.iterator().next() ).getPatch().getLayerSet();
		final ArrayList< Layer > layers = layerSet.getLayers();
		for ( final Tile< ? > t : tiles )
			lastLayerIndex = Math.max( lastLayerIndex, layers.indexOf( ( ( AbstractAffineTile2D< ? > )t ).getPatch().getLayer() ) );
		
		final Rectangle srcRect = new Rectangle( 0, 0, ( int )displayWidth, ( int )displayHeight );
		
		final BufferedImage imgLayerSet = new BufferedImage( width, height, BufferedImage.TYPE_INT_RGB );
		final Graphics2D g = imgLayerSet.createGraphics();
		g.setBackground( Color.WHITE );
		g.clearRect( 0, 0, width, height );
		for ( int l = 0; l <= lastLayerIndex; ++ l )
		{
			if ( l == lastLayerIndex )
				g.setComposite( SubtractInverseARGBComposite.getInstance( 1.0f ) );				
			else
				//g.setComposite( SubtractInverseARGBComposite.getInstance( 0.33f ) );
				g.setComposite( AlphaComposite.getInstance( AlphaComposite.SRC_OVER, 0.33f ) );
			
			final Layer layer = layers.get( l );
			final Image imgLayer = layer.getProject().getLoader().getFlatAWTImage( layer, srcRect, magnification, -1, ImagePlus.COLOR_RGB, Patch.class, null, false, Color.WHITE );
			
			g.drawImage( imgLayer, 0, 0, null );
		}
		
		final ColorProcessor ip = new ColorProcessor( imgLayerSet );
		for ( final Tile< ? > t : tiles )
			PointVis.drawWorldPointMatchLines( ip, t.getMatches(), Color.MAGENTA, 1, srcRect, srcRect, magnification, magnification );
		
		for ( final Tile< ? > t : tiles )
		{
			final ArrayList< Point > srcPoints = new ArrayList< Point >();
			PointMatch.sourcePoints( t.getMatches(), srcPoints );
			PointVis.drawWorldPoints( ip, srcPoints, Color.GREEN, 3, srcRect, magnification );
		}
		
		ip.setAntialiasedText( true );
		ip.setFont( new Font( "Sans", Font.PLAIN, 18 ) );
		ip.setColor( Color.BLACK );
		ip.drawString( "i: " + i, 20, 490 );
		ip.drawString( "e: (" + String.format( "%.2f", minError ) + ", " + String.format( "%.2f", error ) + ", " + String.format( "%.2f", maxError ) + ")", 120, 490 );
		
		final ImagePlus impI = new ImagePlus( "i " + i, ip );
		IJ.save( impI, "optimize-" + String.format( "%04d", lastLayerIndex ) + "-" + String.format( "%04d", i ) + ".tif" );
	}
}
