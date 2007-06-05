package jRenderer3D;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.filter.GaussianBlur;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.PixelGrabber;

class SurfacePlot {
	
//	Surface plot modes
//	private static final int PLOT_DOTSNOLIGHT = 0;
//	private static final int PLOT_DOTS = 1;
//	private final static int PLOT_LINES = 2;
//	private final static int PLOT_MESH = 3;
//	private final static int PLOT_FILLED = 4;
	
	/*****************************************************************************************/
	
	/**
	 * The size of the surface plot data 
	 */
	private int gridWidth = 256; 
	private int gridHeight = 256;
	
	private SurfacePlotData[] plotList = null;
	
	Image image;
	private int[] bufferPixels;
	private double[] zbufferPixels;
	private int bufferWidth;
	private int bufferHeight;
	private int lutNr = JRenderer3D.LUT_ORIGINAL;
	private Lut lut;
	private Transform tr;
	private double light;
	private int surfacePlotMode;
	private double xCenter;
	private double yCenter;
	private double zCenter;
	private int min = 0;
	private int max = 255;
	private int inversefactor = 1;
	
	private int[] pixelsOrigColor;
	private int[] pixelsOrigLum;
	private int widthOrig;
	private int heightOrig;
	private int widthTex;
	private int heightTex;
	private int[] pixelsTexColor;
	
	private static final int OPAQUE = 0xFF000000;
	
	protected void draw() {
		if (surfacePlotMode == JRenderer3D.SURFACEPLOT_FILLED)
			surfacePlotFilled();
		else if (surfacePlotMode == JRenderer3D.SURFACEPLOT_ISOLINES)
			surfacePlotIsoLines();
		else if (surfacePlotMode == JRenderer3D.SURFACEPLOT_MESH)
			surfacePlotMesh();
		else if (surfacePlotMode == JRenderer3D.SURFACEPLOT_LINES)
			surfacePlotLines();
		else if (surfacePlotMode == JRenderer3D.SURFACEPLOT_DOTS)
			surfacePlotDots();
		else if (surfacePlotMode == JRenderer3D.SURFACEPLOT_DOTSNOLIGHT)
			surfacePlotDotsNoLight();
	}
	
	protected void setSurfacePlotImage(ImagePlus imp){
	
			int widthTmp = imp.getWidth();
			int heightTmp = imp.getHeight();
	
			ImageProcessor ip = imp.getProcessor();
	
			lut = new Lut();
	
			int[] pixelsTmp = new int[widthTmp*heightTmp];
	
			image = imp.getImage();
			PixelGrabber pg =  new PixelGrabber(image, 0, 0, widthTmp, heightTmp, pixelsTmp, 0, widthTmp);
	
			try {
				pg.grabPixels();
			} catch (InterruptedException ex) {IJ.error("error grabbing pixels");}
	
	
			boolean isLut = ip.isColorLut();
	
			byte[] lutPixels = null;
	
			if( isLut ) 
				lutPixels = (byte []) ip.getPixels();
	
			Roi roi = imp.getRoi();
	
			if (roi != null) {
				
				ImageProcessor mask = roi.getMask();
				ImageProcessor ipMask;
	
				byte[] mpixels = null;
				int mWidth = 0;
				if (mask != null) {
					ipMask = mask.duplicate();
					mpixels = (byte[])ipMask.getPixels();
					mWidth = ipMask.getWidth();
				}
	
				Rectangle rect = roi.getBoundingRect();
				if (rect.x < 0)
					rect.x = 0;
				if (rect.y < 0)
					rect.y = 0;
	
				widthOrig = rect.width;
				heightOrig = rect.height;
				
				pixelsOrigColor = new int[widthOrig*heightOrig];
				pixelsOrigLum = new int[widthOrig*heightOrig];
				
				for (int j=0, y=rect.y; y<rect.y+rect.height; y++) {
					int offset = y*widthTmp;
					
					for (int x=rect.x; x< rect.x+rect.width ; x++, j++) {
						int i = offset + x;
							
						int c;
						if (mask != null) { 
							int mx = x-rect.x;
							int my = y-rect.y;
	
							if (mpixels[my *mWidth + mx ] == 0) 
								c = pixelsOrigColor[j] = (63 << 24) |  (pixelsTmp[i] & 0xFFFFFF);
							else
								c = pixelsOrigColor[j] = pixelsTmp[i];
						}
						else
							c =pixelsOrigColor[j] = pixelsTmp[i];
						
						int lum;
						if (!isLut) { 
							int r = ((c >> 16) & 255);
							int g = ((c >>  8) & 255);
							int b = ((c      ) & 255);
							
							lum = (int)(0.299*r  + 0.587*g + 0.114*b);
						}
						else
							lum = (int)(0xFF & lutPixels[i]);
						
						pixelsOrigLum[j] = lum;
					}
				}
			}
			else {
				widthOrig = widthTmp;
				heightOrig = heightTmp;
				
				pixelsOrigColor = new int[widthOrig*heightOrig];
				pixelsOrigLum = new int[widthOrig*heightOrig];
				
				for (int y = 0; y < heightTmp; y++) {
					
					for (int x = 0; x < widthTmp; x++) {
						int pos = y * widthTmp + x;
	
						int c = pixelsOrigColor[pos] = pixelsTmp[pos];
	
	//					if ((x-widthTmp/2) * (x-widthTmp/2) + (y-heightTmp/2)*(y-heightTmp/2) < 2500 )
	//						pixelsOrigColor[pos] = (63 << 24) |  (pixelsOrigColor[pos] & 0xFFFFFF);
						
						int lum; 
						
						int r = ((c >> 16) & 255);
						int g = ((c >>  8) & 255);
						int b = ((c      ) & 255);
						if (!isLut) {
							lum = (int)Math.round(0.299*r  + 0.587*g + 0.114*b);
						}
						else
							lum = (int)(0xFF & lutPixels[pos]);
	
						pixelsOrigLum[pos] = lum;
					}
				}
			}
		}

	
	protected void setSurfacePlotTextureImage(ImagePlus imp){

		widthTex = imp.getWidth();
		heightTex = imp.getHeight();

		pixelsTexColor = new int[widthTex*heightTex];

		image = imp.getImage();
		PixelGrabber pg =  new PixelGrabber(image, 0, 0, widthTex, heightTex, pixelsTexColor, 0, widthTex);

		try {
			pg.grabPixels();
		} catch (InterruptedException ex) {
			IJ.error("error grabbing pixels");
			pixelsTexColor =  null;
		}

	}
	


