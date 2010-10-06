package ini.trakem2.display;

public class Tag implements Comparable<Tag> {
	final private Object tag;
	final private int keyCode;
	public Tag(final Object ob, final int keyCode) {
		this.tag = ob;
		this.keyCode = keyCode;
	}
	public String toString() {
		return tag.toString();
	}
	public int getKeyCode() {
		return keyCode;
	}
	public boolean equals(final Object ob) {
		final Tag t = (Tag)ob;
		return t.keyCode == this.keyCode && t.tag.equals(this.tag);
	}
	public int compareTo(final Tag t) {
		return t.tag.toString().compareTo(this.tag.toString());
	}
}
