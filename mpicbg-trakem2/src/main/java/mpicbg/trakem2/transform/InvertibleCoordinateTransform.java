/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de> and Albert Cardona <acardona@ini.phys.ethz.ch>
 *
 */
package mpicbg.trakem2.transform;

/**
 * {@link mpicbg.models.InvertibleCoordinateTransform} with {@link String}
 * import and export as used in
 * <a href="http://www.ini.uzh.ch/~acardona/trakem2.html">TrakEM2</a>.
 * 
 */
public interface InvertibleCoordinateTransform extends mpicbg.models.InvertibleCoordinateTransform, CoordinateTransform
{
	public InvertibleCoordinateTransform copy();
}
