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

import javax.swing.JLabel;

/** A class to add a MouseListener to a JLabel
 * and enable the display of only part of the text when too large
 *
 */
public final class DisplayableTitleLabel extends JLabel {

	private String short_text;

	public DisplayableTitleLabel(final String text) {
		makeShortText(text);
		super.setText(short_text);
	}

	private final void makeShortText(final String text) {
		if (text.length() > 23) {
			short_text = text.substring(0, 7) + "..." + text.substring(text.length()-13);
			super.setToolTipText(text);
		} else {
			short_text = text;
			super.setToolTipText(null);
		}
	}

	public void setText(final String text) {
		if (null != text && text.length() > 0) {
			makeShortText(text);
			super.setText(short_text);
		}
	}
}
