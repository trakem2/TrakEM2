
package ini.trakem2.tree;

import ini.trakem2.utils.*;

import java.io.*;
import java.util.*;

/** Reads either a DOCTYPE chunk from an xml file or a .dtd file directly. */
public class DTDParser {

	private DTDParser() {}

	/** Extracts the template by reading the ELEMENT and ATTLIST tags from a .dtd file or the DOCTYPE of an .xml file. */
	static public TemplateThing[] extractTemplate(String path) {
		if (path.length() -4 == path.lastIndexOf(".xml")) return parseXMLFile(path);
		if (path.length() -4 == path.lastIndexOf(".dtd")) return parseDTDFile(path);
		return null;
	}

	/** Parses the tags of a .dtd file. Returns the TemplateThing roots. */
	static public TemplateThing[] parseDTDFile(String dtd_path) {
		// fetch file
		File f = new File(dtd_path);
		if (!f.exists()) return null;
		BufferedReader dis = null;
		StringBuffer data = new StringBuffer();
		try {
			dis = new BufferedReader(new InputStreamReader(new FileInputStream(dtd_path)));
			String tmp;
			while (null != (tmp = dis.readLine())) {
				data.append(tmp);
			}
		} catch (Exception e) {
			new IJError(e);
		} finally {
			try {
				dis.close();
			} catch (Exception e) { new IJError(e); }
		}

		return parseDTD(data);
	}

	/** Parses a !DOCTYPE chunk from an .xml file, if any. Returns the TemplateThing roots. Assumes there is only one continuous DOCTYPE clause and the root template thing, the layer_set and the display are part of the project tag. */
	static public TemplateThing[] parseXMLFile(String xml_path) {
		// fetch file
		File f = new File(xml_path);
		if (!f.exists()) return null;
		BufferedReader dis = null;
		StringBuffer data = new StringBuffer();
		try {
			dis = new BufferedReader(new InputStreamReader(new FileInputStream(xml_path)));
			String tmp;
			while (null != (tmp = dis.readLine())) {
				int i_doc = tmp.indexOf("<!DOCTYPE ");
				if (-1 != i_doc) {
					// start listening
					int i_end = -1;
					// advance lines until finding an opening bracket
					while (null != tmp && -1 == (i_end = tmp.indexOf('['))) {
						tmp = dis.readLine();
					}
					if (-1 == i_end) break; // oops
					// found. Collect everything between both brackets
					String st = tmp.substring(i_end +1).trim();
					if (st.length() > 0) data.append(st);
					while (null != (tmp = dis.readLine()) && -1 == (i_end = tmp.indexOf(']'))) {
						data.append(tmp);
					}
					// get in last line
					st = tmp.substring(0, i_end).trim();
					if (st.length() > 0) data.append(st);
					// done!
					break;
				}
			}
		} catch (Exception e) {
			new IJError(e);
		} finally {
			try {
				dis.close();
			} catch (Exception e) { new IJError(e); }
		}

		if (0 == data.length()) return null;

		return parseDTD(data);
	}

	static private class Attribute {
		String type, name, a1=null, a2=null;
		Attribute(String chunk) {
			chunk = Utils.cleanString(chunk);
			String[] words = chunk.split(" ");
			this.type = words[0];
			this.name = words[1];
			if (words.length > 2) this.a1 = words[2];
			if (words.length > 3) this.a2 = words[3];
			if (words.length > 4) Utils.log("WARNING: ignoring past the 4th word in the DTD: " + words[4] + " ... ");
		}
		public boolean equals(Object ob) {
			if (ob instanceof Attribute && ((Attribute)ob).name.equals(this.name)) {
				return true;
			}
			return false;
		}
		public void createAttribute(TemplateThing tt) {
			// ignore id and oid
			if (name.equals("id") || name.equals("oid")) return;
			tt.addAttribute(name, null); // lots of XML-specified qualities missing ... TODO
		}
	}

	static private class Type {
		String name;
		String[] children = null;
		String[] limits = null;
		ArrayList al_attributes = null;

		private Type() {} // for cloning

