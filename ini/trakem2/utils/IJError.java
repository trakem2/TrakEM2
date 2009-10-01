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

package ini.trakem2.utils;



import java.io.CharArrayWriter;
import java.io.PrintWriter;

/** A class to easily show the stack trace of an error in different operating systems.
 *
 */
public class IJError {

	static public final void print(final Throwable e) {
		print(e, false);
	}
	static public final void print(Throwable e, final boolean stdout) {
		StringBuilder sb = new StringBuilder("==================\nERROR:\n");
		while (null != e) {
			CharArrayWriter caw = new CharArrayWriter();
			PrintWriter pw = new PrintWriter(caw);
			e.printStackTrace(pw);
			String s = caw.toString();
			if (isMacintosh()) {
				if (s.indexOf("ThreadDeath")>0)
					;//return null;
				else s = fixNewLines(s);
			}
			sb.append(s);

			Throwable t = e.getCause();
			if (e == t || null == t) break;
			sb.append("==> Caused by:\n");
			e = t;
		}
		sb.append("==================\n");
		if (stdout) Utils.log2(sb.toString());
		else Utils.log(sb.toString());
	}

	/** Converts carriage returns to line feeds. Copied from ij.util.tools by Wayne Rasband*/

	static final String fixNewLines(final String s) {
		final char[] chars = s.toCharArray();
		for (int i=0; i<chars.length; i++) {
			if (chars[i]=='\r') chars[i] = '\n';
		}
		return new String(chars);
	}

	static String osname;
	static boolean isWin, isMac;

	static {
		osname = System.getProperty("os.name");
		isWin = osname.startsWith("Windows");
		isMac = !isWin && osname.startsWith("Mac");
	}

	static boolean isMacintosh() {
		return isMac; 
	}
}
