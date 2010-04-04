
package ini.trakem2.tree;

import ini.trakem2.persistence.FSLoader;
import ini.trakem2.utils.*;

import java.io.*;
import java.util.*;

/** Reads either a DOCTYPE chunk from an xml file or a .dtd file directly. */
public class DTDParser {

	private DTDParser() {}

	/** Extracts the template by reading the ELEMENT and ATTLIST tags from a .dtd file or the DOCTYPE of an .xml file. */
	static public TemplateThing[] extractTemplate(String path) throws Exception {
		if (path.length() -4 == path.lastIndexOf(".xml")) return parseXMLFile(path);
		if (path.length() -4 == path.lastIndexOf(".dtd")) return parseDTDFile(path);
		return null;
	}

	/** Parses the tags of a .dtd file. Returns the TemplateThing roots. */
	static public TemplateThing[] parseDTDFile(String dtd_path) throws Exception {
		// fetch file
		BufferedReader dis = null;
		StringBuffer data = new StringBuffer();
		try {
			InputStream i_stream;
			if (FSLoader.isURL(dtd_path)) {
				i_stream = new java.net.URL(dtd_path).openStream();
			} else {
				File f = new File(dtd_path);
				if (!f.exists()) return null;
				i_stream = new FileInputStream(dtd_path);
			}
			dis = new BufferedReader(new InputStreamReader(i_stream));
			String tmp;
			while (null != (tmp = dis.readLine())) {
				data.append(tmp);
			}
		} catch (Exception e) {
			IJError.print(e);
		} finally {
			try {
				dis.close();
			} catch (Exception e) { IJError.print(e); }
		}

		return parseDTD(data);
	}

	/** Parses a !DOCTYPE chunk from an .xml file, if any. Returns the TemplateThing roots. Assumes there is only one continuous DOCTYPE clause and the root template thing, the layer_set and the display are part of the project tag. */
	static public TemplateThing[] parseXMLFile(String xml_path) throws Exception {
		// fetch file
		BufferedReader dis = null;
		StringBuffer data = new StringBuffer();
		try {
			InputStream i_stream;
			if (FSLoader.isURL(xml_path)) {
				i_stream = new java.net.URL(xml_path).openStream();
			} else {
				File f = new File(xml_path);
				if (!f.exists()) return null;
				i_stream = new FileInputStream(xml_path);
			}
			dis = new BufferedReader(new InputStreamReader(i_stream));
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
			IJError.print(e);
		} finally {
			try {
				dis.close();
			} catch (Exception e) { IJError.print(e); }
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
		void createAttributesAndChildren(final TemplateThing parent, final Map<String,Object> ht_attributes, final Map<String,DTDParser.Type> ht_types) {
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
					//Utils.log2(parent.getType() + "  new attr: " + attr.name);
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
		 || type.startsWith("user_id") // davi-experimenting
		 || 0 == type.indexOf("ict_transform")
		) return false;
		
		return true;
	}

	/** Parses a chunk of text into a hierarchy of TemplateThing instances, the roots of which are in the returned array. */
	static public TemplateThing[] parseDTD(final StringBuffer data) throws Exception {
		// debug:
		// Utils.log(data.toString());

		// extract all tags into a hashtable of type names
		final HashMap<String,DTDParser.Type> ht_types = new HashMap<String,DTDParser.Type>();
		final HashMap<String,Object> ht_attributes = new HashMap<String,Object>();
		int i = -1;
		final String text = data.toString();
		int len = text.length();
		int i_first = text.indexOf('<');
		int i_last = text.indexOf('>');
		int i_space;
		String root_type_name = null;

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
				Utils.log2("davi-experimenting found: " + type.name);
				if (isAllowed(type.name)) {
					ht_types.put(type.name, type);
				} else if (type.name.equals("project")) {
					if (null != root_type_name) {
						throw new Exception("ERROR in XML file: more than one project template element defined:\n   At least: " + root_type_name + " and " + type.name);
					}
					// the root is what the project has in parentheses, which must only be one element
					// (given that the TemplateTree has a single root)
					int openp = chunk.indexOf('(');
					if (-1 == openp) {
						throw new Exception("ERROR in XML file: project template doesn't have a child element!");
					}
					int closep = chunk.indexOf(')', openp +1);
					root_type_name = chunk.substring(openp+1, closep).trim();
					if (-1 != root_type_name.indexOf(',')) {
						throw new Exception("ERROR in XML file: project template has more than one child element!");
					}
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

		if (null == root_type_name) {
			throw new Exception("ERROR in XML file: could not find the root element!");
		}

		// find root_type as a Type instance
		DTDParser.Type root_type = ht_types.get(root_type_name);
		if (null == root_type) {
			throw new Exception("ERROR in XML file: could not find the root element DTDParser.Type instance!");
		}

		// The root is the one and only element of the project node
		TemplateThing root = new TemplateThing(root_type_name);
		root_type.createAttributesAndChildren(root, ht_attributes, ht_types); // avoids nested

		return new TemplateThing[]{root};
	}

	static public void main(String[] args) {
		try {
			if (args[0].length() -4 == args[0].indexOf(".xml")) {
				DTDParser.parseXMLFile(args[0]);
			} else {
				DTDParser.parseDTDFile(args[0]);
			}
		} catch (Exception e) { IJError.print(e); }
	}
}

