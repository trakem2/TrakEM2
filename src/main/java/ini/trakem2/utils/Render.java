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


import ij.IJ;
import ij.gui.YesNoCancelDialog;
import ij.io.OpenDialog;
import ij.io.SaveDialog;
import ini.trakem2.display.Ball;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Pipe;
import ini.trakem2.display.Profile;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.tree.Thing;

import java.awt.Color;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

/** For now, generates a .shapes file that I can load into Blender with the CurveMorphing CPython module by means of the load_shapes_v1.4.3.py script. 
 *
 * This class is VERY temporary and a quick hack.
 *
 * */
public class Render {

	/** All the objects found, an object for each 'profile_list'; in here the Displayable objects of type PROFILE are stored, each one packed in a Displayable[]. */
	private HashMap<String,Ob> ht_objects = new HashMap<String,Ob>();
	private ArrayList<Pipe> al_pipes = new ArrayList<Pipe>();
	private ArrayList<Ball> al_balls = new ArrayList<Ball>();

	public Render(Thing thing) {
		render(thing);
	}

	/** Recursive search for profile_list and pipe objects */
	public void render(Thing thing) {
		// the thing itself
		if (thing.getType().equals("profile_list")) {
			renderObject(thing);
		} else if (thing.getType().equals("pipe")) {
			al_pipes.add((Pipe)thing.getObject());
		} else if (thing.getType().equals("ball")) {
			al_balls.add((Ball)thing.getObject());
		}
		// the children
		if (null == thing.getChildren()) return;
		for (final Thing child : thing.getChildren()) {
			if (child.getType().equals("profile_list")) {
				renderObject(child);
			} else if (child.getType().equals("pipe")) {
				al_pipes.add((Pipe)child.getObject());
			} else if (child.getType().equals("ball")) {
				al_balls.add((Ball)child.getObject());
			} else {
				render(child);
			}
		}
	}

	/** Represents an object in the 3D space, composed by a list of 2D curves that define its skin. */
	private class Ob {
		String name;
		Profile[] profiles;
		Ob(String name, Profile[] profiles) {
			this.name = name;
			this.profiles = profiles;
		}
	}

	/** Accepts a 'profile_list' Thing and composes a new Ob */
	private void renderObject(Thing profile_list) {
		// check preconditions
		if (!profile_list.getType().equals("profile_list")) return;
		// do not accept an empty profile_list Thing
		final ArrayList<? extends Thing> al = profile_list.getChildren();
		if (null == al || al.size() < 2) return;

		// new style: follows profiles links and generates several obs, one per branch, ensuring that there is oly one profile per layer in the generated Ob for the .shapes file.
		// 1 - gather all profiles
		final HashSet<Profile> hs = new HashSet<Profile>();
		for (final Thing child : al) {
			Object ob = child.getObject();
			if (ob instanceof Profile) {
				hs.add((Profile)ob);
			} else {
				Utils.log2("Render: skipping non Profile class child");
			}
		}
		String name = profile_list.getParent().getTitle();
		final ArrayList<String> al_used_names = new ArrayList<String>();
		// make unique object name, since it'll be the group
		String name2 = name;
		int k = 1;
		while (ht_objects.containsKey(name2)) {
			name2 = name + "-" + k;
			k++;
		}
		name = name2;
		al_used_names.add(name);
		// 2 - start at the last found profile with the lowest Z, and recurse until done
		//Utils.log2("Calling renderSubObjects with " + hs.size() + " profiles");
		renderSubObjects(hs, al_used_names);


		/* //old style, assumes a single profile per section
		Profile[] profiles = new Profile[al.size()];
		Iterator it = al.iterator();
		int i = 0;
		while (it.hasNext()) {
			Thing child = (Thing)it.next();
			Displayable displ = (Displayable)child.getObject();
			profiles[i] = (Profile)displ; //this cast is safe (as long as I'm the only programmer and I remember that Thing objects added to a 'profile_list' Thing are of class Profile only)
			i++;
		}
		// make unique object name, since it'll be the group
		String name = profile_list.getParent().getTitle();
		String name2 = name;
		int k = 1;
		while (ht_objects.containsKey(name2)) {
			name2 = name + "_" + k;
			k++;
		}
		name = name2;
		// store
		ht_objects.put(name, new Ob(name, profiles));
		*/
	}

