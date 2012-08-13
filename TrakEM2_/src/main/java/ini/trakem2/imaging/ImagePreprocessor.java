/**
TrakEM2 plugin for ImageJ(C).
Copyright (C) 2007-2009 Albert Cardona and Rodney Douglas.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
/s published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

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

package ini.trakem2.imaging;
import ij.ImagePlus;

/** Any class implementing this interface is suitable as an image preprocessor for a TrakEM2 project.<br />
 *  The role of the preprocessor is to do whatever is necessary to the given ImagePlus object before TrakEM2 ever sees the pixels of its ImageProcessor. */
public interface ImagePreprocessor {
	public void run(ImagePlus imp, String arg);
}
