/**

Copyright (C) 2008 Verena Kaynig.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 **/

/* ****************************************************************  *
 * Representation of a non linear transform by explicit polynomial
 * kernel expansion.
 *
 * TODO:
 * 	- make different kernels available
 * 	- inverse transform for visualization
 *  - improve image interpolation
 *  - apply and applyInPlace should use precalculated transform?
 *    (What about out of image range pixels?)
 *
 *  Author: Verena Kaynig
 *  Kontakt: verena.kaynig@inf.ethz.ch
 *
 * ****************************************************************  */

package lenscorrection;

import ij.ImagePlus;
import ij.io.FileSaver;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.awt.geom.GeneralPath;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;

import mpicbg.trakem2.transform.NonLinearCoordinateTransform;
import Jama.Matrix;


public class NonLinearTransform extends NonLinearCoordinateTransform {

	private double[][][] transField = null;

	public int getDimension(){ return dimension; }
	/** Deletes all dimension dependent properties */
	public void setDimension( final int dimension )
	{
		this.dimension = dimension;
		length = (dimension + 1)*(dimension + 2)/2;

		beta = new double[length][2];
		normMean = new double[length];
		normVar = new double[length];

		for (int i=0; i < length; i++){
			normMean[i] = 0;
			normVar[i] = 1;
		}
		transField = null;
		precalculated = false;
	}

	private boolean precalculated = false;

	public int getMinNumMatches()
	{
		return length;
	}


	public void fit( final double x[][], final double y[][], final double lambda )
	{
		final double[][] expandedX = kernelExpandMatrixNormalize( x );

		final Matrix phiX = new Matrix( expandedX, expandedX.length, length );
		final Matrix phiXTransp = phiX.transpose();

		final Matrix phiXProduct = phiXTransp.times( phiX );

		final int l = phiXProduct.getRowDimension();
		final double lambda2 = 2 * lambda;

		for (int i = 0; i < l; ++i )
			phiXProduct.set( i, i, phiXProduct.get( i, i ) + lambda2 );

		final Matrix phiXPseudoInverse = phiXProduct.inverse();
		final Matrix phiXProduct2 = phiXPseudoInverse.times( phiXTransp );
		final Matrix betaMatrix = phiXProduct2.times( new Matrix( y, y.length, 2 ) );

		setBeta( betaMatrix.getArray() );
	}