	/** Recursive; returns the last added profile. */
	private Profile accumulate(final HashSet<Profile> hs_done, final ArrayList<Profile> al, final Profile step, int z_trend) {
		final HashSet<Displayable> hs_linked = step.getLinked(Profile.class);
		if (al.size() > 1 && hs_linked.size() > 2) {
			// base found
			return step;
		}
		double step_z = step.getLayer().getZ();
		Profile next_step = null;
		boolean started = false;
		for (final Displayable ob : hs_linked) {
			// loop only one cycle, to move only in one direction
			if (al.contains(ob) || started || hs_done.contains(ob)) continue;
			started = true;
			next_step = (Profile)ob;
			double next_z = next_step.getLayer().getZ();
			if (0 == z_trend) {
				// define trend
				if (next_z > step_z) {
					z_trend = 1;
				} else {
					z_trend = -1;
				}
				// add!
				al.add(next_step);
			} else {
				// if the z trend is broken, finish
				if ( (next_z > step_z &&  1 == z_trend)
				  || (next_z < step_z && -1 == z_trend) ) {
					// z trend continues
					al.add(next_step);
				} else {
					// z trend broken
					next_step = null;
				}
			}
		}
		Profile last = step;
		//Utils.log2("next_step is " + next_step);
		if (null != next_step) {
			hs_done.add(next_step);
			last = accumulate(hs_done, al, next_step, z_trend);
		}
		//Utils.log2("returning last " + last);
		return last;
	}

	/** Render an object from the given profile, following the chain of links, until reaching a profile that is linked to more than two profiles. */
	private void renderSubObjects(final HashSet<Profile> hs_all, final ArrayList<String> al_used_names) {
		int size = hs_all.size();
		Profile[] p = new Profile[size];
		hs_all.toArray(p);
		// collect starts and ends
		HashSet<Profile> hs_bases = new HashSet<Profile>();
		HashSet<Profile> hs_done = new HashSet<Profile>();
		do {
			Profile base = null;
			// choose among existing bases
			if (hs_bases.size() > 0) {
				base = (Profile)hs_bases.iterator().next();
			} else {
				// find a new base, simply by taking the lowest Z or remaining profiles
				double min_z = Double.MAX_VALUE;
				for (int i=0; i<p.length; i++) {
					if (hs_done.contains(p[i])) continue;
					double z = p[i].getLayer().getZ();
					if (z < min_z) {
						min_z = z;
						base = p[i];
					}
				}
				// add base
				if (null != base) hs_bases.add(base);
			}
			if (null == base) {
				Utils.log2("No more bases.");
				return;
			}
			// crawl list to get a sequence of profiles in increasing or decreasing Z order, but not mixed z trends
			ArrayList<Profile> al_profiles = new ArrayList<Profile>();
			//Utils.log2("Calling accumulate for base " + base);
			al_profiles.add(base);
			Profile last = accumulate(hs_done, al_profiles, base, 0);
			// if the trend was not empty, add it
			if (last != base) {
				//Utils.log2("creating Ob with " + al_profiles.size());
				// count as done
				hs_done.addAll(al_profiles);
				// add new possible base (which may have only 2 links if it was from a broken Z trend)
				hs_bases.add(last);
				// create 3D object from base to base
				Profile[] profiles = new Profile[al_profiles.size()];
				al_profiles.toArray(profiles);
				String name = createName(al_used_names);
				String name2 = name;
				int k = 1;
				while (ht_objects.containsKey(name2)) {
					name2 = name + "_" + k;
				}
				name = name2;
				al_used_names.add(name);
				ht_objects.put(name, new Ob(name, profiles));
				counter++;
				Utils.log("count: " + counter + " vs " + ht_objects.size());
				//Utils.log2("Storing ob with name=[" + name + "] and n=" + profiles.length);
			} else {
				// remove base
				//Utils.log2("Removing base " + base);
				hs_bases.remove(base);
			}
		} while (0 != hs_bases.size());
	}

	private int counter = 0;

	private String createName(final ArrayList<String> al_used_names) {
		String[] s = new String[al_used_names.size()];
		al_used_names.toArray(s);
		if (1 == s.length) return s[0];
		Arrays.sort(s);
		String last = s[s.length -1];
		int i_us = last.lastIndexOf('_');
		if (-1 != i_us) {
			for (int i=i_us +1; i<last.length(); i++) {
				if (!Character.isDigit(last.charAt(i))) return last + "_1";
			}
			return last.substring(0,  i_us +1) + (Integer.parseInt(last.substring(i_us +1)) + 1);
		} else {
			return last + "_1";
		}
	}

