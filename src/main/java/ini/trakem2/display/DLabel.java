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

import ij.gui.GenericDialog;
import ij.gui.TextRoi;
import ini.trakem2.Project;
import ini.trakem2.persistence.XMLOptions;
import ini.trakem2.utils.Utils;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** This class is named funny to avoid confusion with java.awt.Label.
 * The 'D' stands for Displayable Label.
 *
 * Types:
 *  - text
 *  - arrow
 *  - dot
 *
 * All of them can contain text, editable through double-click.
 *
 * */
public class DLabel extends Displayable implements VectorData {

	public static final int TEXT = 0;
	public static final int ARROW = 1;
	public static final int DOT = 2;

	private int type;
	private Font font;
	private JFrame editor = null;

	public DLabel(final Project project, final String text, final double x, final double y) {
		super(project, text, x, y);
		this.type = TEXT; // default
		this.width = 1;
		this.height = 1;
		this.font = new Font(TextRoi.getFont(), TextRoi.getStyle(), TextRoi.getSize());
		addToDatabase();
	}

	/** For reconstruction purposes. */
	public DLabel(final Project project, final long id, final String text, final float width, final float height, final int type, final String font_name, final int font_style, final int font_size, final boolean locked, final AffineTransform at) {
		super(project, id, text, locked, at, width, height);
		this.type = TEXT; // default
		this.font = new Font(font_name, font_style, font_size);
	}

	public int getType() {
		return type;
	}

	/** To reconstruct from an XML entry. */
	public DLabel(final Project project, final long id, final HashMap<String,String> ht, final HashMap<Displayable,String> ht_links) {
		super(project, id, ht, ht_links);
		// default:
		int font_size = 12;
		int font_style = Font.PLAIN;
		String font_family = "Courier";
		// parse data
		String data;
		if (null != (data = ht.get("style"))) {
			final String[] s1 = data.split(";");
			for (int i=0; i<s1.length; i++) {
				final String[] s2 = s1[i].split(":");
				if (s2[0].equals("font-size")) {
					font_size = Integer.parseInt(s2[1].trim());
				} else if (s2[0].equals("font-style")) {
					font_style = Integer.parseInt(s2[1].trim());
				} else if (s2[0].equals("font-family")) {
					font_family = s2[1].trim();
				}
			}
		}
		this.font = new Font(font_family, font_style, font_size);
	}

	public Font getFont() {
		if (null == font) reload();
		return font;
	}

	public void flush() {
		this.title = null;
		this.font = null;
	}

	@Override
    public void setTitle(final String title) {
		setText(title, true);
	}

	public void setText(final String title, final boolean update) {
		super.setTitle(title);
		if (null == title || 0 == title.length()) return;
		final String text = getShortTitle();
		// measure dimensions of the painted label
		final Dimension dim = Utils.getDimensions(text, font);
		this.width = dim.width;
		this.height = dim.height;
		Display.updateTransform(this); // need to update the Selection with the actual width and height!
		updateInDatabase("dimensions");
		updateBucket();
	}

	private void reload() {
		// reload
		final Object[] ob = project.getLoader().fetchLabel(this);
		if (null == ob) return;
		title = (String)ob[0];
		font = new Font((String)ob[1], ((Integer)ob[2]).intValue(), ((Integer)ob[3]).intValue());
	}

	@Override
    public String getShortTitle() {
		if (null == title) reload();
		if (null == title) return "";
		return title;
	}

	@Override
    public String toString() {
		if (null == this.title || 0 == this.title.length()) {
			return "<empty label> #" + id;
		}
		return getShortTitle() + " #" + id;
	}

	public void setType(final int type) {
		if (type < TEXT || type > DOT) return;
		this.type = type;
	}


