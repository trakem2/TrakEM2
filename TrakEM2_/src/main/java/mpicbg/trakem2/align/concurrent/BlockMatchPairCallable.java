package mpicbg.trakem2.align.concurrent;

import ij.ImagePlus;
import ij.plugin.filter.GaussianBlur;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import ini.trakem2.Project;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Filter;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Image;
import java.awt.Rectangle;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

import mpicbg.ij.blockmatching.BlockMatching;
import mpicbg.models.AbstractModel;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.InvertibleCoordinateTransform;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.trakem2.align.AlignmentUtils;
import mpicbg.trakem2.align.ElasticLayerAlignment;
import mpicbg.trakem2.align.Util;
import mpicbg.trakem2.transform.ExportUnsignedByte;
import mpicbg.trakem2.transform.ExportUnsignedShort;
import mpicbg.trakem2.util.Pair;
import mpicbg.trakem2.util.Triple;

/**
 *
 */
public class BlockMatchPairCallable implements
        Callable<BlockMatchPairCallable.BlockMatchResults>, Serializable
{

    public static class BlockMatchResults implements Serializable
    {
        public Collection<? extends Point> v1, v2;
        public final Collection<PointMatch> pm12, pm21;
        public final boolean layer1Fixed, layer2Fixed;
        public final Triple<Integer, Integer, AbstractModel<?>> pair;

        public BlockMatchResults(final Collection<? extends Point> v1,
                                 final Collection<? extends Point> v2,
                                 final Collection<PointMatch> pm12,
                                 final Collection<PointMatch> pm21,
                                 final boolean layer1Fixed,
                                 final boolean layer2Fixed,
                                 final Triple<Integer, Integer, AbstractModel<?>> pair)
        {
            this.v1 = v1;
            this.v2 = v2;
            this.pm12 = pm12;
            this.pm21 = pm21;
            this.layer1Fixed = layer1Fixed;
            this.layer2Fixed = layer2Fixed;
            this.pair = pair;
        }
    }

    private final Layer layer1, layer2;
    private final boolean layer1Fixed, layer2Fixed;
    private final Filter<Patch> filter;
    private final ElasticLayerAlignment.Param param;
    private final Collection<? extends Point> v1, v2;
    private final Rectangle box;
    private final Triple<Integer, Integer, AbstractModel<?>> pair;


    public BlockMatchPairCallable(final Triple<Integer, Integer, AbstractModel<?>> pair,
                                  final List<Layer> layerRange,
                                  final boolean layer1Fixed,
                                  final boolean layer2Fixed,
                                  final Filter<Patch> filter,
                                  final ElasticLayerAlignment.Param param,
                                  final Collection< ? extends Point > sourcePoints1,
                                  final Collection< ? extends Point > sourcePoints2,
                                  final Rectangle box)
    {
        this.pair = pair;
        layer1 = layerRange.get(pair.a);
        layer2 = layerRange.get(pair.b);
        this.layer1Fixed = layer1Fixed;
        this.layer2Fixed = layer2Fixed;
        this.filter = filter;
        this.param = param;
        v1 = sourcePoints1;
        v2 = sourcePoints2;
        this.box = box;
    }

    @Override
    public BlockMatchResults call() throws Exception
    {
        final ArrayList< PointMatch > pm12 = new ArrayList< PointMatch >();
        final ArrayList< PointMatch > pm21 = new ArrayList< PointMatch >();

        System.out.println("BMC rev 0: " + pair.a + " " + pair.b);

        final Pair< FloatProcessor, FloatProcessor > pair1 = makeFlatImage( layer1, AlignmentUtils.filterPatches( layer1, filter ), box, param.layerScale );
        final Pair< FloatProcessor, FloatProcessor > pair2 = makeFlatImage( layer2, AlignmentUtils.filterPatches( layer2, filter ), box, param.layerScale );
        
        final FloatProcessor ip1 = pair1.a;
        final FloatProcessor ip1Mask = pair1.b;
        final FloatProcessor ip2 = pair2.a;
        final FloatProcessor ip2Mask = pair2.b;
        
        final AbstractModel< ? > localSmoothnessFilterModel =
                Util.createModel(param.localModelIndex);

        final int blockRadius =
                Math.max( 16, mpicbg.util.Util.roundPos( param.layerScale * param.blockRadius ) );

        /* scale pixel distances */
        final int searchRadius = ( int )Math.round( param.layerScale * param.searchRadius );
        final double localRegionSigma = param.layerScale * param.localRegionSigma;
        final double maxLocalEpsilon = param.layerScale * param.maxLocalEpsilon;

        if (!layer1Fixed)
        {


            BlockMatching.matchByMaximalPMCC(
                    ip1,
                    ip2,
                    ip1Mask,
                    ip2Mask,
                    1.0,
                    ((InvertibleCoordinateTransform) pair.c).createInverse(),
                    blockRadius,
                    blockRadius,
                    searchRadius,
                    searchRadius,
                    param.minR,
                    param.rodR,
                    param.maxCurvatureR,
                    v1,
                    pm12,
                    new ErrorStatistic(1));

            if ( Thread.interrupted() )
            {
                throw new InterruptedException("Block matching interrupted.");
            }

            if ( param.useLocalSmoothnessFilter )
            {
                localSmoothnessFilterModel.localSmoothnessFilter( pm12, pm12, localRegionSigma,
                        maxLocalEpsilon, param.maxLocalTrust );
            }
        }

        if (!layer2Fixed)
        {
            BlockMatching.matchByMaximalPMCC(
                    ip2,
                    ip1,
                    ip2Mask,
                    ip1Mask,
                    1.0f,
                    pair.c,
                    blockRadius,
                    blockRadius,
                    searchRadius,
                    searchRadius,
                    param.minR,
                    param.rodR,
                    param.maxCurvatureR,
                    v2,
                    pm21,
                    new ErrorStatistic( 1 ) );

            if ( Thread.interrupted() )
            {
                throw new InterruptedException("Block matching interrupted.");
            }

            if ( param.useLocalSmoothnessFilter )
            {
                localSmoothnessFilterModel.localSmoothnessFilter( pm21, pm21, localRegionSigma, maxLocalEpsilon, param.maxLocalTrust );
            }
        }

//        Utils.log( pair.a + " <> " + pair.b + " spring constant = " + springConstant );


        return new BlockMatchResults(v1, v2, pm12, pm21, layer1Fixed, layer2Fixed, pair);
    }
    
    /**
     * The alpha channel is returned with values between [0..1]
     * 
     * 
     * @param layer
     * @param patches
     * @param box
     * @param scale
     * @return
     */
    private Pair< FloatProcessor, FloatProcessor > makeFlatImage( final Layer layer, final List<Patch> patches, final Rectangle box, final double scale ) {
    	
    	// Use different methods depending on the dimensions of the target image and the availability of mipmaps.
    	// The goal is to obtain the best possible image.
    	
    	final long fullSize = box.width * box.height;
    	
    	if ( fullSize < Math.pow(2, 29) && layer.getProject().getLoader().isMipMapsRegenerationEnabled() ) // 0.5 GB
    	{
    		// Will use an image 4x larger and then downscale with area averaging
    		final Image img = layer.getProject().getLoader().getFlatAWTImage( layer, box, scale, -1, ImagePlus.GRAY8, Patch.class, patches, true, null, null );
    		final FloatProcessor fp = new FloatProcessor( img.getWidth( null ), img.getHeight( null ) );
    		final FloatProcessor alpha = new FloatProcessor( img.getWidth( null ), img.getHeight( null ) );
    		
    		Util.imageToFloatAndMask( img, fp, alpha ); // already maps alpha into the [0..1] range
    		img.flush();
    		
    		return new Pair< FloatProcessor, FloatProcessor >( fp, alpha );
    	}
    	
    	if ( layer.getProject().getLoader().isMipMapsRegenerationEnabled() )
    	{
    		// Use mipmaps directly at the correct image size
    		final Pair< FloatProcessor, FloatProcessor > pair = ExportUnsignedByte.makeFlatImageFloat( patches, box, 0, scale );
    		
    		// Map alpha to [0..1]
            final float[] alpha = ( float[] ) pair.b.getPixels();
            for ( int i=0; i<alpha.length; ++i )
            	alpha[i] = alpha[i] / 255.0f;
    		
    		return pair;
    	}
    	
    	// Check if the image is too large for java 8.0
    	final int area = box.width * box.height;

    	if ( area > Math.pow(2,  31) )
    	{
    		Utils.log("Cannot create an image larger than 2 GB.");
    		return null;
    	}
    	
    	// Else, no mipmaps, and image smaller than 2 GB:
    	
    	// 1. Create an image of at most 2 GB or at most the maximum size
    	// Determine the largest size to work with
    	final int max_area = ( int ) Math.min( area, Math.pow(2, 31) );

    	// Determine the scale corresponding to the calculated max_area
    	final double scaleUP = Math.min(1.0, box.height / Math.sqrt( max_area / ( box.width / ( float ) (box.height) )));
    	
    	// Generate an image at the upper scale
    	// using ExportUnsignedShort which works without mipmaps
    	final Pair< FloatProcessor, FloatProcessor> pair = new Callable< Pair< FloatProcessor, FloatProcessor > >() {
    		// Use a local context to aid in GC'ing the ShortProcessor
    		public Pair< FloatProcessor, FloatProcessor > call() {
    			final Pair< ShortProcessor, ByteProcessor > pair = ExportUnsignedShort.makeFlatImage( patches, box, 0, scaleUP, true );
    			short[] pixS = (short[]) pair.a.getPixels();
    			final float[] pixF = new float[ pixS.length ];
    			for ( int i=0; i<pixS.length; ++i) pixF[i] = pixS[i] & 0xffff;
    			pixS = null;
    			pair.a.setPixels( null ); // "destructor"
    			
    			byte[] pixB = (byte[]) pair.b.getPixels();
    			final float[] pixA = new float[ pixB.length ];
    			for( int i=0; i<pixB.length; ++i ) pixA[i] = pixB[i] & 0xff;
    			
    			return new Pair< FloatProcessor, FloatProcessor > (
    					new FloatProcessor( pair.a.getWidth(), pair.a.getHeight(), pixF ),
    					new FloatProcessor( pair.b.getWidth(), pair.b.getWidth(),  pixA ) );
    		}
    	}.call();

    	patches.get(0).getProject().getLoader().releaseAll();
    	
    	// Gaussian-downsample the image and the mask
    	final double max_dimension_source = Math.max( pair.a.getWidth(), pair.a.getHeight() );
    	final double max_dimension_target = Math.max(
    			( int ) (box.width  * scale ),
    			( int ) (box.height * scale ) );
    	final double s = 0.5; // same sigma for source and target
    	final double sigma = s * max_dimension_source / max_dimension_target - s * s ;

    	Utils.log("Gaussian downsampling. If this is slow, check the number of threads in the plugin preferences.");
    	new GaussianBlur().blurFloat( pair.a, sigma, sigma, 0.0002 );
    	new GaussianBlur().blurFloat( pair.b, sigma, sigma, 0.0002 );

    	pair.a.setInterpolationMethod( ImageProcessor.NEAREST_NEIGHBOR );
    	pair.b.setInterpolationMethod( ImageProcessor.NEAREST_NEIGHBOR );
    	
    	// Map alpha to [0..1]
    	final FloatProcessor alpha_fp = ( FloatProcessor ) pair.b.resize( ( int ) Math.ceil( box.width * scale ), ( int ) Math.ceil( box.height * scale ) );
        final float[] alphaPix = ( float[] ) alpha_fp.getPixels();
        for ( int i=0; i<alphaPix.length; ++i )
        	alphaPix[i] = alphaPix[i] / 255.0f;

    	return new Pair< FloatProcessor, FloatProcessor >(
    			( FloatProcessor ) pair.a.resize( ( int ) Math.ceil( box.width * scale ), ( int ) Math.ceil( box.height * scale ) ),
    			alpha_fp );
    }
}