	protected void resample(){
		
		plotList = new SurfacePlotData[gridWidth*gridHeight];
		
		if (pixelsOrigColor != null && pixelsOrigLum != null) {
			double xOffset = xCenter;
			double yOffset = yCenter;

			double sx = (widthOrig-1) / (double) (gridWidth-1);
			double sy = (heightOrig-1) / (double) (gridHeight-1);

			for (int y = 0; y < gridHeight; y++) {
				int yB = (int) Math.round(y * sy);

				for (int x = 0; x < gridWidth; x++) {
					int pos = y * gridWidth + x;

					int xB = (int) Math.round (x * sx);
					int c = pixelsOrigColor[yB * widthOrig + xB];
					int lum = pixelsOrigLum[yB * widthOrig + xB];

					if ( (c & OPAQUE) == OPAQUE ) { // opaque

						plotList[pos] = new SurfacePlotData();

						plotList[pos].color = c;
						plotList[pos].x = sx*x - xOffset;
						plotList[pos].y = sy*y - yOffset;
						plotList[pos].z = plotList[pos].zf = plotList[pos].lum =  lum -zCenter;
					}
				}
			}
		}
		
		if (pixelsTexColor != null) {
			
			double sx = (widthTex-1) / (double) (gridWidth-1);
			double sy = (heightTex-1) / (double) (gridHeight-1);

			for (int y = 0; y < gridHeight; y++) {
				int yB = (int) Math.round(y * sy);

				for (int x = 0; x < gridWidth; x++) {
					int pos = y * gridWidth + x;

					int xB = (int) Math.round (x * sx);
					int c = pixelsTexColor[yB * widthTex + xB];
					
					if ( (c & OPAQUE) == OPAQUE ) { // opaque

						plotList[pos].color = c;
					}
				}
			}
		}

		computeNormals();
	}
	
	private void computeNormals() {
		for (int y = 0; y < gridHeight; y++) {
			for (int x = 0; x < gridWidth; x++) {
				int i = y * gridWidth + x;
				
				if (plotList[i] != null) {
					double dx1 = 0;
					double dy1 = 0;
					double dz1 = 0;
					
					for (int y_ = -1; y_ <= 1; y_++) {
						int yn = y + y_;
						if (yn < 0) yn = 0;
						if (yn >= gridHeight) yn = gridHeight-1;
						
						for (int x_ = -1; x_ < 1; x_++) {

							int xn = x + x_;
							if (xn < 0) xn = 0;
							if (xn >= gridWidth) xn = gridWidth-1;

							int xn1 = xn+1;
							if (xn1 < 0) xn1 = 0;
							if (xn1 >= gridWidth) xn1 = gridWidth-1;

							int posn = yn*gridWidth+xn;
							int posn1 = yn*gridWidth+xn1;
							
							if (plotList[posn1] != null && plotList[posn] != null ) {
								dx1 += plotList[posn1].x - plotList[posn].x;
								dz1 += plotList[posn1].z - plotList[posn].z;
							}
						}
					}
					
					double dx2 = 0;
					double dy2 = 0;
					double dz2 = 0;
					
					for (int y_ = -1; y_ < 1; y_++) {
						int yn = y + y_;
						if (yn < 0) yn = 0;
						if (yn >= gridHeight) yn = gridHeight-1;

						int yn1 = yn+1;
						if (yn1 < 0) yn1 = 0;
						if (yn1 >= gridHeight) yn1 = gridHeight-1;

						for (int x_ = -1; x_ <= 1; x_++) {
							int xn = x + x_;
							if (xn < 0) xn = 0;
							if (xn >= gridWidth) xn = gridWidth-1;

							int posn =  yn*gridWidth+xn;
							int posn1 = yn1*gridWidth+xn;
							
							if (plotList[posn1] != null && plotList[posn] != null ) {
								dy2 += plotList[posn1].y - plotList[posn].y;
								dz2 += plotList[posn1].z - plotList[posn].z;
							}
						}
					}
					
					// outer product
					double dx =  (dy1*dz2 - dz1*dy2);
					double dy =  (dz1*dx2 - dx1*dz2);
					double dz =  (dx1*dy2 - dy1*dx2);
					
					double len = Math.sqrt(dx*dx + dy*dy + dz*dz); 
					
					plotList[i].dx = dx/len; 
					plotList[i].dy = dy/len; 
					plotList[i].dz = dz/len; 					
				}
			}
		}
	}
	
