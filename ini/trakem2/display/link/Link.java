package ini.trakem2.display.link;
import ini.trakem2.Project;
import ini.trakem2.display.Displayable;
import ini.trakem2.utils.Utils;
import java.util.HashMap;
import java.util.Map;

/** A default directional link between two Displayable objects, with a set of properties. */
public abstract class Link {

	protected Map<String,String> props = null;

	protected Displayable origin, target;

	/** Needed to instantiate by reflection. */
	public Link() {}

	public Link(final Displayable origin, final Displayable target) throws IllegalArgumentException {
		this.origin = origin;
		this.target = target;
		if (null == origin || null == target) {
			throw new IllegalArgumentException("Invalid Link: can't point to a null Displayable!");
		}
	}

	/** Returns false on failure. */
	abstract public boolean init(final Displayable origin, final String data) throws IllegalArgumentException;

	public Displayable getOrigin() { return origin; }

	public Displayable getTarget() { return target; }

	/** Returns false if key/value pair NOT added, which happens when key is an invalid identifier (that is, does not start with a letter, and contains characters other than just letters, numbers and underscore.
	 * To remove a property, set it to null. */
	synchronized public boolean setProperty(final String key, final String value) {
		if (null == key || !Utils.isValidIdentifier(key)) return false;
		if (null == value && null != props) {
			props.remove(key);
			if (props.isEmpty()) props = null;
			return true;
		}
		if (null == props) props = new HashMap<String,String>();
		props.put(key, value);
		return true;
	}

	/** If key is null or not found, returns default_value; otherwise returns the stored value for key. */
	synchronized public String getProperty(final String key, final String default_value) {
		if (null == key) return default_value;
		final String val = props.get(key);
		if (null == val) return default_value;
		return val;
	}

	/** Returns a copy of the properties table, which may be empty. */
	synchronized public Map getProperties() {
		if (null == props) return new HashMap();
		return new HashMap(props);
	}

	/** Returns an XML-attribute content safe String representation of this link, which can then
	 *  be passed to the init() function to recreate it. */
	abstract public String asXML();

	/** Returns a full XML description of this object, with tags and properties (if any) included. */
	public String toXML(final String indent) {
		StringBuffer sb = new StringBuffer(indent);
			sb.append("<t2_link class=\"").append(this.getClass().getName())
			  .append("\" data=\"").append(this.asXML());
		if (null == props) {
			sb.append("\"/>\n");
		} else {
			sb.append("\">\n");
			for (Map.Entry<String,String> e : props.entrySet()) {
				// NOT checking validity of the property as an XML-undisruptive string.
				sb.append(indent).append("\t<t2_prop key=\"").append(e.getKey()).append("\" value=\"").append(e.getValue()).append("\"/>\n");
			}
			sb.append(indent).append("/>\n");
		}
		return sb.toString();
	}

	/** Checks for identity of origin and target, not any properties. */
	public final boolean equals(final Object ob) {
		if (!(ob instanceof Link)) return false;
		final Link ln = (Link) ob;
		return ln.origin == this.origin && ln.target == this.target;
	}
}
