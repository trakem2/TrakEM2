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

package ini.trakem2.io;

import ini.trakem2.utils.IJError;

import java.io.File;
import java.io.FilenameFilter;
import java.util.regex.Pattern;

/** Filters out non-accepted image formats.
 *
 */
public class ImageFileFilter implements FilenameFilter {

	/* // not checking for extension anymore, so the opener can open strange file formats through HandleExtraFileTypes plugin
	final String JPG = ".jpg";
	final String TIF = ".tif";
	final String PNG = ".png";
	final String GIF = ".gif";
	final String JPEG = ".jpeg";
	final String TIFF = ".tiff";
	final String TIFZIP = ".tif.zip";
	*/
	//static final String EXTENSIONS = ".dat.tif.tiff.jpg.jpeg.png.gif.tif.zip";
	private String code = null;
	private Pattern pattern = null;

	public ImageFileFilter() {}

	public ImageFileFilter(String regex, String code) {
		this.code = code;
		if (null == regex || 0 == regex.length()) return;
		// create pattern
		final StringBuilder sb = new StringBuilder(); // I hate java, all these loops just to avoid parsing the backslashes
		if (!regex.startsWith("^")) sb.append("^.*");
		for (int i=0; i<regex.length(); i++) {
			sb.append(regex.charAt(i));
		}
		if (!regex.endsWith("$")) sb.append(".*$");
		this.pattern = Pattern.compile(sb.toString()); // case-sensitive
	}

	public boolean accept(File dir, String name) {
		boolean b = accept0(dir, name);
		if (b) System.out.println("accepting " + name);
		return b;
	}

	private boolean accept0(File dir, String name) {
		// skip directories
		try {
			final File file = new File(dir.getCanonicalPath().replace('\\', '/') + "/" + name);
			if (file.isDirectory() || file.isHidden() || name.equals(".") || name.equals("..")) {
				return false;
			}
		} catch (Exception e) {
			IJError.print(e);
		}

		if (null != code) {
			if (startsWithCode(name)) return true;
		} else if (null == pattern || pattern.matcher(name).matches()) return true;

		return false;
	}

	//static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
	//static final String NUMBERS = "0123456789";

	boolean startsWithCode(String name) {
		// code is of type 'ccxdd' (for example). 'x' is any, 'c' char, 'd' digit
		for (int i=0; i<code.length(); i++) {
			char c = name.charAt(i);
			switch (code.charAt(i)) {
				case 'c':
					//if (-1 == LETTERS.indexOf(c)) return false;
					if (!Character.isLetter(c)) return false;
					break;
				case 'd':
					//if (-1 == NUMBERS.indexOf(c)) return false;
					if (!Character.isDigit(c)) return false;
					break;
				case 'x': // any character
					continue;
					//if (-1 == LETTERS.indexOf(c)) return false;
				//	break;
			}
		}
		return true;
	}
}
