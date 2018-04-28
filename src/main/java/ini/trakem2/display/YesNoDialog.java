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
import ij.IJ;
import ij.gui.GUI;
import ij.gui.MultiLineLabel;
import ini.trakem2.utils.IJError;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Panel;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

/** A modal dialog box with a one line message and
	"Yes" and "No" buttons.
	Almost literally copied from ij.gui.YesNoCancelDialog class
*/
public class YesNoDialog extends Dialog implements ActionListener, KeyListener {
	private static final long serialVersionUID = 1L;
	private Button yesB, noB;
    private boolean yesPressed;
	private boolean firstPaint = true;
	private Runnable closing_task = null;

	public YesNoDialog(String title, String msg) {
		this(IJ.getInstance(), title, msg);
	}
	
	public YesNoDialog(Frame parent, String title, String msg) {
		this(parent, title, msg, true);
	}

	public YesNoDialog(Frame parent, String title, String msg, boolean show) {
		super(parent, title, true);
		setLayout(new BorderLayout());
		Panel panel = new Panel();
		panel.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 10));
		MultiLineLabel message = new MultiLineLabel(msg);
		message.setFont(new Font("Dialog", Font.PLAIN, 12));
		panel.add(message);
		add("North", panel);

		panel = new Panel();
		panel.setLayout(new FlowLayout(FlowLayout.RIGHT, 15, 8));
		if (IJ.isMacintosh() && msg.startsWith("Save")) {
			yesB = new Button("  Save  ");
			noB = new Button("Don't Save");
		} else {
			yesB = new Button("  Yes  ");
			noB = new Button("  No  ");
		}
		yesB.addActionListener(this);
		noB.addActionListener(this);
		yesB.addKeyListener(this);
		noB.addKeyListener(this);
		if (IJ.isMacintosh()) {
			panel.add(noB);
			panel.add(yesB);
			setResizable(false);
		} else {
			panel.add(yesB);
			panel.add(noB);
		}
		add("South", panel);
		pack();
		GUI.center(this);
		if (show) setVisible(true);
	}
	
	public void setClosingTask(final Runnable r) {
		this.closing_task = r;
	}
  
	public void actionPerformed(ActionEvent e) {
		if (e.getSource()==yesB)
			yesPressed = true;
		closeDialog();
	}
	
	/** Returns true if the user dismissed dialog by pressing "Yes". */
	public boolean yesPressed() {
		return yesPressed;
	}
	
	void closeDialog() {
		if (null != closing_task) try { closing_task.run(); } catch (Throwable t) { IJError.print(t); }
		setVisible(false);
		dispose();
	}

	public void keyPressed(KeyEvent e) { 
		int keyCode = e.getKeyCode(); 
		IJ.setKeyDown(keyCode); 
		if (keyCode==KeyEvent.VK_ENTER||keyCode==KeyEvent.VK_Y||keyCode==KeyEvent.VK_S) {
			/*
			yesPressed = true;
			closeDialog(); 
			*/
			// PREVENTING unintentional saving of projects. TODO setup an auto save just like Blender, a .quit file with the contents of the last XML, without exporting images (but with their correct paths) (maybe as an .xml1, similar to the .blend1)
		} else if (keyCode==KeyEvent.VK_N || keyCode==KeyEvent.VK_D) {
			closeDialog(); 
		} 
	} 

	public void keyReleased(KeyEvent e) {
		int keyCode = e.getKeyCode(); 
		IJ.setKeyUp(keyCode); 
	}

	public void keyTyped(KeyEvent e) {}

	public void paint(Graphics g) {
		super.paint(g);
		if (firstPaint) {
			yesB.requestFocus();
			firstPaint = false;
		}
	}
}