	private void computeNormals_orig() {
		for (int y = 0; y < gridHeight; y++) {
			for (int x = 0; x < gridWidth; x++) {
				int i = y * gridWidth + x;
				
				if (plotList[i] != null) {
					int dx1 = 0;
					int dy1 = 0;
					int dz1 = 0;
					
					for (int y_ = -1; y_ <= 1; y_++)
						for (int x_ = -1; x_ < 1; x_++)
						{
							int yn = y + y_;
							int xn = x + x_;
							
							if (yn >= 0 && yn < gridHeight && xn >= 0 && xn < gridWidth-1) {
								dx1 += 1;
								int pos = yn*gridWidth+xn;
								if (plotList[pos+1] != null && plotList[pos] != null )
									dz1 += plotList[pos+1].z - plotList[pos].z;
							}
						}
					
					int dx2 = 0;
					int dy2 = 0;
					int dz2 = 0;
					
					for (int y_ = -1; y_ < 1; y_++)
						for (int x_ = -1; x_ <= 1; x_++)
						{
							int yn = y + y_;
							int xn = x + x_;
							
							if (yn >= 0 && yn < gridHeight-1 && xn >= 0 && xn < gridWidth) {
								dy2 += 1;
								int pos = yn*gridWidth+xn;
								if (plotList[pos+gridWidth] != null && plotList[pos] != null )
									dz2 += plotList[pos+gridWidth].z - plotList[pos].z;
							}
						}
					
					// outer product
					double dx =  (dy1*dz2 - dz1*dy2);
					double dy = -(dx1*dz2 - dz1*dx2);
					double dz =  (dx1*dy2 - dy1*dx2);
					
					double len = Math.sqrt(dx*dx + dy*dy + dz*dz); 
					
					plotList[i].dx = -dx/len; 
					plotList[i].dy = -dy/len; 
					plotList[i].dz = -dz/len; 					
				}
			}
		}
	}
	
	protected void applyMinMax() {
		for (int i = 0; i < gridHeight*gridWidth; i++) {
			int add = 0;
			if (inversefactor == -1)
				add = -1;
			if (plotList[i] != null) {
				double val = (255*(plotList[i].zf+zCenter - (min))/(max-min) - zCenter);
				plotList[i].z = inversefactor*Math.min(Math.max(-128,val),127) + add;   
			}
		}
		computeNormals();
	}
 	
	
//	protected void applySmoothingFilter_Orig(int rad) {
//		double []  k = getBlurKernel1D(2*rad+1);
//		
//		for (int i=0, y=0; y < gridHeight; y++) {
//			for (int x=0; x < gridWidth; x++, i++) {
//				
//				if (plotList[i] != null) {
//					
//					int offset = y*gridWidth;
//					
//					double sum = 0;
//					double n = 0;
//					for (int dx = -rad, kx = 0; dx<=rad; dx++, kx++ ) {
//						
//						int x_ = x+dx;
//						if (x_ < 0) x_ = 0;
//						else if (x_ > gridWidth-1) x_ = gridWidth-1;
//						
//						int j = offset + x_;
//						
//						if (plotList[j] != null) {
//							double f = k[kx];
//							sum  += f*plotList[j].lum;
//							n += f;
//						}
//					}
//					
//					plotList[i].tmp = sum/n; 
//				}
//			}
//		}
//		
//		for (int i=0, y=0; y < gridHeight; y++) {
//			for (int x=0; x < gridWidth; x++, i++) {
//				
//				if (plotList[i] != null) {
//					
//					double sum = 0;
//					double n = 0;
//					for (int dy = -rad, ky = 0; dy<=rad; dy++, ky++ ){
//						int y_ = y+dy;
//						if (y_ < 0) y_ = 0;
//						else if (y_ > gridWidth-1) y_ = gridWidth-1;
//						
//						int j = y_*gridWidth + x;
//						if (plotList[j] != null) {
//							double f = k[ky]; 
//							sum  += f*plotList[j].tmp;
//							n += f;
//						}
//					}
//					plotList[i].z = plotList[i].zf = (int) (sum/n + 0.5); 
//				}
//			}
//		}
//		
//		applyMinMax();
//		
//	}

	protected void applySmoothingFilter(double rad) {
		
		float[] pixels = new float[gridHeight*gridWidth];
		
		for (int i=0; i < gridHeight*gridWidth; i++) {
			if (plotList[i] != null)
				pixels[i] = (float) plotList[i].lum;
		}
		ImageProcessor ip = new FloatProcessor(gridWidth, gridHeight, pixels, null);
		new GaussianBlur().blur(ip, rad);
		
		pixels = (float[] )ip.getPixels();
		
		for (int i=0; i < gridHeight*gridWidth; i++) {
			if (plotList[i] != null)
				plotList[i].z = plotList[i].zf = pixels[i];
		}
		
		applyMinMax();
		
	}
	
	private double[] getBlurKernel1D(int size) {
		
		double[] values = new double[size];
		
		double [] k = makeKernel((size-1)/2);
		double sum = 0;
		for (int x = 0; x < size; x++) {
			sum += k[x];
		}	
		
		for (int x = 0; x < size; x++) {
			values[x] = k[x]/sum;
		}
		
		return values;
		
	}
	