	public void estimateDistortion( final double hack1[][], final double hack2[][], final double transformParams[][], final double lambda, final int w, final int h )
	{
		beta = new double[ length ][ 2 ];
		normMean = new double[ length ];
		normVar = new double[ length ];

		for ( int i = 0; i < length; i++ )
		{
			normMean[ i ] = 0;
			normVar[ i ] = 1;
		}

		width = w;
		height = h;

		/* TODO Find out how to keep some target points fixed (check fit method of NLT which is supposed to be exclusively forward) */
		final double expandedX[][] = kernelExpandMatrixNormalize( hack1 );
		final double expandedY[][] = kernelExpandMatrix( hack2 );

		final int s = expandedX[ 0 ].length;
		Matrix S1 = new Matrix( 2 * s, 2 * s );
		Matrix S2 = new Matrix( 2 * s, 1 );

		for ( int i = 0; i < expandedX.length; ++i )
		{
			final Matrix xk_ij = new Matrix( expandedX[ i ], 1 );
			final Matrix xk_ji = new Matrix( expandedY[ i ], 1 );

			final Matrix yk1a = xk_ij.minus( xk_ji.times( transformParams[ i ][ 0 ] ) );
			final Matrix yk1b = xk_ij.times( 0.0 ).minus( xk_ji.times( -transformParams[ i ][ 2 ] ) );
			final Matrix yk2a = xk_ij.times( 0.0 ).minus( xk_ji.times( -transformParams[ i ][ 1 ] ) );
			final Matrix yk2b = xk_ij.minus( xk_ji.times( transformParams[ i ][ 3 ] ) );

			final Matrix y = new Matrix( 2, 2 * s );
			y.setMatrix( 0, 0, 0, s - 1, yk1a );
			y.setMatrix( 0, 0, s, 2 * s - 1, yk1b );
			y.setMatrix( 1, 1, 0, s - 1, yk2a );
			y.setMatrix( 1, 1, s, 2 * s - 1, yk2b );

			final Matrix xk = new Matrix( 2, 2 * expandedX[ 0 ].length );
			xk.setMatrix( 0, 0, 0, s - 1, xk_ij );
			xk.setMatrix( 1, 1, s, 2 * s - 1, xk_ij );

			final double[] vals = { hack1[ i ][ 0 ], hack1[ i ][ 1 ] };
			final Matrix c = new Matrix( vals, 2 );

			final Matrix X = xk.transpose().times( xk ).times( lambda );
			final Matrix Y = y.transpose().times( y );

			S1 = S1.plus( Y.plus( X ) );

			final double trans1 = ( transformParams[ i ][ 2 ] * transformParams[ i ][ 5 ] - transformParams[ i ][ 0 ] * transformParams[ i ][ 4 ] );
			final double trans2 = ( transformParams[ i ][ 1 ] * transformParams[ i ][ 4 ] - transformParams[ i ][ 3 ] * transformParams[ i ][ 5 ] );
			final double[] trans = { trans1, trans2 };

			final Matrix translation = new Matrix( trans, 2 );
			final Matrix YT = y.transpose().times( translation );
			final Matrix XC = xk.transpose().times( c ).times( lambda );

			S2 = S2.plus( YT.plus( XC ) );
		}
		final Matrix regularize = Matrix.identity( S1.getRowDimension(), S1.getColumnDimension() );
		final Matrix newBeta = new Matrix( S1.plus( regularize.times( 0.001 ) ).inverse().times( S2 ).getColumnPackedCopy(), s );

		setBeta( newBeta.getArray() );
	}

	public NonLinearTransform(final double[][] b, final double[] nm, final double[] nv, final int d, final int w, final int h){
		beta = b;
		normMean = nm;
		normVar = nv;
		dimension = d;
		length = (dimension + 1)*(dimension + 2)/2;
		width = w;
		height = h;
	}

	public NonLinearTransform(final int d, final int w, final int h){
		dimension = d;
		length = (dimension + 1)*(dimension + 2)/2;

		beta = new double[length][2];
		normMean = new double[length];
		normVar = new double[length];

		for (int i=0; i < length; i++){
			normMean[i] = 0;
			normVar[i] = 1;
		}

		width = w;
		height = h;
	}

	public NonLinearTransform(){};

	public NonLinearTransform(final String filename){
		this.load(filename);
	}

	public NonLinearTransform(final double[][] coeffMatrix, final int w, final int h){
		length = coeffMatrix.length;
		beta = new double[length][2];
		normMean = new double[length];
		normVar = new double[length];
		width = w;
		height = h;
		dimension = (int)(-1.5 + Math.sqrt(0.25 + 2*length));

		for(int i=0; i<length; i++){
			beta[i][0] = coeffMatrix[0][i];
			beta[i][1] = coeffMatrix[1][i];
			normMean[i] = coeffMatrix[2][i];
			normVar[i] = coeffMatrix[3][i];
		}
	}


	void precalculateTransfom(){
		transField = new double[width][height][2];
		//double minX = width, minY = height, maxX = 0, maxY = 0;

		for (int x=0; x<width; x++){
			for (int y=0; y<height; y++){
				final double[] position = {x,y};
				final double[] featureVector = kernelExpand(position);
				final double[] newPosition = multiply(beta, featureVector);

				if ((newPosition[0] < 0) || (newPosition[0] >= width) ||
						(newPosition[1] < 0) || (newPosition[1] >= height))
				{
					transField[x][y][0] = -1;
					transField[x][y][1] = -1;
					continue;
				}

				transField[x][y][0] = newPosition[0];
				transField[x][y][1] = newPosition[1];

				//minX = Math.min(minX, x);
				//minY = Math.min(minY, y);
				//maxX = Math.max(maxX, x);
				//maxY = Math.max(maxY, y);

			}
		}

		precalculated = true;
	}

