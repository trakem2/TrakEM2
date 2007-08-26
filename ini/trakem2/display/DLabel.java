/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005,2006 Albert Cardona and Rodney Douglas.

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

import java.awt.*;
import java.awt.geom.*;
import java.awt.event.*;
import javax.swing.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Vector;

import ij.gui.TextRoi;
import ij.gui.GenericDialog;
import ini.trakem2.ControlWindow;
import ini.trakem2.Project;
import ini.trakem2.utils.Utils;
import ini.trakem2.persistence.DBObject;

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
public class DLabel extends Displayable {

	public static final int TEXT = 0;
	public static final int ARROW = 1;
	public static final int DOT = 2;

	private int type;
	private Font font;
	static private Frame frame = null;
	private JFrame editor = null;

	public DLabel(Project project, String text, double x, double y) {
		super(project, text, x, y);
		this.type = TEXT; // default
		this.width = 1;
		this.height = 1;
		this.font = new Font(TextRoi.getFont(), TextRoi.getStyle(), TextRoi.getSize());
		addToDatabase();
	}

	/** For reconstruction purposes. */
	public DLabel(Project project, long id, String text, double width, double height, int type, String font_name, int font_style, int font_size, boolean locked, AffineTransform at) {
		super(project, id, text, locked, at, width, height);
		this.type = TEXT; // default
		this.font = new Font(font_name, font_style, font_size);
	}

	public int getType() {
		return type;
	}

