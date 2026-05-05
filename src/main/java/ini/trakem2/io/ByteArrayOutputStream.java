package ini.trakem2.io;

/**
 * The purpose of this class is to provide public access to the protected byte[] buf in ByteArrayOutputStream.
 */
public class ByteArrayOutputStream extends java.io.ByteArrayOutputStream {

	public ByteArrayOutputStream() {
		super();
	}
	
	public ByteArrayOutputStream(final int length) {
		super(length);
	}
	
	public byte[] getByteArray() {
		return super.buf;
	}
}
