
package ini.trakem2.persistence;

import ini.trakem2.tree.TemplateThing;
import ini.trakem2.tree.ProjectThing;
import ini.trakem2.utils.Utils;
import java.awt.geom.AffineTransform;

/** An admitedly naive decoder for the t2 lispy format, aiming at avoiding String instantiations and enabling parallel object creation.
 *
 * "Any sufficiently complicated C or Fortran program contains an ad hoc, informally-specified, bug-ridden, slow implementation of half of Common Lisp."
 *
 * Getting there, no worries Mr. Greenspun.
 *
 */
public class Decoder {

	/** Wether @param c is space, tab or line break. */
	static public final boolean isSeparator(final char c) {
		return (' ' == c || '\t' == c || '\n' == c);
	}

	/** Starting at first, scans until finding an open parenthesis that is NOT the value of an atom, and returns the range of that. */
	static public final int[] findNextChildRange(final char[] src, final int first, final int last) {
		if (first < 0 || last >= src.length) return null;
		for (int i=first; i<=last; i++) {
			if ('(' == src[i] && (first == i || '\'' != src[i-1])) {
				// found opening parenthesis
				final int[] se = new int[2];
				se[0] = i;
				// find corresponding closing parenthesis
				int opening = 0;
				for (int j=0; j<=last; j++) {
					if ('(' == src[i+j]) opening++;
					else if (')' == src[i+j]) {
						if (0 == opening) {
							se[1] = i+j -1;
							return se;
						} else {
							opening--;
						}
					}
				}
				Utils.log2("MISSING closing parenthesis for a child within first,last: " + first + ", " + last);
			}
		}
		return null;
	}

	/** Returns the starting and ending indices of the first atom found with a tag named by @param tag, or null if the tag is not found or contains no associated atom. If the atom is encapsulated in parentheses, these are EXCLUDED from the range. */
	static public final int[] findAtomRange(final char[] tag, final char[] src, final int first, final int last) {
		if (first < 0 || last >= src.length) return null;
		search_tag: for (int i=first; i<last; i++) {
			// find potential start
			if (src[i] == tag[0]) {
				// found potential tag start. Check if proper
				int j=1;
				for (; j<tag.length && j<last; j++) {
					if (tag[j] != src[i+j]) continue search_tag;
				}
				// check tag end delimiter
				j++;
				if (i+j >= last) return null; // no end possible, and no atom possible
				if (!(' ' == src[i+j] || '\t' == src[i+j])) continue search_tag; // tag name doesn't finish here
				// ok, now find data start: the colon
				for (; j<last; j++) {
					if (isSeparator(src[i+j])) continue; // ok, spaces or tabs or line breaks between tag and start of atom
					else if ('\'' == src[i+j]) {
						// found starting colon. Read until end
						final int[] se = new int[2];
						j++;
						if (i+j > last) return null;
						if ('(' == src[i+j]) {
							//start
							int count = 0; // enable parenthesis within
							se[0] = i+j +1;
							for (; j<=last; j++) {
								if ('(' == src[i+j]) count++;
								else if (')' == src[i+j]) {
									if (0 == count) {
										se[1] = i+j -1;
										return se; // returning atom range excluding the parentheses
									} else count--;
								}
							}
							// End reached without closing parenthesis: error!
							Utils.log2("Atom tagged " + String.valueOf(tag) + " lacks a closing parenthesis. Was within first,last : " + first + ", " + last);
							return null;
						} else {
							se[0] = i+j;
							for (; j<=last; j++) {
								if (isSeparator(src[i+j]) || i+j == last) {
									if (')' == src[i+j]) {
										Utils.log2("Atom tagged " + String.valueOf(tag) + " lacks an opening parenthesis. Was within first,last : " + first + ", " + last); // this error may not be cached at all, because there may be spaces and tabs and line breaks in between that already cut it short.
										return null;
									}
									se[1] = i+j;
									return se; // returning atom range without parentheses
								}
							}
							// End reached. Return everything as the atom
							se[1] = last;
							return se;
						}
					} else continue search_tag;
				}
			} else continue search_tag;
		}
		Utils.log2("Tag NOT FOUND: " + String.valueOf(tag) + " within first,last : " + first + ", " + last);
		return null;
	}

	static public final boolean isDigit(final char c) {
		return c >= '0' && c <= '9';
	}

	// my kingdom for a lisp macro !@#$%^&
	/** Accepts ONLY digits, or returns Integer.MIN_Value. */
	static public final int decodeInt(final char[] src, final int first, final int last) {
		int val = 0;
		for (int i=first; i<=last; i++) {
			if (!isDigit(src[i])) return Integer.MIN_VALUE;
			val += (src[i] - '0') * (int)Math.round(Math.pow(10, last - i)); // I hate floating point math
		}
		return val;
	}

	/** Accepts ONLY digits, or returns Long.MIN_Value. */
	static public final long decodeLong(final char[] src, final int first, final int last) {
		long val = 0;
		for (int i=first; i<=last; i++) {
			if (!isDigit(src[i])) return Long.MIN_VALUE;
			val += (src[i] - '0') * (long)Math.round(Math.pow(10, last - i));
		}
		return val;
	}

