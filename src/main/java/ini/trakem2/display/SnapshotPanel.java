/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005-2009 Albert Cardona and Rodney Douglas.

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

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.display;


import ini.trakem2.persistence.FSLoader;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;

import javax.swing.JPanel;

public class SnapshotPanel extends JPanel implements MouseListener {

	private static final long serialVersionUID = 1L;
	private Display display;
	private Displayable d;
	static public final int SIDE = 50;
	static public final Color GREY = new Color(215, 215, 215);

	public SnapshotPanel(Display display, Displayable d) {
		this.display = display;
		this.d = d;
		// Always a square
		Dimension dim = new Dimension(SIDE, SIDE);
		setMinimumSize(dim);
		setMaximumSize(dim);
		setPreferredSize(dim);
	}

	public void set(final Displayable d) {
		this.d = d;
		repaint();
	}

	public void update(Graphics g) {
		paint(g);
	}

	private BufferedImage img = null;

	private void fillBackground(Graphics g, double lw, double lh, int slw, int slh) {
		if (lw != lh) {
			g.setColor(Color.black);
			g.fillRect(0, 0, slw, slh);
			g.setColor(GREY);
			g.fillRect(slw, 0, SIDE - slw, SIDE);
			g.fillRect(0, slh, slw, SIDE - slh);
		} else {
			g.setColor(Color.black);
			g.fillRect(0, 0, SIDE, SIDE);
		}
	}

	/** Paint the snapshot image over a black background that represents a scaled Layer. */
	public void paint(final Graphics g) {
		if (null == g) return; // happens if not visible
		synchronized (this) {
			if (null != img) {
				// Paint and flush
				g.drawImage(img, 0, 0, null);
				this.img.flush();
				this.img = null;
				return;
			}
		}
		// Else, repaint background to avoid flickering
		final Layer la = display.getLayer();
		if (null == la) {
			Utils.log2("SnapshotPanel: null layer?");
			return;
		}

		final double lw = la.getLayerWidth();
		final double lh = la.getLayerHeight();
		final double scale = Math.min(SIDE / lw,
			                      SIDE / lh);
		final int slw = (int)(lw * scale);
		final int slh = (int)(lh * scale);

		fillBackground(g, lw, lh, slw, slh);

		// ... and create the image in a separate thread and repaint again
		FSLoader.repainter.submit(new Runnable() { public void run() {
			try {
				if (!display.isPartiallyWithinViewport(d)) return;
				BufferedImage img = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
				Graphics2D g2 = img.createGraphics();

				fillBackground(g2, lw, lh, slw, slh);

				g2.scale(scale, scale);

				try {
					// Avoid painting images that have an alpha mask: takes forever.
					//if (d.getClass() == Patch.class && ((Patch)d).hasAlphaChannel()) {
					//	d.paintAsBox(g2);
					//} else {
					final Layer la = display.getLayer();
					d.paintSnapshot(g2, la, la.getParent().getColorCueLayerRange(la), d.getLayerSet().get2DBounds(), scale);
					//}
				} catch (Exception e) {
					d.paintAsBox(g2);
				}
				synchronized (this) {
					if (null != SnapshotPanel.this.img) SnapshotPanel.this.img.flush();
					SnapshotPanel.this.img = img;
				}
				repaint();
			} catch (Throwable t) {
				IJError.print(t);
			}
		}});
	}

	public void mousePressed(MouseEvent me) {
		//must enable cancel!//if (display.isTransforming()) return;
		display.setActive(d);
		if (me.isPopupTrigger() || (ij.IJ.isMacOSX() && me.isControlDown()) || MouseEvent.BUTTON2 == me.getButton()) {
			display.showPopup(this, me.getX(), me.getY());
		}
	}
	public void mouseReleased(MouseEvent me) {}
	public void mouseEntered(MouseEvent me) {}
	public void mouseExited (MouseEvent me) {}
	public void mouseClicked(MouseEvent me) {}
}