	public double[][] getCoefficients(){
		final double[][] coeffMatrix = new double[4][length];

		for(int i=0; i<length; i++){
			coeffMatrix[0][i] = beta[i][0];
			coeffMatrix[1][i] = beta[i][1];
			coeffMatrix[2][i] = normMean[i];
			coeffMatrix[3][i] = normVar[i];

		}
		return coeffMatrix;
	}

	public void setBeta(final double[][] b){
		beta = b;
		//FIXME: test if normMean and normVar are still valid for this beta
	}

	public void print(){
		System.out.println("beta:");
		for (int i=0; i < beta.length; i++){
			for (int j=0; j < beta[i].length; j++){
				System.out.print(beta[i][j]);
				System.out.print(" ");
			}
			System.out.println();
		}

		System.out.println("normMean:");
		for (int i=0; i < normMean.length; i++){
			System.out.print(normMean[i]);
			System.out.print(" ");
		}

		System.out.println("normVar:");
		for (int i=0; i < normVar.length; i++){
			System.out.print(normVar[i]);
			System.out.print(" ");
		}

		System.out.println("Image size:");
		System.out.println("width: " + width + " height: " + height);

		System.out.println();

	}

	public void save( final String filename )
	{
		try{
			final BufferedWriter out = new BufferedWriter(
					new OutputStreamWriter(
							new FileOutputStream( filename) ) );
			try{
				out.write("Kerneldimension");
				out.newLine();
				out.write(Integer.toString(dimension));
				out.newLine();
				out.newLine();
				out.write("number of rows");
				out.newLine();
				out.write(Integer.toString(length));
				out.newLine();
				out.newLine();
				out.write("Coefficients of the transform matrix:");
				out.newLine();
				for (int i=0; i < length; i++){
					String s = Double.toString(beta[i][0]);
					s += "    ";
					s += Double.toString(beta[i][1]);
					out.write(s);
					out.newLine();
				}
				out.newLine();
				out.write("normMean:");
				out.newLine();
				for (int i=0; i < length; i++){
					out.write(Double.toString(normMean[i]));
					out.newLine();
				}
				out.newLine();
				out.write("normVar: ");
				out.newLine();
				for (int i=0; i < length; i++){
					out.write(Double.toString(normVar[i]));
					out.newLine();
				}
				out.newLine();
				out.write("image size: ");
				out.newLine();
				out.write(width + "    " + height);
				out.close();
			}
			catch(final IOException e){System.out.println("IOException");}
		}
		catch(final FileNotFoundException e){System.out.println("File not found!");}
	}

	public void load(final String filename){
		try{
			final BufferedReader in = new BufferedReader(new FileReader(filename));
			try{
				String line = in.readLine(); //comment;
				dimension = Integer.parseInt(in.readLine());
				line = in.readLine(); //comment;
				line = in.readLine(); //comment;
				length = Integer.parseInt(in.readLine());
				line = in.readLine(); //comment;
				line = in.readLine(); //comment;

				beta = new double[length][2];

				for (int i=0; i < length; i++){
					line = in.readLine();
					final int ind = line.indexOf(" ");
					beta[i][0] = Double.parseDouble(line.substring(0, ind));
					beta[i][1] = Double.parseDouble(line.substring(ind+4));
				}

				line = in.readLine(); //comment;
				line = in.readLine(); //comment;

				normMean = new double[length];

				for (int i=0; i < length; i++){
					normMean[i]=Double.parseDouble(in.readLine());
				}

				line = in.readLine(); //comment;
				line = in.readLine(); //comment;

				normVar = new double[length];

				for (int i=0; i < length; i++){
					normVar[i]=Double.parseDouble(in.readLine());
				}
				line = in.readLine(); //comment;
				line = in.readLine(); //comment;
				line = in.readLine();
				final int ind = line.indexOf(" ");
				width = Integer.parseInt(line.substring(0, ind));
				height = Integer.parseInt(line.substring(ind+4));
				in.close();

				print();
			}
			catch(final IOException e){System.out.println("IOException");}
		}
		catch(final FileNotFoundException e){System.out.println("File not found!");}
	}