	/** To reconstruct from an XML entry. */
	public DLabel(Project project, long id, Hashtable ht, Hashtable ht_links) {
		super(project, id, ht, ht_links);
		// default:
		int font_size = 12;
		int font_style = Font.PLAIN;
		String font_family = "Courier";
		// parse data
		for (Enumeration e = ht.keys(); e.hasMoreElements(); ) {
			String key = (String)e.nextElement();
			String data = (String)ht.get(key);
			if (key.equals("style")) {
				String[] s1 = data.split(";");
				for (int i=0; i<s1.length; i++) {
					String[] s2 = s1[i].split(":");
					if (s2[0].equals("font-size")) {
						font_size = Integer.parseInt(s2[1].trim());
					} else if (s2[0].equals("font-style")) {
						font_style = Integer.parseInt(s2[1].trim());
					} else if (s2[0].equals("font-family")) {
						font_family = s2[1].trim();
					}
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

	public void setTitle(String title) {
		setText(title, true);
	}

	public void setText(String title, boolean update) {
		super.setTitle(title);
		if (null == title || 0 == title.length()) return;
		String text = getShortTitle();
		// measure dimensions of the painted label
		if (null == frame) { frame = new Frame(); frame.pack(); frame.setBackground(Color.white); } // emulating the ImageProcessor class
		FontMetrics fm = frame.getFontMetrics(font);
		this.height = fm.getHeight();
		int[] w = fm.getWidths(); // the advance widths of the first 256 chars
		this.width = 0;
		for (int i = text.length() -1; i>-1; i--) {
			int c = (int)text.charAt(i);
			if (c < 256) this.width += w[c];
		}
		frame = null;
		Display.updateTransform(this); // need to update the Selection with the actual width and height!
		updateInDatabase("dimensions");
	}

	private void reload() {
		// reload
		Object[] ob = project.getLoader().fetchLabel(this);
		title = (String)ob[0];
		font = new Font((String)ob[1], ((Integer)ob[2]).intValue(), ((Integer)ob[3]).intValue());
	}

	public String getShortTitle() {
		if (null == title) reload();
		int[] ib = new int[] {
			title.indexOf('\n'),
			title.indexOf(','),
			title.indexOf(' '),
			title.indexOf('\t')
		};
		int min = 10;
		for (int i=0; i<ib.length; i++)
			if (-1 != ib[i] && ib[i] < min)
				min = ib[i];
		if (min > title.length()) return title;
		return title.substring(0, min);
	}

	public String toString() {
		if (null == this.title || 0 == this.title.length()) {
			return "<empty label> #" + id;
		}
		return getShortTitle() + " #" + id;
	}

	public void setType(int type) {
		if (type < TEXT || type > DOT) return;
		this.type = type;
	}


	public void paint(Graphics2D g, double magnification, boolean active, int channels, Layer active_layer) {
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
	public Rectangle getBoundingBox(Rectangle r) {
		if (null == r) r = new Rectangle();
		if (this.at.isIdentity()) {
			r.x = 0;
			r.y = 0;
			r.width = (int)this.width;
			r.height = -(int)this.height;
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

	public void mousePressed(MouseEvent me, int x_p, int y_p, Rectangle srcRect, double mag) {
	}

	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old, Rectangle srcRect, double mag) {
	}

	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r, Rectangle srcRect, double mag) {
		//repaint snapshot
		Display.repaint(layer, this); // the DisplayablePanel
	}

	public boolean isDeletable() {
		return null == title || "" == title;
	}

	public void keyPressed(KeyEvent ke) {
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
		if (null == frame) { frame = new Frame(); frame.pack(); frame.setBackground(Color.white); } // emulating the ImageProcessor class
		if (null == editor) editor = new Editor(this);
		else editor.toFront();
	}

	/** When closed, the editor sets the text to the label. */
	private class Editor extends JFrame implements WindowListener {

		private DLabel label;
		private JTextArea jta;

		Editor(DLabel l) {
			super(getShortTitle());
			label = l;
			jta = new JTextArea(label.title.equals("  ") ? "" : label.title, 5, 20); // the whole text is the 'title' in the Displayable class.
			jta.setLineWrap(true);
			jta.setWrapStyleWord(true);
			JScrollPane jsp = new JScrollPane(jta);
			jta.setPreferredSize(new Dimension(200,200));
			getContentPane().add(jsp);
			pack();
			setVisible(true);
			Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
			Rectangle box = this.getBounds();
			setLocation((screen.width - box.width) / 2, (screen.height - box.height) / 2);
			setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			addWindowListener(this);
			new ToFront(this);
		}

		public void windowClosing(WindowEvent we) {
			String text = jta.getText().trim();
			if (null != text && text.length() > 0) {
				label.setTitle(text);
			} else {
				//label.setTitle("  "); // double space
				// delete the empty label
				label.remove(true);
			}
			dispose();
			Display.repaint(layer, label, 1);
			editor = null;
			frame = null;
		}
		public void windowClosed(WindowEvent we) {}
		public void windowOpened(WindowEvent we) {}
		public void windowActivated(WindowEvent we) {}
		public void windowDeactivated(WindowEvent we) {}
		public void windowIconified(WindowEvent we) {}
		public void windowDeiconified(WindowEvent we) {}
	}

	private class ToFront extends Thread {
		JFrame frame;
		ToFront(JFrame frame) {
			this.frame = frame;
			start();
		}
		public void run() {
			try { Thread.sleep(200); } catch (Exception e) {}
			frame.toFront();
		}
	}

	public void handleDoubleClick() {
		edit();
	}

	/** */
	public void exportXML(StringBuffer sb_body, String indent, Object any) {
		sb_body.append(indent).append("<t2_label\n");
		String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"font-size:").append(font.getSize())
		       .append(";font-style:").append(font.getStyle())
		       .append(";font-family:").append(font.getFamily())
		       .append(";fill:#").append(RGB[0]).append(RGB[1]).append(RGB[2])
		       .append(";fill-opacity:").append(alpha).append(";\"\n")
		;
		sb_body.append(indent).append("/>\n");
	}

	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		if (hs.contains("t2_label")) return;
		sb_header.append(indent).append("<!ELEMENT t2_label EMPTY>\n");
		Displayable.exportDTD("t2_label", sb_header, hs, indent);
	}

	public void adjustProperties() {
		final GenericDialog gd = makeAdjustPropertiesDialog();

		final GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
		final String[] fonts = ge.getAvailableFontFamilyNames();
		final String[] sizes = {"8","9","10","12","14","18","24","28","36","48","60","72"};
		final String[] styles = {"Plain", "Bold", "Italic", "Bold+Italic"};
		int i = 0;
		String family = this.font.getFamily();
		for (i = fonts.length -1; i>-1; i--) {
			if (family.equals(fonts[i])) break;
		}
		if (-1 == i) i = 0;
		gd.addChoice("Font Family: ", fonts, fonts[i]);
		int size = this.font.getSize();
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
		String new_font = gd.getNextChoice();
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

	/** Performs a deep copy of this object, without the links, unlocked and visible. */
	public Object clone() {
		final DLabel copy = new DLabel(project, project.getLoader().getNextId(), title, width, height, type, font.getName(), font.getStyle(), font.getSize(), false, (AffineTransform)this.at.clone());
		snapshot.remake();
		copy.alpha = this.alpha;
		copy.color = new Color(color.getRed(), color.getGreen(), color.getBlue());
		copy.visible = true;
		// add
		copy.layer = this.layer;
		copy.layer.add(copy); // TODO: added to the Layer !?!?
		copy.addToDatabase();
		Display.repaint(layer, this, 5);
		return copy;
	}
}
