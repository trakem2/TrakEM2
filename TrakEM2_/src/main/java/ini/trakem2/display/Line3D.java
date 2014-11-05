/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2008-2009 Albert Cardona and Rodney Douglas.

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

import ini.trakem2.Project;
import ini.trakem2.vector.VectorString3D;

import java.awt.Color;
import java.util.List;

import javax.vecmath.Point3f;

/** By definition, only ZDisplayable objects may implement Line3D. */
public interface Line3D {

	public VectorString3D asVectorString3D();
	public String toString();
	public Project getProject();
	public LayerSet getLayerSet();
	public long getId();
	public int length();
	public Color getColor();
	public List<Point3f> generateTriangles(double scale, int parallels, int resample);
}
