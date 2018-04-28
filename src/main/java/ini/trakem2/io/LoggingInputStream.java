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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;


/** A class to monitor an input stream for speed and total byte download. */
public class LoggingInputStream extends BufferedInputStream {

	private long last;
	private long n = 0;
	private long accum_time = 0;
	private long accum_bytes = 0;

	public LoggingInputStream(InputStream in) {
		super(in);
		last = System.currentTimeMillis();
	}

	public int read() throws IOException {
		int m = super.read();
		n += m;
		return m;
	}

	public int read(byte[] b) throws IOException {
		int m = super.read(b);
		n += m;
		return m;
	}

	public int read(byte[] b, int off, int len) throws IOException {
		int m = super.read(b, off, len);
		n += m;
		return m;
	}

	/** Put the counter to zero. */
	public void resetInfo() { // to work perfect, this would need a synchronized clause, but no such perfection is needed, and there are perfomance issues.
		accum_bytes = n = 0;
		last = System.currentTimeMillis();
		accum_time = 0;
	}

	/** Returns info as
	* [0] = current time in ms
	* [1] = elapsed time in ms since last call to getInfo(long[])
	* [2] = n_bytes_read since last call to getInfo(long[])
	* [3] = accumulated time in ms since last call to resetInfo()
	* [4] = accumulated bytes since last call to resetInfo()
	* 
	* So current speed = info[2]/info[1] Kb/s
	*/
	public void getInfo(long[] info) {
		long now = System.currentTimeMillis();
		accum_time += now - last;
		accum_bytes += n;
		info[0] = now;
		info[1] = now - last; // elapsed time
		info[2] = n;
		info[3] = accum_time; // total time since last call to resetInfo()
		info[4] = accum_bytes; // total bytes since last call to resetInfo()
		// reset cycle vars:
		n = 0;
		last = now;
	}
}