		/** Parses itself out of a chunk of text between '&lt;' and '&gt;'. */
		Type(String chunk) {
			chunk = Utils.cleanString(chunk);
			// first word is the type
			int i = chunk.indexOf(' ');
			this.name = chunk.substring(0, i).toLowerCase(); // types are ALWAYS lowercase. I need no more headaches.
			// remove prepended tag if any
			chunk = chunk.substring(i+1);
			i = chunk.indexOf('(');
			if (-1 == i) return; // contains an EMPTY
			int i_end = chunk.lastIndexOf(')');
			chunk = chunk.substring(i+1, i_end); //capturing contents of parenthesis
			chunk = chunk.replaceAll(" ", ""); //no spaces allowed inside the parenthesis
			this.children = chunk.split(",");
			this.limits = new String[children.length];
			for (i=0; i<children.length; i++) {
				char c = children[i].charAt(children[i].length() - 1);
				switch (c) {
					case '?': // optional
					case '*': // zero or more
					case '+': // one or more
						limits[i] = Character.toString(c);
						children[i] = children[i].substring(0, children[i].length() -1);
						break;
					default:
						limits[i] = null;
						break;
				}
				// print children
				//Utils.log("parent " + this.name + " has child : __" + children[i] + "__");
			}
		}
		boolean containsChild(String type) {
			if (null == children) return false;
			for (int i=0; i<children.length; i++) {
				if (children[i].equals(type)) return true;
			}
			return false;
		}
		/** Recursive, but avoids adding children to nested types. The table ht_attributes contains type names as keys, and hashtables of attributes as values. */
		void createAttributesAndChildren(TemplateThing parent, Hashtable ht_attributes, Hashtable ht_types) {
			// create attributes if any
			Object ob = ht_attributes.get(name);
			if (null != ob) {
				Hashtable ht_attr = (Hashtable)ob;
				// delete redundant attributes (which overlap with fields or node properties)
				ht_attr.remove("id"); // built-in to the class
				ht_attr.remove("oid"); // temporary reference
				ht_attr.remove("title"); // built-in to the class
				ht_attr.remove("index"); //obsolete
				ht_attr.remove("expanded"); // 'remove' doesn't fail when the key is not there
				// What the above is saying: only user-defined attributes should be preserved
				for (Iterator it = ht_attr.values().iterator(); it.hasNext(); ) {
					Attribute attr = (Attribute)it.next();
					attr.createAttribute(parent);
					Utils.log2(parent.getType() + "  new attr: " + attr.name);
				}
			}

			// create children for it, unless nested
			if (!parent.isNested() && null != children) {
				for (int k=0; k<children.length; k++) {
					Type ty = (Type)ht_types.get(children[k]);
					if (null == ty) {
						Utils.log2("DTDParser: ignoring " + children[k]);
						continue;
					}
					// remove prepended tag if any
					String tyn = ty.name;
					if (0 == tyn.indexOf("t2_")) {
						tyn = tyn.substring(3);
					}
					TemplateThing child = new TemplateThing(tyn);
					//Utils.log2("DTDParser: created TT " + tyn);
					parent.addChild(child);
					ty.createAttributesAndChildren(child, ht_attributes, ht_types);
				}
			}
		}
	}

	/** A method to check whether a type is internal to TrakEM2 and should be ignored for a template. */
	static private boolean isAllowed(String type) {
		/*
		// ignore meta
		if (0 == type.length()) return false;
		char c = type.charAt(type.length() -1);
		switch (c) {
			case '*':
			case '+':
			case '?':
				type = type.substring(0, type.length() -1);
				break;
		}
		*/
		/*
		if (type.equals("layer")
		 || type.equals("layer_set")
		 || type.equals("label")
		 || type.equals("pipe")
		 || type.equals("profile")
		 || type.equals("ball")
		 || type.equals("ball_ob")
		 || type.equals("patch")
		 || type.equals("display")
		 || type.equals("project")
		 || type.equals("trakem2")
		) return false;
		*/
		if (0 == type.indexOf("t2_")
		 || type.equals("trakem2")
		 || type.equals("project")
		) return false;
		
		return true;
	}