	private double[] getBlurKernel(int size) {
		
		double[] values = new double[size*size];
		
		double [] k = makeKernel((size-1)/2);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				values[y*size + x] = k[x]*k[y];
			}
		}
		return values;
		
	}
	private double[] makeKernel(double radius) {
		
		radius += 1;
		int size = (int)radius*2+1;
		double[] kernel = new double[size];
		double v = radius - (int)radius; 
		for (int i=0; i<size; i++)
			kernel[i] = (float)Math.exp(-0.5*(sqr((i-(radius-v))/((radius)*2)))/sqr(0.2));
		double[] kernel2 = new double[size-2];
		for (int i=0; i<size-2; i++)
			kernel2[i] = kernel[i+1];
		if (kernel2.length==1)
			kernel2[0] = 1f;
		return kernel2;
		
	}
	
	private double sqr(double x) {return x*x;}
	
	
	/**************************************************************************************
	 *  
	 *  Drawing Routines 
	 *  
	 **************************************************************************************/
	private void surfacePlotFilledFast(){
		
		for (int row=0; row<gridHeight-1; row++){
			for (int col=0; col<gridWidth-1; col++){
				int i = row*gridWidth + col;
				
				SurfacePlotData p0 = plotList[i];
				
				if (p0 != null) {
					SurfacePlotData p1 = plotList[i+1];
					SurfacePlotData p2 = plotList[i+gridWidth];
					SurfacePlotData p3 = plotList[i+gridWidth+1];
					
					if ( (p1 != null) && (p2 != null) && (p3 != null)) {
						
						tr.transform(p0);
						int X0 = (int) tr.X, Y0 = (int) tr.Y, Z0 = (int) tr.Z;
						tr.transform(p1);
						int X1 = (int) tr.X, Y1 = (int) tr.Y, Z1 = (int) tr.Z;
						tr.transform(p2);
						int X2 = (int) tr.X, Y2 = (int) tr.Y, Z2 = (int) tr.Z;
						tr.transform(p3);
						int X3 = (int) tr.X, Y3 = (int) tr.Y, Z3 = (int) tr.Z;				
						
						if(!(   X0 >= bufferWidth && X0 < 0 &&  Y0 >= bufferHeight && Y0 < 0 &&
								X1 >= bufferWidth && X1 < 0 &&  Y1 >= bufferHeight && Y1 < 0 &&
								X2 >= bufferWidth && X2 < 0 &&  Y2 >= bufferHeight && Y2 < 0 &&
								X3 >= bufferWidth && X3 < 0 &&  Y3 >= bufferHeight && Y3 < 0 ) ) {

							int c0 = getColor(p0);
							int c1 = getColor(p1);
							int c2 = getColor(p2);
							int c3 = getColor(p3);
							

							int r0 = ((c0 >> 16) & 0xff);
							int g0 = ((c0 >>  8) & 0xff);
							int b0 = ((c0      ) & 0xff);
							int r1 = ((c1 >> 16) & 0xff);
							int g1 = ((c1 >>  8) & 0xff);
							int b1 = ((c1      ) & 0xff);
							int r2 = ((c2 >> 16) & 0xff);
							int g2 = ((c2 >>  8) & 0xff);
							int b2 = ((c2      ) & 0xff);
							int r3 = ((c3 >> 16) & 0xff);
							int g3 = ((c3 >>  8) & 0xff);
							int b3 = ((c3      ) & 0xff);

							int xMin = Math.min(Math.min(X0,X1), Math.min(X2,X3));
							int xMax = Math.max(Math.max(X0,X1), Math.max(X2,X3));
							int yMin = Math.min(Math.min(Y0,Y1), Math.min(Y2,Y3));
							int yMax = Math.max(Math.max(Y0,Y1), Math.max(Y2,Y3));

							int nY = yMax - yMin;
							double dy = (nY > 0) ? 1./nY : 0;

							int nX = xMax - xMin;
							double dx = (nX > 0) ? 1./nX : 0;

							tr.x   =  (p0.dx + p1.dx + p2.dx +p3.dx)/4; 
							tr.y   =  (p0.dy + p1.dy + p2.dy +p3.dy)/4;

							double li = -light * tr.getScalarProduct() *255;

							double v = 0;
							
							for (int y= yMin ; y < yMax ; y++, v += dy ) {
								double h = 0;

								for (int x= xMin ; x < xMax ; x++, h += dx ) {

									if( Misc.inside(x, y, X0, Y0, X1, Y1, X3, Y3) || 
											Misc.inside(x, y, X0, Y0, X3, Y3, X2, Y2))	{

										double d0 = (1-h)*(1-v);
										double d1 = h*(1-v);
										double d2 = (1-h)*v;
										double d3 = h*v;

										int z = (int) (d0*Z0 + d1*Z1 +d2*Z2 +d3*Z3);

										if (y >= 0 && y < bufferHeight && x >= 0 && x < bufferWidth) { 

											int pos = y*bufferWidth + x;  

											if (z < zbufferPixels[pos]) {
												zbufferPixels[pos] = z;

												int r = (int) (r3*d3 + r2*d2 + r1*d1 + r0*d0);
												int g = (int) (g3*d3 + g2*d2 + g1*d1 + g0*d0);
												int b = (int) (b3*d3 + b2*d2 + b1*d1 + b0*d0);

												r = (int) Math.min(255, Math.max(0, r + li));
												g = (int) Math.min(255, Math.max(0, g + li));
												b = (int) Math.min(255, Math.max(0, b + li));

												bufferPixels[pos] = 0xff000000 | (r << 16) | (g << 8) | b;
											}
										}
									}
								}
							}
						}
					}
				}	
			}
		}			
	}


	private int getColor(SurfacePlotData p0) {
		int c0;
		if (lutNr == JRenderer3D.LUT_ORIGINAL) {
			c0 = p0.color; 
		}
		else if (lutNr == JRenderer3D.LUT_GRADIENT) { 
			c0 = ((int) (p0.dx*127 + 127) << 16) | ((int) (p0.dy*127 + 127) << 8) | (int) (p0.dz*127 + 127);	
		}
		else {
			int index = (int) (p0.z + 128);
			if (index > 255)
				index = 255;
			if (index < 0)
				index = 0;
			c0 = lut.colors[index];
		}
		return c0;
	}

	private void surfacePlotFilled(){	
		for (int row = 0; row < gridHeight - 1; row++) {
			for (int col = 0; col < gridWidth - 1; col++) {
				int i = row * gridWidth + col;
	
				SurfacePlotData p0 = plotList[i];
	
				if (p0 != null) {
					SurfacePlotData p1 = plotList[i + 1];
					SurfacePlotData p2 = plotList[i + gridWidth];
					SurfacePlotData p3 = plotList[i + gridWidth + 1];
	
					if ((p1 != null) && (p2 != null) && (p3 != null)) {
						
						tr.transform(p0);
						double x0 = tr.X, y0 = tr.Y, z0 = tr.Z;
						tr.x = p0.dx;
						tr.y = p0.dy;
						double light0 = tr.getScalarProduct();
						
						tr.transform(p1);
						double x1 = tr.X, y1 = tr.Y, z1 = tr.Z;
						tr.x = p1.dx;
						tr.y = p1.dy;
						double light1 = tr.getScalarProduct();
						
						tr.transform(p2);
						double x2 = tr.X, y2 = tr.Y, z2 = tr.Z;
						tr.x = p2.dx;
						tr.y = p2.dy;
						double light2 = tr.getScalarProduct();
						
						tr.transform(p3);
						double x3 = tr.X, y3 = tr.Y, z3 = tr.Z;
						tr.x = p3.dx;
						tr.y = p3.dy;
						double light3 = tr.getScalarProduct();
						
						if(!(   x0 >= bufferWidth && x0 < 0 &&  y0 >= bufferHeight && y0 < 0 &&
								x1 >= bufferWidth && x1 < 0 &&  y1 >= bufferHeight && y1 < 0 &&
								x2 >= bufferWidth && x2 < 0 &&  y2 >= bufferHeight && y2 < 0 &&
								x3 >= bufferWidth && x3 < 0 &&  y3 >= bufferHeight && y3 < 0 ) ) {
							
							int c0 = getColor(p0);
							int c1 = getColor(p1);
							int c2 = getColor(p2);
							int c3 = getColor(p3);
							
							int r0 = ((c0 >> 16) & 0xff);
							int g0 = ((c0 >>  8) & 0xff);
							int b0 = ((c0      ) & 0xff);
							int r1 = ((c1 >> 16) & 0xff);
							int g1 = ((c1 >>  8) & 0xff);
							int b1 = ((c1      ) & 0xff);
							int r2 = ((c2 >> 16) & 0xff);
							int g2 = ((c2 >>  8) & 0xff);
							int b2 = ((c2      ) & 0xff);
							int r3 = ((c3 >> 16) & 0xff);
							int g3 = ((c3 >>  8) & 0xff);
							int b3 = ((c3      ) & 0xff);
							
							double n13 = Math.abs(x1-x3) + Math.abs(y1-y3);
							double n02 = Math.abs(x0-x2) + Math.abs(y0-y2);
							int stepsY = (int) (Math.max(n13, n02) + 1);
							
							double dy = 1./stepsY;
							
							double dx02 = (x2-x0)*dy;
							double dy02 = (y2-y0)*dy;
							double dx13 = (x3-x1)*dy;
							double dy13 = (y3-y1)*dy;
	
							double x02 = x0;
							double y02 = y0;
	
							double x13 = x1;
							double y13 = y1;
							
							double v = 0;
							
							for(int sy=0; sy<stepsY; sy++, v+= dy) {
	
								x02 += dx02;
								y02 += dy02;
	
								x13 += dx13;
								y13 += dy13;
								
								int stepsX = (int) (Math.abs(x02-x13) + Math.abs(y02-y13) + 1);
								
								double dx = 1./stepsX;
	
								double dx0213 = (x13-x02)*dx;
								double dy0213 = (y13-y02)*dx;
	
								double x0213 = x02;
								double y0213 = y02;
								
								double h = 0;
								
								for(int sx=0; sx<stepsX; sx++, h+=dx) {
									
									x0213 += dx0213;
									y0213 += dy0213;
									
									double d0 = (1 - h) * (1 - v);
									double d1 = h * (1 - v);
									double d2 = (1 - h) * v;
									double d3 = h * v;
	
									double z = d0 * z0 + d1 * z1 + d2 * z2 + d3 * z3;
	
									if (x0213 >= 0 && x0213 < bufferWidth && y0213 >= 0 && y0213 < bufferHeight) {
										int pos = (int)y0213 * bufferWidth + (int)x0213;
										if (z < zbufferPixels[pos]) {
											zbufferPixels[pos] = z;
											int r = (int) (r3*d3 + r2*d2 + r1*d1 + r0*d0);
											int g = (int) (g3*d3 + g2*d2 + g1*d1 + g0*d0);
											int b = (int) (b3*d3 + b2*d2 + b1*d1 + b0*d0);
	
											double light0123 = d3*light3 + d2*light2 + d1*light1 + d0*light0;
	
											double l = -light * light0123 *255;
	
											r = (int) Math.min(255, Math.max(0, r + l));
											g = (int) Math.min(255, Math.max(0, g + l));
											b = (int) Math.min(255, Math.max(0, b + l));
	
											bufferPixels[pos] = 0xff000000 | (r << 16) | (g << 8) | b;
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}

	private void surfacePlotIsoLines(){	
		for (int row = 0; row < gridHeight - 1; row++) {
			for (int col = 0; col < gridWidth - 1; col++) {
				int i = row * gridWidth + col;

				SurfacePlotData p0 = plotList[i];

				if (p0 != null) {
					SurfacePlotData p1 = plotList[i + 1];
					SurfacePlotData p2 = plotList[i + gridWidth];
					SurfacePlotData p3 = plotList[i + gridWidth + 1];

					if ((p1 != null) && (p2 != null) && (p3 != null)) {
						
						tr.transform(p0);
						double x0 = tr.X, y0 = tr.Y, z0 = tr.Z;
						tr.x = p0.dx;
						tr.y = p0.dy;
						double light0 = tr.getScalarProduct();
						
						tr.transform(p1);
						double x1 = tr.X, y1 = tr.Y, z1 = tr.Z;
						tr.x = p1.dx;
						tr.y = p1.dy;
						double light1 = tr.getScalarProduct();
						
						tr.transform(p2);
						double x2 = tr.X, y2 = tr.Y, z2 = tr.Z;
						tr.x = p2.dx;
						tr.y = p2.dy;
						double light2 = tr.getScalarProduct();
						
						tr.transform(p3);
						double x3 = tr.X, y3 = tr.Y, z3 = tr.Z;
						tr.x = p3.dx;
						tr.y = p3.dy;
						double light3 = tr.getScalarProduct();
						
						if(!(   x0 >= bufferWidth && x0 < 0 &&  y0 >= bufferHeight && y0 < 0 &&
								x1 >= bufferWidth && x1 < 0 &&  y1 >= bufferHeight && y1 < 0 &&
								x2 >= bufferWidth && x2 < 0 &&  y2 >= bufferHeight && y2 < 0 &&
								x3 >= bufferWidth && x3 < 0 &&  y3 >= bufferHeight && y3 < 0 ) ) {
							
							int c0 = getColor(p0);
							int c1 = getColor(p1);
							int c2 = getColor(p2);
							int c3 = getColor(p3);
							
							double lum0 = p0.z;
							double lum1 = p1.z;
							double lum2 = p2.z;
							double lum3 = p3.z;
							
							int r0 = ((c0 >> 16) & 0xff);
							int g0 = ((c0 >>  8) & 0xff);
							int b0 = ((c0      ) & 0xff);
							int r1 = ((c1 >> 16) & 0xff);
							int g1 = ((c1 >>  8) & 0xff);
							int b1 = ((c1      ) & 0xff);
							int r2 = ((c2 >> 16) & 0xff);
							int g2 = ((c2 >>  8) & 0xff);
							int b2 = ((c2      ) & 0xff);
							int r3 = ((c3 >> 16) & 0xff);
							int g3 = ((c3 >>  8) & 0xff);
							int b3 = ((c3      ) & 0xff);
							
							double n13 = Math.abs(x1-x3) + Math.abs(y1-y3);
							double n02 = Math.abs(x0-x2) + Math.abs(y0-y2);
							int stepsY = (int) (Math.max(n13, n02) + 1);
							
							double dy = 1./stepsY;
							
							double dx02 = (x2-x0)*dy;
							double dy02 = (y2-y0)*dy;
							double dx13 = (x3-x1)*dy;
							double dy13 = (y3-y1)*dy;

							double x02 = x0;
							double y02 = y0;

							double x13 = x1;
							double y13 = y1;
							
							double v = 0;
							
							for(int sy=0; sy<stepsY; sy++, v+= dy) {

								x02 += dx02;
								y02 += dy02;

								x13 += dx13;
								y13 += dy13;
								
								int stepsX = (int) (Math.abs(x02-x13) + Math.abs(y02-y13) + 1);
								
								double dx = 1./stepsX;

								double dx0213 = (x13-x02)*dx;
								double dy0213 = (y13-y02)*dx;

								double x0213 = x02;
								double y0213 = y02;
								
								double h = 0;
								
								for(int sx=0; sx<stepsX; sx++, h+=dx) {
									
									x0213 += dx0213;
									y0213 += dy0213;
									
									double d0 = (1 - h) * (1 - v);
									double d1 = h * (1 - v);
									double d2 = (1 - h) * v;
									double d3 = h * v;

									double z = d0 * z0 + d1 * z1 + d2 * z2 + d3 * z3;
									
									if (x0213 >= 0 && x0213 < bufferWidth && y0213 >= 0 && y0213 < bufferHeight) {
										int pos = (int)y0213 * bufferWidth + (int)x0213;
										if (z < zbufferPixels[pos]) {
											double lum = d0 * lum0 + d1 * lum1 + d2 * lum2 + d3 * lum3 + 132;

											if (lum - 12*(int)(lum/12) < 1.5) {

												zbufferPixels[pos] = z;
												int r = (int) (r3*d3 + r2*d2 + r1*d1 + r0*d0);
												int g = (int) (g3*d3 + g2*d2 + g1*d1 + g0*d0);
												int b = (int) (b3*d3 + b2*d2 + b1*d1 + b0*d0);

												double light0123 = d3*light3 + d2*light2 + d1*light1 + d0*light0;

												double l = -light * light0123 *255;

												r = (int) Math.min(255, Math.max(0, r + l));
												g = (int) Math.min(255, Math.max(0, g + l));
												b = (int) Math.min(255, Math.max(0, b + l));

												bufferPixels[pos] = 0xff000000 | (r << 16) | (g << 8) | b;
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}

	private void surfacePlotMesh(){
		
		for (int row=0; row<gridHeight; row++){ 
			for (int col=0; col<gridWidth; col++){ 
				int i = row*gridWidth + col;
				
				SurfacePlotData p0 = plotList[i];
				int r0, g0, b0, r1, g1, b1, r2, g2, b2; 
				
				if (p0 != null ) {
					tr.transform(p0);
					double x0 = tr.X, y0 = tr.Y, z0 = tr.Z;
					
					int c0 = getColor(p0);
					
					r0 = ((c0 >> 16) & 0xff);
					g0 = ((c0 >>  8) & 0xff);
					b0 = ((c0      ) & 0xff);
					
					SurfacePlotData p1 = (col<gridWidth-1) ? plotList[i+1] : null;
					
					if ( p1 != null ) {
						tr.transform(p1);
						double x1 = tr.X,   y1 = tr.Y,   z1 = tr.Z;
						double dx10 = x1-x0, dy10 = y1-y0, dz10 = z1-z0;
						
						int c1 = getColor(p1);
						
						r1 = ((c1 >> 16) & 0xff);
						g1 = ((c1 >>  8) & 0xff);
						b1 = ((c1      ) & 0xff);
						

						int numSteps = (int) (Math.max(Math.abs(dx10),Math.abs(dy10)) + 1);
						
						double step = 1. / numSteps;
						
						for (int s = 0; s < numSteps; s++) {
							double f = s * step;
							
							int x = (int) (x0 + f*dx10);
							int y = (int) (y0 + f*dy10);
							
							if (x >= 0 && y >= 0 && x < bufferWidth && y < bufferHeight) { 
								int pos = y*bufferWidth + x;  
								int z = (int) (z0 + f*dz10);
								if (z < zbufferPixels[pos]) {
									zbufferPixels[pos] = z;
									
									int r = (int) (f*r1 + (1-f)*r0);
									int g = (int) (f*g1 + (1-f)*g0);
									int b = (int) (f*b1 + (1-f)*b0);
												
									tr.x   =  p0.dx; 
									tr.y   =  p0.dy; 

									double l = -light * tr.getScalarProduct()  *255;

									r = (int) Math.min(255, Math.max(0, r + l));
									g = (int) Math.min(255, Math.max(0, g + l));
									b = (int) Math.min(255, Math.max(0, b + l));								

									bufferPixels[pos] = 0xff000000 | (r << 16) | (g << 8) | b; 
								}
							}
						}
					}
					
					SurfacePlotData p2 = (row<gridHeight-1) ? plotList[i+gridWidth] : null;
					
					if ( p2 != null ) {
						tr.transform(p2);
						double x2 = tr.X,   y2 = tr.Y,   z2 = tr.Z;
						double dx20 = x2-x0, dy20 = y2-y0, dz20 = z2-z0;
						
						int c2 = getColor(p2);
						
						r2 = ((c2 >> 16) & 0xff);
						g2 = ((c2 >>  8) & 0xff);
						b2 = ((c2      ) & 0xff);

						
						int numSteps = (int) (Math.max(Math.abs(dx20),Math.abs(dy20)) + 1);
						
						double step = 1. / numSteps;
						
						for (int s = 0; s < numSteps; s++) {
							double f = s * step;
							
							int x = (int) (x0 + f*dx20);
							int y = (int) (y0 + f*dy20);
							
							if (x >= 0 && y >= 0 && x < bufferWidth && y < bufferHeight) { 
								int pos = y*bufferWidth + x;  
								int z = (int) (z0 + f*dz20);
								if (z < zbufferPixels[pos]) {
									zbufferPixels[pos] = z;
									
									int r = (int) (f*r2 + (1-f)*r0);
									int g = (int) (f*g2 + (1-f)*g0);
									int b = (int) (f*b2 + (1-f)*b0);

									tr.x   =  p0.dx; 
									tr.y   =  p0.dy; 

									double l = -light * tr.getScalarProduct()  *255;

									r = (int) Math.min(255, Math.max(0, r + l));
									g = (int) Math.min(255, Math.max(0, g + l));
									b = (int) Math.min(255, Math.max(0, b + l));	
									
									bufferPixels[pos] = 0xff000000 | (r << 16) | (g << 8) | b; 
								}
							}
						}
					}
				}	
			}
		}			
	}
	
	
	private void surfacePlotLines(){
		for (int row=0; row<gridHeight; row++){
			for (int col=0; col<gridWidth-1; col++){
				int i = row*gridWidth + col;
				SurfacePlotData p0 = plotList[i]; 
				SurfacePlotData p1 = plotList[i+1]; 
				
				if (p0 != null &&  p1 != null) {
					
					tr.transform(p0);
					double x0 = tr.X, y0 = tr.Y, z0 = tr.Z;
					
					int c0 = getColor(p0);
					
					int r0 = ((c0 >> 16) & 0xff);
					int g0 = ((c0 >>  8) & 0xff);
					int b0 = ((c0      ) & 0xff);


					tr.transform(p1);
					double x1 = tr.X,   y1 = tr.Y,   z1 = tr.Z;
					double dx1 = x1-x0, dy1 = y1-y0, dz1 = z1-z0;

					int numSteps = (int) (Math.max(Math.abs(dx1),Math.abs(dy1))+1);

					int c1 = getColor(p1);

					int r1 = ((c1 >> 16) & 0xff);
					int g1 = ((c1 >>  8) & 0xff);
					int b1 = ((c1      ) & 0xff);

					double step = 1. / numSteps;
					int r, g, b;
					for (int s = 0; s < numSteps; s++) {
						double f = s * step;

						int x = (int) (x0 + f*dx1);
						int y = (int) (y0 + f*dy1);

						if (x >= 0 && y >= 0 && x < bufferWidth && y < bufferHeight) { 
							int pos = y*bufferWidth + x;  
							double z = z0 + f*dz1;
							if (z < zbufferPixels[pos]) {
								zbufferPixels[pos] = z;

								r = (int) ((1-f)*r0 + f*r1);
								g = (int) ((1-f)*g0 + f*g1);
								b = (int) ((1-f)*b0 + f*b1);

								tr.x   =  p0.dx; 
								tr.y   =  p0.dy; 

								double l = -light * tr.getScalarProduct()  *255;

								r = (int) Math.min(255, Math.max(0, r + l));
								g = (int) Math.min(255, Math.max(0, g + l));
								b = (int) Math.min(255, Math.max(0, b + l));							

								bufferPixels[pos] = 0xff000000 | (r << 16) | (g << 8) | b; 
							}
						}
					}
				}
			}
		}
	}
	
	private void surfacePlotDots(){
		
		for (int i=plotList.length-1; i>=0; i--){

			SurfacePlotData p0 = plotList[i]; 
			if (p0 != null) {

				tr.transform(p0);
				int x = (int) tr.X, y = (int) tr.Y;

				if (x >= 0 && y >= 0 && x < bufferWidth && y < bufferHeight) { 
					int pos = y*bufferWidth + x;  
					int z = (int) tr.Z;
					if (z < zbufferPixels[pos]) {
						zbufferPixels[pos] = z;		

						int c0 = getColor(p0);
						
						int r0 = ((c0 >> 16) & 0xff);
						int g0 = ((c0 >>  8) & 0xff);
						int b0 = ((c0      ) & 0xff);
						
						tr.x   =  p0.dx; 
						tr.y   =  p0.dy; 

						double l = -light * tr.getScalarProduct()  *255;

						int r = (int) Math.min(255, Math.max(0, r0 + l));
						int g = (int) Math.min(255, Math.max(0, g0 + l));
						int b = (int) Math.min(255, Math.max(0, b0 + l));
						
						bufferPixels[pos] = 0xff000000 | (r << 16) | (g << 8) | b;

					}
				}	
			}	
		}
	}		

	
	
	private void surfacePlotDotsNoLight(){
		
		int delta = Math.max(gridHeight, gridWidth) / 128;
		if (delta < 1)
			delta = 1;
		
		for (int row=0; row<gridHeight; row+= delta){
			for (int col=0; col<gridWidth-1; col+= delta){
				int i = row*gridWidth + col;
				SurfacePlotData p0 = plotList[i]; 
				if (p0 != null) {

					tr.transform(p0);

					int x = (int) tr.X;
					int y = (int) tr.Y;

					if (x >= 0 && y >= 0 && x < bufferWidth-1 && y < bufferHeight-1) { 
						int pos = y*bufferWidth + x;  
						int z = (int) tr.Z;

						if (z < zbufferPixels[pos]) {
							int c0 = 0xFF000000 | getColor(p0);

							zbufferPixels[pos] = z;
							bufferPixels[pos] = c0; 
							zbufferPixels[pos+1] = z;
							zbufferPixels[pos+bufferWidth] = z;
							zbufferPixels[pos+bufferWidth+1] = z;
							bufferPixels[pos+1] = c0; 
							bufferPixels[pos+bufferWidth] = c0; 
							bufferPixels[pos+bufferWidth+1] = c0; 
						}
					}
				}
			}
		}
	}

	protected void setSurfacePLotSetLight(double light) {
		this.light = light;
	}

	protected void setSurfaceGridSize(int width, int height) {
		this.gridWidth = width; 
		this.gridHeight = height; 	
	}

	protected void setSurfacePlotMode(int surfacePlotMode) {
		this.surfacePlotMode = surfacePlotMode;
	}

	protected void setBuffers(int[] bufferPixels, double[] zbufferPixels, int bufferWidth, int bufferHeight) {
		this.bufferPixels =  bufferPixels;
		this.zbufferPixels = zbufferPixels;
		this.bufferWidth = bufferWidth;
		this.bufferHeight = bufferHeight;
	}



	protected void setTransform(Transform transform) {
		this.tr = transform;	
	}

	protected void setSurfacePlotCenter(double xCenter, double yCenter, double zCenter) {
		this.xCenter = xCenter;
		this.yCenter = yCenter;
		this.zCenter = zCenter;
	}

	protected void setSurfacePlotLut(int lutNr) {
		this.lutNr = lutNr;
		if (lut != null)
			lut.setLut(lutNr);	
	}

	protected void setMinMax(int min, int max) {
		this.min = min;
		this.max = max;
	}

	protected void setInverse(boolean b) {
		inversefactor = (b) ? -1 : 1; 
			
		for (int i = 0; i < plotList.length; i++)
			if (plotList[i] != null)
				plotList[i].z = inversefactor*plotList[i].zf;
	}
}