	/** Returns NaN on error; expects only numbers and the '.' char. Beware of the error ... floating-point math is a disaster. */
	static public final double decodeDouble(final char[] src, final int first, final int last) {
		double val = 0;
		int dec = first;
		// find dot, if any
		for (int i=first; i<=last; i++) {
			if ('.' == src[i]) {
				dec = i;
				break;
			}
		}
		// before dot
		for (int i=first; i<dec; i++) {
			if (!isDigit(src[i])) return Double.NaN;
			else val += (src[i] - '0') * Math.pow(10, dec - i -1);
		}
		// after dot
		for (int i=dec+1; i<=last; i++) {
			if (!isDigit(src[i])) return Double.NaN;
			else val += (double)(src[i] - '0') / Math.pow(10, i - dec);
		}
		return val;
	}

	/** Returns 'def'ault if a valid value for the tag is not found. */
	static public final long getLong(final char[] tag, final char[] src, final int first, final int last, final long def) {
		final int[] se = findAtomRange(tag, src, first, last);
		if (null == se) return def; // errors will print, just survive
		return decodeLong(src, se[0], se[1]);
	}

	/** Returns 'def'ault if a valid value for the tag is not found. */
	static public final double getDouble(final char[] tag, final char[] src, final int first, final int last, final double def) {
		final int[] se = findAtomRange(tag, src, first, last);
		if (null == se) return def; // errors will print, just survive
		return decodeDouble(src, se[0], se[1]);
	}

	static public final void putAffineTransform(final AffineTransform at, final char[] src, final int first, final int last) {
		final int[] se = findAtomRange(TRANSFORM, src, first, last);
		if (null == se) return;
		final double[] nums = new double[6];
		// loop through the content to find a maximum of 6 floats
		int k = se[0];
		int next = 0;
		for (int i=k; i<=se[1]; i++) {
			if (isSeparator(src[i]) || i == se[1]) {
				if (next == nums.length) break;
				if (k < i) {
					nums[next] = decodeDouble(src, k, i-1);
					next++;
				}
				// search for a start
				k = i+1;
				continue;
			}
		}
		at.setTransform(nums[0], nums[1], nums[2], nums[3], nums[4], nums[5]);
	}

	static public final boolean getBoolean(final char[] tag, final char[] src, final int first, final int last, final boolean def) {
		final int[] se = findAtomRange(tag, src, first, last);
		if (null == se) return def;
		if ('t' == src[se[0]]) return true;
		return false;
	}

	static public final String getString(final char[] tag, final char[] src, final int first, final int last, final String def) {
		final int[] se = findAtomRange(tag, src, first, last);
		if (null == se) return def;
		return String.valueOf(src, se[0], se[1]); // a new char[] array is created
	}

	static public final char[] ID = "id".toCharArray();
	static public final char[] OID = "oid".toCharArray();
	static public final char[] WIDTH = "width".toCharArray();
	static public final char[] HEIGHT = "height".toCharArray();
	static public final char[] TRANSFORM = "transform".toCharArray();
	static public final char[] VISIBLE = "visible".toCharArray();
	static public final char[] LOCKED = "locked".toCharArray();
	static public final char[] TITLE = "title".toCharArray();
	static public final char[] LAYER_ID = "layer_id".toCharArray();
	static public final char[] PATH = "path".toCharArray();

	/** Reconstructs a project from the given .t2 file, using the given loader.
	* Returns 4 objects packed in an array:
	 <pre>
	 [0] = root TemplateThing
	 [1] = root ProjectThing (contains Project instance)
	 [2] = root LayerThing (contains the top-level LayerSet)
	 [3] = expanded states of all ProjectThing objects
	 </pre>
	 * <br />
	 * Also, triggers the reconstruction of links and assignment of Displayable objects to their layer.
	 *
	 * The above is the exact same data returned by the TMLandler.getProjectData() method, which parses XML files containing TrakEM2 projects.
	 */
	static public final Object[] read(final String path, final FSLoader loader) {
		if (!path.toLowerCase().endsWith(".t2")) {
			Utils.log2("Ignoring file without .t2 extension: " + path);
			return null;
		}
		// load file
		final char[] src = Utils.openTextFileChars(path);
		// 1 - find template description
		int[] se = findAtomRange("template".toCharArray(), src, 0, src.length);
		if (null == se) {
			Utils.log2("Could not find the template node");
			return null;
		}
		final TemplateThing tt_root = parseTemplate(src, se[0], se[1]);


		// Need a generic parser



		return null; // TODO
	}

	static public TemplateThing parseTemplate(final char[] src, final int first, final int last) {
		int[] se = null;
		while (null != (se = findNextChildRange(src, first, last))) {
			// parse TemplateThing: create it, and store into hashtable along with the 'se' of its elem atom, if any.
		}

		return null; // TODO
	}
}
