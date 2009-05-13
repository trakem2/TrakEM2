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

/** Supervise all threads, and report uncaught exceptions. */ // NOT IN USE AT THE MOMENT
public class BigBrother extends ThreadGroup {

	public BigBrother(String name) {
		super(name);
	}

	public void uncaughtException(Thread thread, Throwable t) {
		StackTraceElement[] elems = t.getStackTrace();
		int end = 2;
		if (elems.length < 2) end = elems.length;
		Utils.log2("ERROR: " + t);
		for (int i=0; i<end; i++) {
			Utils.log2("\tat " + elems[i].getClassName() + "." + elems[i].getMethodName() + " - " + elems[i].getFileName() + ":" + elems[i].getLineNumber());
		}
	}
}
