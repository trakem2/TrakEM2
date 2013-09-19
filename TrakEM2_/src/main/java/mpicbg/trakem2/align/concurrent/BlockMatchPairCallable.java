package mpicbg.trakem2.align.concurrent;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import ini.trakem2.Project;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Filter;
import mpicbg.ij.blockmatching.BlockMatching;
import mpicbg.models.AbstractModel;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.InvertibleCoordinateTransform;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.trakem2.align.AlignmentUtils;
import mpicbg.trakem2.align.ElasticLayerAlignment;
import mpicbg.trakem2.align.Util;
import mpicbg.trakem2.util.Triple;

import java.awt.Rectangle;
import java.awt.Image;
import java.awt.Color;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

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

        public BlockMatchResults(Collection<? extends Point> v1,
                                 Collection<? extends Point> v2,
                                 Collection<PointMatch> pm12,
                                 Collection<PointMatch> pm21,
                                 boolean layer1Fixed,
                                 boolean layer2Fixed,
                                 Triple<Integer, Integer, AbstractModel<?>> pair)
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

    private Layer layer1, layer2;
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

        final Image img1 = project.getLoader().getFlatAWTImage(
                layer1,
                box,
                param.layerScale,
                0xffffffff,
                ImagePlus.COLOR_RGB,
                Patch.class,
                AlignmentUtils.filterPatches(layer1, filter),
                true,
                new Color( 0x00ffffff, true ) );

        final Image img2 = project.getLoader().getFlatAWTImage(
                layer2,
                box,
                param.layerScale,
                0xffffffff,
                ImagePlus.COLOR_RGB,
                Patch.class,
                AlignmentUtils.filterPatches( layer2, filter ),
                true,
                new Color( 0x00ffffff, true ) );

        final int width = img1.getWidth( null );
        final int height = img1.getHeight( null );

        final AbstractModel< ? > localSmoothnessFilterModel =
                Util.createModel(param.localModelIndex);

        final FloatProcessor ip1 = new FloatProcessor( width, height );
        final FloatProcessor ip2 = new FloatProcessor( width, height );
        final FloatProcessor ip1Mask = new FloatProcessor( width, height );
        final FloatProcessor ip2Mask = new FloatProcessor( width, height );

        final int blockRadius =
                Math.max( 16, mpicbg.util.Util.roundPos( param.layerScale * param.blockRadius ) );

        /* scale pixel distances */
        final int searchRadius = Math.round( param.layerScale * param.searchRadius );
        final float localRegionSigma = param.layerScale * param.localRegionSigma;
        final float maxLocalEpsilon = param.layerScale * param.maxLocalEpsilon;

        mpicbg.trakem2.align.Util.imageToFloatAndMask( img1, ip1, ip1Mask );
        mpicbg.trakem2.align.Util.imageToFloatAndMask( img2, ip2, ip2Mask );

        if (!layer1Fixed)
        {


            BlockMatching.matchByMaximalPMCC(
                    ip1,
                    ip2,
                    ip1Mask,
                    ip2Mask,
                    1.0f,
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
