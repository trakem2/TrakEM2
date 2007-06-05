package ini.trakem2.display;

import ij.ImagePlus;
import ij.gui.NewImage;
import ij.process.ImageProcessor;

import jRenderer3D.JRenderer3D;
import jRenderer3D.Line3D;
import jRenderer3D.Point3D;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.awt.Polygon;
import java.util.Iterator;

import javax.swing.JFrame;
import javax.swing.JPanel;

import ini.trakem2.tree.*;
import ini.trakem2.display.*;
import ini.trakem2.utils.*;

/* Adapted from Kai Uwe Barthel's ImageJ3D example plugins
 * Example application for displaying a simple 3D scene (interactive)
 * Use the mouse to control x- and y- rotation
 */

public class Interactive3DSpace extends JFrame {

	private JPanel panel;
	private ImageRegion imageRegion;

	private JRenderer3D jRenderer3D;

	private final int width = 400;
	private final int height = 400;

	private int xStart;
	private int yStart;
	protected int xdiff;
	protected int ydiff;

	public Interactive3DSpace(ProjectThing thing, LayerSet layer_set) {
		super("3D Space");

		panel = new JPanel();
		panel.setSize(width, height);

		createImageRegion();
		panel.add(imageRegion);

		// fill the space with the given Thing elements
		initJRenderer3D(thing, layer_set);

		getContentPane().add(panel);
		pack(); 
		setResizable(true);
		setVisible(true);

		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				dispose();
			}
		});

		panel.requestFocus();
	}


	/***************************************************
	 *             J RENDERER 3D  METHODS              *
	 ***************************************************/

	private void initJRenderer3D(ProjectThing thing, LayerSet layer_set){
		//size of cube: proportional to the LayerSet
		double w_ls = layer_set.getLayerWidth();
		double h_ls = layer_set.getLayerHeight();
		double d_ls = layer_set.getDepth();

		int xSize = (int)w_ls;
		int ySize = (int)h_ls;
		int zSize = (int)d_ls;
		double base = 400.0;

		double scale = 1.0;

		// resize so that the largest measures 'base'
		if (w_ls >= h_ls && w_ls >= d_ls) {
			scale = base / w_ls;
		} else if (h_ls >= w_ls && h_ls >= d_ls) {
			scale = base / h_ls;
		} else {
			scale = base / d_ls;
		}


		//position of cube
		int xCenter = xSize/2;
		int yCenter = ySize/2;
		int zCenter = zSize/2;

		//setup 3D rendering
		jRenderer3D = new JRenderer3D(xCenter, yCenter, zCenter); // size of the rendered image

		// set the scale accordingly
		jRenderer3D.setTransformScale(scale);

		//create 3D elements
		drawThing(thing, xSize, ySize, zSize, xCenter, yCenter);
		// draw space box
		drawAxis(xSize, ySize, zSize);

		//render 3D scene
		jRenderer3D.doRendering();

		// show new image
		imageRegion.setImage(jRenderer3D);
		imageRegion.repaint();
	}

	private void drawAxis(int xSize, int ySize, int zSize) {
		Color color = Color.white;
		jRenderer3D.addLine3D(new Line3D(0, 0, 0, xSize, 0, 0, color));
		jRenderer3D.addLine3D(new Line3D(0, 0, 0, 0, ySize, 0, color));
		jRenderer3D.addLine3D(new Line3D(0, 0, 0, 0, 0, zSize, color));
	}

	private void drawThing(ProjectThing thing, int xSize, int ySize, int zSize, int xCenter, int yCenter){
		// draw the Thing itself if it's a basic type
		String type = thing.getType();
		if (thing.getProject().isBasicType(type)) {
			if (type.equals("profile_list")) {
				if (null == thing.getChildren()) return;
				for (Iterator it = thing.getChildren().iterator(); it.hasNext(); ) {
					Profile pr = (Profile)((Thing)it.next()).getObject();
					Color color = pr.getColor();
					int z = (int)pr.getLayer().getZ();
					Polygon p = pr.getPerimeter(pr.getX(), pr.getY());
					if (null == p) Utils.log2("polygon is null");
					if (null == pr) Utils.log2("profile is null");
					for (int i=p.npoints -1; i > 0; i--) {
						jRenderer3D.addLine3D(new Line3D(p.xpoints[i], p.ypoints[i], z, p.xpoints[i-1], p.ypoints[i-1], z, color));
					}
					if (pr.isClosed()) {
						jRenderer3D.addLine3D(new Line3D(p.xpoints[0], p.ypoints[0], z, p.xpoints[p.npoints-1], p.ypoints[p.npoints-1], z, color));
					}
				}
			} else if (type.equals("ball")) {
				Ball ball = (Ball)thing.getObject();
				Color color = ball.getColor();
				double[][] b = ball.getBalls();
				double x = ball.getX();
				double y = ball.getY();
				for (int i=0; i<b.length; i++) {
					jRenderer3D.addPoint3D(new Point3D((int)(x + b[i][0]), (int)(y + b[i][1]), (int)b[i][2], (int)b[i][3], color, Point3D.SPHERE));
				}
			} else if (type.equals("pipe")) {
				// the 2D perimeter, at least it will be useful to show the width
				Pipe pipe = (Pipe)thing.getObject();
				Color color = pipe.getColor();
				double[][] p = pipe.getBackbone();
				double x = pipe.getX();
				double y = pipe.getY();
				for (int i=0; i<p.length-1; i++) {
					jRenderer3D.addLine3D(new Line3D((int)(x + p[i][0]), (int)(y + p[i][1]), (int)p[i][2], (int)(x + p[i+1][0]), (int)(y + p[i+1][1]), (int)p[i+1][2], color));
				}
			}
		}

		// draw its children if any, except for profile_list Things
		if (type.equals("profile_list") || null == thing.getChildren()) return;
		for (Iterator it = thing.getChildren().iterator(); it.hasNext(); ) {
			drawThing((ProjectThing)it.next(), xSize, ySize, zSize, xCenter, yCenter);
		}
	}

	/***************************************************
	 *                   Image Region                  *
	 ***************************************************/

	/**
	 * Creates the image region. Sets mouse and mouse motion listeners.
	 *
	 */
	private void createImageRegion(){
		imageRegion = new ImageRegion();
		imageRegion.addMouseListener(new MouseListener(){

			public void mousePressed(MouseEvent arg0) {
				// set start point
				xStart = arg0.getX();
				yStart = arg0.getY();
				
				xdiff = 0;
				ydiff = 0;	
			}

			public void mouseClicked(MouseEvent arg0) {}
			public void mouseReleased(MouseEvent arg0) {}
			public void mouseEntered(MouseEvent arg0) {}
			public void mouseExited(MouseEvent arg0) {}
			
		});
		imageRegion.addMouseMotionListener(new MouseMotionListener(){
			public void mouseDragged(MouseEvent evt) {
				//where is the mouse now?
				int xAct = evt.getX();
				int yAct = evt.getY();
				
				//difference to start point
				xdiff = xAct - xStart;
				ydiff = yAct - yStart;
				
				//set rotation changes and render image
				jRenderer3D.changeTransformRotationXZ(-ydiff/2, xdiff/2);
				jRenderer3D.doRendering();
				imageRegion.setImage(jRenderer3D);
				imageRegion.repaint();
				
				//set start point to actual point
				xStart = xAct;
				yStart = yAct;
				
			}
			public void mouseMoved(MouseEvent evt) {}
		});
	}


	/***************************************************
	 *            I N N E R   C L A S S E S            *
	 ***************************************************/

	/**
	 * Image Region
	 */
	class ImageRegion  extends JPanel {

		private Image image;
		private int width;
		private int height;

		public Dimension getPreferredSize() {
			return new Dimension(width, height);
		}
		public Dimension getMinimumSize() {
			return new Dimension(width, height);
		}

		public void setImage(JRenderer3D pic){
			height = pic.getHeight();
			width = pic.getWidth();
			image = pic.getImage();
		}

		public void setImage(Image image){
			this.image = image;
		}

		public void paint(Graphics g) {
			
			if (image != null ) {
				g.drawImage(image, 0, 0, width, height, (ImageObserver) this);
			}	
		}

		synchronized void saveToImageJImage() {
			
			BufferedImage bufferedImage =  new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			
			paint(bufferedImage.createGraphics());
			
			ImagePlus plotImage = NewImage.createRGBImage ("ImageJ 3D", width, height, 1, NewImage.FILL_BLACK);
			
			ImageProcessor ip = plotImage.getProcessor();
			
			int[] pixels = (int[]) ip.getPixels();
			bufferedImage.getRGB(0, 0, width, height, pixels, 0, width);
			
			plotImage.show();
			plotImage.updateAndDraw();	
		}

		//-------------------------------------------------------------------
		
		public void update(Graphics g) {
			paint(g);
		}
		public int getHeight() {
			return height;
		}
		public void setHeight(int height) {
			this.height = height;
		}
		public int getWidth() {
			return width;
		}
		public void setWidth(int width) {
			this.width = width;
		}
	}
}