	public ImageProcessor[] transform(final ImageProcessor ip){
		if (!precalculated)
			this.precalculateTransfom();

		final ImageProcessor newIp = ip.createProcessor(ip.getWidth(), ip.getHeight());
		if (ip instanceof ColorProcessor) ip.max(0);
		final ImageProcessor maskIp = new ByteProcessor(ip.getWidth(),ip.getHeight());

		for (int x=0; x < width; x++){
			for (int y=0; y < height; y++){
				if (transField[x][y][0] == -1){
					continue;
				}
				newIp.set(x, y, (int) ip.getInterpolatedPixel((int)transField[x][y][0],(int)transField[x][y][1]));
				maskIp.set(x,y,255);
			}
		}
		return new ImageProcessor[]{newIp, maskIp};
	}

	public double[][] kernelExpandMatrixNormalize(final double positions[][]){
		normMean = new double[length];
		normVar = new double[length];

		for (int i=0; i < length; i++){
			normMean[i] = 0;
			normVar[i] = 1;
		}

		final double expanded[][] = new double[positions.length][length];

		for (int i=0; i < positions.length; i++){
			expanded[i] = kernelExpand(positions[i]);
		}

		for (int i=0; i < length; i++){
			double mean = 0;
			double var = 0;
			for (int j=0; j < expanded.length; j++){
				mean += expanded[j][i];
			}

			mean /= expanded.length;

			for (int j=0; j < expanded.length; j++){
				var += (expanded[j][i] - mean)*(expanded[j][i] - mean);
			}
			var /= (expanded.length -1);
			var = Math.sqrt(var);

			normMean[i] = mean;
			normVar[i] = var;
		}

		return kernelExpandMatrix(positions);

	}

	//this function uses the parameters already stored
	//in this object to normalize the positions given.
	public double[][] kernelExpandMatrix(final double positions[][]){


		final double expanded[][] = new double[positions.length][length];

		for (int i=0; i < positions.length; i++){
			expanded[i] = kernelExpand(positions[i]);
		}

		return expanded;

	}

	public void inverseTransform(final double range[][]){
		Matrix expanded = new Matrix(kernelExpandMatrix(range));
		final Matrix b = new Matrix(beta);

		final Matrix transformed = expanded.times(b);
		expanded = new Matrix(kernelExpandMatrixNormalize(transformed.getArray()));

		final Matrix r = new Matrix(range);
		final Matrix invBeta = expanded.transpose().times(expanded).inverse().times(expanded.transpose()).times(r);
		setBeta(invBeta.getArray());
	}