	@Override
	public void paint(final Graphics2D g, final Rectangle srcRect, final double magnification, final boolean active, final int channels, final Layer active_layer, final List<Layer> layers) {
		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		final AffineTransform atg = g.getTransform();
		final AffineTransform atp = (AffineTransform)atg.clone();
		atp.concatenate(this.at);

		g.setTransform(atp);

		// paint a box of transparent color behind the text if active:
		if (active) {
			if (null == original_composite) original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.67f));
			g.setColor(new Color(255 - color.getRed(), 255 - color.getGreen(), 255 - color.getBlue()).brighter()); // the "opposite", but brighter, so it won't fail to generate contrast if the color is 127 in all channels
			g.fillRect(0, -(int)height, (int)width, (int)height);
			g.setComposite(original_composite);
		}

		g.setColor(color);
		switch (type) {
			case TEXT:
				g.setFont(font);
				g.drawString(getShortTitle(), 0, 0);
		}

		// restore
		g.setTransform(atg);
		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g.setComposite(original_composite);
		}
	}

	/** Saves one allocation, returns the same Rectangle, modified (or a new one if null).
	 * This method is overriden so that the x,y, which underlies the text, is translated upward by the height to generate a box that encloses the text and not just sits under it. */
	@Override
    public Rectangle getBoundingBox(Rectangle r) {
		if (null == r) r = new Rectangle();
		if (this.at.getType() == AffineTransform.TYPE_TRANSLATION) {
			r.x = (int)this.at.getTranslateX();
			r.y = (int)(this.at.getTranslateY() - this.height);
			r.width = (int)this.width;
			r.height = (int)this.height;
		} else {
			// transform points
			final double[] d1 = new double[]{0, 0, width, 0, width, -height, 0, -height};
			final double[] d2 = new double[8];
			this.at.transform(d1, 0, d2, 0, 4);
			// find min/max
			double min_x=Double.MAX_VALUE, min_y=Double.MAX_VALUE, max_x=-min_x, max_y=-min_y;
			for (int i=0; i<d2.length; i+=2) {
				if (d2[i] < min_x) min_x = d2[i];
				if (d2[i] > max_x) max_x = d2[i];
				if (d2[i+1] < min_y) min_y = d2[i+1];
				if (d2[i+1] > max_y) max_y = d2[i+1];
			}
			r.x = (int)min_x;
			r.y = (int)min_y;
			r.width = (int)(max_x - min_x);
			r.height = (int)(max_y - min_y);
		}
		return r;
	}
	@Override
    public Polygon getPerimeter() {
		if (this.at.isIdentity() || this.at.getType() == AffineTransform.TYPE_TRANSLATION) {
			// return the bounding box as a polygon:
			final Rectangle r = getBoundingBox();
			return new Polygon(new int[]{r.x, r.x+r.width, r.x+r.width, r.x},
					   new int[]{r.y, r.y, r.y+r.height, r.y+r.height},
					   4);
		}
		// else, the rotated/sheared/scaled and translated bounding box:
		final double[] po1 = new double[]{0,0,  width,0,  width,-height,  0,-height};
		final double[] po2 = new double[8];
		this.at.transform(po1, 0, po2, 0, 4);
		return new Polygon(new int[]{(int)po2[0], (int)po2[2], (int)po2[4], (int)po2[6]},
				   new int[]{(int)po2[1], (int)po2[3], (int)po2[5], (int)po2[7]},
				   4);
	}

	/** Returns the perimeter enlarged in all West, North, East and South directions, in pixels.*/
	@Override
    public Polygon getPerimeter(final int w, final int n, final int e, final int s) {
		if (this.at.isIdentity() || this.at.getType() == AffineTransform.TYPE_TRANSLATION) {
			// return the bounding box as a polygon:
			final Rectangle r = getBoundingBox();
			return new Polygon(new int[]{r.x -w, r.x+r.width +w+e, r.x+r.width +w+e, r.x -w},
					   new int[]{r.y -n, r.y -n, r.y+r.height +n+s, r.y+r.height +n+s},
					   4);
		}
		// else, the rotated/sheared/scaled and translated bounding box:
		final double[] po1 = new double[]{-w,-n,  width+w+e,-n,  width+w+e,-height+n+s,  -w,-height+n+s};
		final double[] po2 = new double[8];
		this.at.transform(po1, 0, po2, 0, 4);
		return new Polygon(new int[]{(int)po2[0], (int)po2[2], (int)po2[4], (int)po2[6]},
				   new int[]{(int)po2[1], (int)po2[3], (int)po2[5], (int)po2[7]},
				   4);
	}

	@Override
    public void mousePressed(final MouseEvent me, final Layer layer, final int x_p, final int y_p, final double mag) {}

	@Override
    public void mouseDragged(final MouseEvent me, final Layer layer, final int x_p, final int y_p, final int x_d, final int y_d, final int x_d_old, final int y_d_old) {}

	@Override
    public void mouseReleased(final MouseEvent me, final Layer layer, final int x_p, final int y_p, final int x_d, final int y_d, final int x_r, final int y_r) {
		Display.repaint(layer, this); // the DisplayablePanel
	}

	@Override
    public boolean isDeletable() {
		return null == title || "" == title;
	}

	@Override
    public void keyPressed(final KeyEvent ke) {
		super.keyPressed(ke);
		// TODO: screen edition
		/*
		if (null == screen_editor) screen_editor = new ScreenEditor(this);
		if (ke.isConsumed()) {

			return;
		}
		// add char at the end, or delete last if it's a 'delete'
		screen_editor.keyPressed(ke);
		*/
	}

	/* // TODO
	private class ScreenEditor extends TextField {



		ScreenEditor(DLabel label) {

		}

		public void paint(Graphics g) {

		}
	}
	*/

	public void edit() {
		if (null == editor) editor = new Editor(this);
		else editor.toFront();
	}

	/** When closed, the editor sets the text to the label. */
	private class Editor extends JFrame implements WindowListener {

		private static final long serialVersionUID = 1L;
		private final DLabel label;
		private final JTextArea jta;

		Editor(final DLabel l) {
			super(getShortTitle());
			label = l;
			jta = new JTextArea(label.title.equals("  ") ? "" : label.title, 5, 20); // the whole text is the 'title' in the Displayable class.
			jta.setLineWrap(true);
			jta.setWrapStyleWord(true);
			final JScrollPane jsp = new JScrollPane(jta);
			jta.setPreferredSize(new Dimension(200,200));
			getContentPane().add(jsp);
			pack();
			setVisible(true);
			final Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
			final Rectangle box = this.getBounds();
			setLocation((screen.width - box.width) / 2, (screen.height - box.height) / 2);
			setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			addWindowListener(this);
			new ToFront(this);
		}

		@Override
        public void windowClosing(final WindowEvent we) {
			final String text = jta.getText().trim();
			if (null != text && text.length() > 0) {
				label.setTitle(text);
			} else {
				//label.setTitle("  "); // double space
				// delete the empty label
				label.remove(false);
			}
			dispose();
			Display.repaint(layer, label, 1);
			editor = null;
		}
		@Override
        public void windowClosed(final WindowEvent we) {}
		@Override
        public void windowOpened(final WindowEvent we) {}
		@Override
        public void windowActivated(final WindowEvent we) {}
		@Override
        public void windowDeactivated(final WindowEvent we) {}
		@Override
        public void windowIconified(final WindowEvent we) {}
		@Override
        public void windowDeiconified(final WindowEvent we) {}
	}

	private class ToFront extends Thread {
		private final JFrame frame;
		ToFront(final JFrame frame) {
			this.frame = frame;
			start();
		}
		@Override
        public void run() {
			try { Thread.sleep(200); } catch (final Exception e) {}
			frame.toFront();
		}
	}

	/** */
	@Override
    public void exportXML(final StringBuilder sb_body, final String indent, final XMLOptions options) {
		sb_body.append(indent).append("<t2_label\n");
		final String in = indent + "\t";
		super.exportXML(sb_body, in, options);
		final String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"font-size:").append(font.getSize())
		       .append(";font-style:").append(font.getStyle())
		       .append(";font-family:").append(font.getFamily())
		       .append(";fill:#").append(RGB[0]).append(RGB[1]).append(RGB[2])
		       .append(";fill-opacity:").append(alpha).append(";\"\n")
		;
		sb_body.append(indent).append(">\n");
		super.restXML(sb_body, in, options);
		sb_body.append(indent).append("</t2_label>\n");
	}

	static public void exportDTD(final StringBuilder sb_header, final HashSet<String> hs, final String indent) {
		if (hs.contains("t2_label")) return;
		sb_header.append(indent).append("<!ELEMENT t2_label (").append(Displayable.commonDTDChildren()).append(")>\n");
		Displayable.exportDTD("t2_label", sb_header, hs, indent);
	}

	@Override
	public void adjustProperties() {
		final GenericDialog gd = makeAdjustPropertiesDialog();

		final GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
		final String[] fonts = ge.getAvailableFontFamilyNames();
		final String[] sizes = {"8","9","10","12","14","18","24","28","36","48","60","72"};
		final String[] styles = {"Plain", "Bold", "Italic", "Bold+Italic"};
		int i = 0;
		final String family = this.font.getFamily();
		for (i = fonts.length -1; i>-1; i--) {
			if (family.equals(fonts[i])) break;
		}
		if (-1 == i) i = 0;
		gd.addChoice("Font Family: ", fonts, fonts[i]);
		final int size = this.font.getSize();
		for (i = sizes.length -1; i>-1; i--) {
			if (Integer.parseInt(sizes[i]) == size) break;
		}
		if (-1 == i) i = 0;
		gd.addChoice("Font Size: ", sizes, sizes[i]);
		gd.addNumericField("or enter size: ", size, 0);
		i=0;
		switch (this.font.getStyle()) {
			case Font.PLAIN: i=0; break;
			case Font.BOLD: i=1; break;
			case Font.ITALIC: i=2; break;
			case Font.BOLD+Font.ITALIC: i=3; break;
		}
		gd.addChoice("Font style: ", styles, styles[i]);

		gd.showDialog();
		if (gd.wasCanceled()) return;
		// superclass processing
		processAdjustPropertiesDialog(gd);
		// local proccesing
		final String new_font = gd.getNextChoice();
		int new_size = Integer.parseInt(gd.getNextChoice());
		final int new_size_2 = (int)gd.getNextNumber();
		if (new_size_2 != size) {
			new_size = new_size_2;
		}
		int new_style = gd.getNextChoiceIndex();
		switch (new_style) {
			case 0: new_style = Font.PLAIN; break;
			case 1: new_style = Font.BOLD; break;
			case 2: new_style = Font.ITALIC; break;
			case 3: new_style = Font.BOLD+Font.ITALIC; break;
		}
		this.font = new Font(new_font, new_style, new_size);
		updateInDatabase("font");
		// update dimensions
		setText(this.title, true);
	}

	/** Performs a deep copy of this object, except for the Layer pointer. */
	@Override
	public DLabel clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		final DLabel copy = new DLabel(pr, nid, title, width, height, type, font.getName(), font.getStyle(), font.getSize(), this.locked, (AffineTransform)this.at.clone());
		copy.alpha = this.alpha;
		copy.color = new Color(color.getRed(), color.getGreen(), color.getBlue());
		copy.visible = this.visible;
		// add
		copy.addToDatabase();
		return copy;
	}

	@Override
	Class<?> getInternalDataPackageClass() {
		return DPDLabel.class;
	}

	@Override
	Object getDataPackage() {
		return new DPDLabel(this);
	}

	static private final class DPDLabel extends Displayable.DataPackage {
		final Font font;

		DPDLabel(final DLabel label) {
			super(label);
			// no clone method for font.
			this.font = new Font(label.font.getFamily(), label.font.getStyle(), label.font.getSize());
		}
		@Override
        final boolean to2(final Displayable d) {
			super.to1(d);
			((DLabel)d).font = new Font(font.getFamily(), font.getStyle(), font.getSize());
			return true;
		}
	}

	@Override
    synchronized public boolean apply(final Layer la, final Area roi, final mpicbg.models.CoordinateTransform ict) throws Exception {
		// Considers only the point where this floating text label is.
		final double[] fp = new double[2]; // point is 0,0
		this.at.transform(fp, 0, fp, 0, 1); // to world
		if (roi.contains(fp[0], fp[1])) {
			ict.applyInPlace(fp);
			this.at.createInverse().transform(fp, 0, fp, 0, 1); // back to local
			// as a result, there has been a translation:
			this.at.preConcatenate(new AffineTransform(1, 0, 0, 1, fp[0], fp[1]));
			return true;
		}
		return false;
	}
	@Override
    public boolean apply(final VectorDataTransform vdt) throws Exception {
		final double[] fp = new double[2]; // point is 0,0
		this.at.transform(fp, 0, fp, 0, 1); // to world
		for (final VectorDataTransform.ROITransform rt : vdt.transforms) {
			if (rt.roi.contains(fp[0], fp[1])) {
				rt.ct.applyInPlace(fp);
				this.at.createInverse().transform(fp, 0, fp, 0, 1); // back to local
				// as a result, there has been a translation
				this.at.preConcatenate(new AffineTransform(1, 0, 0, 1, fp[0], fp[1]));
				return true;
			}
		}
		return false;
	}
}