	/** Parses a chunk of text into a hierarchy of TemplateThing instances, the roots of which are in the returned array. */
	static private TemplateThing[] parseDTD(StringBuffer data) {
		// debug:
		// Utils.log(data.toString());

		// extract all tags into a hashtable of type names
		Hashtable ht_types = new Hashtable();
		Hashtable ht_attributes = new Hashtable();
		int i = -1;
		String text = data.toString();
		int len = text.length();
		int i_first = text.indexOf('<');
		int i_last = text.indexOf('>');
		int i_space;
		while (-1 != i_first && -1 != i_last) {
			// sanity check:
			if (i_last < i_first) {
				Utils.showMessage("Unbalanced '<' and '>' in the DTD document.");
				return null;
			}
			String chunk = text.substring(i_first +1, i_last);
			i_space = chunk.indexOf(' ');
			if (chunk.startsWith("!ELEMENT")) {
				DTDParser.Type type = new DTDParser.Type(chunk.substring(i_space +1));
				if (isAllowed(type.name)) {
					ht_types.put(type.name, type);
				}
			} else if (chunk.startsWith("!ATTLIST")) {
				DTDParser.Attribute attr = new DTDParser.Attribute(chunk.substring(i_space +1));
				if (isAllowed(attr.type)) {
					Object o = ht_attributes.get(attr.type);
					if (null == o) {
						//Utils.log2("at 1 for " + attr.type + " " + attr.name);
						Hashtable ht = new Hashtable();
						o = ht;
						ht_attributes.put(attr.type, o);
					}
					Hashtable oht = (Hashtable)o;
					if (oht.contains(attr.name)) {
						Utils.log("Parsing DTD: already have attribute " + attr.name + " for type " + attr.type);
					} else {
						//Utils.log2("at 2 for " + attr.type + " " + attr.name);
						oht.put(attr.name, attr);
					}
				}
			} // else ignore
			i_first = text.indexOf('<', i_last +1);
			i_last = text.indexOf('>', i_last +1);
		}
		// Now traverse the hash tables and reconstruct the hierarchy of TemplateThing.

		// Find the roots (insane, but can't assign children first because nested ones get their parent overwritten)
		DTDParser.Type[] type = new DTDParser.Type[ht_types.size()];
		boolean[] is_root = new boolean[type.length];
		Arrays.fill(is_root, true);
		ht_types.values().toArray(type);
		for (int k=0; k<type.length; k++) {
			for (int j=0; j<type.length; j++) {
				if (type[j].containsChild(type[k].name)) {
					is_root[k] = false;
				}
			}
		}
		ArrayList al_roots = new ArrayList();
		for (int k=0; k<type.length; k++) {
			if (is_root[k]) {
				// replace prepended tag if any
				String tyn = type[k].name;
				if (0 == type[k].name.indexOf("t2_")) {
					tyn = type[k].name.substring(3);
				}
				TemplateThing root = new TemplateThing(tyn);
				//Utils.log2("DTDParser: created root TT " + tyn);
				type[k].createAttributesAndChildren(root, ht_attributes, ht_types); // avoids nested
				al_roots.add(root);
			}
		}
		TemplateThing[] tt_roots = new TemplateThing[al_roots.size()];
		al_roots.toArray(tt_roots);

		//debug: print root
		/*
		for (int k=0; k<tt_roots.length; k++) {
			tt_roots[k].debug("");
		}
		*/
		/*
		Utils.log2("tt_roots: " + tt_roots.length);
		// debug: print number of attributes per type
		for (Enumeration e = ht_attributes.keys(); e.hasMoreElements(); ) {
			String ty = (String)e.nextElement();
			Hashtable ht = (Hashtable)ht_attributes.get(ty);
			Utils.log("n attributes for  " + ty + " : " + ht.size());
		}
		*/

		/*
		//debug:
		javax.swing.JFrame jf = new javax.swing.JFrame("debug");
		jf.setDefaultCloseOperation(javax.swing.JFrame.EXIT_ON_CLOSE);
		jf.getContentPane().add(new javax.swing.JScrollPane(new ini.trakem2.tree.TemplateTree(null, tt_roots[0])));
		jf.pack();
		jf.setVisible(true);
		*/

		return tt_roots;
	}

	static public void main(String[] args) {
		if (args[0].length() -4 == args[0].indexOf(".xml")) {
			DTDParser.parseXMLFile(args[0]);
		} else {
			DTDParser.parseDTDFile(args[0]);
		}
	}
}