	//FIXME this takes way too much memory
	public void visualize(){

		final int density = Math.max(width,height)/32;
		final int border = Math.max(width,height)/8;

		final double[][] orig = new double[width *  height][2];
		final double[][] trans = new double[height * width][2];
		final double[][] gridOrigVert = new double[width*height][2];
		final double[][] gridTransVert = new double[width*height][2];
		final double[][] gridOrigHor = new double[width*height][2];
		final double[][] gridTransHor = new double[width*height][2];

		final FloatProcessor magnitude = new FloatProcessor(width, height);
		final FloatProcessor angle = new FloatProcessor(width, height);
		final ColorProcessor quiver = new ColorProcessor(width, height);
		final ByteProcessor empty = new ByteProcessor(width+2*border, height+2*border);
		quiver.setLineWidth(1);
		quiver.setColor(Color.green);

		final GeneralPath quiverField = new GeneralPath();

		float minM = 1000, maxM = 0;
		float minArc = 5, maxArc = -6;
		int countVert = 0, countHor = 0, countHorWhole = 0;

		for (int i=0; i < width; i++){
			countHor = 0;
			for (int j=0; j < height; j++){
				final double[] position = {(double) i,(double) j};
				final double[] posExpanded = kernelExpand(position);
				final double[] newPosition = multiply(beta, posExpanded);

				orig[i*j][0] = position[0];
				orig[i*j][1] = position[1];

				trans[i*j][0] = newPosition[0];
				trans[i*j][1] = newPosition[1];

				double m = (position[0] - newPosition[0]) * (position[0] - newPosition[0]);
				m += (position[1] - newPosition[1]) * (position[1] - newPosition[1]);
				m = Math.sqrt(m);
				magnitude.setf(i,j, (float) m);
				minM = Math.min(minM, (float) m);
				maxM = Math.max(maxM, (float) m);

				final double a = Math.atan2(position[0] - newPosition[0], position[1] - newPosition[1]);
				minArc = Math.min(minArc, (float) a);
				maxArc = Math.max(maxArc, (float) a);
				angle.setf(i,j, (float) a);

				if (i%density == 0 && j%density == 0)
					drawQuiverField(quiverField, position[0], position[1], newPosition[0], newPosition[1]);
				if (i%density == 0){
					gridOrigVert[countVert][0] = position[0] + border;
					gridOrigVert[countVert][1] = position[1] + border;
					gridTransVert[countVert][0] = newPosition[0] + border;
					gridTransVert[countVert][1] = newPosition[1] + border;
					countVert++;
				}
				if (j%density == 0){
					gridOrigHor[countHor*width+i][0] = position[0] + border;
					gridOrigHor[countHor*width+i][1] = position[1] + border;
					gridTransHor[countHor*width+i][0] = newPosition[0] + border;
					gridTransHor[countHor*width+i][1] = newPosition[1] + border;
					countHor++;
					countHorWhole++;
				}
			}
		}

		magnitude.setMinAndMax(minM, maxM);
		angle.setMinAndMax(minArc, maxArc);
		//System.out.println(" " + minArc + " " + maxArc);

		final ImagePlus magImg = new ImagePlus("Magnitude of Distortion Field", magnitude);
		magImg.show();

		//		ImagePlus angleImg = new ImagePlus("Angle of Distortion Field Vectors", angle);
		//		angleImg.show();

		final ImagePlus quiverImg = new ImagePlus("Quiver Plot of Distortion Field", magnitude);
		quiverImg.show();
		quiverImg.getCanvas().setDisplayList(quiverField, Color.green, null );
		quiverImg.updateAndDraw();

		//		GeneralPath gridOrig = new GeneralPath();
		//		drawGrid(gridOrig, gridOrigVert, countVert, height);
		//		drawGrid(gridOrig, gridOrigHor, countHorWhole, width);
		//		ImagePlus gridImgOrig = new ImagePlus("Distortion Grid", empty);
		//		gridImgOrig.show();
		//		gridImgOrig.getCanvas().setDisplayList(gridOrig, Color.green, null );
		//		gridImgOrig.updateAndDraw();

		final GeneralPath gridTrans = new GeneralPath();
		drawGrid(gridTrans, gridTransVert, countVert, height);
		drawGrid(gridTrans, gridTransHor, countHorWhole, width);
		final ImagePlus gridImgTrans = new ImagePlus("Distortion Grid", empty);
		gridImgTrans.show();
		gridImgTrans.getCanvas().setDisplayList(gridTrans, Color.green, null );
		gridImgTrans.updateAndDraw();

		//new FileSaver(quiverImg.getCanvas().imp).saveAsTiff("QuiverCanvas.tif");
		new FileSaver(quiverImg).saveAsTiff("QuiverImPs.tif");

		System.out.println("FINISHED");
	}