	/** Popup a dialog to save a .shapes file with all the curves of the objects inside. The z_scale corrects for sample squashign under the coverslip.*/
	public void save(double z_scale) {
		/* I would have never though that the crappy .shapes file would make it so far.
		 */
		if (ht_objects.isEmpty() && al_pipes.isEmpty() && al_balls.isEmpty()) {
			Utils.log("No objects to save.");
			return;
		}

		Ob[] obs = new Ob[ht_objects.size()];
		ht_objects.values().toArray(obs);
		Pipe[] pipes = new Pipe[al_pipes.size()];
		al_pipes.toArray(pipes);
		Ball[] balls = new Ball[al_balls.size()];
		al_balls.toArray(balls);

		// generare header: the Groups= and Colors= and Supergroups= and Supercolors=
		StringBuffer data = new StringBuffer("Groups=");
		String l = "\n";
		for (int i=0; i<obs.length; i++) {
			data.append(obs[i].name).append(",");
		}
		for (int i=0; i<pipes.length; i++) {
			data.append(pipes[i].toString()).append(",");
		}
		for (int i=0; i<balls.length; i++) {
			data.append(balls[i].toString()).append(",");
		}

		// compute a color from RGB to grayscale// TODO there are tones of improvements to be done here, besides Blender would accept an RGB color straight. The python script has to be modified. For example with bit packing it could save an int alpha/red/green/blue no problem!
		int[] color = new int[obs.length + pipes.length + balls.length];
		data.append(l).append("Colors=");
		int j = 0;
		for (int i=0; i<obs.length; i++) {
			Color colorRGB = obs[i].profiles[0].getColor();
			color[j++] = colorRGB.getGreen();
			data.append(color[i]).append(",");
		}
		for (int i=0; i<pipes.length; i++) {
			Color colorRGB = pipes[i].getColor();
			color[j++] = colorRGB.getGreen();
			data.append(color[i]).append(",");
		}
		for (int i=0; i<balls.length; i++) {
			Color colorRGB = balls[i].getColor();
			color[j++] = colorRGB.getGreen();
			data.append(color[i]).append(",");
		}
		data.append(l).append("Supergroups=");
		for (int i=0; i<obs.length + pipes.length + balls.length; i++) {
			data.append("null,");
		}
		data.append(l).append("Supercolors=");
		for (int i=0; i<obs.length + pipes.length + balls.length; i++) {
			data.append("null,");
		}
		data.append(l).append(l);
		// add the data
		j = 0;
		for (int i=0; i<obs.length; i++) {
			String clr = Integer.toString(color[j++]);
			for (int k=0; k<obs[i].profiles.length; k++) {
				if (null == obs[i] || null == obs[i].profiles[k]) {
					Utils.log("obs[i] is : " + obs[i]);
					if (null != obs[i].profiles[k]) Utils.log("obs[i].profiles[k] is " + obs[i].profiles[k]);
					continue;
				}
				obs[i].profiles[k].toShapesFile(data, obs[i].name, clr, z_scale);
				data.append(l);
			}
		}
		for (int i=0; i<pipes.length; i++) {
			pipes[i].toShapesFile(data, pipes[i].toString(), Integer.toString(color[j++]), z_scale);
			data.append(l);
		}
		for (int i=0; i<balls.length; i++) {
			balls[i].toShapesFile(data, balls[i].toString(), Integer.toString(color[j++]), z_scale);
			data.append(l);
		}
		// save the data in a file
		final SaveDialog sd = new SaveDialog("Save .shapes", OpenDialog.getDefaultDirectory(), "shapes");
		String dir = sd.getDirectory();
		if (null == dir) return;
		if (IJ.isWindows()) dir = dir.replace('\\', '/');
		if (!dir.endsWith("/")) dir += "/";
		String file_name = sd.getFileName();
		String file_path = dir + file_name;
		File f = new File(file_path);
		if (f.exists()) {
			YesNoCancelDialog d = new YesNoCancelDialog(IJ.getInstance(), "Overwrite?", "File " + file_name + " exists! Overwrite?");
			if (!d.yesPressed()) {
				return;
			}
		}
		String contents = data.toString();
		try {
			DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), data.length()));
			dos.writeBytes(contents);
			dos.flush();
		} catch (Exception e) {
			IJError.print(e);
			Utils.log("ERROR: Most likely did NOT save your file.");
		}
	}


	/////////////////////////
	// SVG format//

	static public void exportSVG(ProjectThing thing, double z_scale) {
		StringBuffer data = new StringBuffer();
		data.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
		    .append("<svg\n")
		    .append("\txmlns:dc=\"http://purl.org/dc/elements/1.1/\"\n")
		    .append("\txmlns:cc=\"http://web.resource.org/cc/\"\n")
		    .append("\txmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n")
		    .append("\txmlns:svg=\"http://www.w3.org/2000/svg\"\n")
		    .append("\txmlns=\"http://www.w3.org/2000/svg\"\n")
		    //.append("\twidth=\"1500\"\n")
		    //.append("\theight=\"1000\"\n")
		    .append("\tid=\"").append(thing.getProject().toString()).append("\">\n")
		;
		// traverse the tree at node 'thing'
		thing.exportSVG(data, z_scale, "\t");

		data.append("</svg>");

		// save the file
		final SaveDialog sd = new SaveDialog("Save .svg", OpenDialog.getDefaultDirectory(), "svg");
		String dir = sd.getDirectory();
		if (null == dir) return;
		if (IJ.isWindows()) dir = dir.replace('\\', '/');
		if (!dir.endsWith("/")) dir += "/";
		String file_name = sd.getFileName();
		String file_path = dir + file_name;
		File f = new File(file_path);
		if (f.exists()) {
			YesNoCancelDialog d = new YesNoCancelDialog(IJ.getInstance(), "Overwrite?", "File " + file_name + " exists! Overwrite?");
			if (!d.yesPressed()) {
				return;
			}
		}
		String contents = data.toString();
		try {
			DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), data.length()));
			dos.writeBytes(contents);
			dos.flush();
		} catch (Exception e) {
			IJError.print(e);
			Utils.log("ERROR: Most likely did NOT save your file.");
		}
	}
}
