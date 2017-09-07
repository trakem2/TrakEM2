package mpicbg.trakem2.align.concurrent;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import ini.trakem2.Project;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Filter;

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
        final Project project = layer1.getProject();

        System.out.println("BMC rev 0: " + pair.a + " " + pair.b);

        // Applies the mask
        final Pair< ShortProcessor, ByteProcessor > pair1 = ExportUnsignedShort.makeFlatImage(AlignmentUtils.filterPatches( layer1, filter ), box, 0, param.layerScale, true);
        final Pair< ShortProcessor, ByteProcessor > pair2 = ExportUnsignedShort.makeFlatImage(AlignmentUtils.filterPatches( layer2, filter ), box, 0, param.layerScale, true);

        final FloatProcessor ip1 = pair1.a.convertToFloatProcessor();
        final FloatProcessor ip2 = pair1.a.convertToFloatProcessor();
        
        final FloatProcessor ip1Mask = new FloatProcessor( ip1.getWidth(), ip1.getHeight() );
        final FloatProcessor ip2Mask = new FloatProcessor( ip2.getWidth(), ip2.getHeight() );
        
        // Convert masks from bytes in range [0..255] to floats in range [0..1]
        
        final byte[] alpha1 = ( byte[] )pair1.b.getPixels();
        final byte[] alpha2 = ( byte[] )pair2.b.getPixels();
        
        for ( int i=0; i<alpha1.length; ++i )
        	ip1Mask.setf(i, (alpha1[i] & 0xff) / 255.0f);
        
        for ( int i=0; i<alpha2.length; ++i )
        	ip2Mask.setf(i, (alpha2[i] & 0xff) / 255.0f);  

        
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

}