	public void visualizeSmall(final double lambda){
		final int density = Math.max(width,height)/32;

		final double[][] orig = new double[2][width *  height];
		final double[][] trans = new double[2][height * width];

		final FloatProcessor magnitude = new FloatProcessor(width, height);

		final GeneralPath quiverField = new GeneralPath();

		float minM = 1000, maxM = 0;
		final float minArc = 5, maxArc = -6;
		final int countVert = 0;
		int countHor = 0;
		final int countHorWhole = 0;

		for (int i=0; i < width; i++){
			countHor = 0;
			for (int j=0; j < height; j++){
				final double[] position = {(double) i,(double) j};
				final double[] posExpanded = kernelExpand(position);
				final double[] newPosition = multiply(beta, posExpanded);

				orig[0][i*j] = position[0];
				orig[1][i*j] = position[1];

				trans[0][i*j] = newPosition[0];
				trans[1][i*j] = newPosition[1];

				double m = (position[0] - newPosition[0]) * (position[0] - newPosition[0]);
				m += (position[1] - newPosition[1]) * (position[1] - newPosition[1]);
				m = Math.sqrt(m);
				magnitude.setf(i,j, (float) m);
				minM = Math.min(minM, (float) m);
				maxM = Math.max(maxM, (float) m);

				if (i%density == 0 && j%density == 0)
					drawQuiverField(quiverField, position[0], position[1], newPosition[0], newPosition[1]);
			}
		}

		magnitude.setMinAndMax(minM, maxM);
		final ImagePlus quiverImg = new ImagePlus("Quiver Plot for lambda = "+lambda, magnitude);
		quiverImg.show();
		quiverImg.getCanvas().setDisplayList(quiverField, Color.green, null );
		quiverImg.updateAndDraw();

		System.out.println("FINISHED");
	}


	public static void drawGrid(final GeneralPath g, final double[][] points, final int count, final int s){
		for (int i=0; i < count - 1; i++){
			if ((i+1)%s != 0){
				g.moveTo((float)points[i][0], (float)points[i][1]);
				g.lineTo((float)points[i+1][0], (float)points[i+1][1]);
			}
		}
	}

	public static void drawQuiverField(final GeneralPath qf, final double x1, final double y1, final double x2, final double y2)
	{
		qf.moveTo((float)x1, (float)y1);
		qf.lineTo((float)x2, (float)y2);
	}

	public int getWidth(){
		return width;
	}

	public int getHeight(){
		return height;
	}

	/**
	 * TODO Make this more efficient
	 */
	@Override
	final public NonLinearTransform copy()
	{
		final NonLinearTransform t = new NonLinearTransform();
		t.init( toDataString() );
		return t;
	}

	public void set( final NonLinearTransform nlt )
	{
		this.dimension = nlt.dimension;
		this.height = nlt.height;
		this.length = nlt.length;
		this.precalculated = nlt.precalculated;
		this.width = nlt.width;

		/* arrays by deep cloning */
		this.beta = new double[ nlt.beta.length ][];
		for ( int i = 0; i < nlt.beta.length; ++i )
			this.beta[ i ] = nlt.beta[ i ].clone();

		this.normMean = nlt.normMean.clone();
		this.normVar = nlt.normVar.clone();
		this.transField = new double[ nlt.transField.length ][][];

		for ( int a = 0; a < nlt.transField.length; ++a )
		{
			this.transField[ a ] = new double[ nlt.transField[ a ].length ][];
			for ( int b = 0; b < nlt.transField[ a ].length; ++b )
				this.transField[ a ][ b ] = nlt.transField[ a ][ b ].clone();
		}
	}
}
