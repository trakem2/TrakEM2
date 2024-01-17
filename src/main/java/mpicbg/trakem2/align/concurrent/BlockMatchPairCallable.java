/*-
 * #%L
 * TrakEM2 plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2024 Albert Cardona, Stephan Saalfeld and others.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package mpicbg.trakem2.align.concurrent;

import ij.process.FloatProcessor;
import ini.trakem2.display.Layer;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Filter;
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
import mpicbg.trakem2.transform.ExportBestFlatImage;
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
     *     	
     * Use different methods depending on the dimensions of the target image and the availability of mipmaps.
     * The goal is to obtain the best possible image.
     * 
     * The alpha channel is returned with values between [0..1]
     * 
     * 
     * @param layer
     * @param patches
     * @param box
     * @param scale
     * @return
     */
    private Pair< FloatProcessor, FloatProcessor > makeFlatImage( final Layer layer, final List<Patch> patches, final Rectangle box, final double scale )
    {    	
    	final Pair< FloatProcessor, FloatProcessor > pair = new ExportBestFlatImage( patches, box, 0, scale ).makeFlatFloatGrayImageAndAlpha();
    	
    	// Map alpha from 8-bit to the range [0..1]
    	final float[] alpha = (float[]) pair.b.getPixels();
    	for (int i=0; i<alpha.length; ++i) {
    		alpha[i] = alpha[i] / 255f;
    	}
    	
    	return pair;
    }
}
