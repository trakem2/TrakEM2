/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2005, 2006, 2007 Albert Cardona and Rodney Douglas.

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

package ini.trakem2.render3d;

import ini.trakem2.display.Profile;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.utils.Utils;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.HashSet;
import java.util.Iterator;

/** Extract meshes from the given ProjectThing objects, by scanning for profile_list objects and figuring out their linked structure.
 *
 */
public class MeshMaker {

	private MeshMaker() {}

	/** Returns always an ArrayList, even if empty. This function recurses over the ProjectThing children. */
	static public ArrayList makeMeshes(final ProjectThing thing, final double delta) {
		final ArrayList al_meshes = new ArrayList();
		if (thing.getType().equals("profile_list")) {
			final Mesh mesh = makeMesh(thing, delta);
			if (null != mesh) al_meshes.add(mesh);
			return al_meshes;
		}
		final ArrayList al_children = thing.getChildren();
		if (null == al_children) return al_meshes;
		for (Iterator it = al_children.iterator(); it.hasNext(); ) {
			al_meshes.addAll(makeMeshes((ProjectThing)it.next(), delta));
		}
		return al_meshes;
	}

	static private Mesh makeMesh(final ProjectThing profile_list, double delta) {
		// Get all profiles as perimeters, and compute delta
		final ArrayList al_children = profile_list.getChildren();
		if (null == al_children) return null;
		else if (1 == al_children.size()) {
			Utils.log("MeshMaker: can't make a mesh with a single profile for profile_list " + profile_list);
			return null;
		}
		final Hashtable ht = new Hashtable();
		final boolean compute_delta = (-1 == delta);
		if (compute_delta) delta = 0;
		int n_points = 0;
		Profile first = null; // the one with the lowest Z TODO this should be, one whose linked profiles don't link others that are in the same Z or past it (i.e. one thatwon't ever be involved as target to merge in branched meshing). The logic should identify first all branching points and do those first, then fill in the others! Otherwise errors will occur in the merging
		for (Iterator it = al_children.iterator(); it.hasNext(); ) {
			Profile profile = (Profile)it.next();
			if (null == first) first = profile;
			else {
				if (profile.getLayer().getZ() < first.getLayer().getZ()) {
					first = profile;
				}
			}
			Perimeter2D pe = profile.getPerimeter2D();
			if (0 == pe.length()) continue;
			ht.put(profile, pe);
			n_points += pe.length();
			if (compute_delta) delta += pe.computeAveragePointInterdistance();
		}
		if (compute_delta) delta /= ht.size();
		// create new mesh
		final Mesh mesh = new Mesh(n_points, n_points);
		// delta is now known. Subsample all perimeters so that their average point interdistance is delta
		for (Iterator it = ht.values().iterator(); it.hasNext(); ) {
			Perimeter2D pe = (Perimeter2D)it.next();
			pe.resample(delta);
		}
		// start madness:
		madMeshing(first, mesh, ht);

		return mesh;
	}

	static private class Pair {
		private final Object ob1;
		private final Object ob2;
		Pair(Object ob1_, Object ob2_) {
			ob1 = ob1_;
			ob2 = ob2_;
		}
		public boolean equals(Object ob) {
			if (ob instanceof Pair) {
				Pair p = (Pair)ob;
				if (null == p.ob1 || null == p.ob2) return false;
				if ( (p.ob1.equals(ob1) && p.ob2.equals(ob2))
				  || (p.ob1.equals(ob2) && p.ob2.equals(ob1))) return true;
			}
			return false;
		}
	}

	static private void madMeshing(final Profile first_profile, final Mesh mesh, final Hashtable ht) {
		// need a way to identify all groups of profiles that have to be grown together
		// What defines a group-to-be-grown-together:
		//  - at least two profiles, directly linked
		//  - any linked profiles of a member of the pair who lay at Z positions in the direction of its pair partner.
		// Within this group, there are merging subgroups, which have to be defined for each profile:
		//  - for any given profile, its merging group consists of all its direct linked profiles within the group, and the targets of those.

		/*
		// mark this one as done
		hs_done.add(profile);
		// get the directly linked of class Profile
		final HashSet hs = profile.getLinked(Profile.class);
		if (null == hs) {
			Utils.log("MeshMaker ERROR: at least one profile [" + profile + "] is unlinked!");
			return;
		}
		// classify them by whether they are above or below the profile in Z
		final ArrayList al_below = new ArrayList();
		final ArrayList al_above = new ArrayList();
		final double z = profile.getLayer().getZ();
		for (Iterator ite = hs.iterator(); ite.hasNext(); ) {
			Profile pro = (Profile)ite.next();
			// ignore processed profiles
			if (hs_done.contains(pro)) continue;
			final double zi = pro.getLayer().getZ();
			if (zi < z) al_below.add(ht.get(pro));
			else if (zi > z) al_above.add(ht.get(pro));
			else {
				Utils.log2("Ignoring profile directly linked and within the same layer!\n\t" + profile + "  vs. " + pro);
			}
		}
		// make grouped meshing and recurse into unprocessed profiles
		mesh.add((Perimeter2D)ht.get(profile), al_below);
		mesh.add((Perimeter2D)ht.get(profile), al_above);
		for (Iterator it = al_below.iterator(); it.hasNext(); ) {
			recursiveMeshing((Profile)it.next(), mesh, ht, hs_done);
		}
		for (Iterator it = al_above.iterator(); it.hasNext(); ) {
			recursiveMeshing((Profile)it.next(), mesh, ht, hs_done);
		}
		*/
	}
}
